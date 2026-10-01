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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.cwcc.open.geokori.map.MapRuntime
import org.kori.plugin.geo.map.sp.SatelliteSupport
import org.kori.plugin.geo.map.sp.TerrainSupport
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
import org.maplibre.android.style.layers.SymbolLayer

// =============================================================================================
// 常量
// =============================================================================================

private val BUILTIN_SATELLITE_LAYERS = listOf("天地图卫星影像", "天地图注记")
private const val BUILTIN_SATELLITE_ANCHOR = "天地图注记"

// =============================================================================================
// 内联图标
// =============================================================================================

private val LayersIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Layers",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(11.99f, 18.54f)
            lineTo(4.62f, 12.81f)
            lineTo(3f, 14.07f)
            lineTo(12f, 21.07f)
            lineTo(21f, 14.07f)
            lineTo(19.37f, 12.81f)
            lineTo(11.99f, 18.54f)
            close()
            moveTo(12f, 16f)
            lineTo(19.36f, 10.27f)
            lineTo(21f, 9f)
            lineTo(12f, 2f)
            lineTo(3f, 9f)
            lineTo(4.63f, 10.27f)
            lineTo(12f, 16f)
            close()
        }
    }.build()
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
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // ---------------------------------------------------------------------
    // 内部开关状态（与 config 初始值同步）
    // ---------------------------------------------------------------------
    var locationEnabled by remember { mutableStateOf(config.showUserLocation) }
    var satelliteEnabled by remember { mutableStateOf(config.satelliteOn) }
    var hillshadeEnabled by remember { mutableStateOf(config.hillshadeOn) }
    var contourEnabled by remember { mutableStateOf(config.contourOn) }
    var menuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(config.showUserLocation) {
        if (config.showUserLocation != locationEnabled) locationEnabled = config.showUserLocation
    }
    LaunchedEffect(config.satelliteOn) {
        if (config.satelliteOn != satelliteEnabled) satelliteEnabled = config.satelliteOn
    }
    LaunchedEffect(config.hillshadeOn) {
        if (config.hillshadeOn != hillshadeEnabled) hillshadeEnabled = config.hillshadeOn
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

            // 用户手势平移 → 退出相机跟随
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

            // ★ 构建样式（如果需要 DEM，动态注入 DEM 源定义到 JSON）
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

                // 应用初始图层状态
                applyAllLayers(
                    style = style,
                    config = config,
                    satelliteOn = satelliteEnabled,
                    hillshadeOn = hillshadeEnabled,
                    contourOn = contourEnabled,
                )

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

        // ---------- 图层按钮 + 下拉列表 ----------
        if (config.showLayerButton) {
            val anyOn = satelliteEnabled || hillshadeEnabled || contourEnabled

            Box(
                modifier = Modifier
                    .align(config.layerButtonAlignment)
                    .padding(config.buttonPadding),
            ) {
                FloatingActionButton(
                    onClick = { menuExpanded = true },
                    containerColor = if (anyOn) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    contentColor = if (anyOn) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                ) {
                    Icon(
                        imageVector = LayersIcon,
                        contentDescription = "图层设置",
                    )
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    LayerSwitchItem(
                        label = "卫星影像",
                        checked = satelliteEnabled,
                        onCheckedChange = {
                            satelliteEnabled = it
                            onSatelliteChange?.invoke(it)
                        },
                    )
                    LayerSwitchItem(
                        label = "地形阴影",
                        checked = hillshadeEnabled,
                        onCheckedChange = {
                            hillshadeEnabled = it
                            onHillshadeChange?.invoke(it)
                        },
                    )
                    LayerSwitchItem(
                        label = "等高线",
                        checked = contourEnabled,
                        onCheckedChange = {
                            contourEnabled = it
                            onContourChange?.invoke(it)
                        },
                    )
                }
            }
        }
    }
}

// =============================================================================================
// 辅助 Composable
// =============================================================================================

@Composable
private fun LayerSwitchItem(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        trailingIcon = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
            )
        },
        onClick = { onCheckedChange(!checked) },
    )
}

// =============================================================================================
// 内部逻辑
// =============================================================================================

/**
 * 读取样式 JSON，动态注入 DEM 源定义，构建 Style.Builder。
 *
 * 支持 asset:// / file:// / http(s):// 三种 URI。
 * 如果读取失败或样式不需要 DEM，回退到原始 URI。
 */
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
        // 注入 DEM 源（幂等：若已存在则原样返回）
        val modifiedJson = TerrainSupport.injectDemSourceIntoStyleJson(
            json = json,
            demTiles = demTiles,
            maxZoom = demMaxZoom,
        )
        Style.Builder().fromJson(modifiedJson)
    } else {
        // 回退：直接用原始 URI（适用于 MapTiler 等远程样式）
        Style.Builder().fromUri(styleUri)
    }
}

/**
 * 统一应用卫星、地形、等高线三类图层。
 * 在 setStyle 回调和状态变化 effect 里都会调用，幂等。
 */
private fun applyAllLayers(
    style: Style,
    config: MapConfig,
    satelliteOn: Boolean,
    hillshadeOn: Boolean,
    contourOn: Boolean,
) {
    // 卫星（含深度兜底）
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

    // 地形 + 等高线（锚在第一个符号层之下）
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

/** 第一个符号层的 id；地形/等高线插在其下方，保证标签不被覆盖。 */
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