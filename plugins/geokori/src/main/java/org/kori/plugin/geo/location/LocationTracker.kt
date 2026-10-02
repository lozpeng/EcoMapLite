package org.kori.plugin.geo.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.kori.plugin.geo.math.GeoMath
import org.maplibre.android.geometry.LatLng
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.kori.plugin.geo.track.TrackRecorder
import timber.log.Timber
import java.io.File
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
 */
class LocationTracker(private val context: Context) {

    // =============================================================================================
    // 数据模型
    // =============================================================================================

    data class Fix(
        val lat: Double,
        val lng: Double,
        val speedMps: Float?,
        val bearingDeg: Float?,
        val accuracyM: Float?,
        /** 墙钟时间戳（给用户看的日期，非单调）。 */
        val timestampMs: Long,
        val profile: ActivityProfile,
        val fromDeadReckon: Boolean = false,
    )

    // =============================================================================================
    // 依赖组件
    // =============================================================================================

    private val sanitizer = PositionSanitizer()
    private val speedGate = SpeedGate()
    private val kalman = SpeedKalman()
    private val deadReckoner = DeadReckoner()
    private val activityDetector = ActivityDetector(context)
    private val motionProvider = MotionProvider(context)

    // =============================================================================================
    // 内部状态
    // =============================================================================================

    private var lastFixRtNanos: Long = 0L

    /**
     * ★ 修复：记录最后一次真实 fix 的 **elapsedRealtime**（开机毫秒），
     * 与 [deadReckonTick] 里的 `SystemClock.elapsedRealtime()` 同源。
     * 之前误用 `System.currentTimeMillis()`（墙钟），导致 sinceLastFixMs
     * 恒为负值，DR 从未生效。
     */
    private var lastFixElapsedMs: Long = 0L

    private var prevWasGps: Boolean = false
    private var lastSpeedEvidenceMs: Long = 0L
    private var lastAccuracyM: Float? = null
    private var lastAccuracyRtNanos: Long = 0L

    /** 最后一次被接受的**真实** GPS 位置。仅由 [processFix] 更新。 */
    @Volatile private var lastAcceptedFix: LatLng? = null

    /** 当前**显示**位置。由 [processFix] 和 [deadReckonTick] 共同更新。 */
    @Volatile private var drCurrentPos: LatLng? = null

    @Volatile private var lastBearing: Float? = null

    /** DR 状态跟踪（仅用于日志）。 */
    @Volatile private var wasDrActive: Boolean = false

    private var recorder: TrackRecorder? = null

    private var tickerScope: CoroutineScope? = null
    private var tickerJob: Job? = null

    // =============================================================================================
    // 公开 API
    // =============================================================================================

    val isRecording: Boolean get() = recorder?.isRecording == true

    @SuppressLint("MissingPermission")
    fun locationFlow(): Flow<Fix> = callbackFlow {
        activityDetector.start()
        motionProvider.start()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        tickerScope = scope
        tickerJob = scope.launch {
            while (true) {
                delay(TICK_MS.milliseconds)
                try {
                    tick()
                    deadReckonTick()?.let { trySend(it) }
                } catch (_: Throwable) {
                    // ticker 出错不影响主流程
                }
            }
        }

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val listener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                processFix(loc)?.let { trySend(it) }
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        runCatching {
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper(),
            )
            lm.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper(),
            )
        }

        awaitClose {
            runCatching { lm.removeUpdates(listener) }
            tickerJob?.cancel()
            tickerJob = null
            scope.cancel()
            tickerScope = null
            activityDetector.stop()
            motionProvider.stop()
        }
    }

    // =============================================================================================
    // 轨迹记录 API
    // =============================================================================================

    fun startRecording(dir: File, nameHint: String? = null) {
        recorder?.stop()
        recorder = TrackRecorder(dir).apply { start(nameHint) }
    }

    fun stopRecording(simplify: Boolean = true, simplifyEpsilonM: Double = 0.5): File? {
        val file = recorder?.stop(simplify, simplifyEpsilonM)
        recorder = null
        return file
    }

    fun recordedPoints(): Int = recorder?.size() ?: 0
    fun rejectedPoints(): Int = recorder?.rejected() ?: 0

    // =============================================================================================
    // 生命周期
    // =============================================================================================

    fun stop() {
        tickerJob?.cancel()
        tickerJob = null
        tickerScope?.cancel()
        tickerScope = null
        activityDetector.stop()
        motionProvider.stop()
        recorder?.discard()
        recorder = null
        reset()
    }

    fun reset() {
        sanitizer.reset()
        speedGate.reset()
        kalman.reset()
        deadReckoner.onFix()
        lastFixRtNanos = 0L
        lastFixElapsedMs = 0L
        prevWasGps = false
        lastSpeedEvidenceMs = 0L
        lastAccuracyM = null
        lastAccuracyRtNanos = 0L
        lastAcceptedFix = null
        drCurrentPos = null
        lastBearing = null
        wasDrActive = false
    }

    // =============================================================================================
    // 内部：处理单个真实 fix
    // =============================================================================================

    private fun processFix(loc: Location): Fix? {
        val nowMs = android.os.SystemClock.elapsedRealtime()
        val wallMs = System.currentTimeMillis()
        val isGps = loc.provider == LocationManager.GPS_PROVIDER

        recorder?.record(loc)

        val fixRtNanos = if (loc.elapsedRealtimeNanos != 0L) loc.elapsedRealtimeNanos
        else loc.time * 1_000_000L
        val dt = if (lastFixRtNanos > 0L) (fixRtNanos - lastFixRtNanos) / 1e9 else -1.0
        if (lastFixRtNanos > 0L && dt <= 0.0) return null

        val accM = if (loc.hasAccuracy()) loc.accuracy else if (isGps) 10f else 1000f

        // ---- 网络 fix 门控 ----
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

        // ---- 活动检测 ----
        activityDetector.onGpsFix(if (loc.hasSpeed()) loc.speed else null)
        val profile = activityDetector.profile
        kalman.configure(profile)

        // ---- 位置过滤（outlier 基准用真实 fix，不用 DR 位置） ----
        val prevRealFix = lastAcceptedFix
        val rawHere = LatLng(loc.latitude, loc.longitude)
        val here = sanitizer.sanitize(
            here = rawHere,
            prev = prevRealFix,
            lastSpeedMps = kalman.speed.toFloat(),
            dtSeconds = dt,
            wasUpgrade = wasUpgrade,
            profile = profile,
        )

        // ---- 方位角（位移基准同样用真实 fix） ----
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

        // ---- 速度门控 + 卡尔曼 update ----
        val hasEvidence = loc.hasSpeed() || canDerive
        if (hasEvidence) lastSpeedEvidenceMs = nowMs
        val rawSpeed: Float = when {
            loc.hasSpeed() -> loc.speed
            canDerive -> (moved / dt).toFloat().coerceIn(0f, 70f)
            nowMs - lastSpeedEvidenceMs > 3_000L -> 0f
            else -> kalman.speed.toFloat()
        }
        if (hasEvidence) {
            speedGate.gate(rawSpeed, dt)?.let { accepted ->
                kalman.update(accepted.toDouble())
            }
        }

        // ---- 状态保存 ----
        lastFixRtNanos = fixRtNanos
        // ★ 修复：用 elapsedRealtime（与 deadReckonTick 的 now 同源）
        lastFixElapsedMs = nowMs
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
            timestampMs = wallMs,   // ★ 输出仍用墙钟（给用户看）
            profile = profile,
            fromDeadReckon = false,
        )
    }

    // =============================================================================================
    // 内部：100ms ticker
    // =============================================================================================

    private fun tick() {
        if (!motionProvider.isReady) return
        val bearing = lastBearing ?: return
        val fwdAccel = motionProvider.forwardAccel(bearing)
        kalman.predict(fwdAccel.toDouble(), TICK_MS / 1000.0)
    }

    private fun deadReckonTick(): Fix? {
        val lastPos = drCurrentPos ?: return null
        val now = android.os.SystemClock.elapsedRealtime()
        // ★ 修复：lastFixElapsedMs 与 now 同源，sinceLastFixMs 现在是正确值
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
                Timber.tag("DeadReckon")
                    .d("DR stop (sinceFix=${sinceLastFixMs}ms speed=${"%.1f".format(kalman.speed)}m/s)")
                wasDrActive = false
            }
            return null
        }

        if (!wasDrActive) {
            Timber.tag("DeadReckon").d(
                "DR start (sinceFix=${sinceLastFixMs}ms speed=${"%.1f".format(kalman.speed)}m/s bearing=${
                    "%.0f".format(bearing)
                })"
            )
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

    companion object {
        private const val TICK_MS = 100L
    }
}