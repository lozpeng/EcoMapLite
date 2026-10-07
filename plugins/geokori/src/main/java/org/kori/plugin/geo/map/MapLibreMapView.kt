package org.kori.plugin.geo.map

import android.graphics.RectF
import android.util.Log

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
import org.maplibre.android.geometry.LatLngBounds
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
import org.kori.plugin.geo.track.ui.TrackMediaTapHost
import org.kori.plugin.geo.track.ui.TrackRecordingHud
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
// 相机位置记忆
// =============================================================================================

private object MapCameraMemory {
    @Volatile var lat: Double? = null
    @Volatile var lng: Double? = null
    @Volatile var zoom: Double? = null
    @Volatile var bearing: Double? = null
    @Volatile var tilt: Double? = null

    fun snapshot(map: MapLibreMap) {
        val cp = map.cameraPosition
        cp.target?.let { lat = it.latitude; lng = it.longitude }
        zoom = cp.zoom
        bearing = cp.bearing
        tilt = cp.tilt
    }

    fun clear() {
        lat = null; lng = null; zoom = null; bearing = null; tilt = null
    }
}

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
// 外部相机命令
// =============================================================================================

/**
 * 外部驱动地图相机的命令（独立于跟随管线）。
 *
 *  · [FitPoints]    缩放到包含所有点的范围，进入"概览保持"状态
 *  · [ResumeFollow] 解除概览保持，恢复对外部 fix 的跟随
 */
sealed interface MapCameraCommand {

    /**
     * 缩放到包含所有点的范围。
     *
     * @param points      参与 fitBounds 的点集（≥2 才生效）
     * @param paddingPx   四边内边距（像素），值越大缩得越远
     * @param animateMs   动画时长（毫秒），0 = 立即定位
     */
    data class FitPoints(
        val points: List<TrackPoint>,
        val paddingPx: Int = 120,
        val animateMs: Int = 600,
    ) : MapCameraCommand

    /** 解除概览保持，恢复跟随（下一帧从当前位置平滑接管）。 */
    data object ResumeFollow : MapCameraCommand
}

// =============================================================================================
// 主 Composable
// =============================================================================================

/**
 * MapLibre 地图 Compose 封装。
 *
 * ## 实时轨迹 / 媒体点位
 *
 *  - [liveTrackPoints] / [liveSmoothPoints]：原始 / 平滑轨迹线
 *  - [liveTrackMedia]：媒体点位（录音 / 录像为 MAP_PIN 图标针，照片为照片气泡针）
 *  - [mediaScales]：`filePath -> scale`，控制每个媒体图标的显示比例。
 *    空 map = 全部按 1 显示；回放场景每帧传入新 map 可实现"到点弹出"动画。
 *  - [cameraCommands]：外部相机命令（概览 / 恢复跟随），回放屏常用。
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

    onMapClick: ((position: LatLng, screenPosition: DpOffset) -> Unit)? = null,
    /**
     * ★ 媒体标记点击：`type`（PHOTO/VIDEO/AUDIO）+ 绝对 [filePath]。
     * 命中媒体图标时消费点击（不透传给 [onMapClick]）。
     * photo/video 全屏查看，audio 就地播放。
     *
     * 为 null（默认）时使用内置宿主自动处理，所有调用场景零接线。
     */
    onMediaClick: ((type: String, filePath: String) -> Unit)? = null,
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
     * ★ 媒体点位显示比例：`filePath -> scale`（0 隐藏，1 完全显示，中间值动画）。
     *
     * 空 map = 所有媒体均按 scale=1 显示。
     * 回放循环每帧传入新 map，feature 上的 `scale` 属性随之更新，
     * SymbolLayer 的 `iconSize` 表达式驱动图标缩放。
     */
    mediaScales: Map<String, Float> = emptyMap(),

    /**
     * 历史轨迹叠加层（每元素一条轨迹段，≥2 点才绘制）。
     */
    historySegments: List<List<TrackPoint>> = emptyList(),

    /**
     * 轨迹回放标记点：非 null 时地图显示亮青回放标记。
     */
    playbackPoint: TrackPoint? = null,

    /**
     * ★ 外部相机命令流（可选）。
     *
     * 典型场景：轨迹回放屏进入时先 [MapCameraCommand.FitPoints] 显示整条轨迹，
     * 用户点播放后再 [MapCameraCommand.ResumeFollow] 进入跟随。
     */
    cameraCommands: SharedFlow<MapCameraCommand>? = null,

    // ★ 记录面板回调。非 null 时启用内嵌记录面板。
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

    // 四态循环
    var bearingMode by remember {
        mutableStateOf(
            if (config.showUserLocation && config.customLocationRotateToBearing) {
                BearingMode.GPS_BEARING
            } else {
                BearingMode.NORTH
            },
        )
    }
    var locateCycleState by remember { mutableIntStateOf(if (config.showUserLocation) 1 else 0) }
    var northResetDone by remember { mutableStateOf(false) }

    val compass = remember { CompassProvider(context) }
    var compassIconDeg by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(bearingMode) {
        if (bearingMode == BearingMode.COMPASS) compass.start() else compass.stop()
    }

    val onMapClickHolder = remember { arrayOfNulls<((LatLng, DpOffset) -> Unit)?>(1) }
    onMapClickHolder[0] = onMapClick

    // ★ 内置媒体点击宿主：host 未传 onMediaClick 时自动处理
    //   （照片/视频全屏查看，录音就地播放）——录制 / 回放 / 历史浏览零接线
    val builtInMediaTap = TrackMediaTapHost()
    val onMediaClickHolder = remember { arrayOfNulls<((String, String) -> Unit)?>(1) }
    onMediaClickHolder[0] = onMediaClick ?: builtInMediaTap
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

    /**
     * 概览保持：外部请求 [MapCameraCommand.FitPoints] 后置 true。
     * 为 true 时相机缓动 ticker 停止写相机，fix 也不更新 camTarget。
     */
    val overviewHold = remember { mutableStateOf(false) }

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
            // ★ 概览保持期间不抢相机
            val hasWork = customFollow && camHasTarget[0] && !overviewHold.value

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
                    val dB = ((camTarget[2] - camCurrent[2] + 540.0) % 360.0) - 180.0
                    camCurrent[2] = (camCurrent[2] + dB * kBrg + 360.0) % 360.0
                }
                BearingMode.COMPASS -> {
                    val target = compass.headingDeg.toDouble()
                    compassIconDeg = compass.headingDeg
                    val dB = ((target - camCurrent[2] + 540.0) % 360.0) - 180.0
                    camCurrent[2] = (camCurrent[2] + dB * kBrg + 360.0) % 360.0
                }
                BearingMode.NORTH -> {
                    camCurrent[2] = map.cameraPosition.bearing
                }
            }

            camCurrent[3] += (config.customLocationTrackingZoom - camCurrent[3]) * kZoom
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
            mapRef?.let { MapCameraMemory.snapshot(it) }
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
            map.uiSettings.apply {
                isLogoEnabled = false
                isAttributionEnabled = false
                compassGravity = android.view.Gravity.TOP or android.view.Gravity.END
                val m = (16 * context.resources.displayMetrics.density).toInt()
                val statusBar = (24 * context.resources.displayMetrics.density).toInt()
                val extra48 = (84 * context.resources.displayMetrics.density).toInt()
                setCompassMargins(m, m + statusBar + extra48, m, m)
            }
            map.addOnMapClickListener { point ->
                // ★ 媒体标记优先：以点击点为中心 ±28dp 的方框查询，扩大命中区域
                //   （针尖图标像素很小，精确点按很容易miss）
                val mediaHandler = onMediaClickHolder[0]
                if (mediaHandler != null) {
                    val screenPt = map.projection.toScreenLocation(point)
                    val density = context.resources.displayMetrics.density
                    val half = 28f * density
                    val hit = map.queryRenderedFeatures(
                        RectF(
                            screenPt.x - half, screenPt.y - half,
                            screenPt.x + half, screenPt.y + half,
                        ),
                        LiveTrackLayer.LAYER_MEDIA,
                    ).firstOrNull()
                    if (hit != null) {
                        val type = hit.getStringProperty("type")
                        val path = hit.getStringProperty("filePath")
                        if (type != null && path != null) {
                            Log.i("MapLibreMapView", "media hit: type=$type")
                            mediaHandler(type, path)
                            return@addOnMapClickListener true
                        }
                    }
                }
                onMapClickHolder[0]?.let { cb ->
                    val screenPt = map.projection.toScreenLocation(point)
                    val density = context.resources.displayMetrics.density
                    cb(
                        point,
                        DpOffset(Dp(screenPt.x / density), Dp(screenPt.y / density)),
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
                val lastFix = TrackRecordingEngine.lastKnownLocation
                val initialTarget = if (config.showUserLocation && lastFix != null) {
                    LatLng(lastFix.latitude, lastFix.longitude)
                } else {
                    LatLng(config.initialCenterLat, config.initialCenterLng)
                }

                val mem = MapCameraMemory
                map.cameraPosition = CameraPosition.Builder()
                    .target(
                        if (mem.lat != null && mem.lng != null) LatLng(mem.lat!!, mem.lng!!)
                        else initialTarget
                    )
                    .zoom(mem.zoom ?: config.initialZoom)
                    .bearing(mem.bearing ?: 0.0)
                    .tilt(mem.tilt ?: 0.0)
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

                // 实时轨迹图层（含媒体 SymbolLayer）
                LiveTrackLayer.ensureLayers(style)

                MapRuntime.attach(style, map, mapView)
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

    // =============================================================================================
    // 媒体点位绘制（SymbolLayer；支持按 mediaScales 数据驱动缩放）
    // =============================================================================================
    // key 中加入 mediaScales：回放循环每帧写入新 map → 重新写 GeoJSON，
    // feature 上的 scale 属性随之变化，SymbolLayer iconSize 表达式立即生效。
    // ★ 历史浏览场景：host 未显式传媒体（或传的是空的 liveMedia）时，
    //   回退到引擎的 historyMedia 流，保证"显示到地图"时媒体标记一定出现
    val historyMedia by TrackRecordingEngine.historyMedia.collectAsState()

    LaunchedEffect(styleRef, liveTrackMedia, historyMedia, mediaScales) {
        val style = styleRef ?: return@LaunchedEffect
        val effectiveMedia = liveTrackMedia.ifEmpty { historyMedia }
        Log.i(
            "MapLibreMapView",
            "updateMedia effect: live=${liveTrackMedia.size}, history=${historyMedia.size}",
        )
        LiveTrackLayer.updateMedia(
            style = style,
            context = context.applicationContext,
            media = effectiveMedia,
            scales = mediaScales,
        )
    }

    // =============================================================================================
    // 历史轨迹叠加层（加载时自动缩放至轨迹范围）
    // =============================================================================================
    LaunchedEffect(styleRef, mapRef, historySegments) {
        val style = styleRef ?: return@LaunchedEffect
        val map = mapRef ?: return@LaunchedEffect
        if (historySegments.isEmpty()) {
            LiveTrackLayer.clearHistory(style)
        } else {
            LiveTrackLayer.updateHistory(style, historySegments)

            // ★ 浏览历史轨迹：缩放至轨迹范围（所有段的所有点参与 fitBounds）
            val all = historySegments.flatten()
            if (all.size >= 2) {
                // 暂停跟随管线的相机写入，避免 fitBounds 动画与跟随互相拉扯；
                // 用户点定位按钮重新跟随时解除（见 locationFabOnClick）
                overviewHold.value = true

                val builder = LatLngBounds.Builder()
                all.forEach { builder.include(LatLng(it.lat, it.lng)) }
                runCatching {
                    map.animateCamera(
                        CameraUpdateFactory.newLatLngBounds(builder.build(), 120),
                        700,
                    )
                }
            }
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
    // 外部相机命令（概览 / 恢复跟随）
    // =============================================================================================
    LaunchedEffect(mapRef, cameraCommands) {
        val map = mapRef ?: return@LaunchedEffect
        val flow = cameraCommands ?: return@LaunchedEffect

        flow.collect { cmd ->
            when (cmd) {
                is MapCameraCommand.FitPoints -> {
                    if (cmd.points.size < 2) return@collect
                    // 进入概览保持：ticker 停手、fix 不抢相机
                    overviewHold.value = true

                    val builder = LatLngBounds.Builder()
                    cmd.points.forEach { builder.include(LatLng(it.lat, it.lng)) }
                    runCatching {
                        val update = CameraUpdateFactory.newLatLngBounds(
                            builder.build(),
                            cmd.paddingPx,
                        )
                        if (cmd.animateMs > 0) {
                            map.animateCamera(update, cmd.animateMs)
                        } else {
                            map.moveCamera(update)
                        }
                    }
                }

                MapCameraCommand.ResumeFollow -> {
                    overviewHold.value = false
                    // 让 ticker 从当前位置平滑接管：
                    // 重新播种 → 下一帧从 map.cameraPosition 起缓动到跟随目标
                    camNeedsSeed[0] = true
                    // 标记暂无可用目标，等下一个 fix 建立
                    camHasTarget[0] = false
                }
            }
        }
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
            TrackRecordingEngine.ensureLocationTracking(context)

            customCollectJob = scope.launch {
                externalLocationFixes.collect { fix ->
                    lastFixHolder[0] = fix
                    onLocationFix?.invoke(fix)
                    pushFixToLocationComponent(map, fix)

                    // ★ 概览保持期间不更新相机目标
                    if (!overviewHold.value) {
                        camTarget[0] = fix.lat
                        camTarget[1] = fix.lng
                        fix.bearingDeg?.let { camTarget[2] = it.toDouble() }
                        camTarget[3] = config.customLocationTrackingZoom
                        camHasTarget[0] = true
                    }
                }
            }
            return@LaunchedEffect
        }

        val tracker = locationTracker ?: return@LaunchedEffect
        customCollectJob = scope.launch {
            tracker.fixes.collect { fix ->
                lastFixHolder[0] = fix
                onLocationFix?.invoke(fix)
                pushFixToLocationComponent(map, fix)

                if (tracker.isRecording) {
                    onTrackProgress?.invoke(
                        tracker.recordedPoints(),
                        tracker.rejectedPoints(),
                    )
                }

                // ★ 概览保持期间不更新相机目标
                if (!overviewHold.value) {
                    camTarget[0] = fix.lat
                    camTarget[1] = fix.lng
                    fix.bearingDeg?.let { camTarget[2] = it.toDouble() }
                    camTarget[3] = config.customLocationTrackingZoom
                    camHasTarget[0] = true
                }
            }
        }
    }

    // =============================================================================================
    // 轨迹记录
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

        val locationFabOnClick: () -> Unit = {
            if (config.useCustomLocationPipeline) {
                when {
                    !locationEnabled -> {
                        locationEnabled = true
                        onUserLocationChange?.invoke(true)
                        overviewHold.value = false // ★ 解除历史浏览的概览保持，恢复跟随
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
                        customFollow = true
                        overviewHold.value = false // ★ 解除历史浏览的概览保持，恢复跟随
                        camNeedsSeed[0] = true
                        lastFixHolder[0]?.let { fix ->
                            camTarget[0] = fix.lat
                            camTarget[1] = fix.lng
                            camTarget[3] = config.customLocationTrackingZoom
                            camHasTarget[0] = true
                        }
                    }
                    locateCycleState == 2 -> {
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
                        bearingMode = BearingMode.NORTH
                        northResetDone = true
                        camCurrent[2] = 0.0
                        camLastWrite[2] = Double.NaN
                        runCatching {
                            mapRef?.moveCamera(CameraUpdateFactory.bearingTo(0.0))
                        }
                    }
                    else -> {
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

        if (trackPanelCallbacks != null) {
            RecordingPanelOverlay(
                callbacks = trackPanelCallbacks,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        start = 12.dp,
                        end = 12.dp,
                        bottom = config.trackPanelBottomPadding,
                    ),
            )
        }
    }
}

// =============================================================================================
// 记录面板覆盖层
// =============================================================================================

@Composable
private fun RecordingPanelOverlay(
    callbacks: TrackMapCallbacks,
    modifier: Modifier = Modifier,
) {
    val state by TrackRecordingEngine.state.collectAsState()
    if (!state.recording) return
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
        showToggleButton = false,
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