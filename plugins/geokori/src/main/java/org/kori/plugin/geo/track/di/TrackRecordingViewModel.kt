package org.kori.plugin.geo.track.di

import android.content.Context
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow
import org.kori.plugin.geo.track.TrackRecordingEngine

/**
 * 轨迹记录 ViewModel。
 *
 *  · 构造函数接收 `Context`，**调用方手动构造**（见 `TrackRecordingScreen`）
 *  · 如果宿主/插件使用了 Koin，也可以在 `PluginEntryClass.pluginModule` 里注册：
 *    ```kotlin
 *    override val pluginModule: List<Module>
 *        get() = listOf(module { viewModel { TrackRecordingViewModel(androidContext()) } })
 *    ```
 *    然后改回 `koinViewModel()` 获取。
 *
 * ## 职责
 *
 *  · 只是 [TrackRecordingEngine] 的薄转发层——暴露 state、转发启停/暂停调用
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

    /** 开始 / 结束记录。 */
    fun toggleRecording() = TrackRecordingEngine.toggle(context)

    /** 暂停 / 继续记录（仅记录中有效）。 */
    fun togglePause() = TrackRecordingEngine.togglePause()

    fun startRecording(config: SegmentConfig = SegmentConfig.Default) =
        TrackRecordingEngine.start(context, config)

    fun stopRecording() = TrackRecordingEngine.stop()

    fun refreshSessions() = TrackRecordingEngine.refreshSessions(context)

    fun deleteSession(session: TrackSession) = TrackRecordingEngine.deleteSession(session)
}