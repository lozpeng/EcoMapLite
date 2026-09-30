package org.kori.plugin.geo

import androidx.compose.runtime.Composable
import com.combo.core.api.IPluginEntryClass
import com.combo.core.model.PluginContext
import org.koin.core.module.Module
import org.maplibre.android.MapLibre

class PluginEntryClass : IPluginEntryClass {
    override val pluginModule: List<Module>
        get() = emptyList()

    @Composable
    override fun Content() {
        GeokoriMapScreen()
    }

    override fun onLoad(context: PluginContext) {
        //MapLibre.getInstance(context.application)
    }

    override fun onUnload() {

    }
}
