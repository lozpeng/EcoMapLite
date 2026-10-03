package org.kori.plugin.geo.track.di

import org.kori.plugin.geo.track.TrackRecordingEngine

/**
 * 轨迹记录状态。
 *
 * 由 [TrackRecordingEngine.state] 暴露。UI 通过 `collectAsState()` 订阅。
 *
 * ## 字段说明
 *
 *  · **记录状态**：[recording] / [paused] / [points] / [distanceM] / [elapsedMs] / [segments]
 *  · **实时轨迹**：[liveTrackPoints] / [liveSmoothPoints] / [liveMedia]
 *  · **会话列表**：[sessions] / [loadingSessions]
 */
data class TrackRecordingState(
    // =============================================================================================
    // 记录状态
    // =============================================================================================

    /** 是否正在记录。 */
    val recording: Boolean = false,

    /**
     * 是否已暂停。
     *
     * true 时距离/计时冻结，不再写入轨迹点。仅 [recording] = true 时有意义。
     */
    val paused: Boolean = false,

    /** 已保留的轨迹点数（原始过滤后）。 */
    val points: Int = 0,

    /** 累计距离（米）。暂停期间不累计。 */
    val distanceM: Double = 0.0,

    /** 记录时长（毫秒），从会话开始时算起，**已扣除暂停时长**。 */
    val elapsedMs: Long = 0L,

    /** 当前速度（m/s，滤波管线输出；暂停时保留最后值）。 */
    val currentSpeedMps: Float? = null,

    /** 当前纬度（WGS84；未定位时为 null）。 */
    val currentLat: Double? = null,

    /** 当前经度。 */
    val currentLng: Double? = null,

    /** 已完成的分段数。 */
    val segments: Int = 0,

    // =============================================================================================
    // 实时数据（供地图绘制）
    // =============================================================================================

    /** 实时原始轨迹点（尾部 LIVE_BUFFER_MAX 个）。 */
    val liveTrackPoints: List<TrackPoint> = emptyList(),

    /** 实时平滑轨迹点（与 [liveTrackPoints] 一一对应）。 */
    val liveSmoothPoints: List<TrackPoint> = emptyList(),

    /** 实时媒体附件列表。 */
    val liveMedia: List<TrackMediaRecord> = emptyList(),

    // =============================================================================================
    // 会话列表
    // =============================================================================================

    /** 已保存的会话列表（按时间倒序）。 */
    val sessions: List<TrackSession> = emptyList(),

    /** 是否正在加载会话列表。 */
    val loadingSessions: Boolean = false,

    /**
     * 历史轨迹叠加层（历史浏览时显示在地图上）。
     *
     * 每个元素为一条轨迹点序列（一个会话一条）。由 [TrackRecordingEngine.loadHistoryOnMap] 写入。
     */
    val historySegments: List<List<TrackPoint>> = emptyList(),
) {
    /** 是否完全空闲（未记录且无实时数据）。 */
    val isIdle: Boolean
        get() = !recording &&
                liveTrackPoints.isEmpty() &&
                liveSmoothPoints.isEmpty()

    /**
     * 格式化的时长字符串。
     *
     * · < 1 小时：`MM:SS`
     * · ≥ 1 小时：`HH:MM:SS`
     */
    val elapsedText: String
        get() {
            val totalSec = elapsedMs / 1000
            val h = totalSec / 3600
            val m = (totalSec % 3600) / 60
            val s = totalSec % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s)
            else "%02d:%02d".format(m, s)
        }

    /** 格式化的距离字符串（保留两位小数）。 */
    val distanceText: String
        get() = "%.2f km".format(distanceM / 1000)

    /** 一段人类可读的摘要，用于通知栏。 */
    val summaryText: String
        get() = when {
            !recording -> "未记录"
            paused -> "已暂停 · $points 点 · $distanceText"
            else -> "$elapsedText · $points 点 · $distanceText"
        }
}