package org.kori.plugin.geo.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.cwcc.open.geokori.map.MapRuntime
import org.kori.plugin.geo.map.sp.SatelliteSupport
import org.kori.plugin.geo.map.sp.TerrainSupport
import org.kori.plugin.geo.map.ui.BaseMapOption
import org.kori.plugin.geo.map.ui.LayerEntry
import org.kori.plugin.geo.map.ui.MapLayersControl
import org.kori.plugin.geo.map.ui.OverlayToggle
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
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

// =============================================================================================
// 常量
// =============================================================================================

private val BUILTIN_SATELLITE_LAYERS = listOf("天地图卫星影像", "天地图注记")
private const val BUILTIN_SATELLITE_ANCHOR = "天地图注记"

private const val BASE_MAP_SATELLITE = "satellite"
private const val BASE_MAP_TERRAIN = "terrain"

private const val OVERLAY_CONTOUR = "contour"

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
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

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
    // 从 config 同步初始值
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
            runCatching { mapRef?.let { safeDeactivateLocation(it) } }
            mapView.onStop()
            mapView.onDestroy()
            MapRuntime.detach()
        }
    }

    // ---------------------------------------------------------------------
    // 地图初始化（含 DEM 源动态注入）
    // ---------------------------------------------------------------------
    LaunchedEffect(mapView) {
        mapView.getMapAsync { map ->
            mapRef = map

            map.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                    runCatching {
                        val lc = map.locationComponent
                        if (lc.isLocationComponentActivated &&
                            lc.cameraMode == CameraMode.TRACKING
                        ) {
                            safeSetCameraMode(map, CameraMode.NONE)
                        }
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
    // 位置组件
    // ---------------------------------------------------------------------
    LaunchedEffect(locationEnabled, locationPermissionGranted, styleRef, mapRef) {
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
    // UI
    // ---------------------------------------------------------------------
    Box(modifier) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.matchParentSize(),
        )

        // ---------- 定位按钮 ----------
        if (config.showLocationButton) {
            Box(
                modifier = Modifier.matchParentSize(),
            ) {
                FloatingActionButton(
                    onClick = {
                        val lc = mapRef?.locationComponent
                        val activated = lc?.isLocationComponentActivated == true
                        val isTracking = activated &&
                                lc!!.isLocationComponentEnabled &&
                                lc.cameraMode == CameraMode.TRACKING

                        when {
                            !locationEnabled -> {
                                locationEnabled = true
                                onUserLocationChange?.invoke(true)
                                if (activated) {
                                    runCatching {
                                        mapRef?.let { map ->
                                            safeEnableLocation(map, true)
                                            safeSetCameraMode(map, CameraMode.TRACKING)
                                        }
                                    }
                                }
                            }
                            isTracking -> {
                                locationEnabled = false
                                onUserLocationChange?.invoke(false)
                            }
                            else -> {
                                runCatching {
                                    mapRef?.let { safeSetCameraMode(it, CameraMode.TRACKING) }
                                }
                            }
                        }
                    },
                    modifier = Modifier
                        .align(config.locationButtonAlignment)
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

        // ---------- 图层控制组件 ----------
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
// 图层检索
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

// =============================================================================================
// 内部逻辑
// =============================================================================================

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