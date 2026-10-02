package org.kori.plugin.geo.track

import android.content.Context
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow

/**
 * 轨迹记录 ViewModel（Koin 版）。
 *
 *  · 构造函数接收 `Context`，由 Koin 的 `androidContext()` 注入
 *  · Compose 里用 `koinViewModel()` 获取（替换 `hiltViewModel()`）
 *
 * ## 职责
 *
 *  · 只是 [TrackRecordingEngine] 的薄转发层——暴露 state、转发启停调用
 *  · 不做业务逻辑——业务都在 Engine（应用级单例）
 */
class TrackRecordingViewModel(
    private val context: Context,
) : ViewModel() {

    /** 记录状态。UI 通过 `collectAsState()` 订阅。 */
    val state: StateFlow<TrackRecordingState> = TrackRecordingEngine.state

    init {
        TrackRecordingEngine.init(context)
        TrackRecordingEngine.refreshSessions(context)
    }

    fun toggleRecording() = TrackRecordingEngine.toggle(context)

    fun startRecording(config: SegmentConfig = SegmentConfig.Default) =
        TrackRecordingEngine.start(context, config)

    fun stopRecording() = TrackRecordingEngine.stop()

    fun refreshSessions() = TrackRecordingEngine.refreshSessions(context)

    fun deleteSession(session: TrackSession) = TrackRecordingEngine.deleteSession(session)
}