package org.kori.plugin.geo.track

import org.kori.plugin.geo.track.di.TrackPoint
import org.kori.plugin.geo.track.di.TrackSession
import java.util.TimeZone
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 轨迹导出器（B8 修复版）。
 *
 * ## B8 修复
 *
 * GeoJSON 的 coordinates 不再用 `joinToString` 构建大字符串——
 * 改用 BufferedWriter 分块流式写入，避免大轨迹 OOM。
 */
object TrackExporter {

    enum class Format { CSV, GPX, KML, GEOJSON }
    enum class Track { RAW, SMOOTH }

    fun export(
        session: TrackSession,
        format: Format,
        track: Track,
        outFile: File,
    ): File {
        val points = when (track) {
            Track.RAW -> TrackMerger.mergeRawDeduped(session)
            Track.SMOOTH -> TrackMerger.mergeSmooth(session)
        }
        outFile.parentFile?.mkdirs()
        when (format) {
            Format.CSV -> TrackStore.write(outFile, points)
            Format.GPX -> writeGpx(outFile, points, session)
            Format.KML -> writeKml(outFile, points, session)
            Format.GEOJSON -> writeGeoJson(outFile, points, session)
        }
        return outFile
    }

    private fun writeGpx(file: File, points: List<TrackPoint>, session: TrackSession) {
        file.bufferedWriter().use { w ->
            w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            w.write("<gpx version=\"1.1\" creator=\"Vela\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
            w.write("  <metadata>\n")
            w.write("    <name>${escapeXml(session.name)}</name>\n")
            w.write("    <time>${iso8601(session.startedMs)}</time>\n")
            w.write("  </metadata>\n")
            w.write("  <trk>\n")
            w.write("    <name>${escapeXml(session.name)}</name>\n")
            w.write("    <trkseg>\n")
            for (p in points) {
                w.write("      <trkpt lat=\"${p.lat}\" lon=\"${p.lng}\">\n")
                p.altitudeM?.let { w.write("        <ele>$it</ele>\n") }
                w.write("        <time>${iso8601(p.timestampMs)}</time>\n")
                p.speedMps?.let { w.write("        <extensions><speed>$it</speed></extensions>\n") }
                w.write("      </trkpt>\n")
            }
            w.write("    </trkseg>\n")
            w.write("  </trk>\n")
            w.write("</gpx>\n")
        }
    }

    private fun writeKml(file: File, points: List<TrackPoint>, session: TrackSession) {
        file.bufferedWriter().use { w ->
            w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            w.write("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n")
            w.write("  <Document>\n")
            w.write("    <name>${escapeXml(session.name)}</name>\n")
            w.write("    <Placemark>\n")
            w.write("      <name>${escapeXml(session.name)}</name>\n")
            w.write("      <LineString>\n")
            w.write("        <altitudeMode>clampToGround</altitudeMode>\n")
            w.write("        <coordinates>\n")
            for (p in points) {
                w.write("          ${p.lng},${p.lat},${p.altitudeM ?: 0.0}\n")
            }
            w.write("        </coordinates>\n")
            w.write("      </LineString>\n")
            w.write("    </Placemark>\n")
            w.write("  </Document>\n")
            w.write("</kml>\n")
        }
    }

    /**
     * ★ B8 修复：GeoJSON 分块写入。
     *
     * 之前用 `points.joinToString(",") { "[${it.lng},${it.lat}]" }` 构建一个
     * 包含所有坐标的字符串，10 万个点会产生 ~3 MB 的临时字符串，
     * 在某些设备上触发 OOM。现在逐点写入，内存占用恒定。
     */
    private fun writeGeoJson(file: File, points: List<TrackPoint>, session: TrackSession) {
        file.bufferedWriter().use { w ->
            val start = points.firstOrNull()?.timestampMs ?: session.startedMs
            val end = points.lastOrNull()?.timestampMs ?: session.updatedMs
            w.write("{\n")
            w.write("  \"type\": \"FeatureCollection\",\n")
            w.write("  \"features\": [{\n")
            w.write("    \"type\": \"Feature\",\n")
            w.write("    \"properties\": {\n")
            w.write("      \"name\": \"${jsonEscape(session.name)}\",\n")
            w.write("      \"startedMs\": $start,\n")
            w.write("      \"endedMs\": $end,\n")
            w.write("      \"points\": ${points.size}\n")
            w.write("    },\n")
            w.write("    \"geometry\": {\n")
            w.write("      \"type\": \"LineString\",\n")
            w.write("      \"coordinates\": [")
            writeCoordinatesChunked(w, points)
            w.write("]\n")
            w.write("    }\n")
            w.write("  }]\n")
            w.write("}\n")
        }
    }

    /** 分块写坐标，每 1000 个点 flush 一次。 */
    private fun writeCoordinatesChunked(w: BufferedWriter, points: List<TrackPoint>) {
        val chunkSize = 1000
        var first = true
        var i = 0
        while (i < points.size) {
            val end = minOf(i + chunkSize, points.size)
            for (j in i until end) {
                if (!first) w.write(",")
                first = false
                val p = points[j]
                w.write("[")
                w.write(p.lng.toString())
                w.write(",")
                w.write(p.lat.toString())
                w.write("]")
            }
            w.flush()
            i = end
        }
    }

    private fun iso8601(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(ms))

    private fun escapeXml(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    private fun jsonEscape(s: String): String = s
        .replace("\\", "\\\\").replace("\"", "\\\"")
}