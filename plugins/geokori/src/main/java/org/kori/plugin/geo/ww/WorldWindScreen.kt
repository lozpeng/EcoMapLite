package org.kori.plugin.geo.ww

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
import org.kori.plugin.geo.service.TrackMediaCaptureActivity
import org.kori.plugin.geo.service.VideoCaptureActivity
import org.kori.plugin.geo.track.RecordingPermissions
import org.kori.plugin.geo.track.TrackMapCallbacks
import org.kori.plugin.geo.track.TrackPlaybackScreen
import org.kori.plugin.geo.track.TrackRecordingEngine
import org.kori.plugin.geo.track.di.TrackRecordingViewModel
import org.kori.plugin.geo.track.di.TrackSession
import org.kori.plugin.geo.track.ui.TrackHistoryScreen
import org.kori.plugin.geo.track.ui.TrackTimelineScreen

/**
 * WorldWind 版轨迹记录屏幕（对应 MapLibre 版 [org.kori.plugin.geo.TrackRecordingScreen]）。
 *
 * 薄层结构完全一致：3D 地球 + 权限闸门 + HUD + 历史/回放/时间线覆盖层。
 * 引擎、HUD、回调、媒体 Activity 全部复用，只有地图内核换成 [TrackGlobeView]。
 */
@Composable
fun WorldWindowScreen(
    onOpenTrackList: () -> Unit = {},
) {
    val context = LocalContext.current
    val viewModel = remember { TrackRecordingViewModel(context.applicationContext) }
    val state by viewModel.state.collectAsState()

    var showSatelliteStatus by remember { mutableStateOf(false) }
    var showTrackHistory by remember { mutableStateOf(false) }
    var playbackSession by remember { mutableStateOf<TrackSession?>(null) }
    var timelineSession by remember { mutableStateOf<TrackSession?>(null) }

    // ---- 权限闸门（与 MapLibre 版相同流程）----
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
        viewModel.toggleRecording()
    }

    val onToggleRecording: () -> Unit = {
        when {
            state.recording -> viewModel.toggleRecording()
            !RecordingPermissions.hasLocation(context) ||
                    !RecordingPermissions.hasNotifications(context) ->
                permissionLauncher.launch(RecordingPermissions.requestablePermissions())
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
        TrackGlobeView(
            modifier = Modifier.fillMaxSize(),
            externalLocationFixes = TrackRecordingEngine.trackerFixes,
            liveTrackPoints = state.liveTrackPoints,
            liveSmoothPoints = state.liveSmoothPoints,
            liveTrackMedia = state.liveMedia,
            historySegments = state.historySegments,
            hudBottomPadding = 130.dp,   // 避让宿主底栏（按实际调整）
            trackPanelCallbacks = TrackMapCallbacks(
                onToggle = onToggleRecording,
                onPauseToggle = { viewModel.togglePause() },
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
                onOpenHistory = { showTrackHistory = true },
                onOpenDetail = onOpenTrackList,
            ),
        )

        if (showSatelliteStatus) {
            SatelliteStatusScreen(onClose = { showSatelliteStatus = false })
        }
        if (showTrackHistory) {
            TrackHistoryScreen(
                onClose = { showTrackHistory = false },
                onPlay = { playbackSession = it },
                onTimeline = { timelineSession = it },
            )
        }
        playbackSession?.let { session ->
            TrackPlaybackScreen(session = session, onClose = { playbackSession = null })
        }
        timelineSession?.let { session ->
            TrackTimelineScreen(session = session, onClose = { timelineSession = null })
        }
    }
}