package com.combo.core.runtime.service

/**
 * 追踪当前正在执行 onLoad/onUnload 的插件 ID。
 *
 * 由 LifecycleManager 在调用插件 onLoad/onUnload 前包裹设置，
 * 插件内即可通过 registerService(clazz, service) 隐式注册。
 */
object PluginContextHolder {

    private val holder = ThreadLocal<String?>()

    fun set(pluginId: String) { holder.set(pluginId) }

    fun current(): String? = holder.get()

    fun clear() { holder.remove() }

    inline fun <T> with(pluginId: String, block: () -> T): T {
        set(pluginId)
        return try {
            block()
        } finally {
            clear()
        }
    }
}