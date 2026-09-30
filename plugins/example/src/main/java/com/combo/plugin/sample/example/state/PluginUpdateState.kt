

package com.combo.plugin.sample.example.state

import com.combo.core.model.PluginInfo
import com.combo.plugin.sample.common.update.model.RemotePlugin
import com.combo.plugin.sample.common.viewmodel.BaseUiState

data class PluginUpdateState(
    val remotePlugins: List<RemotePlugin> = emptyList(),
    val installedPlugins: Map<String, String> = emptyMap(),
    val downloadingPlugins: Map<String, Float> = emptyMap(),
    val installingPlugins: Set<String> = emptySet(),
    val showInstallSuccessDialog: Boolean = false,
    val recentlyInstalledPlugin: PluginInfo? = null,
    val restartRequired: Boolean = false,
    override val isLoading: Boolean = false,
    override val isError: Boolean = false,
    override val errorMessage: String? = null
) : BaseUiState