package org.kori.plugin.geo.track

import org.kori.plugin.geo.math.GeoMath

/**
 * 轨迹中的一个采样点。
 *
 * 记录的是**原始 GPS fix**（未经位置滤波），因此轨迹保留真实细节。
 * 显示时可用 [TrackSimplifier] 简化，但存储保留完整。
 */
data class TrackPoint(
    val lat: Double,
    val lng: Double,
    val altitudeM: Double?,
    val speedMps: Float?,
    val bearingDeg: Float?,
    val accuracyM: Float?,
    /** 系统时钟（毫秒），用于文件名/显示 */
    val timestampMs: Long,
    /** 单调时钟（纳秒），用于回放时间轴 */
    val elapsedNanos: Long,
)


/**
 * Douglas-Peucker 轨迹简化。
 *
 * 保留轨迹形状（转弯、拐点），剔除共线点。默认 0.5m 容差——
 * 步行时能保留转弯细节，驾车时压缩直路段。
 */
object TrackSimplifier {

    fun simplify(points: List<TrackPoint>, epsilonM: Double = 0.5): List<TrackPoint> {
        if (points.size <= 2) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        douglasPeucker(points, 0, points.size - 1, epsilonM, keep)
        return points.filterIndexed { i, _ -> keep[i] }
    }

    private fun douglasPeucker(
        points: List<TrackPoint>,
        start: Int,
        end: Int,
        epsilonM: Double,
        keep: BooleanArray,
    ) {
        if (end - start < 2) return
        var maxDist = 0.0
        var maxIdx = -1
        val a = points[start]
        val b = points[end]
        for (i in start + 1 until end) {
            val p = points[i]
            val d = GeoMath.distanceToSegment(
                p.lat, p.lng,
                a.lat, a.lng,
                b.lat, b.lng,
            )
            if (d > maxDist) {
                maxDist = d
                maxIdx = i
            }
        }
        if (maxDist > epsilonM && maxIdx > 0) {
            keep[maxIdx] = true
            douglasPeucker(points, start, maxIdx, epsilonM, keep)
            douglasPeucker(points, maxIdx, end, epsilonM, keep)
        }
    }
}