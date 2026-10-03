package org.kori.plugin.geo.track

import org.kori.plugin.geo.math.GeoMath
import org.kori.plugin.geo.track.di.TrackPoint
import java.util.concurrent.atomic.AtomicInteger

/**
 * 轨迹点入口过滤。
 *
 * ## 目标
 *
 * **拒绝 GPS 漂移点**，同时不误杀真实移动。
 *
 * ## 六条规则（按顺序）
 *
 *  1. **精度门控**：`accuracyM > maxAccuracyM` 直接拒绝
 *  2. **首点必收**：第一个保留点无条件写入
 *  3. **物理可能性**：与上一点距离超过物理上限 → 拒绝
 *  4. **静止漂移抑制**：速度极低但移动明显 → 疑似漂移，连续多次同一区域才接受
 *  5. **运动开始锚点**：从静止转移动的转变点，即使距离小也保留
 *  6. **自适应最小距离**：距离 < max(1m, 0.5·v·dt, accuracy·0.3) → 拒绝
 *
 * ## 状态化
 *
 *  · [driftStreak]：连续漂移次数，让真实的大距离移动在 3 次后通过
 *  · [rejectedCount]：累计拒绝数（供 UI 显示"拒绝 N 点"）
 */
class TrackFilter(
    /** 精度差于此值的点直接拒绝。40m 覆盖城市峡谷；乡村 GPS 通常 < 10m。 */
    private val maxAccuracyM: Float = 40f,
    /** 静止判定速度阈值（m/s）。 */
    private val stillSpeedMps: Float = 0.5f,
    /** 静止时"漂移"的距离阈值（m）。小于此距离即使速度低也接受。 */
    private val driftDistanceM: Double = 8.0,
    /** 连续 N 次同一区域的"漂移"就接受（防误杀真实移动）。 */
    private val driftStreakToAccept: Int = 3,
    /** 连续漂移判定时的"同一区域"半径（m）。 */
    private val driftZoneM: Double = 15.0,
) {

    // =============================================================================================
    // 状态
    // =============================================================================================

    private var driftStreak: Int = 0
    private var driftLat: Double = 0.0
    private var driftLng: Double = 0.0

    private var lastKeptSpeedMps: Float = 0f

    /** 累计拒绝数。UI 可显示"拒绝 N 点"。 */
    private val _rejectedCount = AtomicInteger(0)

    /** 累计拒绝数。 */
    val rejectedCount: Int
        get() = _rejectedCount.get()

    // =============================================================================================
    // 判定
    // =============================================================================================

    /**
     * 判断一个候选点是否应写入轨迹。
     *
     * @param candidate 候选点
     * @param lastKept 上一次被保留的点（null = 这是第一个点）
     * @return true = 写入，false = 拒绝
     */
    fun shouldKeep(candidate: TrackPoint, lastKept: TrackPoint?): Boolean {
        val keep = evaluate(candidate, lastKept)
        if (!keep) _rejectedCount.incrementAndGet()
        return keep
    }

    private fun evaluate(candidate: TrackPoint, lastKept: TrackPoint?): Boolean {
        // 1. 精度门控
        val acc = candidate.accuracyM
        if (acc != null && acc > maxAccuracyM) {
            driftStreak = 0
            return false
        }

        // 2. 首点必收
        if (lastKept == null) {
            driftStreak = 0
            lastKeptSpeedMps = candidate.speedMps ?: 0f
            return true
        }

        val dist = GeoMath.haversineMeters(
            lastKept.lat, lastKept.lng,
            candidate.lat, candidate.lng,
        )
        val dtSec = ((candidate.elapsedNanos - lastKept.elapsedNanos) / 1e9)
            .coerceAtLeast(0.1)

        // 3. 物理可能性（最高 30 m/s ≈ 108 km/h + 35m 的 GPS 波动余量）
        val plausible = 30.0 * dtSec + 35.0
        if (dist > plausible) {
            driftStreak = 0
            return false
        }

        val reportedSpeed = candidate.speedMps ?: 0f

        // 4. 静止漂移抑制
        if (reportedSpeed < stillSpeedMps && dist > driftDistanceM) {
            val nearPreviousDrift = driftStreak > 0 &&
                    GeoMath.haversineMeters(
                        driftLat, driftLng,
                        candidate.lat, candidate.lng,
                    ) < driftZoneM

            if (nearPreviousDrift) {
                driftStreak++
            } else {
                driftStreak = 1
                driftLat = candidate.lat
                driftLng = candidate.lng
            }

            if (driftStreak >= driftStreakToAccept) {
                // 连续 N 次在同一区域 → 用户真的移动了，接受
                driftStreak = 0
                lastKeptSpeedMps = reportedSpeed
                return true
            }
            return false
        }
        driftStreak = 0

        // 5. 运动开始锚点（从静止到移动的转变点）
        if (lastKeptSpeedMps < 0.3f && reportedSpeed >= 1.0f && dist > 3.0) {
            lastKeptSpeedMps = reportedSpeed
            return true
        }

        // 6. 自适应最小距离
        val adaptiveMinDist = maxOf(
            1.0,
            0.5 * reportedSpeed * dtSec,
            (acc ?: 0f) * 0.3,
        )
        if (dist < adaptiveMinDist) {
            return false
        }

        lastKeptSpeedMps = reportedSpeed
        return true
    }

    /** 重置过滤状态（开始新轨迹时调用）。 */
    fun reset() {
        driftStreak = 0
        driftLat = 0.0
        driftLng = 0.0
        lastKeptSpeedMps = 0f
        _rejectedCount.set(0)
    }
}