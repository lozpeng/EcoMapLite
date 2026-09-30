

package com.combo.plugin.sample.common.update.model

data class RemotePlugin(
    val id: String,
    val name: String,
    val description: String,
    val versions: List<PluginVersionInfo>
)
