package org.kori.plugin.geo.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import org.kori.plugin.geo.math.GeoMath
import org.maplibre.android.geometry.LatLng
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.kori.plugin.geo.track.SegmentConfig
import org.kori.plugin.geo.track.SegmentedTrackRecorder
import org.kori.plugin.geo.track.SensorSampler
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import kotlin.time.Duration.Companion.milliseconds

/**
 * 全场景位置追踪器。
 *
 * ## 特性
 *
 *  · **活动检测** —— 从加速度计方差 + GPS 速度推断 STILL / WALKING / CYCLING / DRIVING
 *  · **位置过滤** —— Outlier 拒绝 + 按 profile 自适应的低速低通
 *  · **速度融合** —— 加速度计 predict（10Hz）+ GPS update（~1Hz）的卡尔曼滤波
 *  · **Dead Reckoning** —— 无 GPS fix 时（隧道 / 桥下）沿最后已知方位推算位置
 *  · **轨迹记录** —— 独立记录原始 fix（入口过滤 + Douglas-Peucker 简化）
 *  · **硬件降级** —— 无加速度计/旋转矢量传感器时自动跳过预测，仅用 GPS 测量
 *
 * ## 两个位置的概念（关键）
 *
 * 本类内部跟踪 **两个独立的位置**：
 *
 *  · [lastAcceptedFix] —— 最后一次被接受的**真实** GPS 位置。
 *    仅由 [processFix] 更新。用于：
 *      · 位置过滤的 outlier 判定基准（`prev`）
 *      · 方位角推导的位移基准
 *
 *  · [drCurrentPos] —— **当前显示**位置。由 [processFix]（真实 fix 到达）和
 *    [deadReckonTick]（无 fix 时推算）共同更新。用于：
 *      · 位置流输出的 lat/lng
 *      · DR 推算的起点
 *
 * **为什么必须分开**：如果把显示位置直接当作 outlier 判定基准，隧道里 DR 会
 * 沿着旧方位偏移；出隧道后真实 fix 与 DR 位置相差几百米，被 outlier 拒绝，
 * 蓝点卡住直到 DR 位置衰减到合理范围。分开后，真实 fix 与**最后一次真实位置**
 * 比对，正常接受。
 *
 * ## 生命周期
 *
 * `locationFlow()` 首次 collect 时启动传感器 + ticker；Flow 取消时自动停止。
 * 想彻底销毁请调用 [stop]。
 *
 * ## 两个用途
 *
 *  1. **位置追踪**（必需）：[start] / [stop] 驱动位置采集，
 *     通过 [fixes] SharedFlow 对外发布过滤后的 fix。
 *
 *  2. **轨迹记录**（可选）：[startRecording] / [stopRecording]
 *     在位置采集之上叠加记录器，把原始 fix 写入 CSV。
 *     不需要记录时不用调用——[start] 单独也能工作。
 *
 * ## 与 TrackRecordingEngine 的关系
 *
 * ```
 * TrackRecordingEngine (应用级共享单例)
 *     └─ 内部持有一个 LocationTracker，用于地图 + 记录共享
 *
 * 独立使用 LocationTracker (比如轻量场景)
 *     ├─ start() 只做位置显示
 *     └─ startRecording() 加上记录功能
 * ```
 *
 * ## 线程安全
 *
 *  · [start] / [stop] 用 [lifecycleLock] 保护，幂等
 *  · [startRecording] / [stopRecording] 用 [recordLock] 保护
 *  · 内部状态用 `@Volatile` 或 `StateFlow` 保证可见性
 */
class LocationTracker(private val context: Context) {

    // =============================================================================================
    // 数据模型
    // =============================================================================================

    /**
     * 一次过滤后的位置更新。
     */
    data class Fix(
        val lat: Double,
        val lng: Double,
        val speedMps: Float?,
        val bearingDeg: Float?,
        val accuracyM: Float?,
        val timestampMs: Long,
        val profile: ActivityProfile,
        val fromDeadReckon: Boolean = false,
    )

    // =============================================================================================
    // 组件
    // =============================================================================================

    private val sanitizer = PositionSanitizer()
    private val speedGate = SpeedGate()
    private val kalman = SpeedKalman()
    private val deadReckoner = DeadReckoner()
    private val activityDetector = ActivityDetector(context)
    private val motionProvider = MotionProvider(context)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickerJob: Job? = null
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    // =============================================================================================
    // 位置追踪状态
    // =============================================================================================

    private val _fixes = MutableSharedFlow<Fix>(replay = 1, extraBufferCapacity = 16)
    /** 过滤后的位置流。多个订阅者共享。 */
    val fixes: SharedFlow<Fix> = _fixes.asSharedFlow()

    @Volatile private var started = false
    private val lifecycleLock = Any()

    private var lastFixRtNanos: Long = 0L
    private var lastFixElapsedMs: Long = 0L
    private var lastFixWallMs: Long = 0L
    private var prevWasGps: Boolean = false
    private var lastSpeedEvidenceMs: Long = 0L
    private var lastAccuracyM: Float? = null
    private var lastAccuracyRtNanos: Long = 0L

    @Volatile private var lastAcceptedFix: LatLng? = null
    @Volatile private var drCurrentPos: LatLng? = null
    @Volatile private var lastBearing: Float? = null
    @Volatile private var wasDrActive: Boolean = false

    /** 最近一次原始 Location（供记录/媒体使用）。 */
    @Volatile var lastRawLocation: Location? = null
        private set

    // =============================================================================================
    // 记录状态
    // =============================================================================================

    private val recordLock = Any()
    private var recorder: SegmentedTrackRecorder? = null
    private var sensorSampler: SensorSampler? = null
    private var recordJob: Job? = null

    /** 是否正在记录。 */
    val isRecording: Boolean
        get() = synchronized(recordLock) { recorder != null }

    /** 当前已保留的点数。未记录时返回 0。 */
    fun recordedPoints(): Int =
        synchronized(recordLock) { recorder?.totalRawPoints ?: 0 }

    /** 当前已拒绝的漂移点数。未记录时返回 0。 */
    fun rejectedPoints(): Int =
        synchronized(recordLock) { recorder?.rejectedPoints ?: 0 }

    // =============================================================================================
    // 位置追踪生命周期
    // =============================================================================================

    /**
     * 启动位置采集。
     *
     * 幂等——多次调用只启动一次。
     */
    @SuppressLint("MissingPermission")
    fun start() {
        synchronized(lifecycleLock) {
            if (started) return
            started = true

            activityDetector.start()
            motionProvider.start()

            tickerJob = scope.launch {
                while (isActive) {
                    delay(TICK_MS)
                    try {
                        tick()
                        deadReckonTick()?.let { _fixes.tryEmit(it) }
                    } catch (_: Throwable) {
                        // 单帧异常不影响循环
                    }
                }
            }

            runCatching {
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 1000L, 0f,
                    locationListener, Looper.getMainLooper(),
                )
                lm.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, 1000L, 0f,
                    locationListener, Looper.getMainLooper(),
                )
            }
        }
    }

    /**
     * 停止位置采集。
     *
     * 幂等。**不会**停止记录——若在记录中，先调 [stopRecording]。
     */
    fun stop() {
        synchronized(lifecycleLock) {
            if (!started) return
            started = false
            runCatching { lm.removeUpdates(locationListener) }
            tickerJob?.cancel()
            tickerJob = null
            activityDetector.stop()
            motionProvider.stop()
            reset()
        }
    }

    /** 停止并释放协程作用域。**仅用于彻底销毁**（比如测试）。 */
    fun destroy() {
        stop()
        stopRecording()
        scope.cancel()
    }

    fun reset() {
        sanitizer.reset()
        speedGate.reset()
        kalman.reset()
        deadReckoner.onFix()
        lastFixRtNanos = 0L
        lastFixElapsedMs = 0L
        lastFixWallMs = 0L
        prevWasGps = false
        lastSpeedEvidenceMs = 0L
        lastAccuracyM = null
        lastAccuracyRtNanos = 0L
        lastAcceptedFix = null
        drCurrentPos = null
        lastBearing = null
        wasDrActive = false
        lastRawLocation = null
    }

    // =============================================================================================
    // 记录生命周期
    // =============================================================================================

    /**
     * 开始记录轨迹。
     *
     * 幂等——若已在记录，先停止旧会话再开新会话。
     *
     * @param tracksRoot  轨迹根目录（如 `File(filesDir, "tracks")`）
     * @param nameHint    会话名称提示（默认 "session"）
     * @param config      分段配置（默认 [SegmentConfig.Default]）
     * @return 新会话目录
     */
    fun startRecording(
        tracksRoot: File,
        nameHint: String? = null,
        config: SegmentConfig = SegmentConfig.Default,
    ): File {
        synchronized(recordLock) {
            // 若已在记录，先停
            if (recorder != null) {
                stopRecordingInternal()
            }

            val rec = SegmentedTrackRecorder(
                tracksRoot = tracksRoot,
                segmentConfig = config,
                sessionNameHint = nameHint ?: "session",
            )
            val sampler = SensorSampler(context)
            sampler.start()
            rec.setSensorSampler(sampler)

            val sessionDir = rec.startSession()

            recorder = rec
            sensorSampler = sampler

            // 订阅自己的 fixes 驱动记录
            recordJob = scope.launch {
                fixes.collect { fix ->
                    val loc = lastRawLocation ?: return@collect
                    rec.record(loc, fix.profile)
                }
            }

            return sessionDir
        }
    }

    /**
     * 停止记录。
     *
     * @param simplify        是否做 Douglas-Peucker 简化
     * @param simplifyEpsilonM 简化容差（米）
     * @return 写入的文件，或 null（无数据或未记录）
     */
    fun stopRecording(
        simplify: Boolean = true,
        simplifyEpsilonM: Double = 0.5,
    ): File? {
        synchronized(recordLock) {
            return stopRecordingInternal(simplify, simplifyEpsilonM)
        }
    }

    private fun stopRecordingInternal(
        simplify: Boolean = true,
        simplifyEpsilonM: Double = 0.5,
    ): File? {
        recordJob?.cancel()
        recordJob = null

        sensorSampler?.stop()
        sensorSampler = null

        val file = recorder?.stop(simplify, simplifyEpsilonM)
        recorder = null

        return file
    }

    // =============================================================================================
    // 内部：Location 监听
    // =============================================================================================

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            processFix(loc)?.let { _fixes.tryEmit(it) }
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

        override fun onProviderEnabled(provider: String) {}

        override fun onProviderDisabled(provider: String) {}
    }

    // =============================================================================================
    // 内部：处理 fix
    // =============================================================================================

    private fun processFix(loc: Location): Fix? {
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()
        val isGps = loc.provider == LocationManager.GPS_PROVIDER

        lastRawLocation = loc

        val fixRtNanos = if (loc.elapsedRealtimeNanos != 0L) loc.elapsedRealtimeNanos
        else loc.time * 1_000_000L
        val dt = if (lastFixRtNanos > 0L) (fixRtNanos - lastFixRtNanos) / 1e9 else -1.0
        if (lastFixRtNanos > 0L && dt <= 0.0) return null

        val accM = if (loc.hasAccuracy()) loc.accuracy else if (isGps) 10f else 1000f

        val lastAcc = lastAccuracyM
        if (!isGps && lastAcc != null) {
            val ageS = (fixRtNanos - lastAccuracyRtNanos) / 1e9
            val locSpeedD = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0
            val avgSpeed = (kalman.speed + locSpeedD) / 2.0
            if (!FixRules.betterThanLast(accM, lastAcc, ageS, avgSpeed)) return null
        }

        val wasUpgrade = FixRules.isUpgrade(lastAccuracyM, accM)
        lastAccuracyM = accM
        lastAccuracyRtNanos = fixRtNanos

        activityDetector.onGpsFix(if (loc.hasSpeed()) loc.speed else null)
        val profile = activityDetector.profile
        kalman.configure(profile)

        val prevRealFix = lastAcceptedFix

        // ★ 预测位置：prev + 速度×dt 沿最后方位角外推（alpha-beta 思路）。
        // sanitizer 向预测点而非 prev 收敛——匀速运动时消除系统性滞后，
        // 只在真实加速度上做平滑。
        val predicted: LatLng? = if (prevRealFix != null && dt > 0.0) {
            val brg = lastBearing
            val spd = kalman.speed
            if (brg != null && spd > 0.3) {
                val dist = spd * dt
                val rad = Math.toRadians(brg.toDouble())
                LatLng(
                    prevRealFix.latitude + dist * cos(rad) / GeoMath.METERS_PER_DEG_LAT,
                    prevRealFix.longitude + dist * sin(rad) /
                            GeoMath.metersPerDegLng(prevRealFix.latitude),
                )
            } else null
        } else null

        val rawHere = LatLng(loc.latitude, loc.longitude)
        val here = sanitizer.sanitize(
            here = rawHere,
            prev = prevRealFix,
            lastSpeedMps = kalman.speed.toFloat(),
            dtSeconds = dt,
            wasUpgrade = wasUpgrade,
            profile = profile,
            predicted = predicted,
        )

        val moved = prevRealFix?.let {
            GeoMath.haversineMeters(it.latitude, it.longitude, here.latitude, here.longitude)
        } ?: 0.0
        val canDerive = FixRules.canDeriveBearing(moved, accM, prevWasGps, isGps, dt)
        val bearing: Float? = when {
            loc.hasBearing() && loc.speed > 0.5f -> loc.bearing
            canDerive && moved > 3.0 && prevRealFix != null -> GeoMath.bearingDeg(
                prevRealFix.latitude, prevRealFix.longitude,
                here.latitude, here.longitude,
            )
            else -> lastBearing
        }

        val hasEvidence = loc.hasSpeed() || canDerive
        if (hasEvidence) lastSpeedEvidenceMs = nowElapsed
        val rawSpeed: Float = when {
            loc.hasSpeed() -> loc.speed
            canDerive -> (moved / dt).toFloat().coerceIn(0f, 70f)
            nowElapsed - lastSpeedEvidenceMs > 3_000L -> 0f
            else -> kalman.speed.toFloat()
        }
        if (hasEvidence) {
            speedGate.gate(rawSpeed, dt)?.let { accepted ->
                kalman.update(accepted.toDouble())
            }
        }

        lastFixRtNanos = fixRtNanos
        lastFixElapsedMs = nowElapsed
        lastFixWallMs = nowWall
        prevWasGps = isGps
        lastAcceptedFix = here
        drCurrentPos = here
        if (bearing != null) lastBearing = bearing
        deadReckoner.onFix()

        return Fix(
            lat = here.latitude,
            lng = here.longitude,
            speedMps = kalman.speed.toFloat(),
            bearingDeg = bearing,
            accuracyM = accM,
            timestampMs = nowWall,
            profile = profile,
            fromDeadReckon = false,
        )
    }

    // =============================================================================================
    // 内部：100ms ticker
    // =============================================================================================
    private fun tick() {
        // ★ DR 期间不 predict——deadReckonTick 里的 kalman.decay 会接管
        // 速度衰减。若同时 predict，两者在 10Hz 上交替会互相抵消。
        if (deadReckoner.isActive()) return

        if (!motionProvider.isReady) return
        val bearing = lastBearing ?: return
        val fwdAccel = motionProvider.forwardAccel(bearing)
        kalman.predict(fwdAccel.toDouble(), TICK_MS / 1000.0)
    }

    private fun deadReckonTick(): Fix? {
        val lastPos = drCurrentPos ?: return null
        val now = android.os.SystemClock.elapsedRealtime()
        val sinceLastFixMs = if (lastFixElapsedMs > 0L) now - lastFixElapsedMs else Long.MAX_VALUE
        val bearing = lastBearing ?: return null
        val profile = activityDetector.profile

        val newPos = deadReckoner.tick(
            sinceLastFixMs = sinceLastFixMs,
            speedMps = kalman.speed,
            bearingDeg = bearing,
            lastPos = lastPos,
            profile = profile,
            kalman = kalman,
            dtSeconds = TICK_MS / 1000.0,
        )

        if (newPos == null) {
            if (wasDrActive) {
                android.util.Log.d("DeadReckon", "DR stop")
                wasDrActive = false
            }
            return null
        }

        if (!wasDrActive) {
            android.util.Log.d("DeadReckon", "DR start sinceFix=${sinceLastFixMs}ms")
            wasDrActive = true
        }

        drCurrentPos = newPos
        return Fix(
            lat = newPos.latitude,
            lng = newPos.longitude,
            speedMps = kalman.speed.toFloat(),
            bearingDeg = bearing,
            accuracyM = null,
            timestampMs = System.currentTimeMillis(),
            profile = profile,
            fromDeadReckon = true,
        )
    }

    // =============================================================================================
    // 常量
    // =============================================================================================

    companion object {
        private const val TICK_MS = 100L
    }
}