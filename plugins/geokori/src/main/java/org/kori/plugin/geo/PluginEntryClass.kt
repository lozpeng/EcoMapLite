package org.kori.plugin.geo

import androidx.compose.runtime.Composable
import com.combo.core.api.IPluginEntryClass
import com.combo.core.model.PluginContext
import org.koin.core.module.Module
import org.kori.plugin.geo.service.TrackFgsReceiver
import org.kori.plugin.geo.track.TrackRecordingEngine

class PluginEntryClass : IPluginEntryClass {
    override val pluginModule: List<Module>
        get() = emptyList()
    /**
     * 通知栏"停止"按钮的接收器。
     *
     * 由本类的 `onLoad` / `onUnload` 管理注册与反注册。
     */
    private val fgsReceiver = TrackFgsReceiver()

    @Composable
    override fun Content() {
        GeokoriMapScreen()
    }

    override fun onLoad(context: PluginContext) {
        TrackRecordingEngine.init(context.application)
        // 2. 动态注册停止按钮的 Receiver
        fgsReceiver.register(context.application)
    }

    override fun onUnload() {
        // 1. 若正在记录，优雅停止
        if (TrackRecordingEngine.isRecording) {
            runCatching { TrackRecordingEngine.stop() }
        }
        // 2. 反注册 Receiver
        fgsReceiver.unregister()
    }
}
