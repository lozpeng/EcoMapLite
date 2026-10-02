package org.kori.plugin.geo.location

import kotlin.math.abs

/**
 * 速度测量门控（状态化）。
 *
 * 单一 outlier 拒绝，连续 outlier 接受（自愈）——防止 doppler 跳变污染速度显示，
 * 又不会因一次 hard brake 卡住显示。
 *
 * 典型场景：
 *  · 60 km/h 巡航中，一次 fix 报 0（隧道出口第一次 fix 的典型 doppler 噪声）→ 拒绝
 *  · 连续两次报 0 → 接受（真的停车了）
 */
class SpeedGate {

    private var base: Float? = null
    private var streak = 0

    /**
     * @param rawMps 本次 fix 实测的速度（m/s）
     * @param dtSeconds 与上次 fix 的间隔
     * @return 接受则返回 [rawMps]；拒绝则返回 null（调用方应保持上次显示的速度）
     */
    fun gate(rawMps: Float, dtSeconds: Double): Float? {
        val b = base
        // 允许的偏差：8 m/s² × dt 内的加速度 + 5 m/s 的宽松余量
        val bound = (8.0 * dtSeconds.coerceIn(0.5, 3.0) + 5.0).toFloat()
        return if (b != null && dtSeconds > 0.0 && dtSeconds <= 3.0 &&
            abs(rawMps - b) > bound && streak == 0
        ) {
            streak = 1
            null  // 单次 outlier → 拒绝
        } else {
            streak = 0
            base = rawMps
            rawMps
        }
    }

    /** 重置（会话切换）。 */
    fun reset() {
        base = null
        streak = 0
    }
}