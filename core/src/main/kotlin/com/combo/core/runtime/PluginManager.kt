package com.combo.core.runtime

import android.app.Application
import com.combo.core.api.IPluginEntryClass
import com.combo.core.model.LoadedPluginInfo
import com.combo.core.model.PluginFrameworkContext
import com.combo.core.model.PluginInfo
import com.combo.core.proxy.ProxyManager
import com.combo.core.runtime.InitState.INITIALIZED
import com.combo.core.runtime.InitState.INITIALIZING
import com.combo.core.runtime.InitState.NOT_INITIALIZED
import com.combo.core.runtime.installer.InstallerManager
import com.combo.core.runtime.resource.PluginResourcesManager
import com.combo.core.runtime.service.ServiceRegistry
import com.combo.core.security.auth.AuthorizationManager
import com.combo.core.security.permission.PermissionLevel
import com.combo.core.security.permission.RequiresPermission
import com.combo.core.security.permission.checkApiCaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext.startKoin
import timber.log.Timber
import kotlin.reflect.jvm.javaMethod

enum class InitState { NOT_INITIALIZED, INITIALIZING, INITIALIZED }

enum class ValidationStrategy { Strict, UserGrant, Insecure }

object PluginManager {

    private const val TAG = "PluginManager"

    private var frameworkContext: PluginFrameworkContext? = null

    val initStateFlow: StateFlow<InitState>
        get() = requireContext().initState
    val loadedPluginsFlow: StateFlow<Map<String, LoadedPluginInfo>>
        get() = requireContext().loadedPlugins
    val pluginInstancesFlow: StateFlow<Map<String, IPluginEntryClass>>
        get() = requireContext().pluginInstances

    val isInitialized: Boolean
        get() = frameworkContext?.initState?.value == InitState.INITIALIZED

    val validationStrategy: ValidationStrategy
        get() = requireContext().validationStrategy

    val installerManager: InstallerManager
        get() = requireContext().installerManager
    val resourcesManager: PluginResourcesManager
        get() = requireContext().resourcesManager
    val proxyManager: ProxyManager
        get() = requireContext().proxyManager
    val authorizationManager: AuthorizationManager
        get() = requireContext().authorizationManager

    internal fun getClassIndex(): Map<String, String> = requireContext().classIndex

    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private fun requireContext(): PluginFrameworkContext {
        return frameworkContext ?: throw IllegalStateException("PluginManager has not been initialized.")
    }

    @Synchronized
    fun initialize(
        context: Application,
        onSetup: (suspend () -> Unit)? = null
    ) {
        if (frameworkContext != null && frameworkContext?.initState?.value != InitState.NOT_INITIALIZED) {
            Timber.Forest.tag(TAG).w("PluginManager 正在初始化或已完成，跳过重复操作。")
            return
        }

        frameworkContext = PluginFrameworkContext(context)
        requireContext().initState.value = INITIALIZING

        try {
            Timber.Forest.tag(TAG).i("开始初始化 PluginManager 核心组件...")
            startKoin { androidContext(context) }
        } catch (e: Exception) {
            Timber.Forest.tag(TAG).e(e, "PluginManager 初始化失败: ${e.message}")
            requireContext().initState.value = NOT_INITIALIZED
            throw e
        }

        requireContext().initState.value = INITIALIZED
        Timber.Forest.tag(TAG).i("PluginManager 核心已就绪。")

        managerScope.launch {
            try {
                onSetup?.invoke()
                Timber.Forest.tag(TAG).i("宿主自定义的框架设置任务已完成。")
            } catch (e: Exception) {
                Timber.Forest.tag(TAG).e(e, "宿主自定义的框架设置任务执行失败。")
            }
        }
    }

    suspend fun awaitInitialization() {
        if (isInitialized) return
        initStateFlow.first { it == INITIALIZED }
    }

    @RequiresPermission(PermissionLevel.HOST, hardFail = true)
    suspend fun setValidationStrategy(strategy: ValidationStrategy) {
        if (::setValidationStrategy.javaMethod?.checkApiCaller() == false) return
        requireContext().validationStrategy = strategy
        Timber.i("PluginManager: ValidationStrategy 已更新为: ${strategy::class.java.simpleName}")
    }

    // ============ 新增：跨插件服务注册表 ============

    /**
     * 当前所有已注册服务（响应式）。
     * 依赖方通过 collect 该流自动感知服务注册/注销/更新。
     */
    val servicesFlow: StateFlow<Map<String, ServiceRegistry.Entry>>
        get() = ServiceRegistry.servicesFlow

    /**
     * 注册跨插件服务。
     * @param clazz 服务接口类型（必须由宿主 ClassLoader 加载）
     * @param service 服务实现实例
     * @param pluginId 提供者插件 ID
     */
    fun <T : Any> registerService(clazz: Class<T>, service: T, pluginId: String) {
        ServiceRegistry.register(clazz, service, pluginId)
    }

    /**
     * 注册跨插件服务（pluginId 从 PluginContextHolder 获取）。
     * 只能在 onLoad 期间调用。
     */
    fun <T : Any> registerService(clazz: Class<T>, service: T) {
        ServiceRegistry.register(clazz, service)
    }

    /**
     * 获取跨插件服务。
     * @return 当前活动的服务实例，若提供者未加载则返回 null
     */
    fun <T : Any> getService(clazz: Class<T>): T? = ServiceRegistry.get(clazz)

    /** 手动注销服务（一般不需要，插件卸载时框架自动清理） */
    fun unregisterService(clazz: Class<*>) = ServiceRegistry.unregister(clazz)

    // ============ 原有 API 转发层 ============

    @RequiresPermission(PermissionLevel.SELF)
    suspend fun launchPlugin(pluginId: String): Boolean {
        if (::launchPlugin.javaMethod?.checkApiCaller(targetPluginId = pluginId) == false) {
            Timber.w("权限不足：插件启动操作被拒绝 [pluginId: $pluginId]")
            return false
        }
        return requireContext().lifecycleManager.launchPlugin(pluginId)
    }

    /**
     * 卸载插件。
     *
     * ★ 关键变更：在 finally 中自动清理该插件注册的所有服务，
     *   确保插件卸载/热更新后，旧的服务引用不会残留。
     */
    @RequiresPermission(PermissionLevel.SELF)
    suspend fun unloadPlugin(pluginId: String) {
        if (::unloadPlugin.javaMethod?.checkApiCaller(targetPluginId = pluginId) == false) {
            Timber.w("权限不足：插件卸载操作被拒绝 [pluginId: $pluginId]")
            return
        }
        try {
            requireContext().lifecycleManager.unloadPlugin(pluginId)
        } finally {
            // 无论卸载成功与否，都清理该插件注册的服务
            ServiceRegistry.unregisterAllByPlugin(pluginId)
        }
    }

    @RequiresPermission(PermissionLevel.HOST)
    suspend fun loadEnabledPlugins(): Int {
        if (::loadEnabledPlugins.javaMethod?.checkApiCaller() == false) {
            Timber.w("权限不足：插件加载操作被拒绝")
            return 0
        }
        return requireContext().lifecycleManager.loadEnabledPlugins()
    }

    fun <T : Any> getInterface(interfaceClass: Class<T>, className: String): T? {
        try {
            val targetPluginId = requireContext().classIndex[className]
            if (targetPluginId == null) {
                getInterfaceFromHost(interfaceClass, className)?.let { return it }
                Timber.Forest.tag(TAG).w("无法找到类 '$className' 的宿主插件，类索引中不存在该条目。")
                return null
            }

            val loadedPlugin = requireContext().loadedPlugins.value[targetPluginId]
            if (loadedPlugin == null) {
                Timber.Forest.tag(TAG)
                    .e("类索引不一致：类 '$className' 指向插件 '$targetPluginId'，但该插件当前未加载。")
                return null
            }

            Timber.Forest.tag(TAG)
                .d("正在从插件 '$targetPluginId' 中获取接口 '${interfaceClass.simpleName}' 的实现 '$className'...")
            return loadedPlugin.classLoader.getInterface(interfaceClass, className)

        } catch (e: Exception) {
            Timber.Forest.tag(TAG).e(e, "通过 PluginQueryManager 获取接口 '$className' 的实例时发生未知错误。")
            return null
        }
    }

    fun getPluginInstance(pluginId: String): IPluginEntryClass? {
        return requireContext().pluginInstances.value[pluginId]
    }

    fun getPluginInfo(pluginId: String): LoadedPluginInfo? {
        return requireContext().loadedPlugins.value[pluginId]
    }

    fun getAllPluginInstances(): Map<String, IPluginEntryClass> {
        return requireContext().pluginInstances.value
    }

    fun getAllInstallPlugins(): List<PluginInfo> {
        return requireContext().xmlManager.getAllPlugins()
    }

    fun getPluginDependentsChain(pluginId: String): List<String> {
        return requireContext().dependencyManager.findDependentsRecursive(pluginId)
    }

    fun getPluginDependenciesChain(pluginId: String): List<String> {
        return requireContext().dependencyManager.findDependenciesRecursive(pluginId)
    }

    @RequiresPermission(PermissionLevel.HOST)
    suspend fun setPluginEnabled(pluginId: String, enabled: Boolean): Boolean {
        if (::setPluginEnabled.javaMethod?.checkApiCaller(targetPluginId = pluginId) == false) {
            Timber.w("权限不足：插件状态设置操作被拒绝 [pluginId: $pluginId]")
            return false
        }
        return try {
            val pluginInfo = requireContext().xmlManager.getPluginById(pluginId) ?: return false
            if (pluginInfo.enabled == enabled) return true
            val updatedPluginInfo = pluginInfo.copy(enabled = enabled)
            requireContext().xmlManager.updatePlugin(updatedPluginInfo)
            requireContext().xmlManager.flushToDisk()
            true
        } catch (e: Exception) {
            Timber.Forest.tag(TAG).e(e, "设置插件 '$pluginId' 状态时出错。")
            false
        }
    }

    private fun <T : Any> getInterfaceFromHost(interfaceClass: Class<T>, className: String): T? {
        return try {
            val clazz = requireContext().application.classLoader.loadClass(className)
            val instance = clazz.getDeclaredConstructor().newInstance()
            if (interfaceClass.isInstance(instance)) {
                @Suppress("UNCHECKED_CAST")
                instance as T
            } else {
                Timber.Forest.tag(TAG)
                    .e("类型不匹配：宿主类 '$className' 未实现接口 '${interfaceClass.simpleName}'")
                null
            }
        } catch (_: ClassNotFoundException) {
            null
        } catch (e: Throwable) {
            Timber.Forest.tag(TAG).e(e, "从宿主实例化 '$className' 时发生错误。")
            null
        }
    }
}