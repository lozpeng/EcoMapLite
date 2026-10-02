package org.kori.plugin.geo.track

import org.kori.plugin.geo.math.GeoMath

/**
 * 轨迹中的一个采样点。
 *
 * 记录的是**原始 GPS fix**（未经位置滤波），因此轨迹保留真实细节。
 * 显示时可用 [TrackSimplifier] 简化，但存储保留完整。
 */
/**
 * 轨迹中的一个采样点。
 *
 * ## 数据来源
 *
 *  · 位置/速度/方位：GPS 或网络定位的原始值
 *  · 传感器：由 [SensorSampler] 在 fix 到达时快照
 *
 * ## 空值约定
 *
 * 除 [lat]、[lng]、[timestampMs]、[elapsedNanos] 外，其余字段都可能为 `null`：
 *  · 传感器不存在 → accel/gyro/mag 为 null
 *  · 传感器未采样到 → 同上
 *  · Location 无速度/方位/精度 → 对应字段为 null
 *
 * CSV 用**空字段**表示 `null`（两个逗号之间没东西）。
 *
 * ## 时间戳
 *
 *  · [timestampMs]：墙钟（`System.currentTimeMillis`），可能因 NTP 同步跳变
 *  · [elapsedNanos]：单调时钟（`Location.getElapsedRealtimeNanos`），用于回放时间轴
 */
data class TrackPoint(
    // ---- 位置 ----
    val lat: Double,
    val lng: Double,
    val altitudeM: Double? = null,

    // ---- 运动 ----
    val speedMps: Float? = null,
    val bearingDeg: Float? = null,
    val accuracyM: Float? = null,

    // ---- 时间 ----
    /** 墙钟时间戳（毫秒），给用户看的日期。 */
    val timestampMs: Long,
    /** 单调时钟（纳秒），用于回放时间轴计算。 */
    val elapsedNanos: Long,

    // ---- 传感器：线性加速度（设备坐标系，已去重力） ----
    /** X 轴加速度（m/s²）。 */
    val accelX: Float? = null,
    /** Y 轴加速度（m/s²）。 */
    val accelY: Float? = null,
    /** Z 轴加速度（m/s²）。 */
    val accelZ: Float? = null,

    // ---- 传感器：陀螺仪（设备坐标系） ----
    /** X 轴角速度（rad/s）。 */
    val gyroX: Float? = null,
    /** Y 轴角速度（rad/s）。 */
    val gyroY: Float? = null,
    /** Z 轴角速度（rad/s）。 */
    val gyroZ: Float? = null,

    // ---- 传感器：磁力计（设备坐标系） ----
    /** X 轴磁场强度（μT）。 */
    val magX: Float? = null,
    /** Y 轴磁场强度（μT）。 */
    val magY: Float? = null,
    /** Z 轴磁场强度（μT）。 */
    val magZ: Float? = null,
) {
    /** 是否含任何传感器数据。 */
    val hasSensors: Boolean
        get() = accelX != null || gyroX != null || magX != null

    /** 加速度模长（m/s²）；无数据时返回 null。 */
    val accelMagnitude: Float?
        get() {
            val x = accelX ?: return null
            val y = accelY ?: return null
            val z = accelZ ?: return null
            return kotlin.math.sqrt(x * x + y * y + z * z)
        }

    /** 陀螺仪模长（rad/s）；无数据时返回 null。 */
    val gyroMagnitude: Float?
        get() {
            val x = gyroX ?: return null
            val y = gyroY ?: return null
            val z = gyroZ ?: return null
            return kotlin.math.sqrt(x * x + y * y + z * z)
        }

    /** 磁力计模长（μT）；无数据时返回 null。 */
    val magMagnitude: Float?
        get() {
            val x = magX ?: return null
            val y = magY ?: return null
            val z = magZ ?: return null
            return kotlin.math.sqrt(x * x + y * y + z * z)
        }

    /** 用同一个 fix 的位置/时间替换位置，保留所有传感器和运动字段。 */
    fun withPosition(newLat: Double, newLng: Double): TrackPoint =
        copy(lat = newLat, lng = newLng)

    /** 判断是否与另一个点在传感器数据上一致（浮点误差容忍 1e-6）。 */
    fun sameSensors(other: TrackPoint): Boolean =
        nearlyEqual(accelX, other.accelX) && nearlyEqual(accelY, other.accelY) &&
                nearlyEqual(accelZ, other.accelZ) && nearlyEqual(gyroX, other.gyroX) &&
                nearlyEqual(gyroY, other.gyroY) && nearlyEqual(gyroZ, other.gyroZ) &&
                nearlyEqual(magX, other.magX) && nearlyEqual(magY, other.magY) &&
                nearlyEqual(magZ, other.magZ)

    private fun nearlyEqual(a: Float?, b: Float?): Boolean = when {
        a == null && b == null -> true
        a == null || b == null -> false
        else -> kotlin.math.abs(a - b) < 1e-6f
    }
}

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