package org.kori.plugin.geo.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.combo.core.component.service.BasePluginService

/**
 * 轨迹记录前台服务（ComboLite 插件版）。
 *
 * ## ★ B3 修复：继承 BasePluginService（原来手写 Service + IPluginService）
 *
 * 文档（四大组件指南）明确要求插件 Service 继承 [BasePluginService]：
 *  · 框架自动注入 `proxyActivity` / `proxyService`，无需手写 `onAttach`
 *  · 框架负责把真实宿主 Service 的所有生命周期事件转发给本类
 *  · 原来 `realService = proxyService ?: this` 的回退分支是**坏的**——插件
 *    Service 不是真实组件，`this` 上调用 `startForeground()` 会直接崩溃
 *
 * ## 与 Engine 的边界
 *
 * 本类**不包含业务逻辑**，只做两件事：
 *  · 维护前台通知（保证 Android 8+ 进程保活，满足持续定位要求）
 *  · 响应通知栏"停止"按钮的广播（交给 [TrackFgsReceiver] 处理）
 *
 * 业务逻辑全部在 [org.kori.plugin.geo.track.TrackRecordingEngine] 里。
 *
 * ## 生命周期
 *
 * ```
 * TrackFgsBridge.start(ctx)   → ACTION_START  → startForeground
 * TrackFgsBridge.update(ctx)  → ACTION_UPDATE → notify
 * TrackFgsBridge.stop(ctx)    → ACTION_STOP   → stopForeground + stopSelf
 * ```
 *
 * ## 宿主端配合（★ 缺一不可）
 *
 * 宿主 `Application.onCreate()`：
 * ```kotlin
 * PluginManager.proxyManager.setServicePool(listOf(
 *     HostService1::class.java,   // 至少一个，继承 BaseHostService 的空类
 * ))
 * ```
 * 宿主 `AndroidManifest.xml`：**每个 HostServiceN 必须声明**
 * ```xml
 * <service
 *     android:name=".services.HostService1"
 *     android:foregroundServiceType="location"
 *     android:exported="false" />
 * ```
 * 否则 `startForeground(..., FOREGROUND_SERVICE_TYPE_LOCATION)` 抛异常。
 *
 * ## ★ B4 修复说明
 *
 * 插件自己的 `AndroidManifest.xml` **不应再声明**本 Service——
 * ComboLite 模型下插件 Service 由宿主代理池承载，插件 manifest 里的
 * `<service>` 声明不会被注册为真实组件（已从新 manifest 中移除）。
 */
class TrackRecordingService : BasePluginService() {

    /**
     * 真实宿主 Service（由 BasePluginService 注入）。
     *
     * 所有系统能力（startForeground / getSystemService / packageManager …）
     * 都必须通过它访问。未注入时各操作静默跳过——正常情况下 ProxyManager
     * 一定会在调用 onStartCommand 之前完成注入。
     */
    private val realService: Service?
        get() = proxyService

    // =============================================================================================
    // Service 生命周期（由宿主代理 Service 转发）
    // =============================================================================================

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: DEFAULT_TITLE
                val text = intent.getStringExtra(EXTRA_TEXT) ?: DEFAULT_TEXT
                startForegroundCompat(title, text)
            }

            ACTION_UPDATE -> {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: DEFAULT_TITLE
                val text = intent.getStringExtra(EXTRA_TEXT) ?: DEFAULT_TEXT
                updateNotification(title, text)
            }

            ACTION_STOP -> {
                // 通知栏"停止"按钮的兜底路径；主路径见 [TrackFgsReceiver]。
                // stopSelf() 必须执行——它触发代理槽位归还。
                runCatching { stopForegroundCompat() }
                runCatching { realService?.stopSelf() }
            }

            else -> {
                // 无 action 时的兜底：老版本系统确保进入前台
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                    startForegroundCompat(DEFAULT_TITLE, DEFAULT_TEXT)
                }
            }
        }
        return Service.START_STICKY
    }

    override fun onDestroy() {
        runCatching { stopForegroundCompat() }
        super.onDestroy()
    }

    // =============================================================================================
    // 前台 / 通知
    // =============================================================================================

    private fun startForegroundCompat(title: String, text: String) {
        val target = realService ?: return
        val notif = buildNotification(title, text)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+：显式声明 foregroundServiceType。
            // 必须与宿主 Manifest 中 HostServiceN 声明的 type 有交集（location）。
            target.startForeground(
                NOTIFICATION_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            target.startForeground(NOTIFICATION_ID, notif)
        }
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        val target = realService ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            target.stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } else {
            target.stopForeground(true)
        }
    }

    private fun updateNotification(title: String, text: String) {
        val target = realService ?: return
        val nm = target.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        runCatching { nm.notify(NOTIFICATION_ID, buildNotification(title, text)) }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val target = realService
            ?: throw IllegalStateException(
                "proxyService 未注入——TrackRecordingService 必须由 ComboLite ProxyManager 实例化",
            )

        // 点击通知 → 打开宿主 App
        val contentIntent: PendingIntent? = target.packageManager
            .getLaunchIntentForPackage(target.packageName)
            ?.let { launch ->
                PendingIntent.getActivity(
                    target,
                    REQUEST_CONTENT,
                    launch,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }

        // 停止按钮 → 广播给动态注册的 [TrackFgsReceiver]
        val stopIntent = PendingIntent.getBroadcast(
            target,
            REQUEST_STOP,
            Intent(ACTION_USER_STOP).setPackage(target.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(target, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "停止",
                stopIntent,
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val target = realService ?: return
        val nm = target.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return

        val ch = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "记录位置轨迹时显示"
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    // =============================================================================================
    // 常量
    // =============================================================================================

    companion object {

        // ---- Intent action（插件内部协议，与宿主无关） ----
        const val ACTION_START = "org.kori.plugin.geo.fgs.START"
        const val ACTION_UPDATE = "org.kori.plugin.geo.fgs.UPDATE"
        const val ACTION_STOP = "org.kori.plugin.geo.fgs.STOP"

        /**
         * 通知栏"停止"按钮发出的广播。
         *
         * 由 [TrackFgsReceiver] 动态注册接收（注册时机见 `PluginEntryClass`）。
         * 广播**显式 setPackage(packageName)**，不会泄漏到其它应用。
         */
        const val ACTION_USER_STOP = "org.kori.plugin.geo.fgs.USER_STOP"

        // ---- Intent extras ----
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"

        // ---- 通知 ----
        private const val CHANNEL_ID = "vela_track_host"
        private const val CHANNEL_NAME = "轨迹记录"
        private const val DEFAULT_TITLE = "Vela 轨迹记录"
        private const val DEFAULT_TEXT = "记录中..."
        private const val NOTIFICATION_ID = 0x5E1B

        // ---- PendingIntent request codes ----
        private const val REQUEST_CONTENT = 0
        private const val REQUEST_STOP = 1
    }
}