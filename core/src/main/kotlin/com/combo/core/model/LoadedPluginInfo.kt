

package com.combo.core.model

import com.combo.core.runtime.loader.PluginClassLoader

/**
 * 包含已加载插件的详细运行时信息。
 * @property pluginInfo 插件的静态描述信息。
 * @property classLoader 插件专属的类加载器。
 */
data class LoadedPluginInfo(
    val pluginInfo: PluginInfo,
    val classLoader: PluginClassLoader,
)