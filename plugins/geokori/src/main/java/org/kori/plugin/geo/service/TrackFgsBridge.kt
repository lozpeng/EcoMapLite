package org.kori.plugin.geo.service

import android.content.Context
import com.combo.core.utils.startPluginService

/**
 * 前台服务桥（ComboLite 适配版）。
 *
 * ## 为什么不能再用 `Context.startForegroundService`
 *
 * 旧实现：
 * ```kotlin
 * val intent = Intent(context, HostTrackFgs::class.java)  // ❌
 * ContextCompat.startForegroundService(context, intent)
 * ```
 * 在 ComboLite 下不成立：
 *  1. 插件不能声明 `android.app.Service`（宿主 Service 属于宿主）
 *  2. 插件不应直接引用宿主类（宿主类不在插件的类索引里）
 *
 * ## 新实现
 *
 * 通过 ComboLite 的 `Context.startPluginService(...)` 扩展函数启动
 * **插件自己的** [TrackRecordingService]（继承 [android.app.Service] +
 * 实现 [com.combo.core.api.IPluginService]）。ProxyManager 会从
 * 宿主 `servicePool` 里挑一个空闲槽位作为代理，并向真实宿主 Service
 * 转发生命周期。
 *
 * ## instanceId
 *
 * ComboLite 的插件 Service 支持多实例（通过 `instanceId` 区分）。
 * 本场景只需要一个"轨迹记录"服务，因此固定传 [INSTANCE_ID]。
 * 所有 start / update / stop 调用必须使用同一 instanceId，否则会被
 * 分发到不同实例。
 *
 * ## 调用方
 *
 *  · [org.kori.plugin.geo.track.TrackRecordingEngine.start] / [stop]
 *  · 传入 **Application Context**（不要传 Activity）
 */
object TrackFgsBridge {

    /**
     * 固定实例 ID。全局唯一即可，语义上表达"这是一个轨迹记录 FGS"。
     * ProxyManager 内部会拼成 `TrackRecordingService:vela_track_fgs`。
     */
    private const val INSTANCE_ID = "vela_track_fgs"

    /**
     * 启动前台服务。幂等——多次调用会复用同一实例。
     */
    fun start(
        context: Context,
        title: String = "Vela 轨迹记录",
        text: String = "记录中...",
    ) {
        context.startPluginService(
            cls = TrackRecordingService::class.java,
            instanceId = INSTANCE_ID,
        ) {
            action = TrackRecordingService.ACTION_START
            putExtra(TrackRecordingService.EXTRA_TITLE, title)
            putExtra(TrackRecordingService.EXTRA_TEXT, text)
        }
    }

    /**
     * 更新通知文字。服务未在前台时是无害的——ProxyManager 会把这次
     * start 分发到同一实例，[TrackRecordingService] 走 ACTION_UPDATE 分支。
     */
    fun update(
        context: Context,
        title: String = "Vela 轨迹记录",
        text: String,
    ) {
        context.startPluginService(
            cls = TrackRecordingService::class.java,
            instanceId = INSTANCE_ID,
        ) {
            action = TrackRecordingService.ACTION_UPDATE
            putExtra(TrackRecordingService.EXTRA_TITLE, title)
            putExtra(TrackRecordingService.EXTRA_TEXT, text)
        }
    }

    /**
     * 停止前台服务。
     *
     * 触发 [TrackRecordingService.onStartCommand] 的 ACTION_STOP 分支，
     * 内部 `stopSelf()` 让 ProxyManager 归还代理槽位。
     */
    fun stop(context: Context) {
        context.startPluginService(
            cls = TrackRecordingService::class.java,
            instanceId = INSTANCE_ID,
        ) {
            action = TrackRecordingService.ACTION_STOP
        }
    }
}