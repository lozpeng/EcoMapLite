package org.kori.plugin.geo.track.di

import org.kori.plugin.geo.track.TrackEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一个轨迹会话。
 *
 * ## 概念
 *
 * 一次"开始记录 → 停止记录"产生一个 session。session 里包含：
 *  · **多个 segment**：按 [org.kori.plugin.geo.track.SegmentConfig] 自动切分的段，每段有 raw + smooth 两个文件
 *  · **多个 media**：用户拍照 / 录音 / 录像的附件
 *  · **统计信息**：总点数、总距离、时长、段数等
 *
 * ## 目录布局
 *
 * ```
 * filesDir/tracks/{sessionId}/
 *   ├─ session.json                   会话元数据（本类的序列化形式）
 *   ├─ seg-001.raw.csv                第 1 段原始轨迹
 *   ├─ seg-001.smooth.csv             第 1 段平滑轨迹
 *   ├─ seg-002.raw.csv
 *   ├─ seg-002.smooth.csv
 *   └─ media/
 *       ├─ photo-20261002-153045.jpg
 *       ├─ photo-20261002-153045.json
 *       ├─ audio-20261002-154012.m4a
 *       ├─ audio-20261002-154012.json
 *       └─ video-20261002-160233.mp4
 *       └─ video-20261002-160233.json
 * ```
 *
 * ## 生命周期
 *
 *  · [startSession] 时创建目录 + 写初始 JSON
 *  · 记录过程中每切一段就 close 一段（写文件）
 *  · [endSession] 时写最终 JSON（含所有统计）
 *  · 之后只读——通过 [org.kori.plugin.geo.track.TrackSessionStore.readSession] 从磁盘加载
 */
data class TrackSession(
    // =============================================================================================
    // 标识
    // =============================================================================================

    /**
     * 会话 ID（同时是目录名）。
     *
     * 格式：`{nameHint}-{yyyyMMdd-HHmmss}`
     * 示例：`session-20261002-153045`
     */
    val id: String,

    /**
     * 会话目录（绝对路径）。
     *
     * 所有文件路径都相对于此目录。
     */
    val dir: File,

    /**
     * 会话名称（用户可见）。
     *
     * 初始等于 [id] 的 nameHint 部分，用户可后续重命名。
     */
    val name: String,

    // =============================================================================================
    // 时间
    // =============================================================================================

    /** 会话开始时间（墙钟毫秒）。 */
    val startedMs: Long,

    /**
     * 会话最后更新时间（墙钟毫秒）。
     *
     * 可以是：
     *  · 记录停止时的时间
     *  · 记录中途每次切段的时间
     *  · 用户重命名的时间
     */
    val updatedMs: Long,

    // =============================================================================================
    // 段
    // =============================================================================================

    /** 所有段（按 index 升序）。 */
    val segments: List<Segment>,

    // =============================================================================================
    // 媒体
    // =============================================================================================

    /** 所有媒体附件（按 timestamp 升序）。 */
    val media: List<TrackMediaRecord>,

    // =============================================================================================
    // 统计
    // =============================================================================================

    /** 总原始点数（所有段之和，过滤后）。 */
    val totalRawPoints: Int,

    /** 总平滑点数（应等于 [totalRawPoints]，因为一一对应）。 */
    val totalSmoothPoints: Int,

    /** 总距离（米）。 */
    val totalDistanceM: Double,

    // =============================================================================================
    // 可选元数据
    // =============================================================================================

    /**
     * 会话备注（用户可编辑）。
     *
     * 与 [TrackMediaRecord.note] 不同——那是附件级备注，这是会话级。
     */
    val note: String? = null,

    /**
     * 用户标签（用于分类 / 过滤）。
     *
     * 例如 `["跑步", "晨练"]`。
     */
    val tags: List<String> = emptyList(),

    /**
     * 记录过程中的事件（暂停/继续等），由 [org.kori.plugin.geo.track.SegmentedTrackRecorder.recordEvent] 写入。
     *
     * v2 及更早的会话无此字段（空列表）。
     */
    val events: List<TrackEvent> = emptyList(),

    /**
     * 会话数据版本号。
     *
     * 用于未来 schema 升级时兼容旧文件。
     *  · v1：仅位置数据
     *  · v2：加入传感器数据（当前）
     */
    val version: Int = 2,
) {

    // =============================================================================================
    // 计算属性
    // =============================================================================================

    /** 会话时长（毫秒）。 */
    val durationMs: Long
        get() = (updatedMs - startedMs).coerceAtLeast(0L)

    /** 会话时长（秒）。 */
    val durationSec: Double
        get() = durationMs / 1000.0

    /** 段数。 */
    val segmentCount: Int
        get() = segments.size

    /** 媒体数量。 */
    val mediaCount: Int
        get() = media.size

    /**
     * 平均速度（m/s）。
     *
     * `总距离 / 时长`。时长为 0 时返回 0。
     */
    val averageSpeedMps: Double
        get() {
            val sec = durationSec
            return if (sec > 0.0) totalDistanceM / sec else 0.0
        }

    /**
     * 平均速度（km/h）。
     */
    val averageSpeedKmh: Double
        get() = averageSpeedMps * 3.6

    /**
     * 每段平均点数。
     */
    val averagePointsPerSegment: Int
        get() = if (segmentCount > 0) totalRawPoints / segmentCount else 0

    /**
     * 是否为空会话（无任何段和媒体）。
     *
     * 空会话通常因为用户立即停止，无实际轨迹数据。
     */
    val isEmpty: Boolean
        get() = segments.isEmpty() && media.isEmpty()

    /**
     * 是否有媒体附件。
     */
    val hasMedia: Boolean
        get() = media.isNotEmpty()

    /**
     * 是否包含传感器数据。
     *
     * 通过检查所有段的原始文件是否标注了 v2 格式判定。
     */
    val hasSensorData: Boolean
        get() = version >= 2 && totalRawPoints > 0

    /**
     * 格式化的时长字符串。
     *
     * · < 1 小时：`MM:SS`
     * · ≥ 1 小时：`HH:MM:SS`
     */
    val durationText: String
        get() {
            val totalSec = durationMs / 1000
            val h = totalSec / 3600
            val m = (totalSec % 3600) / 60
            val s = totalSec % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s)
            else "%02d:%02d".format(m, s)
        }

    /** 格式化的距离字符串（保留两位小数）。 */
    val distanceText: String
        get() = "%.2f km".format(totalDistanceM / 1000)

    /**
     * 格式化的速度字符串。
     */
    val averageSpeedText: String
        get() = "%.1f km/h".format(averageSpeedKmh)

    // =============================================================================================
    // 文件访问
    // =============================================================================================

    /**
     * 用相对路径解析出绝对文件。
     *
     * @param relativePath 相对路径，如 `"media/photo-xxx.jpg"` 或 `"seg-001.raw.csv"`
     */
    fun resolve(relativePath: String): File = File(dir, relativePath)

    /**
     * 会话元数据 JSON 文件。
     */
    val metaFile: File
        get() = File(dir, "session.json")

    /**
     * 媒体目录。
     */
    val mediaDir: File
        get() = File(dir, "media")

    /**
     * 列出所有段的原始文件。
     */
    val rawFiles: List<File>
        get() = segments.map { it.rawFile }

    /**
     * 列出所有段的平滑文件（可能为 null）。
     */
    val smoothFiles: List<File>
        get() = segments.mapNotNull { it.smoothFile }

    // =============================================================================================
    // 子结构
    // =============================================================================================

    /**
     * 一个分段。
     *
     * 每段包含：
     *  · [rawFile]：原始 GPS + 传感器
     *  · [smoothFile]：位置平滑后
     *  · [points]：本段的点数（原始）
     */
    data class Segment(
        /**
         * 段索引（从 1 开始）。
         *
         * 对应文件名 `seg-001.raw.csv` / `seg-001.smooth.csv`。
         */
        val index: Int,

        /**
         * 原始文件。
         */
        val rawFile: File,

        /**
         * 平滑文件。
         *
         * 可能为 null（旧版本只保存 raw，或简化后无点）。
         */
        val smoothFile: File?,

        /**
         * 本段原始点数（过滤后）。
         */
        val points: Int,

        /**
         * 本段时长（毫秒）。
         *
         * 可选——从 CSV 里的时间戳推导。
         */
        val durationMs: Long = 0L,

        /**
         * 本段距离（米）。
         *
         * 可选——从 CSV 里的坐标推导。
         */
        val distanceM: Double = 0.0,
    ) {
        /** 段的显示名（如 "第 1 段"）。 */
        val displayName: String
            get() = "第 $index 段"

        /** 是否有平滑文件。 */
        val hasSmooth: Boolean
            get() = smoothFile != null && smoothFile.exists()

        /** 原始文件是否存在。 */
        val rawExists: Boolean
            get() = rawFile.exists()
    }

    // =============================================================================================
    // 序列化 / 反序列化
    // =============================================================================================

    companion object {

        /**
         * 生成新的会话 ID。
         *
         * @param nameHint 名称提示（如 "morning-ride"），空则用 "session"
         * @param atMs     时间戳（默认当前时间）
         */
        fun generateId(nameHint: String? = null, atMs: Long = System.currentTimeMillis()): String {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                .format(Date(atMs))
            val safe = nameHint
                ?.replace(Regex("[^A-Za-z0-9_-]"), "_")
                ?.take(24)
                ?.takeIf { it.isNotBlank() }
                ?: "session"
            return "$safe-$stamp"
        }

        /**
         * 从目录名推导一个默认显示名。
         *
         * `session-20261002-153045` → `session`
         * `morning-run-20261002-153045` → `morning-run`
         */
        fun displayNameFromId(id: String): String {
            val idx = id.lastIndexOf('-')
            if (idx <= 0) return id
            val beforeLastDash = id.substring(0, idx)
            val idx2 = beforeLastDash.lastIndexOf('-')
            if (idx2 <= 0) return beforeLastDash
            return beforeLastDash.substring(0, idx2)
        }
    }
}