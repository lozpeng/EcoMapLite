package org.kori.plugin.geo.math

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 几何数学工具（纯函数，无 Android / MapLibre 依赖）。
 *
 * 单位约定：
 *  · 角度：度（0 = 正北，顺时针为正）
 *  · 距离：米
 *  · 坐标：纬度在前 (lat, lng)
 */
object GeoMath {

    const val EARTH_RADIUS_M = 6_371_000.0
    const val METERS_PER_DEG_LAT = 111_320.0

    // =========================================================================================
    // 距离
    // =========================================================================================

    /** Haversine 距离（米）。适用于任意距离，10 km 内精度优于 0.5%。 */
    fun haversineMeters(
        lat1: Double, lng1: Double,
        lat2: Double, lng2: Double,
    ): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).let { it * it } +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
    }

    /** 某纬度处每度经度的米数（用于局部平面近似）。 */
    fun metersPerDegLng(atLat: Double): Double =
        METERS_PER_DEG_LAT * cos(Math.toRadians(atLat)).coerceAtLeast(0.01)

    // =========================================================================================
    // 方位角
    // =========================================================================================

    /** 从 a 到 b 的罗盘方位角（度，0 = 北，顺时针）。 */
    fun bearingDeg(
        aLat: Double, aLng: Double,
        bLat: Double, bLng: Double,
    ): Float {
        val dLng = Math.toRadians(bLng - aLng)
        val la1 = Math.toRadians(aLat)
        val la2 = Math.toRadians(bLat)
        val y = sin(dLng) * cos(la2)
        val x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dLng)
        return ((Math.toDegrees(atan2(y, x)) + 360.0) % 360.0).toFloat()
    }

    /** 两个罗盘方位角之间的最小夹角（0..180，Float 版本）。 */
    fun angleDelta(a: Float, b: Float): Float =
        abs((a - b + 540f) % 360f - 180f)

    /** 两个罗盘方位角之间的最小夹角（0..180，Double 版本）。 */
    fun angleDiff(a: Double, b: Double): Double =
        abs(((a - b + 540.0) % 360.0) - 180.0)

    // =========================================================================================
    // 点-线段投影
    // =========================================================================================

    /**
     * 把点 p 投影到线段 a→b 上，返回 (投影点, 参数 t ∈ [0,1])。
     * 局部平面近似（equirectangular），在几百米尺度下误差可忽略。
     */
    fun projectOnSegment(
        pLat: Double, pLng: Double,
        aLat: Double, aLng: Double,
        bLat: Double, bLng: Double,
    ): Pair<Pair<Double, Double>, Double> {
        val k = cos(Math.toRadians((aLat + bLat) / 2.0))
        val ax = aLng * k; val ay = aLat
        val bx = bLng * k; val by = bLat
        val px = pLng * k; val py = pLat
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
        return ((ay + t * dy) to ((ax + t * dx) / k)) to t
    }

    /** p 到线段 a→b 的距离（米）。 */
    fun distanceToSegment(
        pLat: Double, pLng: Double,
        aLat: Double, aLng: Double,
        bLat: Double, bLng: Double,
    ): Double {
        val (proj, _) = projectOnSegment(pLat, pLng, aLat, aLng, bLat, bLng)
        return haversineMeters(pLat, pLng, proj.first, proj.second)
    }

    // =========================================================================================
    // 局部平面工具
    // =========================================================================================

    /** 局部平面上的两点距离（快速近似，仅用于短距离）。 */
    fun fastDistanceMeters(
        lat1: Double, lng1: Double,
        lat2: Double, lng2: Double,
        atLat: Double = (lat1 + lat2) / 2.0,
    ): Double {
        val dLat = (lat2 - lat1) * METERS_PER_DEG_LAT
        val dLng = (lng2 - lng1) * metersPerDegLng(atLat)
        return hypot(dLat, dLng)
    }
}