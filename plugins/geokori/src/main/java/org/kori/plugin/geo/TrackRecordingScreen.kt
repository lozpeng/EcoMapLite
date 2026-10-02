package org.kori.plugin.geo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.combo.core.utils.startPluginActivity
import org.kori.plugin.geo.gnss.SatelliteStatusScreen
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
 *  · 不处理相机、图层、位置——全部由 `MapLibreMapView` 内部管理
 *
 * ## 交互
 *
 *  · 记录面板：MapLibreMapView 内部按 Engine 状态自动显示/隐藏
 *  · 媒体采集：`startPluginActivity` 走 ComboLite 代理
 *  · ★ 定位按钮**长按**：弹出 [SatelliteStatusScreen] 卫星状态覆盖层
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

    // 手动构造 ViewModel（pluginModule 为空，不能用 koinViewModel()）。
    // 传 applicationContext，防止泄漏 Activity。
    val viewModel = remember {
        TrackRecordingViewModel(context.applicationContext)
    }
    val state by viewModel.state.collectAsState()

    // ★ 卫星状态覆盖层开关（定位按钮长按触发）
    var showSatelliteStatus by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        MapLibreMapView(
            modifier = Modifier.fillMaxSize(),
            config = MapConfig(
                useCustomLocationPipeline = true,
                customLocationTrackingZoom = 17.0,
                showLocationButton = true,
                showLayerButton = true,
                // 避让宿主底部导航栏（按宿主底栏实际高度调整）
                trackPanelBottomPadding = 130.dp,
                locationButtonOffsetY = (-130).dp,
            ),
            // 位置源：Engine 的 fix 流
            externalLocationFixes = TrackRecordingEngine.trackerFixes,
            // 实时轨迹数据
            liveTrackPoints = state.liveTrackPoints,
            liveSmoothPoints = state.liveSmoothPoints,
            liveTrackMedia = state.liveMedia,
            // ★ 定位按钮长按 → 卫星状态
            onLocationButtonLongClick = {
                showSatelliteStatus = true
            },
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

        // ★ 卫星状态覆盖层（定位按钮长按打开）
        if (showSatelliteStatus) {
            SatelliteStatusScreen(
                onClose = { showSatelliteStatus = false },
            )
        }
    }
}