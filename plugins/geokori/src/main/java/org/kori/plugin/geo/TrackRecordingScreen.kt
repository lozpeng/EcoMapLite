package org.kori.plugin.geo

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.combo.core.utils.startPluginActivity
import org.kori.plugin.geo.map.MapConfig
import org.kori.plugin.geo.map.MapLibreMapView
import org.kori.plugin.geo.service.TrackMediaCaptureActivity
import org.kori.plugin.geo.service.VideoCaptureActivity
import org.kori.plugin.geo.track.TrackMapCallbacks
import org.kori.plugin.geo.track.TrackRecordingEngine
import org.kori.plugin.geo.track.TrackRecordingViewModel

/**
 * 轨迹记录屏幕（薄层）。
 *
 * ## 职责
 *
 *  · 只做**参数组装**——把 Engine 状态、媒体回调、面板回调传给 [MapLibreMapView]
 *  · 不再自己渲染 `TrackRecordingPanel`——由 `MapLibreMapView` 内部的
 *    `RecordingPanelOverlay` 按状态自动显示/隐藏
 *  · 不处理相机、图层、位置——全部由 `MapLibreMapView` 内部管理
 *
 * ## 修复记录
 *
 *  · **B1 修复**：媒体 Activity 改用 ComboLite 的 `startPluginActivity` 扩展函数
 *  · **B2 修复**：手动构造 ViewModel（`pluginModule` 为空，不能用 `koinViewModel()`）
 *  · **UI 修复**：`showLocationButton` 恢复为 true（之前 false 导致定位 FAB 不可见）；
 *    `trackPanelBottomPadding` / `locationButtonOffsetY` 抬高控件避让宿主底部导航栏
 *    （★ 数值按宿主底部栏实际高度调整，以面板不被遮挡为准）
 *
 * ## 使用
 *
 * ```kotlin
 * TrackRecordingScreen()
 * // 或
 * TrackRecordingScreen(onOpenTrackList = { navController.navigate("tracks") })
 * ```
 */
@Composable
fun TrackRecordingScreen(
    onOpenTrackList: () -> Unit = {},
) {
    val context = LocalContext.current

    // B2 修复：手动构造 ViewModel（避免依赖 Koin 模块注册）。
    // 传 applicationContext，防止泄漏 Activity。
    val viewModel = remember {
        TrackRecordingViewModel(context.applicationContext)
    }
    val state by viewModel.state.collectAsState()

    MapLibreMapView(
        modifier = Modifier.fillMaxSize(),
        config = MapConfig(
            useCustomLocationPipeline = true,
            customLocationTrackingZoom = 17.0,
            // ★ 修复：恢复定位 FAB（之前 false 导致"显示我的位置"按钮不可见）
            showLocationButton = true,
            showLayerButton = true,
            // ★ 宿主底部导航栏遮挡规避：
            // 记录面板底距抬到导航栏之上（按宿主底栏实际高度调整）
            trackPanelBottomPadding = 130.dp,
            // 定位 FAB 向上偏移，避开底栏（负值 = 向上）
            locationButtonOffsetY = (-130).dp,
        ),
        // 位置源：Engine 的 fix 流
        externalLocationFixes = TrackRecordingEngine.trackerFixes,
        // 实时轨迹数据
        liveTrackPoints = state.liveTrackPoints,
        liveSmoothPoints = state.liveSmoothPoints,
        liveTrackMedia = state.liveMedia,
        // 记录面板回调——非 null 时 MapLibreMapView 会在 recording 时自动显示面板
        trackPanelCallbacks = TrackMapCallbacks(
            // 开始 / 结束
            onToggle = { viewModel.toggleRecording() },

            // 暂停 / 继续
            onPauseToggle = { viewModel.togglePause() },

            // 媒体采集：★ 必须走 ComboLite 的 startPluginActivity
            onPhoto = {
                context.startPluginActivity(TrackMediaCaptureActivity::class.java) {
                    putExtra(
                        TrackMediaCaptureActivity.EXTRA_CAPTURE_KIND,
                        TrackMediaCaptureActivity.KIND_PHOTO,
                    )
                }
            },
            onAudio = {
                context.startPluginActivity(TrackMediaCaptureActivity::class.java) {
                    putExtra(
                        TrackMediaCaptureActivity.EXTRA_CAPTURE_KIND,
                        TrackMediaCaptureActivity.KIND_AUDIO,
                    )
                }
            },
            onVideo = {
                context.startPluginActivity(VideoCaptureActivity::class.java)
            },

            onOpenDetail = onOpenTrackList,
        ),
    )
}