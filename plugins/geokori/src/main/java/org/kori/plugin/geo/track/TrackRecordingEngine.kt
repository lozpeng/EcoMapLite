package org.kori.plugin.geo.track

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kori.plugin.geo.location.LocationTracker
import org.kori.plugin.geo.service.TrackFgsBridge
import org.kori.plugin.geo.service.TrackRecordingService
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 轨迹记录引擎（应用级单例）。
 *
 * ## 设计目标
 *
 *  · **单例**：不受 Compose / Activity 生命周期影响，App 切后台记录继续
 *  · **共享**：地图和记录器共用**同一个** [LocationTracker]，保证位置一致
 *  · **长生命周期 tracker**：★ tracker 从 init/首次获得权限起常驻，fix 常热——
 *    蓝点即开即有，开始记录时立刻有数据（避免每次录制的 GPS 冷启动 10~30s）
 *  · **FGS 桥接**：通过 [TrackFgsBridge] 让宿主的前台服务保持进程存活
 *  · **暂停/继续**：暂停期间不写入轨迹点、距离与计时冻结（见 [pause] / [resume]）
 *
 * ## 生命周期
 *
 * ```
 * Application.onCreate / 插件 onLoad
 *     └─ init()                          → 存 context；有定位权限则启动共享 tracker
 * 地图页面获得定位权限
 *     └─ ensureLocationTracking(ctx)     → 幂等启动共享 tracker（无录制也有 fix）
 *
 * 用户点击"开始"   → start()    → 只创建 recorder + sampler + FGS（tracker 已热）
 * 用户点击"暂停"   → pause()    → 停止写入，计时/距离冻结，FGS 显示"已暂停"
 * 用户点击"继续"   → resume()   → 恢复写入（仍在同一会话/段内）
 * 用户点击"结束"   → stop()     → 关闭会话、停止 FGS（★ tracker 保持运行）
 * ```
 *
 * ## 暂停的语义（重要）
 *
 *  · 暂停**不关闭会话、不切断 GPS**——resume 时继续写入当前段，不产生新段
 *  · 暂停期间 fix 仍转发给地图（[trackerFixes]），蓝点继续跟随，方便用户暂停时浏览地图
 *  · [TrackRecordingState.elapsedMs] 与 distance 在暂停期间**冻结**
 *  · 进程在暂停期间仍受 FGS 保护（避免系统杀进程导致会话丢失）
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
    // 暂停状态（在 lifecycleLock 内读写）
    // =============================================================================================

    /** 是否已暂停。 */
    @Volatile private var paused: Boolean = false

    /** 本次暂停开始的墙钟时间（毫秒）。未暂停时为 0。 */
    @Volatile private var pauseStartMs: Long = 0L

    /** 历史累计暂停时长（毫秒），用于从 elapsed 中扣除。 */
    @Volatile private var pausedTotalMs: Long = 0L

    // =============================================================================================
    // 对外状态
    // =============================================================================================

    private val _state = MutableStateFlow(TrackRecordingState())
    /** 记录状态。UI 和宿主可订阅。 */
    val state: StateFlow<TrackRecordingState> = _state.asStateFlow()

    /**
     * 位置 fix 流（供地图订阅驱动相机/蓝点）。
     *
     * replay = 1：新订阅者会收到最近一次 fix，避免相机延迟跟到。
     *
     * 注意：暂停期间此流**仍然发射**（用户暂停时往往想继续看地图位置），
     * 只是不再写入轨迹。
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

    /**
     * 共享位置追踪器。★ 长生命周期：init / ensureLocationTracking 时创建并启动，
     * 录制开始/停止都不重建它——保证 fix 常热。
     */
    @Volatile private var tracker: LocationTracker? = null

    /** 共享 tracker 的 fix 收集 job（常驻，录制与否都在跑）。 */
    @Volatile private var trackerJob: Job? = null

    private var recorder: SegmentedTrackRecorder? = null
    private var sensorSampler: SensorSampler? = null

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
     * 应用/插件启动时调用一次。幂等。
     *
     * 通常在 `Application.onCreate()` 或 `PluginEntryClass.onLoad()` 中：
     *
     * ```kotlin
     * TrackRecordingEngine.init(context)
     * ```
     *
     * 若此时已有定位权限，会直接启动共享 tracker；没有则等 [ensureLocationTracking]
     * 在权限授予后调用（地图页面会在权限弹窗通过后主动调用）。
     */
    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        val ctx = context.applicationContext
        appContext = ctx

        // 订阅媒体事件 → 转发给 recorder
        mediaJob = scope.launch {
            _mediaEvents.collect { record ->
                runCatching { recorder?.recordMedia(record) }
                _state.update { it.copy(liveMedia = it.liveMedia + record) }
            }
        }

        // 有权限就直接把共享 tracker 跑起来（fix 常热）
        runCatching { ensureLocationTracking(ctx) }
    }

    // =============================================================================================
    // 共享 tracker（长生命周期）
    // =============================================================================================

    /**
     * 幂等启动共享 [LocationTracker]。
     *
     * 调用时机：
     *  · `init()` 内部自动调用
     *  · 地图页面获得定位权限后（`MapLibreMapView` 自定义管线 effect 会调用）
     *
     * 未授予定位权限时静默返回（不抛异常）。tracker 一旦启动持续运行，
     * 录制开始/停止均不影响它。
     */
    fun ensureLocationTracking(context: Context) {
        val ctx = context.applicationContext
        synchronized(lifecycleLock) {
            if (!hasLocationPermission(ctx)) return
            val t = tracker
            if (t == null) {
                val created = LocationTracker(ctx)
                tracker = created
                created.start()
                trackerJob = scope.launch { collectFixes(created) }
            } else {
                // start() 内部幂等（已启动则直接返回）
                runCatching { t.start() }
            }
        }
    }

    private fun hasLocationPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    /**
     * 常驻 fix 收集：转发地图 + 驱动 recorder（录制中且未暂停时）。
     *
     * 单一收集点，避免多处 collect 竞争 lastRawLocation。
     */
    private suspend fun collectFixes(t: LocationTracker) {
        t.fixes.collect { fix ->
            lastKnownLocation = t.lastRawLocation
            _trackerFixes.tryEmit(fix)

            // ★ 暂停期间：位置仍转发给地图，但不写入轨迹、不计时
            if (paused) return@collect

            val rec = recorder ?: return@collect
            val loc = t.lastRawLocation ?: return@collect
            rec.record(loc, fix.profile)
            val now = System.currentTimeMillis()
            _state.update {
                it.copy(
                    recording = true,
                    points = rec.totalRawPoints,
                    distanceM = rec.totalDistanceM,
                    segments = rec.totalSegments,
                    elapsedMs = effectiveElapsedMs(rec, now),
                )
            }
            // 1 Hz 节流更新 FGS 通知
            if (now - lastFgsUpdateMs >= 1000L) {
                lastFgsUpdateMs = now
                updateFgsNotification(rec)
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
     * 只创建 recorder / sampler / FGS；共享 tracker 由 [ensureLocationTracking]
     * 保证已热（本函数内也会再调一次，幂等）。
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

            // ---- 0. 确保共享 tracker 已运行（fix 常热，无需冷启动）----
            runCatching { ensureLocationTracking(ctx) }

            // ---- 1. 创建 recorder / sampler ----
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

            recorder = r
            sensorSampler = s

            // ---- 3. 订阅实时轨迹 ----
            liveJob = scope.launch {
                r.liveTrack.collect { (raw, smooth) ->
                    _state.update {
                        it.copy(liveTrackPoints = raw, liveSmoothPoints = smooth)
                    }
                }
            }

            // ---- 4. 状态更新 + 启动 FGS ----
            paused = false
            pauseStartMs = 0L
            pausedTotalMs = 0L
            _state.update {
                it.copy(recording = true, paused = false, elapsedMs = 0L)
            }
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
     * 暂停记录。
     *
     * 幂等：仅记录中且未暂停时生效。
     *
     * 暂停期间：
     *  · 不再写入轨迹点（[trackerFixes] 仍发射，地图照常跟随）
     *  · 距离 / 计时冻结
     *  · FGS 通知显示"已暂停"
     */
    fun pause() {
        synchronized(lifecycleLock) {
            if (!_state.value.recording || paused) return
            paused = true
            pauseStartMs = System.currentTimeMillis()
            _state.update { it.copy(paused = true) }
            runCatching {
                TrackFgsBridge.update(
                    appContext ?: return@synchronized,
                    title = "Vela 轨迹记录",
                    text = "已暂停",
                )
            }
        }
    }

    /**
     * 继续记录。
     *
     * 幂等：仅暂停中时生效。恢复写入当前段（不切新段）。
     */
    fun resume() {
        synchronized(lifecycleLock) {
            if (!_state.value.recording || !paused) return
            paused = false
            pausedTotalMs += System.currentTimeMillis() - pauseStartMs
            pauseStartMs = 0L
            _state.update { it.copy(paused = false) }
            runCatching {
                TrackFgsBridge.update(
                    appContext ?: return@synchronized,
                    title = "Vela 轨迹记录",
                    text = "记录中...",
                )
            }
        }
    }

    /** 暂停 / 继续切换。 */
    fun togglePause() {
        if (paused) resume() else pause()
    }

    /**
     * 计算扣除暂停时长后的有效记录时长。
     */
    private fun effectiveElapsedMs(r: SegmentedTrackRecorder, nowMs: Long): Long {
        val pausedNow = if (paused && pauseStartMs > 0L) nowMs - pauseStartMs else 0L
        return (nowMs - r.startTimeMs - pausedTotalMs - pausedNow).coerceAtLeast(0L)
    }

    /**
     * 停止记录。
     *
     * 幂等：若未在记录，直接返回。
     *
     * ★ 共享 tracker 保持运行（fix 常热，地图蓝点不消失）——只有
     * [destroyForTest] 才会停它。
     */
    fun stop() {
        synchronized(lifecycleLock) {
            if (!_state.value.recording) return
            val ctx = appContext

            // ---- 1. 取消实时轨迹订阅 ----
            liveJob?.cancel(); liveJob = null

            // ---- 2. 关闭 recorder / sampler（不动 tracker！）----
            sensorSampler?.stop(); sensorSampler = null
            recorder?.endSession(); recorder = null

            // ---- 3. 清空状态 ----
            currentSessionDir = null
            lastFgsUpdateMs = 0L
            paused = false
            pauseStartMs = 0L
            pausedTotalMs = 0L
            _state.update {
                it.copy(
                    recording = false,
                    paused = false,
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
     * 切换记录状态（开始 / 结束）。
     *
     * 注意：暂停状态不经过此函数——暂停用 [togglePause]。
     */
    fun toggle(context: Context) {
        if (_state.value.recording) stop() else start(context)
    }

    /** 是否正在记录。 */
    val isRecording: Boolean
        get() = _state.value.recording

    /** 是否已暂停。 */
    val isPaused: Boolean
        get() = paused

    // =============================================================================================
    // 媒体
    // =============================================================================================

    /**
     * 通知 Engine 有新媒体添加。
     *
     * 由 [org.kori.plugin.geo.service.TrackMediaCaptureActivity] 和
     * [org.kori.plugin.geo.service.VideoCaptureActivity] 在保存完成后调用。
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
     * 把会话列表的全部轨迹加载到地图历史叠加层。
     *
     * 传单个会话 = 显示该会话全部段；传 [TrackRecordingState.sessions] = 显示全部历史轨迹。
     * 在 IO 线程读取合并点，完成后写入 state.historySegments，地图自动刷新。
     */
    fun loadHistoryOnMap(sessions: List<TrackSession>) {
        scope.launch {
            val segs = withContext(Dispatchers.IO) {
                sessions.mapNotNull { s ->
                    TrackMerger.mergeRawDeduped(s).takeIf { it.size >= 2 }
                }
            }
            _state.update { it.copy(historySegments = segs) }
        }
    }

    /** 清除地图上的历史轨迹叠加层。 */
    fun clearHistoryOnMap() {
        _state.update { it.copy(historySegments = emptyList()) }
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
     * 1 Hz 频率更新 FGS 通知。
     *
     * 格式：
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
            trackerJob?.cancel(); trackerJob = null
            tracker?.stop(); tracker?.destroy(); tracker = null
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
     * 宿主 Receiver 收到 [org.kori.plugin.geo.service.TrackRecordingService.ACTION_USER_STOP]
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
 *  · **记录状态**：[recording] / [paused] / [points] / [distanceM] / [elapsedMs] / [segments]
 *  · **实时轨迹**：[liveTrackPoints] / [liveSmoothPoints] / [liveMedia]
 *  · **会话列表**：[sessions] / [loadingSessions]
 */
data class TrackRecordingState(
    // =============================================================================================
    // 记录状态
    // =============================================================================================

    /** 是否正在记录。 */
    val recording: Boolean = false,

    /**
     * 是否已暂停。
     *
     * true 时距离/计时冻结，不再写入轨迹点。仅 [recording] = true 时有意义。
     */
    val paused: Boolean = false,

    /** 已保留的轨迹点数（原始过滤后）。 */
    val points: Int = 0,

    /** 累计距离（米）。暂停期间不累计。 */
    val distanceM: Double = 0.0,

    /** 记录时长（毫秒），从会话开始时算起，**已扣除暂停时长**。 */
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

    /**
     * 历史轨迹叠加层（历史浏览时显示在地图上）。
     *
     * 每个元素为一条轨迹点序列（一个会话一条）。由 [TrackRecordingEngine.loadHistoryOnMap] 写入。
     */
    val historySegments: List<List<TrackPoint>> = emptyList(),
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
        get() = when {
            !recording -> "未记录"
            paused -> "已暂停 · $points 点 · $distanceText"
            else -> "$elapsedText · $points 点 · $distanceText"
        }
}