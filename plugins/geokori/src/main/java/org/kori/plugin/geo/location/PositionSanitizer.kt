package org.kori.plugin.geo.location

import org.kori.plugin.geo.math.GeoMath
import org.maplibre.android.geometry.LatLng

/**
 * 位置过滤管线（状态化）。
 *
 * ## 改进（精度优化版）
 *
 *  1. **向预测点收敛（alpha-beta 思路）**：低通基准从"上一个已滤波位置"改为
 *     [predicted]（prev + 速度×dt 沿方位角外推）。匀速运动时不再系统性滞后，
 *     抖动抑制完全保留；无预测值时退化为原行为
 *  2. **按 profile 自适应**：步行/骑行/驾车有不同的 kMin
 *  3. **k 上升快、下降慢**：加速立刻跟上，减速时避免卡住
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
        /**
         * 预测位置：prev 按（速度 × dt）沿运动方位角外推。
         *
         * 提供后，低通向预测点而非 prev 收敛——匀速运动时消除系统性滞后，
         * 只在"真实加速度"上产生平滑。为 null 时退化为向 prev 收敛（旧行为）。
         */
        predicted: LatLng? = null,
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

        // ---- 基准点：优先预测位置，退化为上一位置 ----
        val base = predicted ?: prev

        val moved = GeoMath.haversineMeters(
            base.latitude, base.longitude,
            here.latitude, here.longitude,
        )
        val sp = lastSpeedMps ?: 0f

        // ---- Outlier 门控（以预测点为基准判定）----
        // 注意：与 FixRules.isPhysicallyPlausible 的容差匹配（见那儿的新公式）
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
        // 加速时立刻跟上（k 用新值），减速时缓慢下降（k 用旧值的 70%）
        val k = if (kTarget >= lastK) kTarget else (lastK * 0.7f + kTarget * 0.3f)
        lastK = k

        return LatLng(
            base.latitude + (here.latitude - base.latitude) * k,
            base.longitude + (here.longitude - base.longitude) * k,
        )
    }

    fun reset() {
        outlierStreak[0] = 0
        lastK = 0f
    }
}