

package com.combo.core.api

import androidx.compose.runtime.Composable
import com.combo.core.model.PluginContext
import org.koin.core.module.Module

/**
 * 插件配置接口
 * @property pluginModule 插件依赖注入模块
 * @property onLoad 插件被加载时调用
 * @property onUnload 插件被卸载时调用
 * @property Content 插件主界面
 */
interface IPluginEntryClass {

    /**
     *   (可选) 声明此插件提供的 Koin 依赖注入模块
     *   插件化框架会自动处理依赖注入的加载和卸载
     */
    val pluginModule: List<Module>

    /**
     * onLoad 生命周期回调
     * 当插件被框架加载后，此方法会被调用。
     * 这是执行所有初始化逻辑的最佳位置。
     *
     * @param context 插件运行的上下文环境
     */
    fun onLoad(context: PluginContext)

    /**
     * onUnload 生命周期回调
     * 当插件被框架卸载前，此方法会被调用。
     * 这是执行所有资源清理工作的最佳位置。
     */
    fun onUnload()

    /**
     * 提供插件的 UI 入口
     * 这个方法专门用于定义和返回插件的 Jetpack Compose 界面。
     */
    @Composable
    fun Content()
}
