package com.combo.core.runtime.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import timber.log.Timber
/**
 * 跨插件服务注册表（全局单例）。
 *
 * 设计要点：
 * 1. 与 PluginManager 同属 core，由宿主 ClassLoader 加载，所有插件共享同一实例。
 * 2. key 使用接口 Class.name（String），避免 Class 对象跨 ClassLoader 不一致。
 * 3. 每条记录携带 pluginId，插件卸载时自动批量清理。
 * 4. 通过 StateFlow 通知依赖方，支持插件热更新场景。
 */
object ServiceRegistry {

    private const val TAG = "ServiceRegistry"

    data class Entry(
        val service: Any,
        val pluginId: String,
        val registeredAt: Long = System.currentTimeMillis()
    )

    private val _services = MutableStateFlow<Map<String, Entry>>(emptyMap())

    /** 依赖方通过 collect 该流自动获取最新服务快照 */
    val servicesFlow: StateFlow<Map<String, Entry>> = _services.asStateFlow()

    // ---------- 注册 ----------

    fun <T : Any> register(clazz: Class<T>, service: T, pluginId: String) {
        require(clazz.isInstance(service)) {
            "Service ${service.javaClass.name} must implement ${clazz.name}"
        }
        val key = clazz.name
        val previous = _services.value[key]
        if (previous != null && previous.pluginId != pluginId) {
            Timber.tag(TAG).w(
                "服务 '$key' 被插件 '$pluginId' 覆盖，原提供者: '${previous.pluginId}'"
            )
        }
        _services.update { it + (key to Entry(service, pluginId)) }
        Timber.tag(TAG).d("服务已注册: $key (by $pluginId)")
    }

    /** 若未显式传 pluginId，尝试从 PluginContextHolder 获取 */
    fun <T : Any> register(clazz: Class<T>, service: T) {
        val pluginId = PluginContextHolder.current()
            ?: throw IllegalStateException(
                "无法确定 pluginId：请显式传入，或在 onLoad/onUnload 期间调用"
            )
        register(clazz, service, pluginId)
    }

    // ---------- 获取 ----------

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(clazz: Class<T>): T? =
        _services.value[clazz.name]?.service as? T

    fun getEntry(clazz: Class<*>): Entry? = _services.value[clazz.name]

    fun getEntry(key: String): Entry? = _services.value[key]

    // ---------- 注销 ----------

    fun unregister(clazz: Class<*>) {
        val key = clazz.name
        _services.update { it - key }
        Timber.tag(TAG).d("服务已注销: $key")
    }

    fun unregister(key: String) {
        _services.update { it - key }
    }

    /** 插件卸载时调用，批量清理该插件注册的所有服务 */
    fun unregisterAllByPlugin(pluginId: String) {
        val before = _services.value.size
        _services.update { map ->
            map.filterValues { it.pluginId != pluginId }
        }
        val after = _services.value.size
        if (before != after) {
            Timber.tag(TAG).d(
                "已清理插件 '$pluginId' 注册的 ${before - after} 个服务"
            )
        }
    }

    fun clear() {
        _services.update { emptyMap() }
    }
}