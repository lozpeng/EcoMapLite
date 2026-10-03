package org.kori.plugin.geo.track

/**
 * 记录面板的回调集合。
 *
 * ## 为什么用 data class 收口回调？
 *
 * 所有交互通过命名参数收口，加字段不用改调用点（默认空实现）。
 */
data class TrackMapCallbacks(
    /**
     * 点击主按钮（▶ / ■）切换记录状态。
     *
     * 语义：
     *  · 未记录时点击 → 开始
     *  · 记录中点击 → 结束（停止并保存）
     */
    val onToggle: () -> Unit = {},

    /**
     * 点击暂停 / 继续按钮（⏸ / ▶）。
     *
     * 语义（由调用方实现为 toggle）：
     *  · 记录中点击 → 暂停（不再写入轨迹点，计时停止）
     *  · 暂停中点击 → 继续
     *  · 未记录时此按钮不显示
     */
    val onPauseToggle: () -> Unit = {},

    /**
     * 点击拍照按钮（📷）。
     *
     * 通常启动 [org.kori.plugin.geo.service.TrackMediaCaptureActivity]
     * 并传 `capture_kind = "PHOTO"`（通过 ComboLite 的 startPluginActivity）。
     */
    val onPhoto: () -> Unit = {},

    /**
     * 点击录音按钮（🎤）。
     */
    val onAudio: () -> Unit = {},

    /**
     * 点击录像按钮（🎥）。
     */
    val onVideo: () -> Unit = {},

    /**
     * 点击"历史"按钮（📋），打开历史轨迹浏览界面。
     *
     * 由调用方渲染历史覆盖层（如 [org.kori.plugin.geo.track.TrackHistoryScreen]），
     * 支持把历史轨迹加载到地图、导出分享、删除。
     * 任何状态（未记录/记录中/暂停中）都显示该按钮。
     */
    val onOpenHistory: () -> Unit = {},

    /**
     * 点击"查看"按钮，打开轨迹详情 / 会话列表。
     */
    val onOpenDetail: () -> Unit = {},
)