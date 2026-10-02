package org.kori.plugin.geo.location

/**
 * 运动类型。每种类型有独立的滤波参数，让步行不再被当作驾车处理（过度抑制），
 * 驾车也不会因为散步参数而抖动。
 *
 * @param kMin              最低混合系数（静止/极低速时）
 * @param fullFollowSpeed   达到此速度时 k=1（完全跟随）
 * @param deadReckonSpeed   低于此速度不启动 dead reckoning
 * @param deadReckonMaxS    单次 dead reckoning 的最大推算距离（米）
 * @param speedDecayTau     dead reckoning 时速度衰减的时间常数
 */
enum class ActivityProfile(
    val kMin: Float,
    val fullFollowSpeed: Float,
    val deadReckonSpeed: Float,
    val deadReckonMaxS: Double,
    val speedDecayTau: Double,
) {
    STILL(
        kMin = 0.10f,
        fullFollowSpeed = 1.0f,
        deadReckonSpeed = Float.MAX_VALUE, // 静止不推算
        deadReckonMaxS = 0.0,
        speedDecayTau = 2.0,
    ),
    WALKING(
        kMin = 0.35f,               // 步行 k 高，减少滞后
        fullFollowSpeed = 3.0f,     // 3 m/s 达到完全跟随
        deadReckonSpeed = 0.8f,
        deadReckonMaxS = 200.0,     // 步行隧道最多推 200m
        speedDecayTau = 3.0,
    ),
    CYCLING(
        kMin = 0.55f,
        fullFollowSpeed = 8.0f,
        deadReckonSpeed = 1.5f,
        deadReckonMaxS = 1000.0,
        speedDecayTau = 5.0,
    ),
    DRIVING(
        kMin = 0.85f,               // 驾车 k 高，几乎不抑制
        fullFollowSpeed = 15.0f,
        deadReckonSpeed = 2.0f,
        deadReckonMaxS = 3000.0,
        speedDecayTau = 8.0,
    );

    /** 根据瞬时速度计算混合系数（未考虑 k 上升/下降的非对称）。 */
    fun mixFactor(speedMps: Float): Float =
        kMin + (1f - kMin) * (speedMps / fullFollowSpeed).coerceIn(0f, 1f)
}