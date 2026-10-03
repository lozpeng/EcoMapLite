package org.kori.plugin.geo

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import org.kori.plugin.geo.track.RecordingPermissions
import org.kori.plugin.geo.track.TrackHistoryScreen
import org.kori.plugin.geo.track.TrackMapCallbacks
import org.kori.plugin.geo.track.TrackRecordingEngine
import org.kori.plugin.geo.track.TrackRecordingViewModel

/**
 * 轨迹记录屏幕（薄层）。
 *
 * ## 交互
 *
 *  · 记录面板：MapLibreMapView 内部按 Engine 状态自动显示/隐藏
 *  · 媒体采集：`startPluginActivity` 走 ComboLite 代理
 *  · 定位按钮**长按**：弹出 [SatelliteStatusScreen] 卫星状态覆盖层
 *  · ★ 开始记录前的权限闸门（后台记录前提）：
 *     1. 定位 + 通知权限 → 普通弹框
 *     2. 后台定位（"始终允许"）→ 未授予时引导到系统设置页
 *
 * ## 使用
 *
 * ```kotlin
 * TrackRecordingScreen()
 * ```
 */
@Composable
fun TrackRecordingScreen(
    onOpenTrackList: () -> Unit = {},
) {
    val context = LocalContext.current

    // 手动构造 ViewModel（pluginModule 为空，不能用 koinViewModel()）
    val viewModel = remember {
        TrackRecordingViewModel(context.applicationContext)
    }
    val state by viewModel.state.collectAsState()

    // ★ 卫星状态覆盖层开关（定位按钮长按触发）
    var showSatelliteStatus by remember { mutableStateOf(false) }

    // ★ 历史轨迹浏览覆盖层开关（面板"历史"按钮触发）
    var showTrackHistory by remember { mutableStateOf(false) }

    // =========================================================================
    // ★ 权限闸门：开始记录前确保 定位 + 通知 + 后台定位
    // =========================================================================
    // 流程：弹框申请（定位+通知）→ 若后台定位未授予，打开系统设置页引导
    // "位置信息 → 始终允许"，Toast 提示后用户回到页面再次点击开始。
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val anyGranted = result.values.any { it }
        if (!anyGranted) {
            Toast.makeText(context, "需要定位权限才能记录轨迹", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        if (!RecordingPermissions.hasBackgroundLocation(context)) {
            Toast.makeText(
                context,
                "后台记录需要“始终允许”定位：设置 → 应用 → 权限 → 位置信息",
                Toast.LENGTH_LONG,
            ).show()
            RecordingPermissions.openAppSettings(context)
            return@rememberLauncherForActivityResult
        }
        // 全部就绪 → 开始记录
        viewModel.toggleRecording()
    }

    /** 面板"开始/结束"的统一入口：先过权限闸门。 */
    val onToggleRecording: () -> Unit = {
        when {
            // 结束记录不需要权限检查
            state.recording -> viewModel.toggleRecording()

            // 缺定位/通知 → 弹框申请
            !RecordingPermissions.hasLocation(context) ||
                    !RecordingPermissions.hasNotifications(context) -> {
                permissionLauncher.launch(RecordingPermissions.requestablePermissions())
            }

            // 缺后台定位 → 引导系统设置（"始终允许"无法弹框授予）
            !RecordingPermissions.hasBackgroundLocation(context) -> {
                Toast.makeText(
                    context,
                    "后台记录需要“始终允许”定位：设置 → 应用 → 权限 → 位置信息",
                    Toast.LENGTH_LONG,
                ).show()
                RecordingPermissions.openAppSettings(context)
            }

            else -> viewModel.toggleRecording()
        }
    }

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
            // ★ 历史轨迹叠加层（历史浏览加载到地图）
            historySegments = state.historySegments,
            // ★ 定位按钮长按 → 卫星状态
            onLocationButtonLongClick = {
                showSatelliteStatus = true
            },
            // 记录面板回调
            trackPanelCallbacks = TrackMapCallbacks(
                // 开始 / 结束（★ 经过权限闸门）
                onToggle = onToggleRecording,

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

                // ★ 历史轨迹浏览
                onOpenHistory = {
                    showTrackHistory = true
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

        // ★ 历史轨迹浏览（面板"历史"按钮打开）
        if (showTrackHistory) {
            TrackHistoryScreen(
                onClose = { showTrackHistory = false },
            )
        }
    }
}