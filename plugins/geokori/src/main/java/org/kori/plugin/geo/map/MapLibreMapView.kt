package org.kori.plugin.geo.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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

/**
 * 相机跟随的时间常数（秒）。分开设置以免地图"甩头"：
 *  · 位置：快（0.25s）—— 蓝点保持在屏幕中心
 *  · 方位角：慢（0.55s）—— 转弯时地图平滑旋转而非瞬间转
 *  · 缩放：中（0.5s）—— 速度变化时缓慢呼吸
 */
private object CameraSmoothing {
    const val TAU_POS_S = 0.25f
    const val TAU_BRG_S = 0.55f
    const val TAU_ZOOM_S = 0.50f

    /** 判定"已收敛"的阈值，收敛后停止向相机写入，省 JNI 调用。 */
    const val CONVERGED_DEG = 1e-6
    const val CONVERGED_BRG = 0.05
    const val CONVERGED_ZOOM = 0.001

    /** 单帧 dt 上限（防 app 挂起后一帧跳太大）。 */
    const val MAX_DT_S = 0.1f

    // ★ 空闲降频参数
    /** 空闲多久后开始降频（毫秒）。 */
    val IDLE_GRACE: Duration = 1.seconds
    /** 降频后的 tick 间隔（毫秒）。100ms = 10Hz。 */
    val IDLE_TICK: Duration = 100.milliseconds
}

// =============================================================================================
// 主 Composable
// =============================================================================================

@Composable
fun MapLibreMapView(
    modifier: Modifier = Modifier,
    config: MapConfig = MapConfig.Default,
    onUserLocationChange: ((Boolean) -> Unit)? = null,
    onSatelliteChange: ((Boolean) -> Unit)? = null,
    onHillshadeChange: ((Boolean) -> Unit)? = null,
    onContourChange: ((Boolean) -> Unit)? = null,
    onLocationFix: ((LocationTracker.Fix) -> Unit)? = null,
    trackRecording: Boolean = false,
    onTrackSaved: ((File) -> Unit)? = null,
    onTrackProgress: ((kept: Int, rejected: Int) -> Unit)? = null,
    externalLocationTracker: LocationTracker? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    // ---------------------------------------------------------------------
    // 内部状态
    // ---------------------------------------------------------------------
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

    // ---------------------------------------------------------------------
    // config 同步
    // ---------------------------------------------------------------------
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

    // ---------------------------------------------------------------------
    // 位置权限
    // ---------------------------------------------------------------------
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

    // ---------------------------------------------------------------------
    // MapView 实例
    // ---------------------------------------------------------------------
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(null) }
    }

    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleRef by remember { mutableStateOf<Style?>(null) }

    // ---------------------------------------------------------------------
    // 自定义管线
    // ---------------------------------------------------------------------
    val locationTracker = remember(config.useCustomLocationPipeline, externalLocationTracker) {
        if (!config.useCustomLocationPipeline) null
        else externalLocationTracker ?: LocationTracker(context)
    }

    var customFollow by remember { mutableStateOf(config.showUserLocation) }
    var customCollectJob by remember { mutableStateOf<Job?>(null) }
    var lastFix by remember { mutableStateOf<LocationTracker.Fix?>(null) }

    val trackDir = remember {
        File(context.filesDir, config.trackStorageDirName).apply { mkdirs() }
    }

    // =====================================================================
    // 相机缓动状态
    // ---------------------------------------------------------------------
    // 目标值：由 fix 写入，ticker 读它做缓动
    // 用普通数组而非 mutableStateOf，避免每帧触发重组
    // 单线程访问（collect 和 ticker 都在主线程），无需 @Volatile
    // =====================================================================
    val camTarget = remember {
        doubleArrayOf(
            /* lat     */ 0.0,
            /* lng     */ 0.0,
            /* bearing */ 0.0,
            /* zoom    */ 0.0,
            /* valid   */ 0.0,  // 0 = 未收到目标
        )
    }
    // 当前缓动值
    val camCurrent = remember { doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0) }
    // 上次写入相机的值（收敛判定用）
    val camLastWrite = remember { doubleArrayOf(Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN) }
    // 是否需要从当前相机重新 seed
    val camNeedsSeed = remember { booleanArrayOf(true) }

    // =====================================================================
    // 相机缓动 ticker
    // ---------------------------------------------------------------------
    // 逐帧运行，把 camCurrent 缓动到 camTarget。
    // 只在 customFollow = true 时驱动相机。
    //
    // ## 空闲降频
    // 无跟随 / 无 fix 时，累积空闲时间；超过 1 秒后从 60fps 降到 10Hz，
    // 省电。任何"有事可做"的帧（新 fix 到达、用户点了重新居中）
    // 立即恢复全帧率。
    // =====================================================================
    LaunchedEffect(
        config.useCustomLocationPipeline,
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

            // 帧回调里 dt 计算：lastNanos 为 0 说明是 ticker 刚启动或从 delay 恢复
            if (lastNanos == 0L) {
                lastNanos = nowNanos
                continue
            }
            val dt = ((nowNanos - lastNanos) / 1e9).toFloat()
                .coerceIn(0f, CameraSmoothing.MAX_DT_S)
            lastNanos = nowNanos

            val nowMs = nowNanos / 1_000_000L

            // ---- 是否有事可做 ----
            val hasWork = customFollow && camTarget[4] != 0.0

            if (!hasWork) {
                // 空闲：记录起始时间；超过 1 秒后降到 10Hz
                if (idleSinceMs == 0L) idleSinceMs = nowMs
                if (nowMs - idleSinceMs > CameraSmoothing.IDLE_GRACE.inWholeMilliseconds)  {
                    delay(CameraSmoothing.IDLE_TICK)
                    // 重置 dt 基准，避免 delay 后第一帧算出巨大的 dt
                    lastNanos = 0L
                }
                continue
            }
            idleSinceMs = 0L

            // 未收到 fix 时不驱动
            if (camTarget[4] == 0.0) continue

            // 首次（或用户刚点重新居中）时，从当前相机位置 seed
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

            // 指数缓动（每轴独立 tau）
            val kPos = 1.0 - exp(-dt / CameraSmoothing.TAU_POS_S)
            val kBrg = 1.0 - exp(-dt / CameraSmoothing.TAU_BRG_S)
            val kZoom = 1.0 - exp(-dt / CameraSmoothing.TAU_ZOOM_S)

            camCurrent[0] += (camTarget[0] - camCurrent[0]) * kPos
            camCurrent[1] += (camTarget[1] - camCurrent[1]) * kPos

            // 方位角走最短路
            val dB = ((camTarget[2] - camCurrent[2] + 540.0) % 360.0) - 180.0
            camCurrent[2] = (camCurrent[2] + dB * kBrg + 360.0) % 360.0

            camCurrent[3] += (config.customLocationTrackingZoom - camCurrent[3]) * kZoom

            // 收敛判定
            val dLat = abs(camCurrent[0] - camLastWrite[0])
            val dLng = abs(camCurrent[1] - camLastWrite[1])
            val dB2 = abs(camCurrent[2] - camLastWrite[2])
            val dZ = abs(camCurrent[3] - camLastWrite[3])

            val converged = dLat.isNaN() ||
                    dLat > CameraSmoothing.CONVERGED_DEG ||
                    dLng > CameraSmoothing.CONVERGED_DEG ||
                    dB2 > CameraSmoothing.CONVERGED_BRG ||
                    dZ > CameraSmoothing.CONVERGED_ZOOM

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
    // ---------------------------------------------------------------------
    // 生命周期转发
    // ---------------------------------------------------------------------
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
            if (externalLocationTracker == null) {
                runCatching { locationTracker?.stop() }
            }
            runCatching { mapRef?.let { safeDeactivateLocation(it) } }
            mapView.onStop()
            mapView.onDestroy()
            MapRuntime.detach()
        }
    }

    // ---------------------------------------------------------------------
    // 地图初始化
    // ---------------------------------------------------------------------
    LaunchedEffect(mapView) {
        mapView.getMapAsync { map ->
            mapRef = map

            // 用户手势 → 退出跟随
            map.addOnCameraMoveStartedListener { reason ->
                if (reason != MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) return@addOnCameraMoveStartedListener
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

                MapRuntime.attach(style, map)
            }
        }
    }

    // ---------------------------------------------------------------------
    // 图层开关变化 → 重新应用
    // ---------------------------------------------------------------------
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

    // ---------------------------------------------------------------------
    // 位置组件 — MapLibre 默认管线
    // ---------------------------------------------------------------------
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

    // ---------------------------------------------------------------------
    // 位置组件 — 自定义管线
    // ---------------------------------------------------------------------
    LaunchedEffect(
        config.useCustomLocationPipeline,
        locationEnabled,
        locationPermissionGranted,
        styleRef,
        mapRef,
    ) {
        if (!config.useCustomLocationPipeline) return@LaunchedEffect

        if (!locationEnabled) {
            customCollectJob?.cancel()
            customCollectJob = null
            customFollow = false
            // 下次开启时重新 seed 相机
            camNeedsSeed[0] = true
            camTarget[4] = 0.0
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

        val tracker = locationTracker ?: return@LaunchedEffect

        // 开启跟随（若已在跟随之保持）
        customFollow = true
        camNeedsSeed[0] = true  // 强制从当前相机 seed

        customCollectJob?.cancel()
        customCollectJob = scope.launch {
            tracker.locationFlow().collect { fix ->
                lastFix = fix
                onLocationFix?.invoke(fix)

                // 轨迹进度
                if (tracker.isRecording) {
                    onTrackProgress?.invoke(
                        tracker.recordedPoints(),
                        tracker.rejectedPoints(),
                    )
                }

                // ★ 只写目标，不直接操作相机
                //   ticker 下一帧会缓动过去
                camTarget[0] = fix.lat
                camTarget[1] = fix.lng
                fix.bearingDeg?.let { camTarget[2] = it.toDouble() }
                camTarget[3] = config.customLocationTrackingZoom
                camTarget[4] = 1.0  // valid
            }
        }
    }

    // ---------------------------------------------------------------------
    // 轨迹记录
    // ---------------------------------------------------------------------
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

    LaunchedEffect(config.autoStartTrackRecording, config.useCustomLocationPipeline, locationEnabled) {
        if (!config.autoStartTrackRecording) return@LaunchedEffect
        if (!config.useCustomLocationPipeline) return@LaunchedEffect
        if (!locationEnabled || !locationPermissionGranted) return@LaunchedEffect
        val tracker = locationTracker ?: return@LaunchedEffect
        if (!tracker.isRecording) {
            tracker.startRecording(trackDir)
        }
    }

    // ---------------------------------------------------------------------
    // UI
    // ---------------------------------------------------------------------
    Box(modifier) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.matchParentSize(),
        )

        // ---------- 定位按钮 ----------
        if (config.showLocationButton) {
            Box(modifier = Modifier.matchParentSize()) {
                FloatingActionButton(
                    onClick = {
                        if (config.useCustomLocationPipeline) {
                            // === 自定义管线 ===
                            when {
                                !locationEnabled -> {
                                    // 关 → 开
                                    locationEnabled = true
                                    onUserLocationChange?.invoke(true)
                                    customFollow = true
                                    camNeedsSeed[0] = true
                                }
                                customFollow -> {
                                    // 跟随中 → 关闭
                                    locationEnabled = false
                                    onUserLocationChange?.invoke(false)
                                    customFollow = false
                                }
                                else -> {
                                    // 未跟随 → 重新居中
                                    customFollow = true
                                    camNeedsSeed[0] = true  // 从当前位置重新缓动
                                    // 若有 fix，立即把它设为目标（避免 ticker 等到下一帧）
                                    lastFix?.let { fix ->
                                        camTarget[0] = fix.lat
                                        camTarget[1] = fix.lng
                                        fix.bearingDeg?.let { camTarget[2] = it.toDouble() }
                                        camTarget[3] = config.customLocationTrackingZoom
                                        camTarget[4] = 1.0
                                    }
                                }
                            }
                        } else {
                            // === 默认管线 ===
                            handleDefaultLocationButton(
                                map = mapRef,
                                locationEnabled = locationEnabled,
                                setLocationEnabled = { locationEnabled = it },
                                onUserLocationChange = onUserLocationChange,
                            )
                        }
                    },
                    modifier = Modifier
                        .align(config.locationButtonAlignment)
                        .offset(
                            x = config.locationButtonOffsetX,
                            y = config.locationButtonOffsetY,
                        )
                        .padding(config.buttonPadding),
                    containerColor = if (locationEnabled) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    contentColor = if (locationEnabled) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                ) {
                    Icon(
                        imageVector = Icons.Filled.LocationOn,
                        contentDescription = if (locationEnabled) "关闭位置显示" else "显示我的位置",
                    )
                }
            }
        }

        // ---------- 图层控制 ----------
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
    }
}

// =============================================================================================
// 默认管线的定位按钮
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

// =============================================================================================
// 图层检索 / 应用 / 样式注入
// =============================================================================================

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
                java.io.File(styleUri.removePrefix("file://")).readText()
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