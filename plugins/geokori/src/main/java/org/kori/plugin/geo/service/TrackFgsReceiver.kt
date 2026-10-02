package org.kori.plugin.geo.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import org.kori.plugin.geo.track.TrackRecordingEngine

/**
 * 通知栏"停止"按钮的接收器（动态注册版）。
 *
 * ## 生命周期由 [org.kori.plugin.geo.PluginEntryClass] 持有
 *
 * 本类是**普通类**，不是 `object` 单例，也不是伴生对象单例。
 * 每个 [org.kori.plugin.geo.PluginEntryClass] 实例持有一个
 * [TrackFgsReceiver] 实例：
 *
 * ```kotlin
 * class PluginEntryClass {
 *     private val fgsReceiver = TrackFgsReceiver()
 *     override fun onLoad(ctx) { fgsReceiver.register(ctx.application) }
 *     override fun onUnload()   { fgsReceiver.unregister() }
 * }
 * ```
 *
 * 这样：
 *  · [registeredContext] 是**实例字段**，不会触发
 *    `StaticFieldLeak` lint 警告
 *  · Receiver 实例随 PluginEntryClass 一起被 GC，不会残留
 *  · 多插件场景下每个插件有独立的 Receiver
 *
 * ## 注册 / 反注册要求
 *
 *  · `register` 和 `unregister` 都**幂等**，重复调用安全
 *  · `register` / `unregister` 之间不能跨实例调用——每个 receiver
 *    实例只能反注册自己的注册
 *
 * ## 为什么不用静态注册
 *
 * ComboLite 静态广播要求 Receiver 实现 `IPluginReceiver` 接口，且要
 * 在插件 Manifest 声明。我们的场景只做**进程内**通信
 * （[TrackRecordingService] 用 `setPackage(宿主包名)` 发显式广播），
 * 动态注册更简单。
 *
 * ## API 33+ 兼容
 *
 * 用 `Build.VERSION.SDK_INT` 分支直接调用系统的 `registerReceiver(...,
 * Context.RECEIVER_NOT_EXPORTED)`，避免依赖 androidx.core 版本的
 * `ContextCompat.RECEIVER_NOT_EXPORTED` 常量。
 */
class TrackFgsReceiver : BroadcastReceiver() {

    /**
     * 注册时使用的 Context（Application Context）。
     *
     * 反注册必须传同一个实例，因此这里保存引用。它是**实例字段**，
     * 不会造成 static 泄漏。
     */
    @Volatile
    private var registeredContext: Context? = null

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            TrackRecordingService.ACTION_USER_STOP -> {
                // 先停 Engine，它会内部再调 TrackFgsBridge.stop() 撤掉 FGS
                TrackRecordingEngine.stop()
                // 兜底：Engine.stop() 早退时确保通知栏也消失
                runCatching {
                    TrackFgsBridge.stop(context.applicationContext)
                }
            }
        }
    }

    // =============================================================================================
    // 注册 / 反注册
    // =============================================================================================

    /**
     * 动态注册 Receiver。幂等。
     *
     * @param context 建议传 `applicationContext`
     */
    fun register(context: Context) {
        if (registeredContext != null) return

        val appContext = context.applicationContext ?: context
        val filter = IntentFilter(TrackRecordingService.ACTION_USER_STOP)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // API 33+：显式声明 not-exported，避免 SecurityException
            appContext.registerReceiver(this, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            // API 32 及以下：无导出性概念，直接注册
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(this, filter)
        }

        registeredContext = appContext
    }

    /**
     * 反注册。幂等——未注册时调用是安全的。
     */
    fun unregister() {
        val ctx = registeredContext ?: return
        runCatching { ctx.unregisterReceiver(this) }
        registeredContext = null
    }
}