package org.kori.plugin.geo.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.cwcc.open.geokori.map.MapRuntime
import org.kori.plugin.geo.location.LocationTracker
import org.kori.plugin.geo.map.sp.SatelliteSupport
import org.kori.plugin.geo.map.sp.TerrainSupport
import org.kori.plugin.geo.map.ui.BaseMapOption
import org.kori.plugin.geo.map.ui.LayerEntry
import org.kori.plugin.geo.map.ui.MapLayersControl
import org.kori.plugin.geo.map.ui.OverlayToggle
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.HillshadeLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import java.io.File
import kotlin.math.abs
import kotlin.math.exp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import org.kori.plugin.geo.map.ui.BearingMode
import org.kori.plugin.geo.map.ui.LocationFab
import org.kori.plugin.geo.track.LiveTrackLayer
import org.kori.plugin.geo.track.TrackMapCallbacks
import org.kori.plugin.geo.track.di.TrackMediaRecord
import org.kori.plugin.geo.track.di.TrackPoint
import org.kori.plugin.geo.track.TrackRecordingEngine
import org.kori.plugin.geo.track.TrackRecordingHud
import org.kori.plugin.geo.track.di.TrackServiceState
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
// =============================================================================================
// 常量
// =============================================================================================

private val BUILTIN_SATELLITE_LAYERS = listOf("天地图卫星影像", "天地图注记")
private const val BUILTIN_SATELLITE_ANCHOR = "天地图注记"

private const val BASE_MAP_SATELLITE = "satellite"
private const val BASE_MAP_TERRAIN = "terrain"

private const val OVERLAY_CONTOUR = "contour"

// =============================================================================================
// 相机缓动参数
// =============================================================================================

private object CameraSmoothing {
    const val TAU_POS_S = 0.25f
    const val TAU_BRG_S = 0.55f
    const val TAU_ZOOM_S = 0.50f

    const val CONVERGED_DEG = 1e-6
    const val CONVERGED_BRG = 0.05
    const val CONVERGED_ZOOM = 0.001

    const val MAX_DT_S = 0.1f

    val IDLE_GRACE: Duration = 1.seconds
    val IDLE_TICK: Duration = 100.milliseconds
}

// =============================================================================================
// 主 Composable
// =============================================================================================

/**
 * MapLibre 地图 Compose 封装。
 *
 * ## 位置源（三种，优先级从高到低）
 *
 *  1. [externalLocationFixes]：外部的 `SharedFlow<Fix>`（Engine 单例模式）
 *  2. [externalLocationTracker]：外部持有的 `LocationTracker` 实例
 *  3. 内部创建：默认
 *
 * ## 实时轨迹
 *
 * 通过 [liveTrackPoints] / [liveSmoothPoints] / [liveTrackMedia] 三个参数绘制，
 * 使用 [LiveTrackLayer] 在样式加载时自动注册图层。
 *
 * ## 记录面板（可选）
 *
 * 传入 [trackPanelCallbacks] 后，地图会在 [TrackRecordingEngine] 的 `recording=true`
 * 时**自动叠加** [TrackRecordingPanel]，记录结束时自动隐藏。
 *
 * 不传 `trackPanelCallbacks`（默认 null）→ 纯地图，零开销。
 *
 * ## Bug 修复
 *
 *  · B6：相机 ticker 里实时读 `map.cameraPosition.tilt`
 */
@Composable
fun MapLibreMapView(
    modifier: Modifier = Modifier,
    config: MapConfig = MapConfig.Default,
    onUserLocationChange: ((Boolean) -> Unit)? = null,
    onSatelliteChange: ((Boolean) -> Unit)? = null,
    onHillshadeChange: ((Boolean) -> Unit)? = null,
    onContourChange: ((Boolean) -> Unit)? = null,
    onLocationFix: ((LocationTracker.Fix) -> Unit)? = null,

    /**
     * 地图单击回调（★ 不消费点击事件）。
     *
     *  · 返回点击点的经纬度 [LatLng] 与屏幕位置 [DpOffset]（相对 MapView 左上角，dp 单位）
     *  · 内部监听器**永远返回 false**，事件继续传递给 MapLibre 的图层/标注点击链路，
     *    不影响 `queryRenderedFeatures` 等图层点击功能
     *  · 典型用法：发送广播通知其它界面处理
     */
    onMapClick: ((position: LatLng, screenPosition: DpOffset) -> Unit)? = null,

    /**
     * 定位按钮**长按**回调（可选）。
     *
     * 典型用法：弹出卫星状态屏（见 [org.kori.plugin.geo.map.SatelliteStatusScreen]）。
     * null = 长按无附加功能。短按行为（四态循环）不受影响。
     */
    onLocationButtonLongClick: (() -> Unit)? = null,
    trackRecording: Boolean = false,
    onTrackSaved: ((File) -> Unit)? = null,
    onTrackProgress: ((kept: Int, rejected: Int) -> Unit)? = null,
    externalLocationTracker: LocationTracker? = null,
    externalLocationFixes: SharedFlow<LocationTracker.Fix>? = null,
    liveTrackPoints: List<TrackPoint> = emptyList(),
    liveSmoothPoints: List<TrackPoint> = emptyList(),
    liveTrackMedia: List<TrackMediaRecord> = emptyList(),

    /**
     * 历史轨迹叠加层（历史浏览）。
     *
     * 每个元素为一条轨迹点序列（通常一个会话一条），以灰色线绘制在实时轨迹之下。
     * 传空列表 = 清除叠加层。由 [TrackRecordingEngine.loadHistoryOnMap] 驱动。
     */
    historySegments: List<List<TrackPoint>> = emptyList(),

    /**
     * 轨迹回放标记点：非 null 时地图显示亮青色回放标记（配合 [org.kori.plugin.geo.track.TrackPlaybackBar]）。
     */
    playbackPoint: TrackPoint? = null,

    // ★ 新增：记录面板回调。非 null 时启用内嵌记录面板。
    trackPanelCallbacks: TrackMapCallbacks? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    // =============================================================================================
    // 内部状态
    // =============================================================================================
    var locationEnabled by remember { mutableStateOf(config.showUserLocation) }
    var selectedBaseMap by remember {
        mutableStateOf(
            when {
                config.satelliteOn -> BASE_MAP_SATELLITE
                config.hillshadeOn -> BASE_MAP_TERRAIN
                else -> BASE_MAP_SATELLITE
            },
        )
    }
    var contourEnabled by remember { mutableStateOf(config.contourOn) }

    var layerOverrides by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var availableLayers by remember { mutableStateOf<List<LayerEntry>>(emptyList()) }

    val satelliteEnabled = selectedBaseMap == BASE_MAP_SATELLITE
    val hillshadeEnabled = selectedBaseMap == BASE_MAP_TERRAIN

    // =============================================================================================
    // config 同步
    // =============================================================================================
    LaunchedEffect(config.showUserLocation) {
        if (config.showUserLocation != locationEnabled) locationEnabled = config.showUserLocation
    }
    LaunchedEffect(config.satelliteOn, config.hillshadeOn) {
        val want = when {
            config.satelliteOn -> BASE_MAP_SATELLITE
            config.hillshadeOn -> BASE_MAP_TERRAIN
            else -> return@LaunchedEffect
        }
        if (want != selectedBaseMap) selectedBaseMap = want
    }
    LaunchedEffect(config.contourOn) {
        if (config.contourOn != contourEnabled) contourEnabled = config.contourOn
    }

    // =============================================================================================
    // 位置权限
    // =============================================================================================
    var locationPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED,
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        locationPermissionGranted = granted
        if (!granted) {
            locationEnabled = false
            onUserLocationChange?.invoke(false)
        }
    }

    // =============================================================================================
    // MapView 实例
    // =============================================================================================
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(null) }
    }

    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleRef by remember { mutableStateOf<Style?>(null) }

    // =============================================================================================
    // 自定义管线：tracker 实例选择
    // =============================================================================================
    val locationTracker = remember(
        config.useCustomLocationPipeline,
        externalLocationTracker,
        externalLocationFixes,
    ) {
        if (!config.useCustomLocationPipeline) return@remember null
        if (externalLocationTracker != null) return@remember externalLocationTracker
        if (externalLocationFixes != null) return@remember null
        LocationTracker(context)
    }

    var customFollow by remember { mutableStateOf(config.showUserLocation) }
    var customCollectJob by remember { mutableStateOf<Job?>(null) }

    // =========================================================================================
    // ★ 定位按钮四态循环（自定义管线）
    // =========================================================================================
    /** 相机朝向模式：定位按钮循环驱动（默认朝北）。 */
    var bearingMode by remember { mutableStateOf(BearingMode.NORTH) }

    /** 循环主状态：0=关闭 1=跟随朝北 2=罗盘模式。 */
    var locateCycleState by remember { mutableIntStateOf(0) }

    /** 状态 1 内部子标记：区分"刚进入"与"已点过恢复朝北"，决定下次点击是否进罗盘。 */
    var northResetDone by remember { mutableStateOf(false) }

    /** 罗盘朝向数据源（仅 COMPASS 模式运行，省传感器耗电）。 */
    val compass = remember { CompassProvider(context) }

    /** 罗盘模式下定位按钮箭头图标的旋转角（= 手机朝向，每帧更新）。 */
    var compassIconDeg by remember { mutableFloatStateOf(0f) }

    // 罗盘随模式启停
    LaunchedEffect(bearingMode) {
        if (bearingMode == BearingMode.COMPASS) compass.start() else compass.stop()
    }

    // onMapClick 的 holder：getMapAsync 闭包只注册一次监听器，
    // 通过 holder 读取"当前"回调，避免捕获到旧的 lambda 引用
    val onMapClickHolder = remember { arrayOfNulls<((LatLng, DpOffset) -> Unit)?>(1) }
    onMapClickHolder[0] = onMapClick
    val lastFixHolder = remember { arrayOfNulls<LocationTracker.Fix>(1) }

    val trackDir = remember {
        File(context.filesDir, config.trackStorageDirName).apply { mkdirs() }
    }

    // =============================================================================================
    // 相机缓动状态
    // =============================================================================================
    val camTarget = remember { doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0) }
    val camCurrent = remember { doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0) }
    val camLastWrite = remember {
        doubleArrayOf(Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN)
    }
    val camNeedsSeed = remember { booleanArrayOf(true) }
    val camHasTarget = remember { booleanArrayOf(false) }

    // =============================================================================================
    // 相机缓动 ticker
    // =============================================================================================
    LaunchedEffect(
        config.useCustomLocationPipeline,
        bearingMode,
        locationEnabled,
        customFollow,
        mapRef,
        styleRef,
    ) {
        if (!config.useCustomLocationPipeline) return@LaunchedEffect
        if (!locationEnabled) return@LaunchedEffect
        val map = mapRef ?: return@LaunchedEffect

        var lastNanos = 0L
        var idleSinceMs = 0L

        while (true) {
            val nowNanos = withFrameNanos { it }
            if (lastNanos == 0L) {
                lastNanos = nowNanos
                continue
            }
            val dt = ((nowNanos - lastNanos) / 1e9).toFloat()
                .coerceIn(0f, CameraSmoothing.MAX_DT_S)
            lastNanos = nowNanos

            val nowMs = nowNanos / 1_000_000L
            val hasWork = customFollow && camHasTarget[0]

            if (!hasWork) {
                if (idleSinceMs == 0L) idleSinceMs = nowMs
                if (nowMs - idleSinceMs > CameraSmoothing.IDLE_GRACE.inWholeMilliseconds) {
                    delay(CameraSmoothing.IDLE_TICK)
                    lastNanos = 0L
                }
                continue
            }
            idleSinceMs = 0L

            if (camNeedsSeed[0]) {
                val cp = map.cameraPosition
                camCurrent[0] = cp.target?.latitude ?: camTarget[0]
                camCurrent[1] = cp.target?.longitude ?: camTarget[1]
                camCurrent[2] = cp.bearing
                camCurrent[3] = if (cp.zoom > 1.0) cp.zoom else config.customLocationTrackingZoom
                camCurrent[4] = cp.tilt
                for (i in camLastWrite.indices) camLastWrite[i] = Double.NaN
                camNeedsSeed[0] = false
            }

            val kPos = 1.0 - exp(-dt / CameraSmoothing.TAU_POS_S)
            val kBrg = 1.0 - exp(-dt / CameraSmoothing.TAU_BRG_S)
            val kZoom = 1.0 - exp(-dt / CameraSmoothing.TAU_ZOOM_S)

            camCurrent[0] += (camTarget[0] - camCurrent[0]) * kPos
            camCurrent[1] += (camTarget[1] - camCurrent[1]) * kPos

            when (bearingMode) {
                BearingMode.GPS_BEARING -> {
                    // 转向模式：相机朝向缓动到运动方位角（导航风格）
                    val dB = ((camTarget[2] - camCurrent[2] + 540.0) % 360.0) - 180.0
                    camCurrent[2] = (camCurrent[2] + dB * kBrg + 360.0) % 360.0
                }
                BearingMode.COMPASS -> {
                    // 罗盘模式：地图缓动到手机顶部朝向（最短角路径，跨 0/360 不绕远）
                    val target = compass.headingDeg.toDouble()
                    compassIconDeg = compass.headingDeg
                    val dB = ((target - camCurrent[2] + 540.0) % 360.0) - 180.0
                    camCurrent[2] = (camCurrent[2] + dB * kBrg + 360.0) % 360.0
                }
                BearingMode.NORTH -> {
                    // 朝北但不强制：每帧采纳相机当前 bearing（与 tilt 同理）。
                    // 初始为 0（朝北）；用户手势旋转地图后保持用户角度，不纠正回北。
                    camCurrent[2] = map.cameraPosition.bearing.toDouble()
                }
            }

            camCurrent[3] += (config.customLocationTrackingZoom - camCurrent[3]) * kZoom

            // B6 修复：实时读取用户的倾斜
            camCurrent[4] = map.cameraPosition.tilt

            val dLat = abs(camCurrent[0] - camLastWrite[0])
            val dLng = abs(camCurrent[1] - camLastWrite[1])
            val dB2 = abs(camCurrent[2] - camLastWrite[2])
            val dZ = abs(camCurrent[3] - camLastWrite[3])
            val dT = abs(camCurrent[4] - camLastWrite[4])

            val converged = dLat.isNaN() ||
                    dLat > CameraSmoothing.CONVERGED_DEG ||
                    dLng > CameraSmoothing.CONVERGED_DEG ||
                    dB2 > CameraSmoothing.CONVERGED_BRG ||
                    dZ > CameraSmoothing.CONVERGED_ZOOM ||
                    dT > CameraSmoothing.CONVERGED_BRG

            if (converged) {
                runCatching {
                    map.moveCamera(
                        CameraUpdateFactory.newCameraPosition(
                            CameraPosition.Builder()
                                .target(LatLng(camCurrent[0], camCurrent[1]))
                                .bearing(camCurrent[2])
                                .zoom(camCurrent[3])
                                .tilt(camCurrent[4])
                                .build(),
                        ),
                    )
                }
                System.arraycopy(camCurrent, 0, camLastWrite, 0, camCurrent.size)
            }
        }
    }

    // =============================================================================================
    // 生命周期
    // =============================================================================================
    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            customCollectJob?.cancel()
            customCollectJob = null

            val isInternal = externalLocationTracker == null && externalLocationFixes == null
            if (isInternal) {
                runCatching { locationTracker?.stop() }
                runCatching { locationTracker?.destroy() }
            }
            runCatching { mapRef?.let { safeDeactivateLocation(it) } }
            compass.stop()
            mapView.onStop()
            mapView.onDestroy()
            MapRuntime.detach()
        }
    }

    // =============================================================================================
    // 地图初始化
    // =============================================================================================
    LaunchedEffect(mapView) {
        mapView.getMapAsync { map ->
            mapRef = map

            // =================================================================================
            // ★ 地图单击 → 广播/回调（不消费事件，图层点击照常工作）
            // =================================================================================
            // 关键：onMapClick 必须返回 Boolean——
            //   true  = 消费事件，后续注册的监听器（图层/标注点击）收不到
            //   false = 继续传递，不影响任何已有点击功能
            // 这里永远返回 false，只做"通知"。
            map.addOnMapClickListener { point ->
                onMapClickHolder[0]?.let { cb ->
                    // LatLng → 屏幕像素（相对 MapView 左上角）
                    val screenPt = map.projection.toScreenLocation(point)
                    // 像素 → dp
                    val density = context.resources.displayMetrics.density
                    cb(
                        point,
                        DpOffset(
                            Dp(screenPt.x / density),
                            Dp(screenPt.y / density),
                        ),
                    )
                }
                false
            }

            map.addOnCameraMoveStartedListener { reason ->
                if (reason != MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                    return@addOnCameraMoveStartedListener
                }
                if (!config.useCustomLocationPipeline) {
                    runCatching {
                        val lc = map.locationComponent
                        if (lc.isLocationComponentActivated &&
                            lc.cameraMode == CameraMode.TRACKING
                        ) {
                            safeSetCameraMode(map, CameraMode.NONE)
                        }
                    }
                } else {
                    if (config.customLocationDropFollowOnPan && customFollow) {
                        customFollow = false
                    }
                }
            }

            val styleBuilder = buildStyleWithDemInjection(
                context = context,
                styleUri = config.styleUrl,
                demTiles = config.demTiles,
                demMaxZoom = config.demMaxZoom,
            )

            map.setStyle(styleBuilder) { style ->
                styleRef = style

                map.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(config.initialCenterLat, config.initialCenterLng))
                    .zoom(config.initialZoom)
                    .build()

                applyAllLayers(
                    style = style,
                    config = config,
                    satelliteOn = satelliteEnabled,
                    hillshadeOn = hillshadeEnabled,
                    contourOn = contourEnabled,
                )

                layerOverrides.forEach { (id, visible) ->
                    setLayerVisible(style, id, visible)
                }

                availableLayers = collectSwitchableLayers(style)

                // 实时轨迹图层
                LiveTrackLayer.ensureLayers(style)

                MapRuntime.attach(style, map)
            }
        }
    }

    // =============================================================================================
    // 图层开关变化
    // =============================================================================================
    LaunchedEffect(
        satelliteEnabled, hillshadeEnabled, contourEnabled,
        config.satelliteTiles, config.satelliteFallbackOn, config.satelliteFallbackTiles,
        config.hillshadeExaggeration,
        config.contourUrl, config.contourSourceLayer,
        config.contourMinZoom, config.contourMaxZoom,
        config.darkTheme,
        styleRef,
    ) {
        val style = styleRef ?: return@LaunchedEffect
        runCatching {
            applyAllLayers(
                style = style,
                config = config,
                satelliteOn = satelliteEnabled,
                hillshadeOn = hillshadeEnabled,
                contourOn = contourEnabled,
            )
            layerOverrides.forEach { (id, visible) ->
                setLayerVisible(style, id, visible)
            }
            availableLayers = collectSwitchableLayers(style)
        }
    }

    // =============================================================================================
    // 实时轨迹绘制
    // =============================================================================================
    LaunchedEffect(styleRef, liveTrackPoints, liveSmoothPoints) {
        val style = styleRef ?: return@LaunchedEffect
        if (liveTrackPoints.isEmpty() && liveSmoothPoints.isEmpty()) {
            LiveTrackLayer.clearTrack(style)
        } else {
            LiveTrackLayer.updateTrack(style, liveTrackPoints, liveSmoothPoints)
        }
    }

    LaunchedEffect(styleRef, liveTrackMedia) {
        val style = styleRef ?: return@LaunchedEffect
        LiveTrackLayer.updateMedia(style, liveTrackMedia)
    }

    // =============================================================================================
    // 历史轨迹叠加层
    // =============================================================================================
    LaunchedEffect(styleRef, historySegments) {
        val style = styleRef ?: return@LaunchedEffect
        if (historySegments.isEmpty()) {
            LiveTrackLayer.clearHistory(style)
        } else {
            LiveTrackLayer.updateHistory(style, historySegments)
        }
    }

    // =============================================================================================
    // 轨迹回放标记
    // =============================================================================================
    LaunchedEffect(styleRef, playbackPoint) {
        val style = styleRef ?: return@LaunchedEffect
        LiveTrackLayer.updatePlayback(style, playbackPoint)
    }

    // =============================================================================================
    // 位置组件 — MapLibre 默认管线
    // =============================================================================================
    LaunchedEffect(
        config.useCustomLocationPipeline,
        locationEnabled,
        locationPermissionGranted,
        styleRef,
        mapRef,
    ) {
        if (config.useCustomLocationPipeline) return@LaunchedEffect

        val map = mapRef ?: return@LaunchedEffect
        val style = styleRef ?: return@LaunchedEffect

        if (!locationEnabled) {
            runCatching { safeEnableLocation(map, false) }
            return@LaunchedEffect
        }

        if (!locationPermissionGranted) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
            return@LaunchedEffect
        }

        runCatching {
            safeActivateLocationComponent(map, context, style)
            safeEnableLocation(map, true)
            safeSetCameraMode(map, CameraMode.TRACKING)
        }
    }

    // =============================================================================================
    // 位置组件 — 自定义管线
    // =============================================================================================
    LaunchedEffect(
        config.useCustomLocationPipeline,
        locationEnabled,
        locationPermissionGranted,
        styleRef,
        mapRef,
        externalLocationFixes,
    ) {
        if (!config.useCustomLocationPipeline) return@LaunchedEffect

        if (!locationEnabled) {
            customCollectJob?.cancel()
            customCollectJob = null
            customFollow = false
            camNeedsSeed[0] = true
            camHasTarget[0] = false
            // ★ 同步关闭定位蓝点（若已激活）
            mapRef?.let { safeEnableLocation(it, false) }
            return@LaunchedEffect
        }

        if (!locationPermissionGranted) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
            return@LaunchedEffect
        }

        val styleNow = styleRef ?: return@LaunchedEffect
        val map = mapRef ?: return@LaunchedEffect

        // ★ 自定义管线下也要渲染定位蓝点。
        // 激活 LocationComponent 但 useDefaultLocationEngine(false)——不用它的 GPS 引擎；
        // cameraMode = NONE——相机由上方缓动 ticker 驱动；
        // 位置在 collect 里通过 forceLocationUpdate() 喂入（见 pushFixToLocationComponent）。
        // 这样蓝点显示的是滤波后的位置，与相机/轨迹完全一致，且不会双重定位耗电。
        runCatching {
            val lc = map.locationComponent
            if (!lc.isLocationComponentActivated) {
                val opts = LocationComponentActivationOptions
                    .builder(context, styleNow)
                    .useDefaultLocationEngine(false)
                    .locationComponentOptions(
                        LocationComponentOptions.builder(context)
                            .pulseEnabled(true)
                            .build(),
                    )
                    .build()
                lc.activateLocationComponent(opts)
                lc.renderMode = RenderMode.NORMAL
                lc.cameraMode = CameraMode.NONE
            }
            lc.isLocationComponentEnabled = true
        }

        customFollow = true
        camNeedsSeed[0] = true

        customCollectJob?.cancel()

        if (externalLocationFixes != null) {
            // ★ 默认打开用户定位：通知引擎幂等启动共享 tracker。
            // 未录制时 fix 也持续流动（蓝点/相机即开即有），
            // 录制开始后由同一 tracker 供数，避免 GPS 冷启动变慢。
            TrackRecordingEngine.ensureLocationTracking(context)

            customCollectJob = scope.launch {
                externalLocationFixes.collect { fix ->
                    lastFixHolder[0] = fix
                    onLocationFix?.invoke(fix)

                    // ★ 把滤波后的 fix 喂给 LocationComponent 画蓝点
                    pushFixToLocationComponent(map, fix)

                    camTarget[0] = fix.lat
                    camTarget[1] = fix.lng
                    fix.bearingDeg?.let { camTarget[2] = it.toDouble() }
                    camTarget[3] = config.customLocationTrackingZoom
                    camHasTarget[0] = true
                }
            }
            return@LaunchedEffect
        }

        val tracker = locationTracker ?: return@LaunchedEffect
        customCollectJob = scope.launch {
            tracker.fixes.collect { fix ->
                lastFixHolder[0] = fix
                onLocationFix?.invoke(fix)

                // ★ 把滤波后的 fix 喂给 LocationComponent 画蓝点
                pushFixToLocationComponent(map, fix)

                if (tracker.isRecording) {
                    onTrackProgress?.invoke(
                        tracker.recordedPoints(),
                        tracker.rejectedPoints(),
                    )
                }

                camTarget[0] = fix.lat
                camTarget[1] = fix.lng
                fix.bearingDeg?.let { camTarget[2] = it.toDouble() }
                camTarget[3] = config.customLocationTrackingZoom
                camHasTarget[0] = true
            }
        }
    }

    // =============================================================================================
    // 轨迹记录（ViewModel 内嵌模式）
    // =============================================================================================
    LaunchedEffect(config.useCustomLocationPipeline, trackRecording, locationEnabled) {
        if (!config.useCustomLocationPipeline) return@LaunchedEffect
        val tracker = locationTracker ?: return@LaunchedEffect

        if (trackRecording && locationEnabled && locationPermissionGranted) {
            if (!tracker.isRecording) {
                tracker.startRecording(trackDir)
            }
        } else {
            if (tracker.isRecording) {
                val file = tracker.stopRecording()
                if (file != null) onTrackSaved?.invoke(file)
            }
        }
    }

    // =============================================================================================
    // UI
    // =============================================================================================
    Box(modifier) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.matchParentSize(),
        )

        // ★ 定位按钮点击逻辑（四态循环 / 默认管线分流），由 LocationFab 组件分发
        val locationFabOnClick: () -> Unit = {
            if (config.useCustomLocationPipeline) {
                // ★★ 定位按钮四态循环 ★★
                //  第 1 次（关闭中）：打开定位 + 跟随 + 朝北
                //  第 2 次（跟随中）：地图吸附回正北（保持跟随）
                //  第 3 次（跟随中）：罗盘模式——地图随手机朝向旋转
                //  第 4 次（罗盘中）：关闭定位，恢复朝北，回到第 1 态
                // 支线：用户平移地图后点击 = 恢复跟随（保持当前朝向模式，
                //       不打乱循环计数）
                when {
                    !locationEnabled -> {
                        locationEnabled = true
                        onUserLocationChange?.invoke(true)
                        customFollow = true
                        camNeedsSeed[0] = true
                        bearingMode = if (config.customLocationRotateToBearing) {
                            BearingMode.GPS_BEARING
                        } else {
                            BearingMode.NORTH
                        }
                        locateCycleState = 1
                        northResetDone = false
                        lastFixHolder[0]?.let { fix ->
                            camTarget[0] = fix.lat
                            camTarget[1] = fix.lng
                            fix.bearingDeg?.let { camTarget[2] = it.toDouble() }
                            camTarget[3] = config.customLocationTrackingZoom
                            camHasTarget[0] = true
                        }
                    }
                    !customFollow -> {
                        // 平移后恢复跟随，保持当前朝向模式
                        customFollow = true
                        camNeedsSeed[0] = true
                        lastFixHolder[0]?.let { fix ->
                            camTarget[0] = fix.lat
                            camTarget[1] = fix.lng
                            camTarget[3] = config.customLocationTrackingZoom
                            camHasTarget[0] = true
                        }
                    }
                    locateCycleState == 2 -> {
                        // 第 4 次点击：关闭定位，恢复朝北
                        bearingMode = BearingMode.NORTH
                        locateCycleState = 0
                        northResetDone = false
                        camCurrent[2] = 0.0
                        camLastWrite[2] = Double.NaN
                        runCatching {
                            mapRef?.moveCamera(CameraUpdateFactory.bearingTo(0.0))
                        }
                        locationEnabled = false
                        onUserLocationChange?.invoke(false)
                        customFollow = false
                    }
                    !northResetDone -> {
                        // 第 2 次点击：地图吸附回正北（保持跟随）
                        bearingMode = BearingMode.NORTH
                        northResetDone = true
                        camCurrent[2] = 0.0
                        camLastWrite[2] = Double.NaN
                        runCatching {
                            mapRef?.moveCamera(CameraUpdateFactory.bearingTo(0.0))
                        }
                    }
                    else -> {
                        // 第 3 次点击：罗盘模式
                        bearingMode = BearingMode.COMPASS
                        locateCycleState = 2
                    }
                }
            } else {
                handleDefaultLocationButton(
                    map = mapRef,
                    locationEnabled = locationEnabled,
                    setLocationEnabled = { locationEnabled = it },
                    onUserLocationChange = onUserLocationChange,
                )
            }
        }

        // ★ 定位按钮（独立组件：单击四态循环 + 长按卫星状态）
        if (config.showLocationButton) {
            LocationFab(
                locationEnabled = locationEnabled,
                bearingMode = bearingMode,
                northResetDone = northResetDone,
                compassIconDeg = compassIconDeg,
                onClick = locationFabOnClick,
                onLongClick = onLocationButtonLongClick,
                modifier = Modifier.matchParentSize(),
                alignment = config.locationButtonAlignment,
                offsetX = config.locationButtonOffsetX,
                offsetY = config.locationButtonOffsetY,
                padding = config.buttonPadding,
            )
        }

        // 图层控制
        if (config.showLayerButton) {
            val baseMapOptions = remember {
                listOf(
                    BaseMapOption(BASE_MAP_SATELLITE, "卫星影像"),
                    BaseMapOption(BASE_MAP_TERRAIN, "地形阴影"),
                )
            }
            val overlays = remember(contourEnabled) {
                listOf(
                    OverlayToggle(OVERLAY_CONTOUR, "等高线", contourEnabled),
                )
            }

            MapLayersControl(
                modifier = Modifier.matchParentSize(),
                alignment = config.layerButtonAlignment,
                padding = config.buttonPadding,
                offsetX = config.layerButtonOffsetX,
                offsetY = config.layerButtonOffsetY,

                baseMapOptions = baseMapOptions,
                selectedBaseMapId = selectedBaseMap,
                onBaseMapSelect = { id ->
                    if (id != selectedBaseMap) {
                        selectedBaseMap = id
                        when (id) {
                            BASE_MAP_SATELLITE -> {
                                onSatelliteChange?.invoke(true)
                                onHillshadeChange?.invoke(false)
                            }
                            BASE_MAP_TERRAIN -> {
                                onSatelliteChange?.invoke(false)
                                onHillshadeChange?.invoke(true)
                            }
                        }
                    }
                },

                overlays = overlays,
                onOverlayToggle = { id, enabled ->
                    when (id) {
                        OVERLAY_CONTOUR -> {
                            contourEnabled = enabled
                            onContourChange?.invoke(enabled)
                        }
                    }
                },

                layers = availableLayers,
                layerFilter = config.layerFilter,
                onLayerVisibilityChange = { id, visible ->
                    val style = styleRef
                    if (style != null) {
                        setLayerVisible(style, id, visible)
                        layerOverrides = layerOverrides + (id to visible)
                        availableLayers = availableLayers.map {
                            if (it.id == id) it.copy(visible = visible) else it
                        }
                    }
                },
                onLayersReset = {
                    val style = styleRef
                    if (style != null) {
                        layerOverrides.keys.forEach { id ->
                            setLayerVisible(style, id, true)
                        }
                        layerOverrides = emptyMap()
                        availableLayers = collectSwitchableLayers(style)
                    }
                },
            )
        }

        // ★ 记录面板（可选）——仅 callbacks 非 null 时启用
        if (trackPanelCallbacks != null) {
            RecordingPanelOverlay(
                callbacks = trackPanelCallbacks,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        start = 12.dp,
                        end = 12.dp,
                        // ★ 可被宿主底部导航栏遮挡：通过 config.trackPanelBottomPadding 抬高
                        bottom = config.trackPanelBottomPadding,
                    ),
            )
        }
    }
}

// =============================================================================================
// ★ 记录面板覆盖层（内部 Composable）
// =============================================================================================

/**
 * 记录面板覆盖层。
 *
 * 订阅 [TrackRecordingEngine.state]，仅在 `recording = true` 时渲染
 * [TrackRecordingPanel]。停止记录后自动消失。
 *
 * ## 为什么是独立 Composable？
 *
 *  · `collectAsState()` 需要在稳定的 Composable 里调用
 *  · 只有 [MapLibreMapView] 收到 `trackPanelCallbacks != null` 时才创建此 Composable
 *  · `callbacks == null` 时不订阅 Engine，零开销
 */
@Composable
private fun RecordingPanelOverlay(
    callbacks: TrackMapCallbacks,
    modifier: Modifier = Modifier,
) {
    val state by TrackRecordingEngine.state.collectAsState()

    // ★ 修复：面板在所有状态都渲染（含"未记录"）。
    // 原来 if (!state.recording) return 导致开始按钮永远看不到——
    // 面板只在记录中才出现，而开始按钮恰恰只在未记录时才需要。


    // ★ 沉浸式 HUD（与地图融合的玻璃拟态悬浮界面，替代卡片面板）
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
        callbacks = callbacks,
        modifier = modifier,
    )
}

// =============================================================================================
// 辅助函数
// =============================================================================================

@SuppressLint("MissingPermission")
private fun handleDefaultLocationButton(
    map: MapLibreMap?,
    locationEnabled: Boolean,
    setLocationEnabled: (Boolean) -> Unit,
    onUserLocationChange: ((Boolean) -> Unit)?,
) {
    val lc = map?.locationComponent
    val activated = lc?.isLocationComponentActivated == true
    val isTracking = activated &&
            lc!!.isLocationComponentEnabled &&
            lc.cameraMode == CameraMode.TRACKING

    when {
        !locationEnabled -> {
            setLocationEnabled(true)
            onUserLocationChange?.invoke(true)
            if (activated) {
                runCatching {
                    map?.let { m ->
                        safeEnableLocation(m, true)
                        safeSetCameraMode(m, CameraMode.TRACKING)
                    }
                }
            }
        }
        isTracking -> {
            setLocationEnabled(false)
            onUserLocationChange?.invoke(false)
        }
        else -> {
            runCatching {
                map?.let { safeSetCameraMode(it, CameraMode.TRACKING) }
            }
        }
    }
}

/**
 * 把自定义管线过滤后的 [fix] 推给 MapLibre LocationComponent，驱动定位蓝点。
 *
 * 前提：LocationComponent 已激活且 enabled（见自定义管线 LaunchedEffect 中的激活逻辑），
 * 否则静默跳过。蓝点因此显示的是滤波后的位置，与相机/轨迹完全一致。
 */
@SuppressLint("MissingPermission")
private fun pushFixToLocationComponent(map: MapLibreMap, fix: LocationTracker.Fix) {
    val lc = map.locationComponent
    if (!lc.isLocationComponentActivated || !lc.isLocationComponentEnabled) return
    runCatching {
        val loc = android.location.Location("vela-custom-pipeline").apply {
            latitude = fix.lat
            longitude = fix.lng
            fix.bearingDeg?.let { bearing = it }
            fix.accuracyM?.let { accuracy = it }
            time = fix.timestampMs
        }
        lc.forceLocationUpdate(loc)
    }
}

private fun collectSwitchableLayers(style: Style): List<LayerEntry> {
    return style.layers.mapNotNull { layer ->
        if (layer.id.startsWith("vela-")) return@mapNotNull null
        if (layer.id in BUILTIN_SATELLITE_LAYERS) return@mapNotNull null

        val type = when (layer) {
            is SymbolLayer -> "symbol"
            is LineLayer -> "line"
            is FillLayer -> "fill"
            is RasterLayer -> "raster"
            is CircleLayer -> "circle"
            is HillshadeLayer -> "hillshade"
            is FillExtrusionLayer -> "fill-extrusion"
            else -> return@mapNotNull null
        }

        LayerEntry(
            id = layer.id,
            type = type,
            visible = layer.visibility.value == Property.VISIBLE,
        )
    }
}

private fun setLayerVisible(style: Style, id: String, visible: Boolean) {
    runCatching {
        style.getLayer(id)?.setProperties(
            PropertyFactory.visibility(if (visible) Property.VISIBLE else Property.NONE),
        )
    }
}

private fun buildStyleWithDemInjection(
    context: Context,
    styleUri: String,
    demTiles: List<String>,
    demMaxZoom: Float,
): Style.Builder {
    val json: String? = runCatching {
        when {
            styleUri.startsWith("asset://") -> {
                context.assets.open(styleUri.removePrefix("asset://"))
                    .bufferedReader().use { it.readText() }
            }
            styleUri.startsWith("file://") -> {
                File(styleUri.removePrefix("file://")).readText()
            }
            styleUri.startsWith("http://") || styleUri.startsWith("https://") -> {
                java.net.URL(styleUri).openStream()
                    .bufferedReader().use { it.readText() }
            }
            else -> null
        }
    }.getOrNull()

    return if (json != null) {
        val modifiedJson = TerrainSupport.injectDemSourceIntoStyleJson(
            json = json,
            demTiles = demTiles,
            maxZoom = demMaxZoom,
        )
        Style.Builder().fromJson(modifiedJson)
    } else {
        Style.Builder().fromUri(styleUri)
    }
}

private fun applyAllLayers(
    style: Style,
    config: MapConfig,
    satelliteOn: Boolean,
    hillshadeOn: Boolean,
    contourOn: Boolean,
) {
    SatelliteSupport.apply(
        style = style,
        on = satelliteOn,
        builtinLayerIds = BUILTIN_SATELLITE_LAYERS,
        fallbackBaseTiles = config.satelliteTiles.toTypedArray(),
        fallbackBaseMaxZoom = config.satelliteMaxZoom,
        enableDeep = config.satelliteFallbackOn,
        deepTiles = config.satelliteFallbackTiles.toTypedArray(),
        deepMaxZoom = config.satelliteFallbackMaxZoom,
        deepAnchorLayerId = BUILTIN_SATELLITE_ANCHOR,
    )

    TerrainSupport.apply(
        style = style,
        hillshadeOn = hillshadeOn,
        contourOn = contourOn,
        contourUrl = config.contourUrl,
        contourSourceLayer = config.contourSourceLayer,
        contourMinZoom = config.contourMinZoom,
        contourMaxZoom = config.contourMaxZoom,
        hillshadeExaggeration = config.hillshadeExaggeration,
        darkTheme = config.darkTheme,
        anchorLayerId = firstSymbolLayerId(style),
    )
}

private fun firstSymbolLayerId(style: Style): String? =
    style.layers.firstOrNull { it is SymbolLayer }?.id

// =============================================================================================
// LocationComponent 安全包装
// =============================================================================================

@SuppressLint("MissingPermission")
internal fun safeActivateLocationComponent(
    map: MapLibreMap,
    context: Context,
    style: Style,
) {
    val lc = map.locationComponent
    if (lc.isLocationComponentActivated) return

    val opts = LocationComponentActivationOptions
        .builder(context, style)
        .useDefaultLocationEngine(true)
        .locationComponentOptions(
            LocationComponentOptions.builder(context)
                .pulseEnabled(true)
                .build(),
        )
        .build()

    lc.activateLocationComponent(opts)
    lc.renderMode = RenderMode.NORMAL
}

@SuppressLint("MissingPermission")
internal fun safeEnableLocation(map: MapLibreMap, enable: Boolean) {
    val lc = map.locationComponent
    if (!lc.isLocationComponentActivated) return
    lc.isLocationComponentEnabled = enable
}

@SuppressLint("MissingPermission")
internal fun safeSetCameraMode(map: MapLibreMap, mode: Int) {
    val lc = map.locationComponent
    if (!lc.isLocationComponentActivated) return
    lc.cameraMode = mode
}

@SuppressLint("MissingPermission")
internal fun safeDeactivateLocation(map: MapLibreMap) {
    val lc = map.locationComponent
    if (!lc.isLocationComponentActivated) return
    lc.isLocationComponentEnabled = false
}