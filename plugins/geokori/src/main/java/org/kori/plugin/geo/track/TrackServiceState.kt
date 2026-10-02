package org.kori.plugin.geo.track

/** 面板状态。与 TrackRecordingState 解耦，避免 UI 依赖记录引擎。 */
data class TrackServiceState(
    val recording: Boolean = false,

    /**
     * 是否已暂停。
     *
     * true 时：不再写入轨迹点、距离与计时停止累计、通知栏显示"已暂停"。
     * 只有 [recording] = true 时此字段才有意义。
     */
    val paused: Boolean = false,

    val points: Int = 0,
    val distanceM: Double = 0.0,
    val elapsedMs: Long = 0L,
    val segments: Int = 0,
)