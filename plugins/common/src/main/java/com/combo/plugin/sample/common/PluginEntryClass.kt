

package com.combo.plugin.sample.common

import androidx.compose.runtime.Composable
import com.combo.core.api.IPluginEntryClass
import com.combo.core.model.PluginContext
import com.combo.plugin.sample.common.di.navigationModule
import com.combo.plugin.sample.common.update.UpdateManager
import com.combo.plugin.sample.common.update.net.RetrofitClient
import org.koin.core.module.Module
import org.koin.dsl.module

class PluginEntryClass : IPluginEntryClass {
    override val pluginModule: List<Module>
        get() =
            listOf(
                navigationModule,
                module {
                    single { RetrofitClient.apiService }
                    single { UpdateManager(get(), get()) }
                }
            )

    @Composable
    override fun Content() {
    }

    override fun onLoad(context: PluginContext) {
    }

    override fun onUnload() {
    }
}
