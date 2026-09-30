

package com.combo.core.model

import android.app.Application

/**
 * 插件上下文环境
 * @property application 宿主Application实例
 * @property pluginInfo 当前插件的元数据信息
 */
data class PluginContext(
    val application: Application,
    val pluginInfo: PluginInfo
)