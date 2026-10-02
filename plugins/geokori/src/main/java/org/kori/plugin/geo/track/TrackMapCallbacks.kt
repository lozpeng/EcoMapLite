package org.kori.plugin.geo.track

/**
 * 记录面板的回调集合。
 *
 * ## 为什么用 data class 收口回调？
 *
 * `TrackRecordingPanel` 有 5 个动作按钮，如果全部作为独立参数：
 *
 * ```kotlin
 * TrackRecordingPanel(
 *     state = state,
 *     onToggle = { ... },
 *     onPhoto = { ... },
 *     onAudio = { ... },
 *     onVideo = { ... },
 *     onOpenDetail = { ... },
 * )
 * ```
 *
 * 会导致：
 *  · Composable 签名过长
 *  · 想加新按钮就要改所有调用点
 *  · 参数顺序容易搞错（都是 `() -> Unit`）
 *
 * 用 data class 收口后：
 *
 * ```kotlin
 * TrackRecordingPanel(
 *     state = state,
 *     callbacks = TrackMapCallbacks(
 *         onToggle = { ... },
 *         onPhoto = { ... },
 *     ),
 * )
 * ```
 *
 * 好处：
 *  · 可读性强（命名参数）
 *  · 加字段不用改调用点（默认空实现）
 *  · 可以整体替换（例如预览时传空回调）
 *
 * ## 默认值
 *
 * 每个回调都有默认空实现（`= {}`），所以调用方可以只传关心的回调：
 *
 * ```kotlin
 * TrackRecordingPanel(
 *     state = state,
 *     callbacks = TrackMapCallbacks(onToggle = { ... }),
 * )
 * ```
 */
data class TrackMapCallbacks(
    /**
     * 点击主按钮（▶ / ■）切换记录状态。
     *
     * 语义：
     *  · 未记录时点击 → 开始
     *  · 记录中点击 → 停止
     */
    val onToggle: () -> Unit = {},

    /**
     * 点击拍照按钮（📷）。
     *
     * 通常启动 [org.kori.plugin.geo.map.service.TrackMediaCaptureActivity]
     * 并传 `capture_kind = "PHOTO"`。
     */
    val onPhoto: () -> Unit = {},

    /**
     * 点击录音按钮（🎤）。
     *
     * 通常启动 [org.kori.plugin.geo.map.service.TrackMediaCaptureActivity]
     * 并传 `capture_kind = "AUDIO"`。
     */
    val onAudio: () -> Unit = {},

    /**
     * 点击录像按钮（🎥）。
     *
     * 通常启动 [org.kori.plugin.geo.map.service.VideoCaptureActivity]。
     */
    val onVideo: () -> Unit = {},

    /**
     * 点击"查看"按钮，打开轨迹详情 / 会话列表。
     *
     * 仅在未记录且 [TrackRecordingPanel] 有历史数据时显示。
     */
    val onOpenDetail: () -> Unit = {},
)