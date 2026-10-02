package org.kori.plugin.geo.track

import android.content.Context
import android.location.Location
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.kori.plugin.geo.location.LocationTracker
import java.io.File
import android.content.Intent
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import org.kori.plugin.geo.service.TrackFgsBridge
import org.kori.plugin.geo.service.TrackRecordingService

import java.util.concurrent.atomic.AtomicBoolean
/**
 * 轨迹记录引擎（应用级单例）。
 *
 * ## 设计目标
 *
 *  · **单例**：不受 Compose / Activity 生命周期影响，App 切后台记录继续
 *  · **共享**：地图和记录器共用**同一个** [LocationTracker]，保证位置一致
 *  · **FGS 桥接**：通过 [TrackFgsBridge] 让宿主的前台服务保持进程存活
 *  · **符合 Combolite**：插件不带 Service，Service 由宿主提供
 *
 * ## 生命周期
 *
 * ```
 * Application.onCreate()
 *     └─ TrackRecordingEngine.init(applicationContext)
 *
 * 用户点击"开始"
 *     └─ TrackRecordingEngine.start(context)
 *         ├─ 创建 LocationTracker / SegmentedTrackRecorder / SensorSampler
 *         ├─ 启动 tracker
 *         ├─ 订阅 fix → recorder.record()
 *         ├─ 订阅 recorder.liveTrack → state 更新
 *         └─ 启动 FGS（宿主 HostTrackFgs）
 *
 * 用户点击"停止"
 *     └─ TrackRecordingEngine.stop()
 *         ├─ 取消所有 job
 *         ├─ 关闭 recorder 会话
 *         ├─ 停止 tracker
 *         ├─ 清理缓存
 *         └─ 停止 FGS
 *
 * App 进程结束
 *     └─ 系统自动清理（无需手动 destroy）
 * ```
 *
 * ## 线程安全
 *
 *  · 所有 public 方法都可以从任意线程调用
 *  · `start()` / `stop()` 用 [lifecycleLock] 保护，幂等
 *  · 内部状态用 `@Volatile` 或 `StateFlow` 保证可见性
 *
 * ## 媒体通知链
 *
 * ```
 * TrackMediaCaptureActivity / VideoCaptureActivity
 *     └─ TrackRecordingEngine.notifyMediaAdded(record)
 *         ├─ recorder.recordMedia(record)     ← 写 sidecar JSON
 *         └─ _state.liveMedia += record       ← UI 更新
 * ```
 */
object TrackRecordingEngine {

    // =============================================================================================
    // 常量
    // =============================================================================================

    /** 轨迹文件根目录（相对于 filesDir）。 */
    private const val TRACKS_DIR = "tracks"

    // =============================================================================================
    // 内部状态
    // =============================================================================================

    /** 应用级 CoroutineScope。生命周期与应用相同。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Application Context。init 时赋值。 */
    @Volatile private var appContext: Context? = null

    /** 生命周期锁（保护 start/stop 的原子性）。 */
    private val lifecycleLock = Any()

    // =============================================================================================
    // 对外状态
    // =============================================================================================

    private val _state = MutableStateFlow(TrackRecordingState())
    /** 记录状态。UI 和宿主可订阅。 */
    val state: StateFlow<TrackRecordingState> = _state.asStateFlow()

    /**
     * 位置 fix 流（供地图订阅驱动相机）。
     *
     * replay = 1：新订阅者会收到最近一次 fix，避免相机延迟跟到。
     */
    private val _trackerFixes = MutableSharedFlow<LocationTracker.Fix>(
        replay = 1,
        extraBufferCapacity = 16,
    )
    val trackerFixes: SharedFlow<LocationTracker.Fix> = _trackerFixes.asSharedFlow()

    /**
     * 媒体添加事件流。ViewModel 也可订阅做 UI 提示。
     */
    private val _mediaEvents = MutableSharedFlow<TrackMediaRecord>(extraBufferCapacity = 8)
    val mediaEvents: SharedFlow<TrackMediaRecord> = _mediaEvents.asSharedFlow()

    // =============================================================================================
    // 内部组件
    // =============================================================================================

    private var tracker: LocationTracker? = null
    private var recorder: SegmentedTrackRecorder? = null
    private var sensorSampler: SensorSampler? = null

    private var locationJob: Job? = null
    private var liveJob: Job? = null
    private var mediaJob: Job? = null

    /** 当前会话目录。非 null 表示记录中。 */
    @Volatile var currentSessionDir: File? = null
        private set

    /** 最近一次 GPS fix（供媒体采集定位）。 */
    @Volatile var lastKnownLocation: Location? = null
        private set

    /** 上次更新 FGS 通知的墙钟时间，用于 1Hz 节流。 */
    @Volatile private var lastFgsUpdateMs = 0L

    /** 是否已初始化。 */
    private val initialized = AtomicBoolean(false)

    // =============================================================================================
    // 初始化
    // =============================================================================================

    /**
     * 应用启动时调用一次。幂等。
     *
     * 通常在 `Application.onCreate()` 中：
     *
     * ```kotlin
     * override fun onCreate() {
     *     super.onCreate()
     *     TrackRecordingEngine.init(this)
     * }
     * ```
     *
     * @param context 建议传 applicationContext
     */
    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        appContext = context.applicationContext

        // 订阅媒体事件 → 转发给 recorder
        mediaJob = scope.launch {
            _mediaEvents.collect { record ->
                runCatching { recorder?.recordMedia(record) }
                _state.update { it.copy(liveMedia = it.liveMedia + record) }
            }
        }
    }

    // =============================================================================================
    // 记录启停
    // =============================================================================================

    /**
     * 开始记录。
     *
     * 幂等：若已在记录，直接返回。
     *
     * @param context 建议传 applicationContext
     * @param config  分段配置（默认 [SegmentConfig.Default]）
     * @param nameHint 会话名称提示（默认 "session"）
     */
    fun start(
        context: Context,
        config: SegmentConfig = SegmentConfig.Default,
        nameHint: String? = null,
    ) {
        synchronized(lifecycleLock) {
            if (_state.value.recording) return
            val ctx = context.applicationContext

            // ---- 1. 创建 tracker / recorder / sampler ----
            val t = LocationTracker(ctx)
            val r = SegmentedTrackRecorder(
                tracksRoot = File(ctx.filesDir, TRACKS_DIR).apply { mkdirs() },
                segmentConfig = config,
                sessionNameHint = nameHint ?: "session",
            )
            val s = SensorSampler(ctx)
            s.start()
            r.setSensorSampler(s)

            // ---- 2. 开会话 ----
            val sessionDir = r.startSession()
            currentSessionDir = sessionDir

            tracker = t
            recorder = r
            sensorSampler = s

            // ---- 3. 订阅 fix → 驱动 recorder + 转发地图 + 更新 FGS ----
            locationJob = scope.launch {
                t.fixes.collect { fix ->
                    lastKnownLocation = t.lastRawLocation
                    _trackerFixes.tryEmit(fix)

                    val loc = t.lastRawLocation ?: return@collect
                    r.record(loc, fix.profile)
                    val now = System.currentTimeMillis()
                    val elapsedMs = now - r.startTimeMs
                    _state.update {
                        it.copy(
                            recording = true,
                            points = r.totalRawPoints,
                            distanceM = r.totalDistanceM,
                            segments = r.totalSegments,
                            elapsedMs = elapsedMs,
                        )
                    }
                    if (now - lastFgsUpdateMs >= 1000L) {
                        lastFgsUpdateMs = now
                        updateFgsNotification(r)
                    }
                    // 更新 FGS 通知（每次 fix 一次）
                    updateFgsNotification(r)
                }
            }

            // ---- 4. 订阅实时轨迹 ----
            liveJob = scope.launch {
                r.liveTrack.collect { (raw, smooth) ->
                    _state.update {
                        it.copy(liveTrackPoints = raw, liveSmoothPoints = smooth)
                    }
                }
            }

            // ---- 5. 启动传感器和 GPS ----
            t.start()

            // ---- 6. 状态更新 + 启动 FGS ----
            _state.update { it.copy(recording = true) }
            runCatching {
                TrackFgsBridge.start(
                    ctx,
                    title = "Vela 轨迹记录",
                    text = "记录中...",
                )
            }
        }
    }

    /**
     * 停止记录。
     *
     * 幂等：若未在记录，直接返回。
     */
    fun stop() {
        synchronized(lifecycleLock) {
            if (!_state.value.recording) return
            val ctx = appContext

            // ---- 1. 取消所有 job ----
            locationJob?.cancel(); locationJob = null
            liveJob?.cancel(); liveJob = null

            // ---- 2. 关闭组件 ----
            sensorSampler?.stop(); sensorSampler = null
            recorder?.endSession(); recorder = null
            tracker?.stop(); tracker?.destroy(); tracker = null

            // ---- 3. 清空状态 ----
            currentSessionDir = null
            lastKnownLocation = null
            lastFgsUpdateMs = 0L
            _state.update {
                it.copy(
                    recording = false,
                    liveTrackPoints = emptyList(),
                    liveSmoothPoints = emptyList(),
                    liveMedia = emptyList(),
                )
            }

            // ---- 4. 停止 FGS ----
            ctx?.let {
                runCatching { TrackFgsBridge.stop(it) }
            }

            // ---- 5. 刷新会话列表 ----
            ctx?.let { refreshSessions(it) }
        }
    }

    /**
     * 切换记录状态。
     */
    fun toggle(context: Context) {
        if (_state.value.recording) stop() else start(context)
    }

    /** 是否正在记录。 */
    val isRecording: Boolean
        get() = _state.value.recording

    // =============================================================================================
    // 媒体
    // =============================================================================================

    /**
     * 通知 Engine 有新媒体添加。
     *
     * 由 [org.kori.plugin.geo.map.service.TrackMediaCaptureActivity] 和
     * [org.kori.plugin.geo.map.service.VideoCaptureActivity] 在保存完成后调用。
     *
     * 调用链：
     *  · [mediaJob] 里 collect → recorder.recordMedia() + state.liveMedia += record
     */
    fun notifyMediaAdded(record: TrackMediaRecord) {
        _mediaEvents.tryEmit(record)
    }

    // =============================================================================================
    // 会话管理
    // =============================================================================================

    /**
     * 刷新会话列表（异步）。
     *
     * 结果写入 [TrackRecordingState.sessions]。
     */
    fun refreshSessions(context: Context) {
        val ctx = context.applicationContext
        scope.launch {
            _state.update { it.copy(loadingSessions = true) }
            val sessions = withContext(Dispatchers.IO) {
                runCatching {
                    TrackSessionStore.list(File(ctx.filesDir, TRACKS_DIR))
                }.getOrDefault(emptyList())
            }
            _state.update { it.copy(sessions = sessions, loadingSessions = false) }
        }
    }

    /**
     * 删除一个会话。
     */
    fun deleteSession(session: TrackSession) {
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { TrackSessionStore.delete(session) }
            }
            appContext?.let { refreshSessions(it) }
        }
    }

    // =============================================================================================
    // 内部：FGS 通知更新
    // =============================================================================================

    /**
     * 每次 fix 更新 FGS 通知。
     *
     * 1 Hz 频率，格式：
     * ```
     * 245 点 · 2.13 km
     * ```
     */
    private fun updateFgsNotification(recorder: SegmentedTrackRecorder) {
        val ctx = appContext ?: return
        val text = buildString {
            append(recorder.totalRawPoints).append(" 点")
            append(" · ")
            append("%.2f".format(recorder.totalDistanceM / 1000)).append(" km")
        }
        runCatching {
            TrackFgsBridge.update(
                ctx,
                title = "Vela 轨迹记录",
                text = text,
            )
        }
    }

    // =============================================================================================
    // 内部：调试 / 清理
    // =============================================================================================

    /**
     * 释放所有资源（仅供测试和调试使用）。
     *
     * **生产代码不应该调用**——Engine 是应用级单例，进程结束时系统自动回收。
     * 仅用于单元测试或特殊清理场景。
     */
    internal fun destroyForTest() {
        synchronized(lifecycleLock) {
            stop()
            mediaJob?.cancel(); mediaJob = null
            scope.cancel()
            initialized.set(false)
            appContext = null
        }
    }

    // =============================================================================================
    // 通知栏停止按钮的 Intent（供宿主 Receiver 使用）
    // =============================================================================================

    /**
     * 处理来自通知栏"停止"按钮的 Intent。
     *
     * 宿主 Receiver 收到 [org.kori.plugin.geo.map.service.HostTrackFgs.ACTION_USER_STOP]
     * 后调用此方法。引擎会：
     *  · 停止记录
     *  · 停止 FGS
     */
    fun handleUserStopIntent(context: Context, intent: Intent) {
        when (intent.action) {
            TrackRecordingService.ACTION_USER_STOP -> {
                stop()
                runCatching { TrackFgsBridge.stop(context.applicationContext) }
            }
        }
    }
}

/**
 * 轨迹记录状态。
 *
 * 由 [TrackRecordingEngine.state] 暴露。UI 通过 `collectAsState()` 订阅。
 *
 * ## 字段说明
 *
 *  · **记录状态**：[recording] / [points] / [distanceM] / [elapsedMs] / [segments]
 *  · **实时轨迹**：[liveTrackPoints] / [liveSmoothPoints] / [liveMedia]
 *  · **会话列表**：[sessions] / [loadingSessions]
 */
data class TrackRecordingState(
    // =============================================================================================
    // 记录状态
    // =============================================================================================

    /** 是否正在记录。 */
    val recording: Boolean = false,

    /** 已保留的轨迹点数（原始过滤后）。 */
    val points: Int = 0,

    /** 累计距离（米）。 */
    val distanceM: Double = 0.0,

    /** 记录时长（毫秒），从会话开始时算起。 */
    val elapsedMs: Long = 0L,

    /** 已完成的分段数。 */
    val segments: Int = 0,

    // =============================================================================================
    // 实时数据（供地图绘制）
    // =============================================================================================

    /** 实时原始轨迹点（尾部 LIVE_BUFFER_MAX 个）。 */
    val liveTrackPoints: List<TrackPoint> = emptyList(),

    /** 实时平滑轨迹点（与 [liveTrackPoints] 一一对应）。 */
    val liveSmoothPoints: List<TrackPoint> = emptyList(),

    /** 实时媒体附件列表。 */
    val liveMedia: List<TrackMediaRecord> = emptyList(),

    // =============================================================================================
    // 会话列表
    // =============================================================================================

    /** 已保存的会话列表（按时间倒序）。 */
    val sessions: List<TrackSession> = emptyList(),

    /** 是否正在加载会话列表。 */
    val loadingSessions: Boolean = false,
) {
    /** 是否完全空闲（未记录且无实时数据）。 */
    val isIdle: Boolean
        get() = !recording &&
                liveTrackPoints.isEmpty() &&
                liveSmoothPoints.isEmpty()

    /**
     * 格式化的时长字符串。
     *
     * · < 1 小时：`MM:SS`
     * · ≥ 1 小时：`HH:MM:SS`
     */
    val elapsedText: String
        get() {
            val totalSec = elapsedMs / 1000
            val h = totalSec / 3600
            val m = (totalSec % 3600) / 60
            val s = totalSec % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s)
            else "%02d:%02d".format(m, s)
        }

    /** 格式化的距离字符串（保留两位小数）。 */
    val distanceText: String
        get() = "%.2f km".format(distanceM / 1000)

    /** 一段人类可读的摘要，用于通知栏。 */
    val summaryText: String
        get() = if (recording) "$elapsedText · $points 点 · $distanceText"
        else "未记录"
}