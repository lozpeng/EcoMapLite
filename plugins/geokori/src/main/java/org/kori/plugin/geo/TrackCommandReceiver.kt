package org.kori.plugin.geo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.cwcc.open.geokori.api.ITrackRecordingStateApi
import org.cwcc.open.geokori.api.TrackIntents
import org.kori.plugin.geo.track.TrackRecordingEngine


/**
 * 【新增】插件内 UI 事件总线（非跨插件——仅 geokori 内部使用）。
 *
 * 命令接收器（非 Composable）收到"显示历史"等 UI 类命令后，
 * 经此事件流转发给 TrackRecordingScreen / TrackRecordingScreenWW 消费。
 */
object TrackUiEvents {
    private val _showHistory = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val showHistory: SharedFlow<Unit> = _showHistory.asSharedFlow()

    fun requestShowHistory() {
        _showHistory.tryEmit(Unit)
    }
}


/**
 * 轨迹记录命令接收器（geokori 插件内）。
 *
 * home 等插件通过 sendInternalBroadcast 发命令，本类分发：
 *  · 录制命令 → [TrackRecordingEngine]
 *  · 显示历史 → [TrackUiEvents]（由 Compose 屏幕订阅后弹 TrackHistoryScreen）
 *
 * fire-and-forget：发送方无需知道 geokori 是否加载。
 *
 * 在 PluginEntryClass.onLoad 注册、onUnload 注销（见下方示例）。
 */
class TrackCommandReceiver : BroadcastReceiver() {

    /** 注册时持有的 Context，供 [unregister] 使用。 */
    @Volatile
    private var registeredContext: Context? = null

    override fun onReceive(context: Context, intent: Intent) {
        val appCtx = context.applicationContext
        when (intent.action) {
            TrackIntents.ACTION_START ->
                TrackRecordingEngine.start(
                    appCtx,
                    nameHint = intent.getStringExtra(TrackIntents.EXTRA_NAME_HINT),
                )

            TrackIntents.ACTION_PAUSE  -> TrackRecordingEngine.pause()
            TrackIntents.ACTION_RESUME -> TrackRecordingEngine.resume()
            TrackIntents.ACTION_STOP   -> TrackRecordingEngine.stop()
            TrackIntents.ACTION_TOGGLE -> TrackRecordingEngine.toggle(appCtx)

            // ★【新增】UI 类命令：转发给 Compose 屏幕弹历史轨迹界面
            TrackIntents.ACTION_SHOW_HISTORY -> TrackUiEvents.requestShowHistory()
        }
    }

    /**
     * 注册。Android 13+ 必须显式传 RECEIVER_NOT_EXPORTED（应用内部广播）。
     */
    fun register(context: Context) {
        val appCtx = context.applicationContext
        val filter = IntentFilter().apply {
            TrackIntents.GEOKORI_COMMAND_ACTIONS.forEach { addAction(it) }
        }
        ContextCompat.registerReceiver(
            appCtx,
            this,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        registeredContext = appCtx
    }

    /** 注销（幂等）。 */
    fun unregister() {
        val ctx = registeredContext ?: return
        registeredContext = null
        runCatching { ctx.unregisterReceiver(this) }
    }
}


/**
 * [ITrackRecordingStateApi] 的 geokori 插件侧实现。
 *
 * 命令不经过本类（走广播 [TrackIntents]），这里只把 TrackRecordingEngine.state
 * 投影成两个 StateFlow。
 *
 * ★ 必须保留无参构造函数；混淆需 keep（PluginManager 按类名全局索引定位）。
 */
class GeoTrackStateApiImpl : ITrackRecordingStateApi {

    private val scope = CoroutineScope(Dispatchers.Default)

    override val isRecording: StateFlow<Boolean> =
        TrackRecordingEngine.state
            .map { it.recording }
            .stateIn(scope, SharingStarted.Eagerly, initialValue = false)

    override val isPaused: StateFlow<Boolean> =
        TrackRecordingEngine.state
            .map { it.paused }
            .stateIn(scope, SharingStarted.Eagerly, initialValue = false)
}