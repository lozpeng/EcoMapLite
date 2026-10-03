package org.kori.plugin.geo.track

import android.location.Location
import org.kori.plugin.geo.track.di.TrackPoint
import org.kori.plugin.geo.track.di.TrackSimplifier
import java.io.File

/**
 * 轨迹记录器。
 *
 * 关键设计：
 *  · **记录原始 fix**（不经过位置滤波），保留轨迹真实形状
 *  · **内存累积**，stop 时一次性写入文件（避免频繁 IO）
 *  · **Douglas-Peucker 简化**，默认 0.5m 容差（保留转弯，压缩直路）
 *  · **可选上限**，防止长时间运行撑爆内存
 *
 * 用法：
 *  ```
 *  val recorder = TrackRecorder(File(filesDir, "tracks"))
 *  recorder.start("morning-ride")
 *  // 每个 GPS fix
 *  recorder.record(location)
 *  // 结束
 *  val file = recorder.stop()
 *  ```
 *
 * 两阶段过滤：
 *  1. **入口过滤** [TrackFilter]：每次收到 fix 时判断，拒绝漂移点（不写入内存）
 *  2. **存储简化** [TrackSimplifier]：stop 时用 Douglas-Peucker 合并共线点
 *
 * 记录的原始 fix 只经过 [TrackFilter]，不做位置平滑（保留真实形状）。
 */
class TrackRecorder(private val dir: File) {

    private var buffer: MutableList<TrackPoint>? = null
    private var targetFile: File? = null
    private val filter = TrackFilter()

    /** 累计拒绝的点数（调试用）。 */
    var rejectedCount: Int = 0
        private set

    val isRecording: Boolean get() = buffer != null

    fun start(nameHint: String? = null) {
        buffer = mutableListOf()
        targetFile = TrackStore.newFile(dir, nameHint)
        filter.reset()
        rejectedCount = 0
    }

    /**
     * 记录一个原始 fix。
     *
     * 经过 [TrackFilter] 判断——若判定为漂移点，直接丢弃不写入。
     */
    fun record(loc: Location) {
        val buf = buffer ?: return
        if (buf.size >= MAX_POINTS) return

        val point = TrackPoint(
            lat = loc.latitude,
            lng = loc.longitude,
            altitudeM = if (loc.hasAltitude()) loc.altitude else null,
            speedMps = if (loc.hasSpeed()) loc.speed else null,
            bearingDeg = if (loc.hasBearing()) loc.bearing else null,
            accuracyM = if (loc.hasAccuracy()) loc.accuracy else null,
            timestampMs = System.currentTimeMillis(),
            elapsedNanos = if (loc.elapsedRealtimeNanos != 0L) loc.elapsedRealtimeNanos
            else loc.time * 1_000_000L,
        )

        val lastKept = buf.lastOrNull()
        if (!filter.shouldKeep(point, lastKept)) {
            rejectedCount++
            return
        }
        buf.add(point)
    }

    /**
     * 停止记录并写入文件。
     * @param simplify 是否做 Douglas-Peucker 简化（默认 true，容差 0.5m）
     */
    fun stop(simplify: Boolean = true, simplifyEpsilonM: Double = 0.5): File? {
        val buf = buffer ?: return null
        buffer = null
        val file = targetFile ?: return null
        targetFile = null
        if (buf.size < 2) return null

        val out = if (simplify) TrackSimplifier.simplify(buf, simplifyEpsilonM) else buf
        TrackStore.write(file, out)
        return file
    }

    fun discard() {
        buffer = null
        targetFile = null
        filter.reset()
    }

    /** 已保留的点数。 */
    fun size(): Int = buffer?.size ?: 0

    /** 已拒绝的漂移点数。 */
    fun rejected(): Int = rejectedCount

    companion object {
        private const val MAX_POINTS = 200_000
    }
}