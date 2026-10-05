package org.kori.plugin.geo

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import org.kori.plugin.geo.math.GeoMath
import org.kori.plugin.geo.track.RecordingPermissions
import org.kori.plugin.geo.track.ResumeAction
import org.kori.plugin.geo.track.ResumeCandidate
import org.kori.plugin.geo.track.TrackPlaybackScreen
import org.kori.plugin.geo.track.TrackMapCallbacks
import org.kori.plugin.geo.track.TrackRecordingEngine
import org.kori.plugin.geo.track.di.TrackRecordingViewModel
import org.kori.plugin.geo.track.di.TrackSession
import org.kori.plugin.geo.track.ui.TrackHistoryScreen
import org.kori.plugin.geo.track.ui.TrackTimelineScreen

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
 *  · ★【新增】响应 home 的 ACTION_SHOW_HISTORY 命令：弹出 TrackHistoryScreen
 *    （TrackUiEvents 与本品同包 org.kori.plugin.geo，无需 import）
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

    // ★ 历史轨迹浏览覆盖层开关（面板"历史"按钮 或 home 的 ACTION_SHOW_HISTORY 触发）
    var showTrackHistory by remember { mutableStateOf(false) }

    // ★ 轨迹回放（全屏沉浸式回放屏）
    var playbackSession by remember { mutableStateOf<TrackSession?>(null) }

    // ★ 轨迹时间线
    var timelineSession by remember { mutableStateOf<TrackSession?>(null) }

    // =========================================================================
    // ★ 断点续录：引擎检测到"上次非人为结束"时由 handler 触发询问对话框
    // =========================================================================
    var resumeCandidate by remember { mutableStateOf<ResumeCandidate?>(null) }
    var resumeDecide by remember { mutableStateOf<((ResumeAction) -> Unit)?>(null) }
    DisposableEffect(Unit) {
        TrackRecordingEngine.registerResumePromptHandler { candidate, decide ->
            resumeCandidate = candidate
            resumeDecide = decide
        }
        onDispose { TrackRecordingEngine.registerResumePromptHandler(null) }
    }

    // ★【新增】响应"显示历史"命令（home 广播 → TrackCommandReceiver → TrackUiEvents）
    LaunchedEffect(Unit) {
        TrackUiEvents.showHistory.collect {
            showTrackHistory = true
        }
    }

    // =========================================================================
    // ★ 权限闸门：开始记录前确保 定位 + 通知 + 后台定位
    // =========================================================================
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
                locationButtonOffsetY = (-40).dp,
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

        // ★ 历史轨迹浏览（面板"历史"按钮 / home 的 ACTION_SHOW_HISTORY 打开）
        if (showTrackHistory) {
            TrackHistoryScreen(
                onClose = { showTrackHistory = false },
                onPlay = { session ->
                    playbackSession = session
                },
                onTimeline = { session ->
                    timelineSession = session
                },
            )
        }

        // ★ 轨迹回放（全屏沉浸式）
        playbackSession?.let { session ->
            TrackPlaybackScreen(
                session = session,
                onClose = { playbackSession = null },
            )
        }

        // ★ 轨迹时间线
        timelineSession?.let { session ->
            TrackTimelineScreen(
                session = session,
                onClose = { timelineSession = null },
            )
        }

        // =========================================================================
        // ★ 断点续录询问对话框
        // =========================================================================
        resumeCandidate?.let { candidate ->
            // 当前位置（引擎状态持续更新）与中断点的距离
            val curLat = state.currentLat
            val curLng = state.currentLng
            val distText = if (candidate.lastLat != null && candidate.lastLng != null &&
                curLat != null && curLng != null
            ) {
                val d = GeoMath.haversineMeters(
                    candidate.lastLat, candidate.lastLng, curLat, curLng,
                )
                if (d >= 1000.0) "距中断点 %.2f 公里".format(d / 1000)
                else "距中断点 %d 米".format(d.toInt())
            } else {
                "定位中，暂时无法计算与中断点的距离"
            }

            AlertDialog(
                onDismissRequest = {
                    resumeDecide?.invoke(ResumeAction.CANCEL)
                    resumeCandidate = null
                    resumeDecide = null
                },
                title = { Text("继续上次轨迹？") },
                text = {
                    Column {
                        Text("检测到上次轨迹记录未正常结束（应用退出或系统清理）。")
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "${candidate.session.name}\n" +
                                    "${candidate.session.distanceText} · " +
                                    "${candidate.session.durationText} · " +
                                    "${candidate.session.totalRawPoints} 点\n" +
                                    distText,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        resumeDecide?.invoke(ResumeAction.RESUME)
                        resumeCandidate = null
                        resumeDecide = null
                    }) { Text("继续上次") }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = {
                            resumeDecide?.invoke(ResumeAction.CANCEL)
                            resumeCandidate = null
                            resumeDecide = null
                        }) { Text("取消") }
                        TextButton(onClick = {
                            resumeDecide?.invoke(ResumeAction.START_NEW)
                            resumeCandidate = null
                            resumeDecide = null
                        }) { Text("开始新的") }
                    }
                },
            )
        }
    }
}