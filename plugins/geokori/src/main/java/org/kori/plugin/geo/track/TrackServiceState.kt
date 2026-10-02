package org.kori.plugin.geo.track

/** 面板状态。与 TrackRecordingState 解耦，避免 UI 依赖记录引擎。 */
data class TrackServiceState(
    val recording: Boolean = false,
    val points: Int = 0,
    val distanceM: Double = 0.0,
    val elapsedMs: Long = 0L,
    val segments: Int = 0,
)