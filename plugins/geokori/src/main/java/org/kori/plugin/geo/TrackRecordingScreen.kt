package org.kori.plugin.geo

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import org.kori.plugin.geo.map.MapConfig
import org.kori.plugin.geo.map.MapLibreMapView
import org.koin.androidx.compose.koinViewModel
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
 * ## 与 `MapLibreMapView` 的分工
 *
 *  · `MapLibreMapView`：地图 + 位置 + 图层 + 实时轨迹 + （可选）记录面板
 *  · 本屏：把 Engine 数据接到地图上 + 把用户操作转回 Engine
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
    viewModel: TrackRecordingViewModel = koinViewModel(),
    onOpenTrackList: () -> Unit = {},
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()

    MapLibreMapView(
        modifier = Modifier.fillMaxSize(),
        config = MapConfig(
            useCustomLocationPipeline = true,
            customLocationTrackingZoom = 17.0,
            showLocationButton = false,
            showLayerButton = true,
        ),
        // 位置源：Engine 的 fix 流
        externalLocationFixes = TrackRecordingEngine.trackerFixes,
        // 实时轨迹数据
        liveTrackPoints = state.liveTrackPoints,
        liveSmoothPoints = state.liveSmoothPoints,
        liveTrackMedia = state.liveMedia,
        // 记录面板回调——非 null 时 MapLibreMapView 会在 recording 时自动显示面板
        trackPanelCallbacks = TrackMapCallbacks(
            onToggle = { viewModel.toggleRecording() },
            onPhoto = {
                context.startActivity(
                    Intent(context, TrackMediaCaptureActivity::class.java)
                        .putExtra("capture_kind", "PHOTO")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
            onAudio = {
                context.startActivity(
                    Intent(context, TrackMediaCaptureActivity::class.java)
                        .putExtra("capture_kind", "AUDIO")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
            onVideo = {
                context.startActivity(
                    Intent(context, VideoCaptureActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
            onOpenDetail = onOpenTrackList,
        ),
    )
}