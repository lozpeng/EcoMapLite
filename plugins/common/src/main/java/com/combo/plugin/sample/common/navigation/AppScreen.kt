

package com.combo.plugin.sample.common.navigation

import kotlinx.serialization.Serializable

/**
 * 应用屏幕路由定义
 *
 * 使用密封接口定义应用中所有可导航的屏幕
 * 采用此设计可提供类型安全的导航，并便于扩展
 */
sealed interface AppScreen {
    @Serializable
    data object Home : AppScreen

    @Serializable
    data object PluginService : AppScreen

    @Serializable
    data object PluginActivity : AppScreen

    @Serializable
    data object BroadcastReceiver : AppScreen

    @Serializable
    data object ContentProvider : AppScreen

    @Serializable
    data object SoLibrary : AppScreen

    @Serializable
    data object PluginHotUpdate : AppScreen
}
