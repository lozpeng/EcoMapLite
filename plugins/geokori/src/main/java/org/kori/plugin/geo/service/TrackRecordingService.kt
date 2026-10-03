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
 * ## 作用
 *
 * 后台记录的核心保障：前台服务 + 持续通知让系统认为本进程"用户可见"，
 * 从而不在切后台后被清理，GPS 回调持续到达 [org.kori.plugin.geo.track.TrackRecordingEngine]。
 *
 * ## 生命周期
 *
 * ```
 * TrackFgsBridge.start(ctx)   → ACTION_START  → startForeground
 * TrackFgsBridge.update(ctx)  → ACTION_UPDATE → notify
 * TrackFgsBridge.stop(ctx)    → ACTION_STOP   → stopForeground + stopSelf
 * ```
 *
 * ## ★ sticky 重启的空 Intent 兜底（重要）
 *
 * 返回 START_STICKY 后，进程被系统杀掉再重启时 onStartCommand 的 intent 为 null。
 * 此时**必须在 5 秒内调用 startForeground**（否则系统直接抛 FGS 重启异常）。
 * 旧实现只对 API < 26 兜底，API 26+ 必崩——现已改为全版本兜底。
 *
 * ## 宿主端配合
 *
 *  · `ProxyManager.setServicePool(...)` 至少一个 `BaseHostService` 子类
 *  · 宿主 manifest 每个 HostServiceN 声明 `foregroundServiceType="location"`
 *  · 运行时权限：`ACCESS_BACKGROUND_LOCATION`（始终允许）+ `POST_NOTIFICATIONS`
 */
class TrackRecordingService : BasePluginService() {

    /**
     * 真实宿主 Service（由 BasePluginService 注入）。
     * 未注入时各操作静默跳过——正常情况下 ProxyManager 一定先注入再调 onStartCommand。
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
                runCatching { stopForegroundCompat() }
                runCatching { realService?.stopSelf() }
            }

            else -> {
                // ★ sticky 重启 / 系统拉起：intent 为 null 或无 action。
                // 全版本兜底进入前台——FGS 被系统重启后 5 秒内必须 startForeground。
                startForegroundCompat(DEFAULT_TITLE, DEFAULT_TEXT)
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
         * 由 [TrackFgsReceiver] 动态注册接收。广播**显式 setPackage(packageName)**，
         * 不会泄漏到其它应用。
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