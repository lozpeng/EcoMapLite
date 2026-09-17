/*
 * Copyright (c) 2025, 贵州君城网络科技有限公司
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.combo.core.security.crash

import android.app.Application
import android.content.Intent
import android.content.res.Resources
import android.os.Process
import android.os.Process.killProcess
import com.combo.core.exception.PluginDependencyException
import com.combo.core.model.PluginCrashInfo
import com.combo.core.runtime.PluginManager
import com.combo.core.security.permission.PermissionLevel
import com.combo.core.security.permission.RequiresPermission
import com.combo.core.security.permission.checkApiCaller
import com.combo.core.utils.startPluginActivity
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.jvm.javaMethod
import kotlin.system.exitProcess

/**
 * 全局插件崩溃处理器
 *
 * 核心职责:
 * 1.  **精准识别**: 捕获多种插件化场景下的常见异常。
 * 2.  **定位源头**: 通过分析堆栈轨迹，找到引发崩溃的具体插件。
 * 3.  **分级委托**: 优先调用插件专属的回调，如果未处理再调用宿主设置的全局回调。
 * 4.  **默认保障**: 如果所有回调都未处理，则执行默认的容错逻辑（显示UI、重启应用）。
 */
object PluginCrashHandler : Thread.UncaughtExceptionHandler {

    const val EXTRA_CRASH_INFO = "CRASH_INFO"

    private lateinit var context: Application
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    private var globalCallback: IPluginCrashCallback? = null
    private val pluginCallbacks = ConcurrentHashMap<String, IPluginCrashCallback>()

    /**
     * 初始化并注册全局崩溃处理器
     * @param context Application Context
     * @param globalCallback 宿主App的全局崩溃处理回调
     */
    @JvmStatic
    fun initialize(context: Application) {
        if (this::context.isInitialized) {
            Timber.w("PluginCrashHandler 已被初始化，无需重复设置。")
            return
        }

        this.context = context
        this.defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler(this)
        Timber.i("全局插件崩溃处理器已成功注册。")
    }

    /**
     * (供宿主使用) 设置或更新全局的插件崩溃处理回调。
     * @param callback 宿主自定义的全局崩溃处理回调，传入 null 可清除。
     */
    @JvmStatic
    @RequiresPermission(PermissionLevel.HOST, true)
    suspend fun setGlobalClashCallback(callback: IPluginCrashCallback?) {
        if (::setGlobalClashCallback.javaMethod?.checkApiCaller() == false) return
        this.globalCallback = callback
        Timber.i("PluginCrashHandler 全局回调已更新。")
    }

    /**
     * (供插件使用) 为指定插件注册或注销其专属的崩溃回调。
     * API权限要求：[PermissionLevel.SELF] - 插件只能为自己设置回调。
     *
     * @param pluginId 要设置回调的插件ID。权限检查将确保调用方就是该插件。
     * @param callback 插件的崩溃回调实例。传入 null 可注销该插件的回调。
     */
    @JvmStatic
    @RequiresPermission(PermissionLevel.SELF, true)
    suspend fun setClashCallback(pluginId: String, callback: IPluginCrashCallback?) {
        if (::setClashCallback.javaMethod?.checkApiCaller(targetPluginId = pluginId) == false) {
            Timber.w("权限不足：插件尝试为 [$pluginId] 设置崩溃回调被拒绝。")
            return
        }

        if (callback != null) {
            pluginCallbacks[pluginId] = callback
            Timber.d("为插件 [$pluginId] 注册了崩溃回调。")
        } else {
            if (pluginCallbacks.remove(pluginId) != null) {
                Timber.d("已注销插件 [$pluginId] 的崩溃回调。")
            }
        }
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val wasHandled = handlePluginRelatedException(throwable)

            if (!wasHandled) {
                Timber.d("异常并非由插件引起，或未被任何回调处理，交由默认处理器。")
                defaultHandler?.uncaughtException(thread, throwable)
            }
        } catch (e: Exception) {
            Timber.e(e, "在PluginCrashHandler内部处理异常时发生错误！")
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun handlePluginRelatedException(throwable: Throwable): Boolean {
        val culpritPluginId = findCulpritPluginId(throwable)
        val pluginCallback = culpritPluginId?.let { pluginCallbacks[it] }

        // 1. 依赖缺失异常
        findCause<PluginDependencyException>(throwable)?.let {
            val info = PluginCrashInfo(it, it.culpritPluginId, "该插件缺少必要的依赖组件 (${it.missingClassName.substringAfterLast('.')})，无法正常工作。")
            if (pluginCallback?.onDependencyException(info) == true) return true
            if (globalCallback?.onDependencyException(info) == true) return true
            showCrashActivity(info)
            return true
        }

        // 2. 类型转换异常
        findCause<ClassCastException>(throwable)?.let {
            if (culpritPluginId != null) {
                val info = PluginCrashInfo(it, culpritPluginId, "插件可能未能完全更新，导致内部组件冲突。")
                if (pluginCallback?.onClassCastException(info) == true) return true
                if (globalCallback?.onClassCastException(info) == true) return true
                showCrashActivity(info)
                return true
            }
        }

        // 3. 资源未找到异常
        findCause<Resources.NotFoundException>(throwable)?.let {
            if (culpritPluginId != null) {
                val info = PluginCrashInfo(it, culpritPluginId, "插件尝试访问一个不存在的内部资源。")
                if (pluginCallback?.onClassCastException(info) == true) return true
                if (globalCallback?.onClassCastException(info) == true) return true
                showCrashActivity(info)
                return true
            }
        }

        // 4. API不兼容异常
        if (throwable is NoSuchMethodError || throwable is NoSuchFieldError || throwable is AbstractMethodError) {
            if (culpritPluginId != null) {
                val info = PluginCrashInfo(throwable, culpritPluginId, "该插件版本与当前应用版本不兼容。")
                if (pluginCallback?.onClassCastException(info) == true) return true
                if (globalCallback?.onClassCastException(info) == true) return true
                showCrashActivity(info)
                return true
            }
        }

        // 5. 其他与插件相关的异常
        if (culpritPluginId != null) {
            val info = PluginCrashInfo(throwable, culpritPluginId, "插件发生了一个未知错误，导致其无法正常运行。")
            if (pluginCallback?.onOtherPluginException(info) == true) return true
            if (globalCallback?.onOtherPluginException(info) == true) return true
            showCrashActivity(info)
            return true
        }

        return false
    }

    private fun showCrashActivity(crashInfo: PluginCrashInfo) {
        context.startPluginActivity(CrashActivity::class.java) {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(EXTRA_CRASH_INFO, crashInfo)
        }
        killProcess()
    }

    private fun killProcess() {
        killProcess(Process.myPid())
        exitProcess(10)
    }

    private fun findCulpritPluginId(throwable: Throwable?): String? {
        var current: Throwable? = throwable
        while (current != null) {
            for (element in current.stackTrace) {
                val pluginId = PluginManager.getClassIndex()[element.className]
                if (pluginId != null) {
                    return pluginId
                }
            }
            current = current.cause
        }
        return null
    }

    private inline fun <reified T : Throwable> findCause(throwable: Throwable?): T? {
        var current: Throwable? = throwable
        while (current != null) {
            if (current is T) return current
            current = current.cause
        }
        return null
    }
}