package org.cwcc.open.geokori.api

import kotlinx.coroutines.flow.StateFlow

/**
 * 轨迹记录状态订阅（跨插件，混合方案中的"回执"通道）。
 *
 * ★ 由宿主 implementation 的 lib-geokori 提供，各插件 compileOnly 引用。
 *
 * 命令通道用广播（[TrackIntents]），本接口只负责让 home 等插件
 * 订阅 recording/paused 状态来刷新 UI（按钮图标、颜色等）。
 *
 * 实现方：geokori 插件中的 org.kori.plugin.geo.api.GeoTrackStateApiImpl。
 */
interface ITrackRecordingStateApi {

    /** 是否正在记录。 */
    val isRecording: StateFlow<Boolean>

    /** 是否已暂停（仅记录中时有意义）。 */
    val isPaused: StateFlow<Boolean>
}

/**
 * 轨迹记录广播契约（home → geokori 的消息机制方案）。
 *
 * ★ 由宿主 implementation 的 lib-geokori 提供，各插件 compileOnly 引用。
 * 发送方：context.sendInternalBroadcast(TrackIntents.ACTION_START) { ... }
 * 接收方：geokori 插件注册动态 Receiver，分发到 TrackRecordingEngine。
 *
 * extras 只用基本类型，避免自定义 Parcelable 跨插件 ClassLoader 问题。
 */
object TrackIntents {

    /** 开始记录。可无 extras；也可带 nameHint（String）。 */
    const val ACTION_START = "org.kori.plugin.geo.track.ACTION_START"

    /** 暂停记录。 */
    const val ACTION_PAUSE = "org.kori.plugin.geo.track.ACTION_PAUSE"

    /** 继续记录。 */
    const val ACTION_RESUME = "org.kori.plugin.geo.track.ACTION_RESUME"

    /** 停止记录。 */
    const val ACTION_STOP = "org.kori.plugin.geo.track.ACTION_STOP"

    /** 开始/停止切换（最常用：一个按钮管开始和结束）。 */
    const val ACTION_TOGGLE = "org.kori.plugin.geo.track.ACTION_TOGGLE"

    /** ACTION_START 可选 extra：会话名称提示（String）。 */
    const val EXTRA_NAME_HINT = "extra_name_hint"

    /** 所有 action 的集合，接收方注册 IntentFilter 用。 */
    val ALL_ACTIONS = listOf(
        ACTION_START, ACTION_PAUSE, ACTION_RESUME, ACTION_STOP, ACTION_TOGGLE,
    )
}