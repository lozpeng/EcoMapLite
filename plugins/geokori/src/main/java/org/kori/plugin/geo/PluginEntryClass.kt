package org.kori.plugin.geo

import androidx.compose.runtime.Composable
import com.combo.core.api.IPluginEntryClass
import com.combo.core.model.PluginContext
import org.koin.core.module.Module
import org.kori.plugin.geo.service.TrackFgsReceiver
import org.kori.plugin.geo.track.TrackRecordingEngine

class PluginEntryClass : IPluginEntryClass {

    /**
     * 通知栏"停止"按钮的接收器。
     *
     * 由本类的 `onLoad` / `onUnload` 管理注册与反注册。
     */
    private val fgsReceiver = TrackFgsReceiver()

    override val pluginModule: List<Module>
        get() = emptyList()

    @Composable
    override fun Content() {
        // ★ 修改：挂带记录面板的屏幕（开始 / 暂停 / 结束 + 媒体采集）
        TrackRecordingScreen()
    }

    override fun onLoad(context: PluginContext) {
        // 1. 初始化记录引擎（幂等）
        TrackRecordingEngine.init(context.application)
        // 2. 动态注册通知栏"停止"按钮的 Receiver
        fgsReceiver.register(context.application)
    }

    override fun onUnload() {
        // 1. 若正在记录，优雅停止（内部会停止 tracker / recorder / FGS）
        if (TrackRecordingEngine.isRecording) {
            runCatching { TrackRecordingEngine.stop() }
        }
        // 2. 反注册 Receiver
        fgsReceiver.unregister()
    }
}