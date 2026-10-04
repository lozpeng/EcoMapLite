package org.kori.plugin.wildlife

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.combo.core.api.IPluginEntryClass
import com.combo.core.model.PluginContext
import org.cwcc.open.geokori.framework.PluginModuleUtils
import org.cwcc.open.geokori.map.LocalMapSession
import org.cwcc.open.geokori.map.MapRuntime
import org.cwcc.open.geokori.map.MapSession
import org.koin.core.module.Module
import org.kori.plugin.wildlife.di.diModule
import org.kori.plugin.wildlife.screen.WildLifeScreen

class PluginEntryClass : IPluginEntryClass {
    override val pluginModule: List<Module>
        get() = listOf(
            diModule,
        )

    private lateinit var pluginSession: MapSession
    private lateinit var pluginId: String


    @Composable
    override fun Content() {
        CompositionLocalProvider(LocalMapSession provides pluginSession) {
            WildLifeScreen()
        }
    }

    override fun onLoad(context: PluginContext) {
        pluginId = context.pluginInfo.id
        pluginSession = MapRuntime.openPluginSession(pluginId)
        PluginModuleUtils.init(context.application)
    }

    override fun onUnload() {
        MapRuntime.closePluginSession(pluginId)
    }
}
