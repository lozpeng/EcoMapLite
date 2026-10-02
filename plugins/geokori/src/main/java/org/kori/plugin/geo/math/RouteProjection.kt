package org.kori.plugin.geo.math

import org.maplibre.android.geometry.LatLng
import java.util.Arrays

/**
 * 路线投影工具。
 *
 * "沿路线距离"（along-route meters）是导航的核心坐标——一切按路线进度定位的东西
 * （puck 位置、前方分界、路名标注）都基于它。
 */
object RouteProjection {

    /**
     * 计算每个顶点处的累积沿路线距离。
     * 返回的数组长度与 polyline 相同，cum[0] = 0，cum[last] = 总长度。
     */
    fun cumulative(poly: List<LatLng>): DoubleArray {
        val cum = DoubleArray(poly.size)
        for (i in 1 until poly.size) {
            cum[i] = cum[i - 1] + GeoMath.haversineMeters(
                poly[i - 1].latitude, poly[i - 1].longitude,
                poly[i].latitude, poly[i].longitude,
            )
        }
        return cum
    }

    /** 找到沿路线距离 [m] 处的线段索引（≥1，≤ size-1）。二分搜索。 */
    fun indexAtMeters(cum: DoubleArray, m: Double): Int {
        val idx = Arrays.binarySearch(cum, m)
        return (if (idx >= 0) idx else -idx - 1).coerceIn(1, cum.size - 1)
    }

    /**
     * 求沿路线 [meters] 处的点和该线段的方位角。
     * 返回 Pair(点, 方位角)。
     */
    fun pointAtMeters(
        poly: List<LatLng>,
        cum: DoubleArray,
        meters: Double,
    ): Pair<LatLng, Float> {
        if (poly.size < 2) return (poly.firstOrNull() ?: LatLng(0.0, 0.0)) to 0f
        val total = cum.last()
        val m = meters.coerceIn(0.0, total)
        val i = indexAtMeters(cum, m)
        val a = poly[i - 1]
        val b = poly[i]
        val segLen = cum[i] - cum[i - 1]
        val t = if (segLen <= 0.0) 0.0 else ((m - cum[i - 1]) / segLen).coerceIn(0.0, 1.0)
        val pt = LatLng(
            a.latitude + (b.latitude - a.latitude) * t,
            a.longitude + (b.longitude - a.longitude) * t,
        )
        val brg = GeoMath.bearingDeg(
            a.latitude, a.longitude,
            b.latitude, b.longitude,
        )
        return pt to brg
    }

    /**
     * 把点 [at] 投影到路线上，返回沿路线距离（米）；投影点距路线超过 [tolerance] 时返回 null。
     *
     * 用于：位置吸附、进度计算、"最近的路线点"。
     */
    fun alongMeters(
        poly: List<LatLng>,
        cum: DoubleArray,
        at: LatLng,
        tolerance: Double = 400.0,
    ): Double? {
        if (poly.size < 2) return null
        var bestD = Double.MAX_VALUE
        var bestM = 0.0
        for (i in 1 until poly.size) {
            val a = poly[i - 1]
            val b = poly[i]
            val (proj, t) = GeoMath.projectOnSegment(
                at.latitude, at.longitude,
                a.latitude, a.longitude,
                b.latitude, b.longitude,
            )
            val d = GeoMath.haversineMeters(at.latitude, at.longitude, proj.first, proj.second)
            if (d < bestD) {
                bestD = d
                val segLen = cum[i] - cum[i - 1]
                bestM = cum[i - 1] + t * segLen
            }
        }
        return if (bestD <= tolerance) bestM else null
    }

    /**
     * 把点 [at] 限制在路线的 [loM]..[hiM] 窗口内投影，返回 (吸附点, 该段方位角, 沿路线距离)。
     * 用于导航的"只向前搜索"——防止吸附到平行的返回路段。
     */
    fun snapWindowed(
        at: LatLng,
        gpsBearing: Float?,
        poly: List<LatLng>,
        cum: DoubleArray,
        loM: Double,
        hiM: Double,
        maxMeters: Double = 22.0,
    ): Triple<LatLng, Float, Double>? {
        if (poly.size < 2 || cum.size < poly.size) return null
        var bestD = Double.MAX_VALUE
        var bestPoint: LatLng? = null
        var bestA = poly[0]
        var bestB = poly[1]
        var bestM = 0.0
        for (i in 1 until poly.size) {
            if (cum[i] < loM || cum[i - 1] > hiM) continue
            val a = poly[i - 1]
            val b = poly[i]
            val (proj, t) = GeoMath.projectOnSegment(
                at.latitude, at.longitude,
                a.latitude, a.longitude,
                b.latitude, b.longitude,
            )
            val d = GeoMath.haversineMeters(at.latitude, at.longitude, proj.first, proj.second)
            if (d < bestD) {
                bestD = d
                bestPoint = LatLng(proj.first, proj.second)
                bestA = a; bestB = b
                val segLen = cum[i] - cum[i - 1]
                bestM = cum[i - 1] + t * segLen
            }
        }
        val pt = bestPoint ?: return null
        if (bestD > maxMeters) return null
        val routeBearing = GeoMath.bearingDeg(
            bestA.latitude, bestA.longitude,
            bestB.latitude, bestB.longitude,
        )
        if (gpsBearing != null && GeoMath.angleDelta(gpsBearing, routeBearing) > 55f) return null
        return Triple(pt, routeBearing, bestM)
    }

    /**
     * 从当前位置沿路线前进 [window] 米，返回终点（用于路线"预览"或"前方"标记）。
     * 超过终点则返回 null。
     */
    fun advanceBy(
        poly: List<LatLng>,
        cum: DoubleArray,
        fromM: Double,
        window: Double,
    ): LatLng? {
        val target = fromM + window
        if (target > cum.last()) return null
        return pointAtMeters(poly, cum, target).first
    }

    /**
     * 求路线上 [fromM] 之后的所有点的窗口（[fromM], [fromM]+window]）。
     * 用于"前方 3 公里"的分段渲染。
     */
    fun segmentWindow(
        poly: List<LatLng>,
        cum: DoubleArray,
        fromM: Double,
        window: Double,
    ): List<LatLng> {
        val toM = fromM + window
        val out = ArrayList<LatLng>()
        for (i in poly.indices) {
            if (cum[i] in fromM..toM) out.add(poly[i])
        }
        if (out.isEmpty()) return emptyList()
        // 加上精确的起止点，让窗口边缘与请求的米数完全对齐
        val startPt = pointAtMeters(poly, cum, fromM).first
        val endPt = pointAtMeters(poly, cum, minOf(toM, cum.last())).first
        return listOf(startPt) + out.filter { it != startPt } + listOf(endPt)
    }
}