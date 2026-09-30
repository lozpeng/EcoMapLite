

package com.combo.core.model

import android.app.Application
import com.combo.core.api.IPluginEntryClass
import com.combo.core.proxy.ProxyManager
import com.combo.core.runtime.InitState
import com.combo.core.runtime.ValidationStrategy
import com.combo.core.runtime.installer.InstallerManager
import com.combo.core.runtime.installer.XmlManager
import com.combo.core.runtime.lifecycle.PluginLifecycleManager
import com.combo.core.runtime.loader.DependencyManager
import com.combo.core.runtime.loader.IPluginStateProvider
import com.combo.core.runtime.resource.PluginResourcesManager
import com.combo.core.security.auth.AuthorizationManager
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * 插件框架内部上下文
 *
 * 持有所有共享状态和核心管理器的实例，作为内部组件的依赖中心，
 * 以解耦 PluginManager 和各个具体的功能管理器。
 */
internal data class PluginFrameworkContext(
    val application: Application,
) {
    // 共享状态
    val loadedPlugins = MutableStateFlow<Map<String, LoadedPluginInfo>>(emptyMap())
    val pluginInstances = MutableStateFlow<Map<String, IPluginEntryClass>>(emptyMap())
    val classIndex = ConcurrentHashMap<String, String>()
    val initState = MutableStateFlow(InitState.NOT_INITIALIZED)
    var validationStrategy: ValidationStrategy = ValidationStrategy.Strict

    // 核心管理器实例
    val xmlManager: XmlManager = XmlManager(application)
    val installerManager: InstallerManager = InstallerManager(application, xmlManager)
    val resourcesManager: PluginResourcesManager = PluginResourcesManager(application)
    val proxyManager: ProxyManager = ProxyManager(application)
    val lifecycleManager: PluginLifecycleManager = PluginLifecycleManager(this)
    val authorizationManager: AuthorizationManager = AuthorizationManager(application)

    // 依赖于其他管理器的管理器
    var dependencyManager: DependencyManager

    init {
        val stateProvider = object : IPluginStateProvider {
            override fun getClassIndex(): Map<String, String> = classIndex
            override fun getLoadedPlugins(): Map<String, LoadedPluginInfo> = loadedPlugins.value
        }
        dependencyManager = DependencyManager(stateProvider)
    }
}