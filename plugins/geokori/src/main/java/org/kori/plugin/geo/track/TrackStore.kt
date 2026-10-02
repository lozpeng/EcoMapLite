package org.kori.plugin.geo.track

import java.io.File

/**
 * 轨迹文件读写（CSV 格式）。
 *
 * 文件格式：
 * ```
 * # vela-track v1
 * # started 1696204800000
 * # ended   1696208400000
 * # points  1234
 * timestamp_ms,elapsed_nanos,lat,lng,alt_m,speed_mps,bearing_deg,accuracy_m
 * 1696204800123,1234567890123,39.9091234,116.3976543,45.2,1.35,128.7,4.2
 * ...
 * ```
 *
 * CSV 而非二进制：可读、可调试、易分享、跨平台。
 */
object TrackStore {

    fun write(file: File, points: List<TrackPoint>) {
        file.parentFile?.mkdirs()
        file.bufferedWriter().use { w ->
            w.write("# vela-track v1\n")
            w.write("# started ${points.firstOrNull()?.timestampMs ?: 0}\n")
            w.write("# ended ${points.lastOrNull()?.timestampMs ?: 0}\n")
            w.write("# points ${points.size}\n")
            w.write("timestamp_ms,elapsed_nanos,lat,lng,alt_m,speed_mps,bearing_deg,accuracy_m\n")
            for (p in points) {
                w.write("${p.timestampMs},${p.elapsedNanos},${p.lat},${p.lng},")
                w.write("${p.altitudeM ?: ""},${p.speedMps ?: ""},${p.bearingDeg ?: ""},${p.accuracyM ?: ""}\n")
            }
        }
    }

    fun read(file: File): List<TrackPoint> {
        if (!file.exists()) return emptyList()
        val out = ArrayList<TrackPoint>()
        file.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val t = line.trim()
                if (t.isEmpty() || t.startsWith("#") || t.startsWith("timestamp_ms")) return@forEach
                val parts = t.split(',')
                if (parts.size < 8) return@forEach
                runCatching {
                    out.add(
                        TrackPoint(
                            lat = parts[2].toDouble(),
                            lng = parts[3].toDouble(),
                            altitudeM = parts[4].takeIf { it.isNotBlank() }?.toDouble(),
                            speedMps = parts[5].takeIf { it.isNotBlank() }?.toFloat(),
                            bearingDeg = parts[6].takeIf { it.isNotBlank() }?.toFloat(),
                            accuracyM = parts[7].takeIf { it.isNotBlank() }?.toFloat(),
                            timestampMs = parts[0].toLong(),
                            elapsedNanos = parts[1].toLong(),
                        ),
                    )
                }
            }
        }
        return out
    }

    /** 列出目录下所有轨迹文件，按开始时间倒序。 */
    fun list(dir: File): List<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".csv") }
            ?.sortedByDescending { it.name }
            .orEmpty()

    /** 生成新轨迹文件。 */
    fun newFile(dir: File, nameHint: String? = null): File {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val safe = nameHint?.replace(Regex("[^A-Za-z0-9_-]"), "_")?.take(24) ?: "track"
        return File(dir, "$safe-$stamp.csv")
    }
}