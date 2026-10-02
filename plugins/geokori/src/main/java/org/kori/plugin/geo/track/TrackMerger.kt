package org.kori.plugin.geo.track

import org.kori.plugin.geo.math.GeoMath
import java.io.File

/**
 * 轨迹合并器。
 *
 * ## 用途
 *
 * 把**一个会话内所有段**合并成单一序列。用于：
 *  · 导出为 GPX / KML / GeoJSON（多段合成一条线）
 *  · 全轨迹统计（总距离 / 平均速度 / 时间范围）
 *  · 轨迹回放（顺序播放所有点）
 *  · 轨迹对比（同路线多次记录叠加）
 *
 * ## 两种轨道
 *
 *  · **RAW**：原始 GPS + 传感器数据（[TrackPoint]）
 *  · **SMOOTH**：位置平滑后（[TrackPoint]，位置经 [org.kori.plugin.geo.map.core.location.PositionSanitizer] 处理）
 *
 * ## 不重新排序
 *
 * 段按 [TrackSession.Segment.index] 升序拼接——**保持原始时间顺序**。
 * 不按 `elapsedNanos` 排序，因为跨段时单调时钟可能回绕（罕见但存在）。
 */
object TrackMerger {

    // =============================================================================================
    // 合并 RAW
    // =============================================================================================

    /**
     * 合并所有段的**原始**轨迹点。
     *
     * @param session 会话
     * @return 按段顺序拼接的全部点（不排序，不去重）
     */
    fun mergeRaw(session: TrackSession): List<TrackPoint> {
        if (session.segments.isEmpty()) return emptyList()
        return session.segments
            .sortedBy { it.index }
            .flatMap { seg -> TrackStore.read(seg.rawFile) }
    }

    /**
     * 合并所有段的**平滑**轨迹点。
     *
     * 若某段没有 smooth 文件（旧版本只保存 raw），跳过该段。
     */
    fun mergeSmooth(session: TrackSession): List<TrackPoint> {
        if (session.segments.isEmpty()) return emptyList()
        return session.segments
            .sortedBy { it.index }
            .mapNotNull { it.smoothFile }
            .flatMap { TrackStore.read(it) }
    }

    // =============================================================================================
    // 选择性合并
    // =============================================================================================

    /**
     * 合并指定段的**原始**轨迹点。
     *
     * 用于用户勾选部分段导出（如"只导出上午的记录"）。
     *
     * @param session         会话
     * @param segmentIndices  要合并的段索引集合（1-based）
     */
    fun mergeSelectedRaw(
        session: TrackSession,
        segmentIndices: Set<Int>,
    ): List<TrackPoint> {
        return session.segments
            .filter { it.index in segmentIndices }
            .sortedBy { it.index }
            .flatMap { TrackStore.read(it.rawFile) }
    }

    /**
     * 合并指定段的**平滑**轨迹点。
     */
    fun mergeSelectedSmooth(
        session: TrackSession,
        segmentIndices: Set<Int>,
    ): List<TrackPoint> {
        return session.segments
            .filter { it.index in segmentIndices }
            .sortedBy { it.index }
            .mapNotNull { it.smoothFile }
            .flatMap { TrackStore.read(it) }
    }

    // =============================================================================================
    // 去重合并
    // =============================================================================================

    /**
     * 合并所有段的**原始**轨迹点，并**去除相邻重复点**。
     *
     * 段边界可能产生重叠：
     *  · 上一段的最后一个点与下一段的第一个点距离很近（< [minDistM]）
     *  · 切段时若 `record` 在切段前后各写入一次相同的 fix
     *
     * 去重策略：**保留段的第一个点**，后续距离 < [minDistM] 的点丢弃。
     *
     * @param session  会话
     * @param minDistM 相邻点最小距离（米）。默认 0.5m。
     * @return 去重后的点序列
     */
    fun mergeRawDeduped(
        session: TrackSession,
        minDistM: Double = 0.5,
    ): List<TrackPoint> {
        val all = mergeRaw(session)
        if (all.size <= 1) return all

        val out = ArrayList<TrackPoint>(all.size)
        out.add(all.first())

        for (i in 1 until all.size) {
            val prev = out.last()
            val cur = all[i]
            val d = GeoMath.haversineMeters(
                prev.lat, prev.lng,
                cur.lat, cur.lng,
            )
            if (d >= minDistM) out.add(cur)
        }
        return out
    }

    /**
     * 合并所有段的**平滑**轨迹点，去重。
     */
    fun mergeSmoothDeduped(
        session: TrackSession,
        minDistM: Double = 0.5,
    ): List<TrackPoint> {
        val all = mergeSmooth(session)
        if (all.size <= 1) return all

        val out = ArrayList<TrackPoint>(all.size)
        out.add(all.first())

        for (i in 1 until all.size) {
            val prev = out.last()
            val cur = all[i]
            val d = GeoMath.haversineMeters(
                prev.lat, prev.lng,
                cur.lat, cur.lng,
            )
            if (d >= minDistM) out.add(cur)
        }
        return out
    }

    // =============================================================================================
    // 统计
    // =============================================================================================

    /**
     * 计算合并后轨迹的**总距离**（米）。
     *
     * 与 [TrackSession.totalDistanceM] 不同——那是**记录时**统计的，
     * 这是**事后**从合并点重算的（可能因去重而略小）。
     */
    fun totalDistance(points: List<TrackPoint>): Double {
        if (points.size < 2) return 0.0
        var sum = 0.0
        for (i in 1 until points.size) {
            sum += GeoMath.haversineMeters(
                points[i - 1].lat, points[i - 1].lng,
                points[i].lat, points[i].lng,
            )
        }
        return sum
    }

    /**
     * 计算合并后轨迹的**时长**（毫秒）。
     */
    fun totalDurationMs(points: List<TrackPoint>): Long {
        if (points.size < 2) return 0L
        return (points.last().timestampMs - points.first().timestampMs).coerceAtLeast(0L)
    }

    /**
     * 合并后的统计摘要。
     */
    data class Stats(
        val pointCount: Int,
        val distanceM: Double,
        val durationMs: Long,
        val startMs: Long,
        val endMs: Long,
        val averageSpeedKmh: Double,
    ) {
        val durationText: String
            get() {
                val totalSec = durationMs / 1000
                val h = totalSec / 3600
                val m = (totalSec % 3600) / 60
                val s = totalSec % 60
                return if (h > 0) "%d:%02d:%02d".format(h, m, s)
                else "%02d:%02d".format(m, s)
            }

        val distanceText: String
            get() = "%.2f km".format(distanceM / 1000)

        val averageSpeedText: String
            get() = "%.1f km/h".format(averageSpeedKmh)
    }

    /**
     * 从合并后的点计算统计摘要。
     */
    fun stats(points: List<TrackPoint>): Stats {
        if (points.isEmpty()) {
            return Stats(0, 0.0, 0L, 0L, 0L, 0.0)
        }
        val dist = totalDistance(points)
        val dur = totalDurationMs(points)
        val durSec = dur / 1000.0
        val avgKmh = if (durSec > 0.0) (dist / durSec) * 3.6 else 0.0

        return Stats(
            pointCount = points.size,
            distanceM = dist,
            durationMs = dur,
            startMs = points.first().timestampMs,
            endMs = points.last().timestampMs,
            averageSpeedKmh = avgKmh,
        )
    }

    // =============================================================================================
    // 多会话合并
    // =============================================================================================

    /**
     * 合并**多个会话**的所有原始轨迹点。
     *
     * 按会话的 [TrackSession.startedMs] 升序拼接——用于"一天的所有轨迹"。
     *
     * @param sessions 会话列表
     * @param minDistM 跨会话边界的去重距离
     */
    fun mergeAcrossSessions(
        sessions: List<TrackSession>,
        minDistM: Double = 0.5,
    ): List<TrackPoint> {
        if (sessions.isEmpty()) return emptyList()

        val sorted = sessions.sortedBy { it.startedMs }
        val out = ArrayList<TrackPoint>()

        for (session in sorted) {
            val points = mergeRawDeduped(session, minDistM)
            if (points.isEmpty()) continue

            // 跨会话边界去重
            if (out.isNotEmpty()) {
                val prev = out.last()
                val cur = points.first()
                val d = GeoMath.haversineMeters(
                    prev.lat, prev.lng,
                    cur.lat, cur.lng,
                )
                if (d < minDistM) {
                    // 跳过当前会话的第一点（与上一会话的最后点重复）
                    out.addAll(points.drop(1))
                    continue
                }
            }
            out.addAll(points)
        }
        return out
    }

    // =============================================================================================
    // 按时间切片
    // =============================================================================================

    /**
     * 从合并点中提取一个**时间窗口**内的子序列。
     *
     * @param points   合并后的全部点
     * @param fromMs   起始时间（含，毫秒，墙钟）
     * @param toMs     结束时间（含，毫秒，墙钟）
     */
    fun sliceByTime(
        points: List<TrackPoint>,
        fromMs: Long,
        toMs: Long,
    ): List<TrackPoint> {
        return points.filter { it.timestampMs in fromMs..toMs }
    }

    // =============================================================================================
    // 按距离切片
    // =============================================================================================

    /**
     * 从合并点中提取**前 N 公里**的子序列。
     *
     * @param points     合并后的全部点
     * @param maxDistM   最大距离（米）
     */
    fun sliceByDistance(
        points: List<TrackPoint>,
        maxDistM: Double,
    ): List<TrackPoint> {
        if (points.size < 2 || maxDistM <= 0.0) return emptyList()

        val out = ArrayList<TrackPoint>()
        out.add(points.first())
        var acc = 0.0
        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val cur = points[i]
            val d = GeoMath.haversineMeters(
                prev.lat, prev.lng,
                cur.lat, cur.lng,
            )
            acc += d
            if (acc > maxDistM) {
                // 部分插值到 maxDistM（可选，这里直接停止）
                break
            }
            out.add(cur)
        }
        return out
    }
}