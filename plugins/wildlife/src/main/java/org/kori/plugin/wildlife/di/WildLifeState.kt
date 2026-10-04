package org.kori.plugin.wildlife.di

import com.combo.core.model.PluginInfo
import com.combo.plugin.sample.common.viewmodel.BaseUiState

data class WildLifeState(
    var installedPlugins: List<PluginInfo> = emptyList(),
    override val isLoading: Boolean = false,
    override val isError: Boolean = false,
    override val errorMessage: String? = null,
) : BaseUiState
