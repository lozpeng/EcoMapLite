package org.kori.plugin.geo.location

/**
 * 运动类型。每种类型有独立的滤波参数，让步行不再被当作驾车处理（过度抑制），
 * 驾车也不会因为散步参数而抖动。
 *
 * ★ 精度优化：WALKING/STILL 的 kMin 上调。原值对低速状态抑制过重，
 * 结合"向预测点收敛"的 sanitizer 后，这些值在保留防抖的同时不再拖慢响应。
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
        kMin = 0.15f,               // ★ 0.10 → 0.15：静止时也允许更快收敛
        fullFollowSpeed = 0.5f,
        deadReckonSpeed = Float.MAX_VALUE,
        deadReckonMaxS = 0.0,
        speedDecayTau = 2.0,
    ),
    WALKING(
        kMin = 0.50f,               // ★ 0.35 → 0.50：步行不再被过度抑制（滞后更小）
        fullFollowSpeed = 3.0f,
        deadReckonSpeed = 0.8f,
        deadReckonMaxS = 200.0,
        speedDecayTau = 3.0,
    ),
    CYCLING(
        kMin = 0.60f,               // ★ 0.55 → 0.60
        fullFollowSpeed = 8.0f,
        deadReckonSpeed = 1.5f,
        deadReckonMaxS = 1000.0,
        speedDecayTau = 5.0,
    ),
    DRIVING(
        kMin = 0.85f,
        fullFollowSpeed = 15.0f,
        deadReckonSpeed = 2.0f,
        deadReckonMaxS = 3000.0,
        speedDecayTau = 8.0,
    );

    /** 根据瞬时速度计算混合系数（未考虑 k 上升/下降的非对称）。 */
    fun mixFactor(speedMps: Float): Float =
        kMin + (1f - kMin) * (speedMps / fullFollowSpeed).coerceIn(0f, 1f)
}