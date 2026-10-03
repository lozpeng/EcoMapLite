package org.kori.plugin.geo.track

import android.location.Location
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.kori.plugin.geo.location.ActivityProfile
import org.kori.plugin.geo.location.PositionSanitizer
import org.kori.plugin.geo.math.GeoMath
import org.kori.plugin.geo.track.di.SegmentConfig
import org.kori.plugin.geo.track.di.TrackMediaRecord
import org.kori.plugin.geo.track.di.TrackPoint
import org.kori.plugin.geo.track.di.TrackSimplifier
import org.maplibre.android.geometry.LatLng
import java.io.File
import java.util.concurrent.atomic.DoubleAdder

/**
 * 分段双轨轨迹记录器。
 * ## 关键设计
 *
 *  · **分段**：时长、距离、点数任一达阈值即切段
 *  · **双轨**：raw（原始 GPS + 传感器）+ smooth（位置平滑后）
 *  · **实时绘制**：liveTrack 流暴露最近 LIVE_BUFFER_MAX 个点
 * ## 编译修复
 *
 * `segmentDistanceM` 从 `AtomicReference<Double>` 改为 `DoubleAdder`——
 * `AtomicReference` 没有 `addAndGet`，`DoubleAdder` 才有 `add()` / `sum()` / `reset()`。
 * ## 关键设计
 *
 *  · **分段**：时长、距离、点数任一达阈值即切段
 *  · **双轨**：raw（原始 GPS + 传感器）+ smooth（位置平滑后）
 *  · **实时绘制**：liveTrack 流暴露最近 LIVE_BUFFER_MAX 个点
 *  · **B2 修复**：先写入当前帧，再检查切段——避免切段时丢失当前帧
 *
 * ## 生命周期
 *
 * ```
 * startSession()          → 创建目录 + 开会话
 * record(loc, profile)    → 每次 fix 调用
 * stop()                  → 关闭当前段 + 写元数据 + 返回目录
 * ```
 *
 * ## 与 LocationTracker 的配合
 *
 * `LocationTracker.startRecording()` 内部会：
 *  1. `SegmentedTrackRecorder(...).startSession()`
 *  2. 订阅自己的 fixes 流，每次 fix 调 `record()`
 *  3. `stopRecording()` 时调 `stop(simplify, epsilon)` 拿文件
 */
class SegmentedTrackRecorder(
    private val tracksRoot: File,
    private val segmentConfig: SegmentConfig = SegmentConfig.Default,
    private val sessionNameHint: String? = null,
) {

    // =============================================================================================
    // 会话 / 段
    // =============================================================================================

    private var sessionDir: File? = null
    private var segmentIndex: Int = 0
    private var currentRawBuffer: MutableList<TrackPoint>? = null
    private var currentSmoothBuffer: MutableList<TrackPoint>? = null

    /** 段内累计距离。用 DoubleAdder 支持原子 add / sum / reset。 */
    private val segmentDistanceM = DoubleAdder()

    private var segmentStartMs: Long = 0L

    /** 会话起始时间（供 UI 计算 elapsedMs）。 */
    var startTimeMs: Long = 0L
        private set

    // =============================================================================================
    // 平滑
    // =============================================================================================

    private val smoothSanitizer = PositionSanitizer()
    private var lastSmoothPos: LatLng? = null
    private var lastAcceptedSpeed: Float = 0f
    private var lastElapsedNanos: Long = 0L

    // =============================================================================================
    // 入口过滤
    // =============================================================================================

    private val rawFilter = TrackFilter()

    // =============================================================================================
    // 传感器
    // =============================================================================================

    @Volatile private var sensorSampler: SensorSampler? = null

    // =============================================================================================
    // 会话级实时显示缓冲（★ 跨段累计）
    // =============================================================================================
    // 段切换（rollSegment）会重建 currentRawBuffer，旧实现里地图轨迹随切段被清空。
    // 这里维护整个 session 的显示缓冲：切了 N 段，地图上 N 段全部保留，直到会话结束。
    private val displayRaw = mutableListOf<TrackPoint>()
    private val displaySmooth = mutableListOf<TrackPoint>()

    // =============================================================================================
    // 过程事件（暂停/继续，★ 一等数据：写入 session.json，时间线按事件对精确展示）
    // =============================================================================================

    /** 本会话的事件列表。 */
    private val events = mutableListOf<TrackEvent>()

    /** 最近记录的轨迹点坐标（事件坐标取它；尚未记录点时为 null）。 */
    private var lastRecordedLat: Double? = null
    private var lastRecordedLng: Double? = null

    // =============================================================================================
    // 实时数据
    // =============================================================================================

    @Volatile var lastRawBuffer: List<TrackPoint> = emptyList()
        private set
    @Volatile var lastSmoothBuffer: List<TrackPoint> = emptyList()
        private set

    private val _liveTrack = MutableSharedFlow<Pair<List<TrackPoint>, List<TrackPoint>>>(
        replay = 1,
        extraBufferCapacity = 8,
    )
    val liveTrack: SharedFlow<Pair<List<TrackPoint>, List<TrackPoint>>> = _liveTrack.asSharedFlow()

    // =============================================================================================
    // 累计统计
    // =============================================================================================

    @Volatile var totalRawPoints: Int = 0
        private set
    @Volatile var totalSmoothPoints: Int = 0
        private set
    @Volatile var totalSegments: Int = 0
        private set
    @Volatile var totalDistanceM: Double = 0.0
        private set

    /** 累计被 [TrackFilter] 拒绝的点数。 */
    val rejectedPoints: Int
        get() = rawFilter.rejectedCount

    /** 是否正在记录。 */
    val isRecording: Boolean
        get() = sessionDir != null

    // =============================================================================================
    // 生命周期
    // =============================================================================================

    /**
     * 开始会话。
     *
     * 创建目录结构、写入初始元数据、开第一段。
     */
    fun startSession(): File {
        val sessionId = buildSessionId()
        val dir = File(tracksRoot, sessionId).apply { mkdirs() }
        File(dir, "media").mkdirs()
        sessionDir = dir
        startTimeMs = System.currentTimeMillis()
        totalRawPoints = 0
        totalSmoothPoints = 0
        totalSegments = 0
        totalDistanceM = 0.0
        segmentIndex = 0
        displayRaw.clear()
        displaySmooth.clear()
        events.clear()
        lastRecordedLat = null
        lastRecordedLng = null

        startNewSegment()
        writeSessionJson(dir)
        return dir
    }

    /**
     * 停止记录并返回会话目录。
     *
     * 与 [endSession] 不同——`endSession` 只关闭，`stop` 关闭后返回目录，
     * 供调用方保存/通知。
     *
     * @param simplify         是否对段内点做 Douglas-Peucker 简化（此参数当前由
     *                         [closeCurrentSegment] 内部按类型决定，暂未透传；
     *                         保留签名以便将来扩展）
     * @param simplifyEpsilonM 简化容差（米），同上
     * @return 会话目录，或 null（未开始记录）
     */
    fun stop(
        simplify: Boolean = true,
        simplifyEpsilonM: Double = 0.5,
    ): File? {
        val dir = sessionDir ?: return null
        closeCurrentSegment(simplify, simplifyEpsilonM)  // ★ 透传
        writeSessionJson(dir)
        sessionDir = null
        return dir
    }

    /**
     * 关闭当前段并写文件。
     *
     * @param simplify         是否简化（rollSegment 路径用默认 true）
     * @param simplifyEpsilonM 简化容差（米）
     */
    private fun closeCurrentSegment(
        simplify: Boolean = true,
        simplifyEpsilonM: Double = 0.5,
    ) {
        val rawBuf = currentRawBuffer ?: return
        val smoothBuf = currentSmoothBuffer ?: return
        val dir = sessionDir ?: return

        if (rawBuf.size >= 2) {
            val segId = "seg-%03d".format(segmentIndex)
            val rawFile = File(dir, "$segId.raw.csv")
            val smoothFile = File(dir, "$segId.smooth.csv")

            // smooth 轨道用更小的容差（因为它是已经平滑后的点，再简化容易丢细节）
            val smoothEpsilon = simplifyEpsilonM * 0.6

            val rawOut = if (simplify) TrackSimplifier.simplify(rawBuf, simplifyEpsilonM) else rawBuf
            val smoothOut = if (simplify) TrackSimplifier.simplify(smoothBuf, smoothEpsilon) else smoothBuf

            TrackStore.write(rawFile, rawOut)
            TrackStore.write(smoothFile, smoothOut)
            totalSegments++
        }

        currentRawBuffer = null
        currentSmoothBuffer = null
    }
    /** 只关闭会话，不返回目录。等价于 [stop] 但更清晰地表达"结束"。 */
    fun endSession() {
        val dir = sessionDir ?: return
        closeCurrentSegment()
        writeSessionJson(dir)
        sessionDir = null
    }

    /** 别名——语义上等价 [endSession]。 */
    fun endRecording() = endSession()

    /** 丢弃当前段的缓冲数据（不写文件）。 */
    fun discardCurrentSegment() {
        currentRawBuffer = null
        currentSmoothBuffer = null
    }

    /** 注入传感器采样器（可选）。 */
    fun setSensorSampler(sampler: SensorSampler?) {
        this.sensorSampler = sampler
    }

    // =============================================================================================
    // 记录
    // =============================================================================================

    fun record(loc: Location, profile: ActivityProfile = ActivityProfile.DRIVING) {
        val rawBuf = currentRawBuffer ?: return
        val smoothBuf = currentSmoothBuffer ?: return

        val nowNanos = if (loc.elapsedRealtimeNanos != 0L) loc.elapsedRealtimeNanos
        else loc.time * 1_000_000L
        val wallMs = System.currentTimeMillis()

        // 传感器快照
        val snap = sensorSampler?.snapshot()

        // 构建 raw 点
        val rawPoint = TrackPoint(
            lat = loc.latitude,
            lng = loc.longitude,
            altitudeM = if (loc.hasAltitude()) loc.altitude else null,
            speedMps = if (loc.hasSpeed()) loc.speed else null,
            bearingDeg = if (loc.hasBearing()) loc.bearing else null,
            accuracyM = if (loc.hasAccuracy()) loc.accuracy else null,
            timestampMs = wallMs,
            elapsedNanos = nowNanos,
            accelX = snap?.accel?.getOrNull(0),
            accelY = snap?.accel?.getOrNull(1),
            accelZ = snap?.accel?.getOrNull(2),
            gyroX = snap?.gyro?.getOrNull(0),
            gyroY = snap?.gyro?.getOrNull(1),
            gyroZ = snap?.gyro?.getOrNull(2),
            magX = snap?.mag?.getOrNull(0),
            magY = snap?.mag?.getOrNull(1),
            magZ = snap?.mag?.getOrNull(2),
        )

        // 入口过滤
        val lastKept = rawBuf.lastOrNull()
        if (!rawFilter.shouldKeep(rawPoint, lastKept)) return

        // 构建 smooth 点
        val dtSec = if (lastElapsedNanos > 0L) (nowNanos - lastElapsedNanos) / 1e9 else -1.0
        val rawLatLng = LatLng(loc.latitude, loc.longitude)
        val smoothLatLng = smoothSanitizer.sanitize(
            here = rawLatLng,
            prev = lastSmoothPos,
            lastSpeedMps = lastAcceptedSpeed,
            dtSeconds = dtSec,
            wasUpgrade = false,
            profile = profile,
        )
        val smoothPoint = rawPoint.copy(
            lat = smoothLatLng.latitude,
            lng = smoothLatLng.longitude,
        )

        // ★ B2 修复：先写入当前段
        rawBuf.add(rawPoint)
        smoothBuf.add(smoothPoint)
        totalRawPoints++
        totalSmoothPoints++

        // ★ 事件坐标基准：更新最近记录点
        lastRecordedLat = rawPoint.lat
        lastRecordedLng = rawPoint.lng

        // ★ 会话级显示缓冲：跨段累计，地图持续显示整个 session 的轨迹
        displayRaw.add(rawPoint)
        displaySmooth.add(smoothPoint)
        trimDisplayBuffer(displayRaw)
        trimDisplayBuffer(displaySmooth)

        // 累加距离
        val stepDist = lastKept?.let {
            GeoMath.haversineMeters(it.lat, it.lng, rawPoint.lat, rawPoint.lng)
        } ?: 0.0
        segmentDistanceM.add(stepDist)
        totalDistanceM += stepDist

        // 更新平滑状态
        lastSmoothPos = smoothLatLng
        lastElapsedNanos = nowNanos
        if (loc.hasSpeed()) lastAcceptedSpeed = loc.speed

        // 通知实时绘制（★ 会话级缓冲：含所有已完成的段）
        lastRawBuffer = snapshotTail(displayRaw)
        lastSmoothBuffer = snapshotTail(displaySmooth)
        _liveTrack.tryEmit(lastRawBuffer to lastSmoothBuffer)

        // 检查是否切段（在写入之后）
        val elapsedMs = wallMs - segmentStartMs
        val durHit = segmentConfig.durationMs > 0 && elapsedMs >= segmentConfig.durationMs
        val distHit = segmentConfig.distanceM > 0 && segmentDistanceM.sum() >= segmentConfig.distanceM
        val sizeHit = rawBuf.size >= MAX_POINTS_PER_SEGMENT
        if (durHit || distHit || sizeHit) {
            rollSegment()
        }
    }

    // =============================================================================================
    // 段管理
    // =============================================================================================

    private fun startNewSegment() {
        currentRawBuffer = mutableListOf()
        currentSmoothBuffer = mutableListOf()
        segmentIndex++
        segmentStartMs = System.currentTimeMillis()
        segmentDistanceM.reset()

        smoothSanitizer.reset()
        lastSmoothPos = null
        lastAcceptedSpeed = 0f
        lastElapsedNanos = 0L
        rawFilter.reset()

        // ★ 不再清空 lastRawBuffer/lastSmoothBuffer——显示缓冲跨段累计，
        // 地图应保留已完成的段直至会话结束
    }

    private fun rollSegment() {
        closeCurrentSegment()
        startNewSegment()
    }

    // =============================================================================================
    // 媒体
    // =============================================================================================

    /**
     * 记录一个过程事件（暂停/继续）。
     *
     * 由 [org.kori.plugin.geo.track.TrackRecordingEngine.pause] / [resume] 调用。
     * 坐标取最近记录的轨迹点（尚未记录点时为 0.0）。立即持久化到 session.json。
     */
    fun recordEvent(type: TrackEventType, atMs: Long = System.currentTimeMillis()) {
        if (sessionDir == null) return
        events.add(
            TrackEvent(
                type = type,
                timestampMs = atMs,
                lat = lastRecordedLat ?: 0.0,
                lng = lastRecordedLng ?: 0.0,
            ),
        )
        sessionDir?.let { writeSessionJson(it) }
    }

    fun recordMedia(record: TrackMediaRecord) {
        val dir = sessionDir ?: return
        val mediaDir = File(dir, "media").apply { mkdirs() }
        val base = record.filePath.substringAfterLast('/').substringBeforeLast('.')
        File(mediaDir, "$base.json").writeText(
            org.json.JSONObject().apply {
                put("type", record.type.name)
                put("filePath", record.filePath)
                put("timestampMs", record.timestampMs)
                put("lat", record.lat)
                put("lng", record.lng)
                record.accuracyM?.let { put("accuracyM", it.toDouble()) }
                record.durationSec?.let { put("durationSec", it) }
                record.note?.let { put("note", it) }
            }.toString(2),
        )
    }

    // =============================================================================================
    // 元数据
    // =============================================================================================

    private fun writeSessionJson(dir: File) {
        runCatching {
            org.json.JSONObject().apply {
                put("version", 2)
                put("name", sessionNameHint ?: "session")
                put("startedMs", startTimeMs)
                put("updatedMs", System.currentTimeMillis())
                put("totalRawPoints", totalRawPoints)
                put("totalSmoothPoints", totalSmoothPoints)
                put("totalSegments", totalSegments)
                put("totalDistanceM", totalDistanceM)
                put("rejectedPoints", rejectedPoints)
                put(
                    "events",
                    org.json.JSONArray().apply {
                        for (e in events) {
                            put(
                                org.json.JSONObject().apply {
                                    put("type", e.type.name)
                                    put("timestampMs", e.timestampMs)
                                    put("lat", e.lat)
                                    put("lng", e.lng)
                                },
                            )
                        }
                    },
                )
                put(
                    "segmentConfig",
                    org.json.JSONObject().apply {
                        put("durationMinutes", segmentConfig.durationMinutes)
                        put("distanceMeters", segmentConfig.distanceMeters)
                    },
                )
            }.let { File(dir, "session.json").writeText(it.toString(2)) }
        }
    }

    private fun buildSessionId(): String {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val safe = sessionNameHint
            ?.replace(Regex("[^A-Za-z0-9_-]"), "_")
            ?.take(24)
            ?: "session"
        return "$safe-$stamp"
    }

    /** 显示缓冲超限丢弃最旧点（保持内存有界）。 */
    private fun trimDisplayBuffer(buf: MutableList<TrackPoint>) {
        while (buf.size > MAX_DISPLAY_POINTS) buf.removeAt(0)
    }

    /** 取 buffer 尾部 LIVE_BUFFER_MAX 个点。 */
    private fun snapshotTail(buf: List<TrackPoint>): List<TrackPoint> =
        if (buf.size <= LIVE_BUFFER_MAX) buf.toList()
        else buf.subList(buf.size - LIVE_BUFFER_MAX, buf.size).toList()

    companion object {
        private const val MAX_POINTS_PER_SEGMENT = 20_000
        private const val LIVE_BUFFER_MAX = 2000

        /** 会话级实时显示缓冲上限（跨段累计，地图绘制用）。 */
        private const val MAX_DISPLAY_POINTS = 5000
    }
}