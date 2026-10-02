package org.kori.plugin.geo.location

import org.kori.plugin.geo.math.GeoMath
import org.maplibre.android.geometry.LatLng

/**
 * 位置过滤管线（状态化）。
 *
 * 相比之前的版本，关键改动：
 *  1. **按 profile 自适应** —— 步行/骑行/驾车有不同的 kMin
 *  2. **k 上升快、下降慢** —— 加速立刻跟上，减速时避免卡住
 *  3. **低速下限** —— 静止时也允许缓慢漂移（避免完全冻结）
 */
class PositionSanitizer {

    private val outlierStreak = intArrayOf(0)
    private var lastK: Float = 0f

    fun sanitize(
        here: LatLng,
        prev: LatLng?,
        lastSpeedMps: Float?,
        dtSeconds: Double,
        wasUpgrade: Boolean = false,
        profile: ActivityProfile = ActivityProfile.DRIVING,
    ): LatLng {
        if (prev == null || dtSeconds < 0.0) {
            outlierStreak[0] = 0
            lastK = profile.kMin
            return here
        }
        if (wasUpgrade) {
            outlierStreak[0] = 0
            lastK = profile.kMin
            return here
        }

        val moved = GeoMath.haversineMeters(
            prev.latitude, prev.longitude,
            here.latitude, here.longitude,
        )
        val sp = lastSpeedMps ?: 0f

        // ---- Outlier 门控 ----
        if (!FixRules.isPhysicallyPlausible(moved, sp, dtSeconds)) {
            if (outlierStreak[0] >= 2) {
                outlierStreak[0] = 0
                return here
            }
            outlierStreak[0]++
            return prev
        }
        outlierStreak[0] = 0

        // ---- 自适应低通 + 非对称 k ----
        val kTarget = profile.mixFactor(sp)
        // 加速时立刻跟上（k 用新值），减速时缓慢下降（k 用旧值的 80%）
        val k = if (kTarget >= lastK) kTarget else (lastK * 0.7f + kTarget * 0.3f)
        lastK = k

        return LatLng(
            prev.latitude + (here.latitude - prev.latitude) * k,
            prev.longitude + (here.longitude - prev.longitude) * k,
        )
    }

    fun reset() {
        outlierStreak[0] = 0
        lastK = 0f
    }
}