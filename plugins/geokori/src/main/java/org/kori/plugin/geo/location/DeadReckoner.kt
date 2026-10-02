package org.kori.plugin.geo.location

import org.kori.plugin.geo.math.GeoMath
import org.maplibre.android.geometry.LatLng
import kotlin.math.cos
import kotlin.math.sin

/**
 * 无 GPS fix 时的位置推算（隧道 / 桥下 / 高楼间）。
 *
 * ## 状态机
 *
 *  · 启动条件：无 fix 超过 [START_AFTER_MS]，速度 > profile.deadReckonSpeed
 *  · 停止条件：收到新 fix，或速度衰减到 < 0.5 m/s，或累计推算超过 profile.deadReckonMaxS
 *  · 速度衰减：无新证据时速度按 tau 衰减（不再假设还在移动）
 *  · 方向保持：用最后已知的 bearing，无 bearing 时不推算
 *
 * ## 线程安全
 *
 * [tick] 从 ticker 线程（Dispatchers.Default）以 10Hz 调用；
 * [onFix] 从主线程（LocationManager 回调）以 ~1Hz 调用。
 * 两者都对 `active` / `accumulatedM` 做读-改-写，**必须加锁**——
 * 否则会看到 "onFix 复位 active=false 后被 tick 的 active=true 覆盖" 的竞态：
 * 隧道出口收到真实 fix 后 DR 仍继续推算，污染位置。
 *
 * ## 与 SpeedKalman 的锁顺序
 *
 * [tick] 在持有本类锁时调用 [SpeedKalman.decay]（获取后者锁）。
 * [LocationTracker.tick] 只获取 SpeedKalman 锁，不获取本类锁。
 * [onFix] 只获取本类锁。
 * 因此本类锁与 SpeedKalman 锁的获取顺序在所有路径上一致（本类 → SpeedKalman 或单锁），无死锁风险。
 */
class DeadReckoner {

    private val lock = Any()

    private var accumulatedM: Double = 0.0
    private var active: Boolean = false

    /**
     * 推进状态机。
     *
     * @param sinceLastFixMs 距上一次 GPS fix 的毫秒数
     * @param speedMps 当前速度（来自 SpeedKalman）
     * @param bearingDeg 当前方位角（null = 无方位，无法推算）
     * @param lastPos 最后一次已知位置（DR 位置，非真实 fix）
     * @param profile 当前活动类型
     * @param kalman 速度卡尔曼（会被 decay 修改）
     * @param dtSeconds 本次 tick 的时长
     * @return 推算出的新位置，或 null（未激活/停止）
     */
    fun tick(
        sinceLastFixMs: Long,
        speedMps: Double,
        bearingDeg: Float?,
        lastPos: LatLng,
        profile: ActivityProfile,
        kalman: SpeedKalman,
        dtSeconds: Double,
    ): LatLng? {
        val bearing = bearingDeg ?: return null

        return synchronized(lock) {
            // 启动条件
            if (!active) {
                if (sinceLastFixMs < START_AFTER_MS) return@synchronized null
                if (speedMps < profile.deadReckonSpeed) return@synchronized null
                active = true
                accumulatedM = 0.0
            }

            // 停止条件
            if (speedMps < 0.5 || accumulatedM >= profile.deadReckonMaxS) {
                active = false
                return@synchronized null
            }

            // 积分位移
            val dist = speedMps * dtSeconds
            accumulatedM += dist
            val rad = Math.toRadians(bearing.toDouble())
            val dLat = dist * cos(rad) / GeoMath.METERS_PER_DEG_LAT
            val dLng = dist * sin(rad) / GeoMath.metersPerDegLng(lastPos.latitude)

            // 速度衰减（在锁内调用，获取 SpeedKalman 的锁）
            kalman.decay(dtSeconds, profile.speedDecayTau)

            LatLng(lastPos.latitude + dLat, lastPos.longitude + dLng)
        }
    }

    /**
     * 收到新 fix 时调用，重置累计距离并关闭 DR。
     *
     * **注意**：这个调用会竞争 [tick] 里的 `active = true`——加锁保证可见性和
     * 顺序：如果 onFix 先执行，tick 会看到 active=false 而跳过启动；
     * 如果 tick 先执行，onFix 会把 active 复位为 false，下一帧 tick 重新判定。
     */
    fun onFix() {
        synchronized(lock) {
            active = false
            accumulatedM = 0.0
        }
    }

    /** 当前是否在推算。加锁读取以跨线程安全。 */
    fun isActive(): Boolean = synchronized(lock) { active }

    companion object {
        private const val START_AFTER_MS = 3_000L
    }
}