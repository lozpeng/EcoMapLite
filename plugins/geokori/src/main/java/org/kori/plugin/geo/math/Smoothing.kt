package org.kori.plugin.geo.math

import kotlin.math.exp

/**
 * 时间平滑工具。
 *
 * 核心思想：所有"跟随/朝目标靠拢"的量（方位角、缩放、倾斜、位置）都用指数缓动，
 * 但 time-constant（tau）可以自适应——小误差用小 tau（抗噪声），大误差用大 tau（跟上转向）。
 */
object Smoothing {

    /** 单步指数缓动的混合系数：目标变化 dt 时间后混合多少。 */
    fun mixFactor(dtSeconds: Float, tauSeconds: Float): Double =
        (1f - exp(-dtSeconds / tauSeconds)).toDouble()

    /**
     * 指数缓动旋转角（走最短路，跨越 0°/360° 不绕远）。
     */
    fun smoothBearing(current: Float, target: Float, dt: Float, tau: Float): Float {
        val delta = ((target - current + 540f) % 360f) - 180f
        return ((current + delta * mixFactor(dt, tau).toFloat()) % 360f + 360f) % 360f
    }

    /**
     * 自适应方位角缓动：小误差慢（抗抖动），大误差快（跟转向）。
     *
     * @param stillTau 小误差（几何噪声）时的 tau，例如 1.6s
     * @param turnTau  大误差（真实转向）时的 tau，例如 0.35s
     * @param fullTurnDeg 到达此误差时视为"真实转向"，例如 25°
     */
    fun adaptiveBearing(
        current: Float,
        target: Float,
        dt: Float,
        stillTau: Float,
        turnTau: Float,
        fullTurnDeg: Float,
    ): Float {
        val delta = ((target - current + 540f) % 360f) - 180f
        val errRatio = (kotlin.math.abs(delta) / fullTurnDeg).coerceIn(0f, 1f)
        val tau = stillTau + (turnTau - stillTau) * errRatio
        return ((current + delta * mixFactor(dt, tau).toFloat()) % 360f + 360f) % 360f
    }

    /**
     * 在路线上的 boxcar 平均：沿路线在 [centerM] ± [halfWindowM] 范围内均匀采样 [samples] 个点，
     * 平均得到去抖动的平滑点。用于消除数字化路线的左右微抖。
     */
    fun boxcarOnRoute(
        poly: List<org.maplibre.android.geometry.LatLng>,
        cum: DoubleArray,
        centerM: Double,
        halfWindowM: Double,
        samples: Int = 7,
    ): org.maplibre.android.geometry.LatLng {
        if (samples < 2) return RouteProjection.pointAtMeters(poly, cum, centerM).first
        var lat = 0.0
        var lng = 0.0
        for (k in 0 until samples) {
            val off = -halfWindowM + 2 * halfWindowM * k / (samples - 1)
            val (pt, _) = RouteProjection.pointAtMeters(poly, cum, centerM + off)
            lat += pt.latitude
            lng += pt.longitude
        }
        return org.maplibre.android.geometry.LatLng(lat / samples, lng / samples)
    }
}