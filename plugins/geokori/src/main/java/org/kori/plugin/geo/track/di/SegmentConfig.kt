package org.kori.plugin.geo.track.di

import org.kori.plugin.geo.track.SegmentedTrackRecorder


/**
 * 分段配置。
 *
 * 记录过程中，**时长、距离、点数** 三个维度中**任一**达到阈值即触发切段。
 * 设成 0（或负数）表示该维度不触发。
 *
 * ## 使用场景
 *
 * | 场景 | 时长 | 距离 | 理由 |
 * |---|---|---|---|
 * | 步行 | 30 分钟 | 3 km | 短距离长时间，按时间切 |
 * | 骑行 | 60 分钟 | 20 km | 均等 |
 * | 驾车 | 30 分钟 | 50 km | 长途高速，按距离切 |
 * | 徒步穿越 | 120 分钟 | 10 km | 长时段短距离 |
 * | 马拉松 | 0 | 5 km | 只按距离 |
 * | 短程快递 | 0 | 0 | 不切段（用 MAX_POINTS 兜底） |
 *
 * ## 点数上限
 *
 * 无论 [durationMinutes] / [distanceMeters] 如何设置，
 * [SegmentedTrackRecorder] 都有硬上限 `MAX_POINTS_PER_SEGMENT = 20,000`，
 * 防止极端情况（长时间静止 + 高采样率）撑爆内存。
 */
data class SegmentConfig(
    /**
     * 时长阈值（分钟）。达到后切段。0 = 不按时长切。
     *
     * 建议范围：5–120 分钟。
     */
    val durationMinutes: Int = 30,

    /**
     * 距离阈值（米）。达到后切段。0 = 不按距离切。
     *
     * 建议范围：500–100,000 米。
     */
    val distanceMeters: Int = 5_000,
) {
    /** 时长阈值（毫秒）。 */
    val durationMs: Long
        get() = if (durationMinutes <= 0) 0L else durationMinutes * 60_000L

    /** 距离阈值（米，Double）。 */
    val distanceM: Double
        get() = if (distanceMeters <= 0) 0.0 else distanceMeters.toDouble()

    /** 是否两个维度都关闭（即不自动切段，仅靠点数上限兜底）。 */
    val isDisabled: Boolean
        get() = durationMinutes <= 0 && distanceMeters <= 0

    companion object {
        /** 默认：30 分钟 / 5 km。 */
        val Default = SegmentConfig()

        /** 步行：30 分钟 / 3 km。 */
        val Walk = SegmentConfig(durationMinutes = 30, distanceMeters = 3_000)

        /** 骑行：60 分钟 / 20 km。 */
        val Bike = SegmentConfig(durationMinutes = 60, distanceMeters = 20_000)

        /** 驾车：30 分钟 / 50 km。 */
        val Drive = SegmentConfig(durationMinutes = 30, distanceMeters = 50_000)

        /** 徒步穿越：120 分钟 / 10 km。 */
        val Trek = SegmentConfig(durationMinutes = 120, distanceMeters = 10_000)

        /** 只按距离：每 5 km 切一段。 */
        val DistanceOnly = SegmentConfig(durationMinutes = 0, distanceMeters = 5_000)

        /** 只按时长：每 15 分钟切一段。 */
        val DurationOnly = SegmentConfig(durationMinutes = 15, distanceMeters = 0)

        /** 单段：不自动切段（靠点数上限兜底）。 */
        val SingleSegment = SegmentConfig(durationMinutes = 0, distanceMeters = 0)
    }
}