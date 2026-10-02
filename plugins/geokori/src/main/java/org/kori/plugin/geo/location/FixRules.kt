package org.kori.plugin.geo.location

/**
 * 位置 fix 的判定规则（纯函数）。
 *
 * 这些规则从 OsmAnd、Organic Maps 的实际工程经验提炼，处理三件事：
 *  1. **精度是否比上一个更好**（网络 fix 必须优于 GPS 才能采用）
 *  2. **是否为 outlier**（一个跳跃是否超出物理可能）
 *  3. **是否应为升级**（精度提升 2 倍以上时立即接受，不必等 outlier 缓冲）
 */
object FixRules {

    /** GPS 是否明显优于当前 fix，应直接接受（跳过 outlier 缓冲）。 */
    fun isUpgrade(prevAccuracyM: Float?, newAccuracyM: Float): Boolean {
        if (prevAccuracyM == null || newAccuracyM <= 0f) return false
        // 新 fix 精度至少是旧的 2 倍，且旧的 >= 50m（很差的），才视为"升级"
        return prevAccuracyM >= 50f && newAccuracyM <= prevAccuracyM / 2.0f
    }

    /**
     * 网络 fix 的"是否值得采用"判定（OsmAnd 的 isLocationBetterThanLast）。
     *
     * 上一个 fix 的精度随时间增长，新 fix 必须击败它才被采用。防止
     * 网络 fix 在 40m（WiFi）和 1500m（蜂窝）之间来回跳。
     */
    fun betterThanLast(
        newAccuracyM: Float,
        lastAccuracyM: Float,
        ageSeconds: Double,
        avgSpeedMps: Double,
    ): Boolean {
        // 精度衰减：每秒 +5m 或 +速度（取大者），模拟旧 fix 因时间流逝而变得不可靠
        val decay = maxOf(5.0 * ageSeconds, avgSpeedMps * ageSeconds)
        val oldAdjusted = lastAccuracyM + decay
        return newAccuracyM < oldAdjusted
    }

    /**
     * 一个跳跃是否物理可能（outlier 判定的原始逻辑）。
     *
     * @param movedMeters 本次移动的距离
     * @param lastSpeedMps 上次已知速度
     * @param dtSeconds 两次 fix 间隔
     */
    fun isPhysicallyPlausible(
        movedMeters: Double,
        lastSpeedMps: Float,
        dtSeconds: Double,
    ): Boolean {
        // 允许 12 m/s（~43 km/h）的加速度余量 + 35m 的 GPS 波动
        val plausible = (lastSpeedMps + 12f) * dtSeconds + 35.0
        return movedMeters <= plausible
    }

    /**
     * 从 GPS 位移推导方位角（无 doppler bearing 时的回退）。
     *
     * 需要：两次都是 GPS fix，移动距离超过精度噪声，dt 在合理范围。
     */
    fun canDeriveBearing(
        movedMeters: Double,
        accuracyM: Float?,
        prevWasGps: Boolean,
        isGps: Boolean,
        dtSeconds: Double,
    ): Boolean {
        if (!prevWasGps || !isGps) return false
        val floor = maxOf(3.0, (accuracyM ?: 10f) * 0.7)
        return movedMeters > floor && dtSeconds in 0.3..10.0
    }
}