

package com.combo.plugin.sample.guide

import androidx.compose.runtime.Composable
import com.combo.core.api.IPluginEntryClass
import com.combo.core.model.PluginContext
import org.koin.core.module.Module

class PluginEntryClass : IPluginEntryClass {
    override val pluginModule: List<Module>
        get() = emptyList()

    @Composable
    override fun Content() {
        GuideMainScreen()
    }

    override fun onLoad(context: PluginContext) {
    }

    override fun onUnload() {
    }
}
