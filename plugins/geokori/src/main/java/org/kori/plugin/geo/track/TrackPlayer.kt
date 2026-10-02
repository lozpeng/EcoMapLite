package org.kori.plugin.geo.track

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/**
 * 轨迹回放。
 *
 * 按原始时间戳回放，支持速度倍率（1x/3x/10x）。暂停/恢复通过外部取消+重建实现。
 *
 * 用法：
 *  ```
 *  val player = TrackPlayer(file, speedup = 3f)
 *  player.play().collect { point ->
 *      // 更新地图相机 / puck 位置
 *  }
 *  ```
 */
class TrackPlayer(private val file: File, private val speedup: Float = 1f) {

    fun play(): Flow<TrackPoint> = flow {
        val points = TrackStore.read(file)
        if (points.isEmpty()) return@flow
        var lastNanos = points.first().elapsedNanos
        emit(points.first())
        for (i in 1 until points.size) {
            val p = points[i]
            val dtNanos = p.elapsedNanos - lastNanos
            val waitMs = (dtNanos / 1_000_000L) / speedup.toLong()
            if (waitMs > 0) delay(waitMs)
            emit(p)
            lastNanos = p.elapsedNanos
        }
    }

    companion object {
        /** 估算回放时长（秒）。 */
        fun durationSeconds(file: File, speedup: Float = 1f): Long {
            val points = TrackStore.read(file)
            if (points.size < 2) return 0
            val totalNanos = points.last().elapsedNanos - points.first().elapsedNanos
            return (totalNanos / 1_000_000_000L) / speedup.toLong()
        }
    }
}