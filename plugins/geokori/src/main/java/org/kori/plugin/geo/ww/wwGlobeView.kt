package org.kori.plugin.geo.ww

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import earth.worldwind.WorldWindow
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.navigator.NavigatorEvent
import earth.worldwind.navigator.NavigatorListener
import kotlin.math.abs
import kotlin.math.exp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import org.kori.plugin.geo.location.LocationTracker
import org.kori.plugin.geo.map.CompassProvider
import org.kori.plugin.geo.map.ui.BearingMode
import org.kori.plugin.geo.map.ui.LocationFab
import org.kori.plugin.geo.track.TrackMapCallbacks
import org.kori.plugin.geo.track.TrackRecordingEngine
import org.kori.plugin.geo.track.di.TrackMediaRecord
import org.kori.plugin.geo.track.di.TrackPoint
import org.kori.plugin.geo.track.di.TrackServiceState
import org.kori.plugin.geo.track.ui.TrackRecordingHud

/**
 * WorldWind 地球 Compose 封装（API 已对 worldwind-android 2.1.1 源码逐类核对）。
 *
 * ## 相机模型差异说明
 *
 * WW 无 LookAt 距离概念：camera.set 的 altitude 是**绝对海拔（米）**，跟随高度
 * 直接用 followAltitudeMeters（平地近似成立；高海拔地区可后续接地形采样修正）。
 *
 * ## 四态循环（与 MapLibre 版一致）
 *
 *  第 1 次（关闭中）：打开定位 + 跟随 + 朝北
 *  第 2 次（跟随中）：地图吸附回正北（保持跟随）
 *  第 3 次（跟随中）：罗盘模式——地图随手机朝向旋转
 *  第 4 次（罗盘中）：关闭定位，恢复朝北，回到第 1 态
 *  支线：跟随中用户拖动地图后点击 = 恢复跟随（NavigatorEvent.lastInputEvent 判定）
 */
@Composable
fun wwGlobeView(
    modifier: Modifier = Modifier,
    // ---- 初始相机 ----
    initialCenterLat: Double = 39.909,
    initialCenterLng: Double = 116.397,
    initialAltitudeMeters: Double = 2_000_000.0,   // 初始相机海拔（米）
    // ---- 定位跟随 ----
    externalLocationFixes: SharedFlow<LocationTracker.Fix>? = null,
    followAltitudeMeters: Double = 800.0,          // 跟随时的相机海拔（米）
    showLocationIndicator: Boolean = true,         // 定位蓝点开关
    // ---- 定位按钮 ----
    showLocationButton: Boolean = true,            // 四态循环 FAB
    onLocationButtonLongClick: (() -> Unit)? = null,
    locationButtonAlignment: Alignment = Alignment.BottomEnd,
    locationButtonOffsetX: Dp = 0.dp,
    locationButtonOffsetY: Dp = 0.dp,
    buttonPadding:Dp = 16.dp,
    // ---- 图层控制 ----
    showLayerButton: Boolean = true,
    layerButtonAlignment: Alignment = Alignment.TopEnd,
    layerButtonOffsetX: Dp = 0.dp,
    layerButtonOffsetY: Dp = 0.dp,
    // ---- 轨迹数据（与 MapLibre 版参数同名同义）----
    liveTrackPoints: List<TrackPoint> = emptyList(),
    liveSmoothPoints: List<TrackPoint> = emptyList(),
    liveTrackMedia: List<TrackMediaRecord> = emptyList(),
    historySegments: List<List<TrackPoint>> = emptyList(),
    playbackPoint: TrackPoint? = null,
    // ---- 记录面板 ----
    trackPanelCallbacks: TrackMapCallbacks? = null,
    hudBottomPadding: Dp = 12.dp,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // =============================================================================================
    // 定位/相机状态（四态循环状态机）
    // =============================================================================================
    var locationEnabled by remember { mutableStateOf(showLocationIndicator) }
    var customFollow by remember { mutableStateOf(showLocationIndicator) }
    var bearingMode by remember { mutableStateOf(BearingMode.NORTH) }
    var locateCycleState by remember { mutableIntStateOf(if (showLocationIndicator) 1 else 0) }
    var northResetDone by remember { mutableStateOf(false) }

    val compass = remember { CompassProvider(context) }
    var compassIconDeg by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(bearingMode) {
        if (bearingMode == BearingMode.COMPASS) compass.start() else compass.stop()
    }

    // =============================================================================================
    // WorldWindow（GLSurfaceView 子类，onPause/onResume 继承可用）
    // =============================================================================================
    val worldWindow = remember {
        WorldWindow(context).apply {
            engine.camera.set(
                initialCenterLat.degrees, initialCenterLng.degrees, initialAltitudeMeters,
                AltitudeMode.ABSOLUTE, 0.0.degrees, 0.0.degrees, 0.0.degrees,
            )
        }
    }

    // ---- 图层挂载：标准图层 + 轨迹层 + 定位层 ----
    LaunchedEffect(worldWindow) {
        WwLayerCatalog.ensureStandardLayers(worldWindow)

        val trackLayer = WwLiveTrackLayer.ensureLayers()
        if (WwLayerCatalog.findLayer(worldWindow, WwLiveTrackLayer.LAYER) == null) {
            worldWindow.engine.layers.addLayer(trackLayer)
        }
        val locationLayer = WwLocationIndicator.ensureLayers()
        if (WwLayerCatalog.findLayer(worldWindow, WwLocationIndicator.LAYER) == null) {
            worldWindow.engine.layers.addLayer(locationLayer)
        }
        WwLocationIndicator.setEnabled(showLocationIndicator)
    }

    LaunchedEffect(showLocationIndicator) {
        WwLocationIndicator.setEnabled(showLocationIndicator)
    }

    // ---- 生命周期 ----
    DisposableEffect(lifecycleOwner, worldWindow) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> worldWindow.onResume()
                Lifecycle.Event.ON_PAUSE -> worldWindow.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            compass.stop()
            WwLiveTrackLayer.removeLayers()
            WwLocationIndicator.removeLayers()
            worldWindow.onPause()
        }
    }

    // ---- ★ 用户手势打断跟随（NavigatorEvent.lastInputEvent 非空 = 用户输入触发）----
    LaunchedEffect(worldWindow) {
        runCatching {
            worldWindow.navigatorEvents.addNavigatorListener(object : NavigatorListener {
                override fun onNavigatorEvent(wwd: WorldWindow, event: NavigatorEvent) {
                    if (event.lastInputEvent != null && customFollow) {
                        customFollow = false
                    }
                }
            })
        }
    }

    // =============================================================================================
    // 相机缓动状态（对应 MapLibre 的 camTarget/camCurrent）
    // =============================================================================================
    val camTarget = remember { doubleArrayOf(initialCenterLat, initialCenterLng, 0.0, followAltitudeMeters) }
    val camCurrent = remember { doubleArrayOf(initialCenterLat, initialCenterLng, 0.0, initialAltitudeMeters) }
    val camHasTarget = remember { booleanArrayOf(false) }
    val camNeedsSeed = remember { booleanArrayOf(true) }
    val camLastWrite = remember { doubleArrayOf(Double.NaN, Double.NaN, Double.NaN, Double.NaN) }
    val lastFixHolder = remember { arrayOfNulls<LocationTracker.Fix>(1) }

    // ---- 相机缓动 ticker → engine.camera.set ----
    LaunchedEffect(worldWindow, locationEnabled, customFollow, bearingMode) {
        if (!locationEnabled) return@LaunchedEffect
        val TAU_POS_S = 0.25; val TAU_BRG_S = 0.55; val TAU_ALT_S = 0.50
        var lastNanos = 0L; var idleSinceMs = 0L

        while (true) {
            val nowNanos = withFrameNanos { it }
            if (lastNanos == 0L) { lastNanos = nowNanos; continue }
            val dt = ((nowNanos - lastNanos) / 1e9).toFloat().coerceIn(0f, 0.1f)
            lastNanos = nowNanos
            val nowMs = nowNanos / 1_000_000L

            if (!(customFollow && camHasTarget[0])) {
                if (idleSinceMs == 0L) idleSinceMs = nowMs
                if (nowMs - idleSinceMs > 1_000L) { delay(100); lastNanos = 0L }
                continue
            }
            idleSinceMs = 0L

            if (camNeedsSeed[0]) {
                camCurrent[0] = camTarget[0]
                camCurrent[1] = camTarget[1]
                camCurrent[2] = camTarget[2]
                camCurrent[3] = worldWindow.engine.camera.position.altitude
                for (i in camLastWrite.indices) camLastWrite[i] = Double.NaN
                camNeedsSeed[0] = false
            }

            val kPos = 1.0 - exp(-dt / TAU_POS_S)
            val kBrg = 1.0 - exp(-dt / TAU_BRG_S)
            val kAlt = 1.0 - exp(-dt / TAU_ALT_S)

            camCurrent[0] += (camTarget[0] - camCurrent[0]) * kPos
            camCurrent[1] += (camTarget[1] - camCurrent[1]) * kPos

            // 朝向（最短角路径，跨 0/360 不绕远）
            val targetBrg = when (bearingMode) {
                BearingMode.COMPASS -> {
                    compassIconDeg = compass.headingDeg
                    compass.headingDeg.toDouble()
                }
                else -> camTarget[2]
            }
            val dB = ((targetBrg - camCurrent[2] + 540.0) % 360.0) - 180.0
            camCurrent[2] = (camCurrent[2] + dB * kBrg + 360.0) % 360.0

            camCurrent[3] += (camTarget[3] - camCurrent[3]) * kAlt

            val converged = abs(camCurrent[0] - camLastWrite[0]) > 1e-7 ||
                    abs(camCurrent[1] - camLastWrite[1]) > 1e-7 ||
                    abs(camCurrent[2] - camLastWrite[2]) > 0.05 ||
                    abs(camCurrent[3] - camLastWrite[3]) > 0.5

            if (converged) {
                runCatching {
                    worldWindow.engine.camera.set(
                        camCurrent[0].degrees, camCurrent[1].degrees, camCurrent[3],
                        AltitudeMode.ABSOLUTE, camCurrent[2].degrees, 0.0.degrees, 0.0.degrees,
                    )
                }
                System.arraycopy(camCurrent, 0, camLastWrite, 0, 4)
            }
        }
    }

    // ---- fix 收集：蓝点更新 + 跟随目标值 ----
    LaunchedEffect(worldWindow, externalLocationFixes) {
        val fixes = externalLocationFixes ?: return@LaunchedEffect
        TrackRecordingEngine.ensureLocationTracking(context)
        fixes.collect { fix ->
            lastFixHolder[0] = fix
            WwLocationIndicator.updateFix(fix)

            camTarget[0] = fix.lat
            camTarget[1] = fix.lng
            fix.bearingDeg?.let { camTarget[2] = it.toDouble() }
            camTarget[3] = followAltitudeMeters
            camHasTarget[0] = true
        }
    }

    // ---- 数据 → 轨迹图层 ----
    LaunchedEffect(liveTrackPoints, liveSmoothPoints) {
        WwLiveTrackLayer.updateTrack(liveTrackPoints, liveSmoothPoints)
    }
    LaunchedEffect(liveTrackMedia) { WwLiveTrackLayer.updateMedia(liveTrackMedia) }
    LaunchedEffect(historySegments) { WwLiveTrackLayer.updateHistory(historySegments) }
    LaunchedEffect(playbackPoint) { WwLiveTrackLayer.updatePlayback(playbackPoint) }

    val engineState by TrackRecordingEngine.state.collectAsState()
    LaunchedEffect(engineState.recording) {
        if (!engineState.recording) WwLiveTrackLayer.clearTrack()
    }

    // =============================================================================================
    // ★ 定位按钮四态循环（与 MapLibre 版逐分支对应）
    // =============================================================================================
    val locationFabOnClick: () -> Unit = {
        when {
            // 第 1 次：打开定位 + 跟随 + 朝北
            !locationEnabled -> {
                locationEnabled = true
                customFollow = true
                bearingMode = BearingMode.GPS_BEARING
                locateCycleState = 1
                northResetDone = false
                camNeedsSeed[0] = true
                lastFixHolder[0]?.let { fix ->
                    camTarget[0] = fix.lat
                    camTarget[1] = fix.lng
                    fix.bearingDeg?.let { b -> camTarget[2] = b.toDouble() }
                    camTarget[3] = followAltitudeMeters
                    camHasTarget[0] = true
                }
            }
            // 支线：拖动后恢复跟随（保持当前朝向模式）
            !customFollow -> {
                customFollow = true
                camNeedsSeed[0] = true
                lastFixHolder[0]?.let { fix ->
                    camTarget[0] = fix.lat
                    camTarget[1] = fix.lng
                    camTarget[3] = followAltitudeMeters
                    camHasTarget[0] = true
                }
            }
            // 第 4 次（罗盘中）：关闭定位，恢复朝北
            locateCycleState == 2 -> {
                bearingMode = BearingMode.NORTH
                locateCycleState = 0
                northResetDone = false
                camTarget[2] = 0.0
                locationEnabled = false
                customFollow = false
                camHasTarget[0] = false
            }
            // 第 2 次：地图吸附回正北（保持跟随）
            !northResetDone -> {
                bearingMode = BearingMode.NORTH
                northResetDone = true
                camTarget[2] = 0.0
            }
            // 第 3 次：罗盘模式
            else -> {
                bearingMode = BearingMode.COMPASS
                locateCycleState = 2
            }
        }
    }

    // =============================================================================================
    // UI
    // =============================================================================================
    Box(modifier) {
        AndroidView(
            factory = { worldWindow },
            modifier = Modifier.matchParentSize(),
        )

        // ★ 图层控制
        if (showLayerButton) {
            WwLayerControl(
                worldWindow = worldWindow,
                modifier = Modifier.matchParentSize(),
                alignment = layerButtonAlignment,
                offsetX = layerButtonOffsetX,
                offsetY = layerButtonOffsetY,
            )
        }

        // ★ 定位按钮（复用 geokori 的 LocationFab——纯 Compose，无 MapLibre 依赖）
        if (showLocationButton) {
            LocationFab(
                locationEnabled = locationEnabled,
                bearingMode = bearingMode,
                northResetDone = northResetDone,
                compassIconDeg = compassIconDeg,
                onClick = locationFabOnClick,
                onLongClick = onLocationButtonLongClick,
                modifier = Modifier.matchParentSize(),
                alignment = locationButtonAlignment,
                offsetX = locationButtonOffsetX,
                offsetY = locationButtonOffsetY,
                padding = buttonPadding,
            )
        }

        // ★ 记录面板（复用同一 HUD）
        if (trackPanelCallbacks != null) {
            val state by TrackRecordingEngine.state.collectAsState()
            if (state.recording) {
                TrackRecordingHud(
                    state = TrackServiceState(
                        recording = state.recording,
                        points = state.points,
                        distanceM = state.distanceM,
                        elapsedMs = state.elapsedMs,
                        segments = state.segments,
                        paused = state.paused,
                        currentSpeedMps = state.currentSpeedMps,
                        currentLat = state.currentLat,
                        currentLng = state.currentLng,
                    ),
                    callbacks = trackPanelCallbacks,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = hudBottomPadding),
                    showToggleButton = false,   // 结束走宿主 PUBLISH 按钮
                )
            }
        }
    }
}