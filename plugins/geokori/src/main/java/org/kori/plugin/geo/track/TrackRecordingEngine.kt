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
import org.kori.plugin.geo.track.di.SegmentConfig
import org.kori.plugin.geo.track.di.TrackMediaRecord
import org.kori.plugin.geo.track.di.TrackRecordingState
import org.kori.plugin.geo.track.di.TrackSession
import org.kori.plugin.geo.track.di.TrackSessionStore
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 轨迹记录引擎（应用级单例）。
 *
 * ## 设计目标
 *
 *  · 单例：不受 Compose / Activity 生命周期影响，App 切后台记录继续
 *  · 共享：地图和记录器共用同一个 [LocationTracker]，保证位置一致
 *  · 长生命周期 tracker：tracker 从 init/首次获得权限起常驻，fix 常热——
 *    蓝点即开即有，开始记录时立刻有数据（避免每次录制的 GPS 冷启动 10~30s）
 *  · FGS 桥接：通过 [TrackFgsBridge] 让宿主的前台服务保持进程存活
 *  · 暂停/继续：暂停期间不写入轨迹点、距离与计时冻结
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
 * ## 媒体读取（回放）
 *
 * 回放屏读取媒体的入口是 TrackSession.media（由 TrackSessionStore.readSession
 * 从会话目录 media 子目录下的 *.json sidecar 读出），不需要 Engine 侧另开读取 API。
 */
object TrackRecordingEngine {

    // =============================================================================================
    // 常量
    // =============================================================================================

    private const val TRACKS_DIR = "tracks"
    private const val LOCK_FILE = ".recording_lock"

    // =============================================================================================
    // 内部状态
    // =============================================================================================

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var appContext: Context? = null

    private val lifecycleLock = Any()

    @Volatile private var paused: Boolean = false
    @Volatile private var pauseStartMs: Long = 0L
    @Volatile private var pausedTotalMs: Long = 0L

    // =============================================================================================
    // 对外状态
    // =============================================================================================

    private val _state = MutableStateFlow(TrackRecordingState())
    val state: StateFlow<TrackRecordingState> = _state.asStateFlow()

    private val _trackerFixes = MutableSharedFlow<LocationTracker.Fix>(
        replay = 1,
        extraBufferCapacity = 16,
    )
    val trackerFixes: SharedFlow<LocationTracker.Fix> = _trackerFixes.asSharedFlow()

    private val _mediaEvents = MutableSharedFlow<TrackMediaRecord>(extraBufferCapacity = 8)
    val mediaEvents: SharedFlow<TrackMediaRecord> = _mediaEvents.asSharedFlow()

    // =============================================================================================
    // 内部组件
    // =============================================================================================

    @Volatile private var tracker: LocationTracker? = null
    @Volatile private var trackerJob: Job? = null

    private var recorder: SegmentedTrackRecorder? = null
    private var sensorSampler: SensorSampler? = null

    private var liveJob: Job? = null
    private var mediaJob: Job? = null

    @Volatile var currentSessionDir: File? = null
        private set

    @Volatile var lastKnownLocation: Location? = null
        private set

    @Volatile private var lastFgsUpdateMs = 0L

    private val initialized = AtomicBoolean(false)

    // =============================================================================================
    // 断点续录
    // =============================================================================================

    private val _resumeCandidate = MutableStateFlow<ResumeCandidate?>(null)
    val resumeCandidate: StateFlow<ResumeCandidate?> = _resumeCandidate.asStateFlow()

    @Volatile
    private var resumePromptHandler: ((ResumeCandidate, (ResumeAction) -> Unit) -> Unit)? = null

    // =============================================================================================
    // 历史轨迹浏览：随轨迹一起上地图的媒体点位
    // =============================================================================================

    private val _historyMedia = MutableStateFlow<List<TrackMediaRecord>>(emptyList())
    val historyMedia: StateFlow<List<TrackMediaRecord>> = _historyMedia.asStateFlow()

    fun registerResumePromptHandler(
        handler: ((ResumeCandidate, (ResumeAction) -> Unit) -> Unit)?,
    ) {
        resumePromptHandler = handler
    }

    // =============================================================================================
    // 初始化
    // =============================================================================================

    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        val ctx = context.applicationContext
        appContext = ctx

        mediaJob = scope.launch {
            _mediaEvents.collect { record ->
                runCatching { recorder?.recordMedia(record) }
                _state.update { it.copy(liveMedia = it.liveMedia + record) }
            }
        }

        runCatching { ensureLocationTracking(ctx) }
        detectAbortedSession(ctx)
    }

    private fun detectAbortedSession(ctx: Context) {
        val tracksRoot = File(ctx.filesDir, TRACKS_DIR)
        val lock = File(tracksRoot, LOCK_FILE)
        if (!lock.exists()) return
        val sessionId = runCatching { lock.readText().trim() }.getOrNull().orEmpty()
        runCatching { lock.delete() }
        if (sessionId.isEmpty()) return
        val session = TrackSessionStore.readSession(File(tracksRoot, sessionId)) ?: return
        if (session.segments.isEmpty()) return
        scope.launch {
            val last = withContext(Dispatchers.IO) {
                session.segments.maxByOrNull { it.index }
                    ?.let { TrackStore.read(it.rawFile).lastOrNull() }
            }
            _resumeCandidate.value = ResumeCandidate(
                session = session,
                lastLat = last?.lat,
                lastLng = last?.lng,
            )
        }
    }

    private fun writeLock(ctx: Context, sessionId: String) {
        runCatching {
            File(File(ctx.filesDir, TRACKS_DIR).apply { mkdirs() }, LOCK_FILE)
                .writeText(sessionId)
        }
    }

    private fun deleteLock(ctx: Context) {
        runCatching { File(File(ctx.filesDir, TRACKS_DIR), LOCK_FILE).delete() }
    }

    // =============================================================================================
    // 共享 tracker（长生命周期）
    // =============================================================================================

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
                runCatching { t.start() }
            }
        }
    }

    private fun hasLocationPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    private suspend fun collectFixes(t: LocationTracker) {
        t.fixes.collect { fix ->
            lastKnownLocation = t.lastRawLocation
            _trackerFixes.tryEmit(fix)

            // 暂停期间：位置仍转发给地图，但不写入轨迹、不计时
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
                    currentSpeedMps = fix.speedMps,
                    currentLat = fix.lat,
                    currentLng = fix.lng,
                )
            }
            if (now - lastFgsUpdateMs >= 1000L) {
                lastFgsUpdateMs = now
                updateFgsNotification(rec)
            }
        }
    }

    // =============================================================================================
    // 记录启停
    // =============================================================================================

    fun start(
        context: Context,
        config: SegmentConfig = SegmentConfig.Default,
        nameHint: String? = null,
        resume: Boolean = false,
    ) {
        synchronized(lifecycleLock) {
            if (_state.value.recording) return
            val ctx = context.applicationContext

            runCatching { ensureLocationTracking(ctx) }

            val r = SegmentedTrackRecorder(
                tracksRoot = File(ctx.filesDir, TRACKS_DIR).apply { mkdirs() },
                segmentConfig = config,
                sessionNameHint = nameHint ?: "session",
            )
            val s = SensorSampler(ctx)
            s.start()
            r.setSensorSampler(s)

            val orphan = _resumeCandidate.value
            val sessionDir = if (resume && orphan != null) {
                _resumeCandidate.value = null
                r.resumeSession(orphan.session.dir)
            } else {
                r.startSession()
            }
            currentSessionDir = sessionDir
            writeLock(ctx, sessionDir.name)

            recorder = r
            sensorSampler = s

            liveJob = scope.launch {
                r.liveTrack.collect { (raw, smooth) ->
                    _state.update {
                        it.copy(liveTrackPoints = raw, liveSmoothPoints = smooth)
                    }
                }
            }

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

    fun pause() {
        synchronized(lifecycleLock) {
            if (!_state.value.recording || paused) return
            paused = true
            pauseStartMs = System.currentTimeMillis()
            _state.update { it.copy(paused = true) }
            runCatching { recorder?.recordEvent(TrackEventType.PAUSE, pauseStartMs) }
            runCatching {
                TrackFgsBridge.update(
                    appContext ?: return@synchronized,
                    title = "Vela 轨迹记录",
                    text = "已暂停",
                )
            }
        }
    }

    fun resume() {
        synchronized(lifecycleLock) {
            if (!_state.value.recording || !paused) return
            paused = false
            pausedTotalMs += System.currentTimeMillis() - pauseStartMs
            pauseStartMs = 0L
            _state.update { it.copy(paused = false) }
            runCatching { recorder?.recordEvent(TrackEventType.RESUME, System.currentTimeMillis()) }
            runCatching {
                TrackFgsBridge.update(
                    appContext ?: return@synchronized,
                    title = "Vela 轨迹记录",
                    text = "记录中...",
                )
            }
        }
    }

    fun togglePause() {
        if (paused) resume() else pause()
    }

    private fun effectiveElapsedMs(r: SegmentedTrackRecorder, nowMs: Long): Long {
        val pausedNow = if (paused && pauseStartMs > 0L) nowMs - pauseStartMs else 0L
        return (nowMs - r.startTimeMs - pausedTotalMs - pausedNow).coerceAtLeast(0L)
    }

    fun stop() {
        synchronized(lifecycleLock) {
            if (!_state.value.recording) return
            val ctx = appContext

            liveJob?.cancel(); liveJob = null

            sensorSampler?.stop(); sensorSampler = null
            recorder?.endSession(); recorder = null

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

            ctx?.let {
                runCatching { TrackFgsBridge.stop(it) }
                deleteLock(it)
            }

            ctx?.let { refreshSessions(it) }
        }
    }

    fun startWithResumeCheck(
        context: Context,
        config: SegmentConfig = SegmentConfig.Default,
        nameHint: String? = null,
    ) {
        val candidate = _resumeCandidate.value
        val handler = resumePromptHandler
        when {
            candidate != null && handler != null -> {
                handler(candidate) { action ->
                    when (action) {
                        ResumeAction.RESUME -> start(context, config, nameHint, resume = true)
                        ResumeAction.START_NEW -> {
                            _resumeCandidate.value = null
                            start(context, config, nameHint)
                        }
                        ResumeAction.CANCEL -> _resumeCandidate.value = null
                    }
                }
            }
            candidate != null -> {
                _resumeCandidate.value = null
                start(context, config, nameHint)
            }
            else -> start(context, config, nameHint)
        }
    }

    fun toggle(context: Context) {
        if (_state.value.recording) stop() else startWithResumeCheck(context)
    }

    val isRecording: Boolean
        get() = _state.value.recording

    val isPaused: Boolean
        get() = paused

    // =============================================================================================
    // 媒体
    // =============================================================================================

    /**
     * 通知 Engine 有新媒体添加。
     *
     * 由 TrackMediaCaptureActivity 和 VideoCaptureActivity 在保存完成后调用。
     *
     * 落盘由 mediaJob 协程转发给 SegmentedTrackRecorder.recordMedia，
     * 生成 `<sessionDir>/media/<name>.json` sidecar；回放时由
     * TrackSessionStore.readSession 读回，无需 Engine 侧再开读取 API。
     */
    fun notifyMediaAdded(record: TrackMediaRecord) {
        _mediaEvents.tryEmit(record)
    }

    // =============================================================================================
    // 会话管理
    // =============================================================================================

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

    fun loadHistoryOnMap(sessions: List<TrackSession>) {
        scope.launch {
            val segs = withContext(Dispatchers.IO) {
                sessions.mapNotNull { s ->
                    TrackMerger.mergeRawDeduped(s).takeIf { it.size >= 2 }
                }
            }
            // 媒体点位随轨迹一起上地图：相对路径统一转绝对路径，
            // 供 LiveTrackLayer 读取缩略图生成照片气泡针
            val media = sessions.flatMap { s -> s.media.map { it.toAbsolutePath(s) } }
            _historyMedia.value = media
            _state.update { it.copy(historySegments = segs) }
        }
    }

    fun clearHistoryOnMap() {
        _historyMedia.value = emptyList()
        _state.update { it.copy(historySegments = emptyList()) }
    }

    /** 相对路径 → 绝对路径（幂等），与回放屏的 toAbsolute 同规则。 */
    private fun TrackMediaRecord.toAbsolutePath(session: TrackSession): TrackMediaRecord {
        val f = File(filePath)
        val abs = if (f.isAbsolute) f else session.resolve(filePath)
        return if (abs.absolutePath == filePath) this else copy(filePath = abs.absolutePath)
    }

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
    // 通知栏停止按钮的 Intent
    // =============================================================================================

    fun handleUserStopIntent(context: Context, intent: Intent) {
        when (intent.action) {
            TrackRecordingService.ACTION_USER_STOP -> {
                stop()
                runCatching { TrackFgsBridge.stop(context.applicationContext) }
            }
        }
    }
}