package org.kori.plugin.geo.track.di

import org.json.JSONObject
import org.kori.plugin.geo.track.TrackEvent
import org.kori.plugin.geo.track.TrackEventType
import java.io.File

/**
 * 从磁盘读取会话（B7 修复版）。
 *
 * ## B7 修复
 *
 * 不再遍历文件计数——点数从 `session.json` 的 `totalRawPoints` 字段读。
 * 老版本没有该字段的会话回退到 0。
 */
object TrackSessionStore {

    fun list(root: File): List<TrackSession> =
        root.listFiles { f -> f.isDirectory }
            ?.mapNotNull { readSession(it) }
            ?.sortedByDescending { it.startedMs }
            .orEmpty()

    fun readSession(dir: File): TrackSession? {
        val jsonFile = File(dir, "session.json")
        if (!jsonFile.exists()) return null
        val json = runCatching { JSONObject(jsonFile.readText()) }.getOrNull() ?: return null

        // 从 JSON 读总点数（B7 修复）
        val totalRaw = json.optInt("totalRawPoints", -1)
        val totalSmooth = json.optInt("totalSmoothPoints", 0)

        val rawFiles = dir.listFiles { f ->
            f.name.startsWith("seg-") && f.name.endsWith(".raw.csv")
        }.orEmpty()

        val segments = rawFiles.mapNotNull { rawFile ->
            val base = rawFile.name.removeSuffix(".raw.csv")
            val index = base.removePrefix("seg-").toIntOrNull() ?: return@mapNotNull null
            val smoothFile = File(dir, "$base.smooth.csv").takeIf { it.exists() }
            TrackSession.Segment(
                index = index,
                rawFile = rawFile,
                smoothFile = smoothFile,
                points = readSegmentPointCount(rawFile),
            )
        }.sortedBy { it.index }

        val media = readMedia(dir)
        val events = readEvents(json)

        return TrackSession(
            id = dir.name,
            dir = dir,
            name = json.optString("name", "session"),
            startedMs = json.optLong("startedMs", dir.lastModified()),
            updatedMs = json.optLong("updatedMs", dir.lastModified()),
            segments = segments,
            media = media,
            totalRawPoints = if (totalRaw > 0) totalRaw else segments.sumOf { it.points },
            totalSmoothPoints = totalSmooth,
            totalDistanceM = json.optDouble("totalDistanceM", 0.0),
            events = events,
        )
    }
    /**
     * 从段文件头部的 `# points N` 注释读取点数。
     *
     * 比读取整个 CSV 便宜得多——段文件由 [org.kori.plugin.geo.track.TrackStore.write] 生成，
     * 格式固定：
     * ```
     * # vela-track v1
     * # started ...
     * # ended ...
     * # points 245
     * timestamp_ms,...
     * ```
     *
     * 老版本文件若没有该注释，返回 0；调用方可回退到均分逻辑。
     */
    private fun readSegmentPointCount(file: File): Int = runCatching {
        file.bufferedReader().useLines { lines ->
            lines.take(8)
                .firstOrNull { it.startsWith("# points ") }
                ?.substringAfter("# points ")
                ?.trim()
                ?.toIntOrNull()
        } ?: 0
    }.getOrDefault(0)
    fun delete(session: TrackSession) {
        session.dir.deleteRecursively()
    }

    /** 解析 session.json 的 events 数组（v2 及更早无此字段 → 空列表）。 */
    private fun readEvents(json: JSONObject): List<TrackEvent> =
        json.optJSONArray("events")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                runCatching {
                    val o = arr.getJSONObject(i)
                    TrackEvent(
                        type = TrackEventType.valueOf(o.getString("type")),
                        timestampMs = o.getLong("timestampMs"),
                        lat = o.optDouble("lat", 0.0),
                        lng = o.optDouble("lng", 0.0),
                    )
                }.getOrNull()
            }
        }.orEmpty()

    private fun readMedia(dir: File): List<TrackMediaRecord> {
        val mediaDir = File(dir, "media")
        if (!mediaDir.exists()) return emptyList()
        return mediaDir.listFiles { f -> f.extension.equals("json", true) }
            ?.mapNotNull { readMediaJson(it) }
            ?.sortedBy { it.timestampMs }
            .orEmpty()
    }

    private fun readMediaJson(jsonFile: File): TrackMediaRecord? {
        val json = runCatching { JSONObject(jsonFile.readText()) }.getOrNull() ?: return null
        return runCatching {
            TrackMediaRecord(
                type = TrackMediaRecord.Type.valueOf(json.getString("type")),
                filePath = json.getString("filePath"),
                timestampMs = json.getLong("timestampMs"),
                lat = json.getDouble("lat"),
                lng = json.getDouble("lng"),
                accuracyM = if (json.has("accuracyM")) json.getDouble("accuracyM").toFloat() else null,
                durationSec = if (json.has("durationSec")) json.getDouble("durationSec") else null,
                note = if (json.has("note")) json.getString("note") else null,
            )
        }.getOrNull()
    }
}