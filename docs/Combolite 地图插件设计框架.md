# ComboLite 地图插件框架设计文档

> **版本**：v1.0
> **适用场景**：ComboLite 插件化框架 + MapLibre Native + Jetpack Compose
> **核心目标**：让任意插件中的任意对象（UI、ViewModel、业务 Manager、对话框、菜单等）都能便捷地访问地图，添加数据源、图层、Marker、监听器，并在插件卸载或热更新时自动清理。

---

## 目录

- [一、设计原则](#一设计原则)
- [二、依赖配置](#二依赖配置)
- [三、lib-geokori 模块](#三lib-geokori-模块)
- [四、geokori 地图插件](#四geokori-地图插件)
- [五、home 依赖方插件](#五home-依赖方插件)
- [六、使用方式速查](#六使用方式速查)
- [七、生命周期时序](#七生命周期时序)
- [八、关键设计点总结](#八关键设计点总结)
- [九、ClassLoader 三条铁律](#九classloader-三条铁律)
- [附录：快速开始清单](#附录快速开始清单)

---

## 一、设计原则

### 1.1 核心思路

| 关注点 | 方案 |
|--------|------|
| **跨插件共享 MapLibre 类型** | 宿主 `implementation`，插件 `compileOnly`，所有插件运行时共享同一份 `Class` 对象 |
| **直接使用 MapLibre 原生对象** | 依赖方可以直接 `new GeoJsonSource(...)`、`LineLayer(...)`、`Marker(...)` |
| **自动追踪 + 一键清理** | `MapSession` 记录每次 add，`close()` 按依赖顺序清理 |
| **子 Session 隔离** | `parent.newChild("xxx")` 让对话框、菜单等短生命周期对象独立清理 |
| **任意对象访问** | Compose 用 `LocalMapSession`；普通对象用构造注入或 `MapRuntime.pluginSession(pluginId)` |
| **热更新兼容** | 插件卸载 → 关闭所有 session；加载 → 重建 |
| **无 ServiceRegistry** | `MapRuntime` 由宿主 ClassLoader 加载，天然跨插件共享 |

### 1.2 模块结构

```
project/
├── app/                                # 宿主 App
├── core/                               # ComboLite 核心
├── lib/
│   └── lib-geokori/                    # ★ 地图运行时 + Session 会话（宿主加载）
│       ├── MapRuntime.kt
│       ├── MapSession.kt
│       └── LocalMapSession.kt
└── plugins/
    ├── geokori/                        # ★ 地图插件本体
    │   ├── MapStyle.kt
    │   ├── MapLibreMapView.kt
    │   └── PluginEntryClass.kt
    └── home/                           # 依赖方插件示例
        ├── PluginEntryClass.kt
        ├── HomeScreen.kt
        ├── DemoDialog.kt
        ├── HomeActions.kt
        └── HomeViewModel.kt
```

---

## 二、依赖配置

### 2.1 `gradle/libs.versions.toml`（节选）

```toml
[versions]
composeBom = "2024.10.01"
maplibre = "11.5.0"
coroutines = "1.9.0"

[libraries]
androidx-compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
androidx-compose-runtime = { module = "androidx.compose.runtime:runtime" }
androidx-compose-ui = { module = "androidx.compose.ui:ui" }
androidx-compose-foundation = { module = "androidx.compose.foundation:foundation" }
androidx-compose-material3 = { module = "androidx.compose.material3:material3" }
androidx-lifecycle-runtime-compose = { module = "androidx.lifecycle:lifecycle-runtime-compose:2.8.7" }
maplibre-android = { module = "org.maplibre.gl:android-sdk", version.ref = "maplibre" }
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
```

### 2.2 宿主 `app/build.gradle.kts`

```kotlin
dependencies {
    // ★ 宿主统一提供以下依赖（关键）
    implementation(libs.maplibre.android)
    implementation(project(":lib:lib-geokori"))
    implementation(project(":core"))

    // 插件本体（ComboLite 打包用）
    implementation(project(":plugins:geokori"))
    implementation(project(":plugins:home"))
    // ...
}
```

### 2.3 `lib/lib-geokori/build.gradle.kts`

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "org.kori.lib.geokori"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    compileOnly(project(":core"))
    compileOnly(libs.maplibre.android)

    // CompositionLocal
    compileOnly(platform(libs.androidx.compose.bom))
    compileOnly(libs.androidx.compose.runtime)
}
```

### 2.4 `plugins/geokori/build.gradle.kts` 与 `plugins/home/build.gradle.kts`

```kotlin
dependencies {
    // ★ 跨插件共享依赖：全部 compileOnly
    compileOnly(project(":core"))
    compileOnly(project(":lib:lib-geokori"))
    compileOnly(libs.maplibre.android)

    compileOnly(platform(libs.androidx.compose.bom))
    compileOnly(libs.androidx.compose.runtime)
    compileOnly(libs.androidx.compose.ui)
    compileOnly(libs.androidx.compose.foundation)
    compileOnly(libs.androidx.compose.material3)
    compileOnly(libs.androidx.lifecycle.runtime.compose)
}
```

---

## 三、`lib-geokori` 模块

### 3.1 `MapRuntime.kt`

```kotlin
package org.kori.lib.geokori

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import java.util.concurrent.ConcurrentHashMap

/**
 * 地图运行时（全局单例）。
 *
 * 与 lib-geokori 同属宿主 ClassLoader，所有插件共享同一实例。
 *
 * 三种 Session 使用姿势：
 * 1. 插件级：PluginEntryClass 通过 [openPluginSession] 打开，onUnload 通过 [closePluginSession] 关闭。
 * 2. 从任意位置查询：插件内业务对象通过 [pluginSession] 查询。
 * 3. 独立 Session：临时场景通过 [openDetachedSession]，由调用方自行管理生命周期。
 */
object MapRuntime {

    private val _map = MutableStateFlow<MapLibreMap?>(null)
    private val _style = MutableStateFlow<Style?>(null)

    internal val mapFlow: StateFlow<MapLibreMap?> = _map.asStateFlow()
    internal val styleFlow: StateFlow<Style?> = _style.asStateFlow()

    val isReady: Boolean get() = _map.value != null && _style.value != null
    val currentMap: MapLibreMap? get() = _map.value
    val currentStyle: Style? get() = _style.value

    // ---------- 插件级 Session 注册表 ----------

    private val pluginSessions = ConcurrentHashMap<String, MapSession>()

    /**
     * 打开插件级 Session。若该 pluginId 已有 Session，会先关闭旧的。
     * 通常在 PluginEntryClass.onLoad 中调用一次。
     */
    fun openPluginSession(pluginId: String): MapSession {
        val old = pluginSessions.remove(pluginId)
        old?.close()
        val session = MapSession(owner = pluginId, parent = null)
        pluginSessions[pluginId] = session
        return session
    }

    /** 查询插件级 Session */
    fun pluginSession(pluginId: String): MapSession? = pluginSessions[pluginId]

    /** 关闭并注销插件级 Session。通常在 PluginEntryClass.onUnload 中调用。 */
    fun closePluginSession(pluginId: String) {
        pluginSessions.remove(pluginId)?.close()
    }

    /** 独立 Session，不注册到表，由调用方自行管理生命周期 */
    fun openDetachedSession(owner: String): MapSession =
        MapSession(owner = owner, parent = null)

    // ---------- 地图状态（由地图插件调用） ----------

    /** 由地图插件在 style 就绪时调用 */
    fun attach(style: Style, map: MapLibreMap) {
        _style.value = style
        _map.value = map
    }

    /** 由地图插件在销毁时调用 */
    fun detach() {
        _map.value = null
        _style.value = null
    }
}
```

### 3.2 `MapSession.kt`

```kotlin
package org.kori.lib.geokori

import android.graphics.PointF
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.maplibre.android.annotations.Marker
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.sources.Source
import org.maplibre.geojson.Feature

/**
 * 地图会话。
 *
 * - 通过 session 添加的要素/监听器都会被追踪，[close] 时一并移除。
 * - 支持父子嵌套：[newChild] 创建子 session；父 close 会递归 close 所有子。
 * - 所有 add/close 操作应在主线程调用（onReady 回调已在主线程）。
 */
class MapSession internal constructor(
    val owner: String,
    private val parent: MapSession?
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var closed = false

    // ---------- 子 session ----------

    private val children = mutableListOf<MapSession>()

    /**
     * 创建一个子 session。
     *
     * 用途：对话框、菜单、按钮等短生命周期对象，
     * 它们添加的要素只属于自己，关闭时只清理自己的部分。
     */
    fun newChild(childName: String): MapSession {
        check(!closed) { "MapSession($owner) 已关闭" }
        val child = MapSession(owner = "$owner/$childName", parent = this)
        synchronized(children) { children.add(child) }
        return child
    }

    // ---------- 追踪列表 ----------

    private val trackedSources = mutableSetOf<String>()
    private val trackedLayers = mutableSetOf<String>()
    private val trackedMarkers = mutableSetOf<Marker>()
    private val trackedMapClickListeners = mutableListOf<MapLibreMap.OnMapClickListener>()
    private val trackedMapLongClickListeners = mutableListOf<MapLibreMap.OnMapLongClickListener>()
    private val cleanupActions = mutableListOf<() -> Unit>()

    // ---------- 生命周期 ----------

    /** 首次地图就绪时执行一次；若已就绪立即执行 */
    fun onReady(block: (style: Style, map: MapLibreMap) -> Unit) {
        check(!closed) { "MapSession($owner) 已关闭" }
        scope.launch {
            combine(MapRuntime.mapFlow, MapRuntime.styleFlow) { m, s ->
                if (m != null && s != null) m to s else null
            }
                .filterNotNull()
                .first()
                .let { (m, s) -> if (!closed) block(s, m) }
        }
    }

    // ---------- 数据源 ----------

    fun addSource(source: Source): String {
        check(!closed)
        val id = source.id ?: error("Source 必须有非空 id")
        requireStyle().addSource(source)
        trackedSources.add(id)
        return id
    }

    fun addSource(sourceId: String, source: Source) {
        check(!closed)
        requireStyle().addSource(source)
        trackedSources.add(sourceId)
    }

    fun removeSource(sourceId: String) {
        if (closed) return
        if (trackedSources.remove(sourceId)) {
            runCatching { MapRuntime.currentStyle?.removeSource(sourceId) }
        }
    }

    // ---------- 图层 ----------

    fun addLayer(layer: Layer): String {
        check(!closed)
        val id = layer.id ?: error("Layer 必须有非空 id")
        requireStyle().addLayer(layer)
        trackedLayers.add(id)
        return id
    }

    fun addLayerBelow(layer: Layer, beforeLayerId: String): String {
        check(!closed)
        val id = layer.id ?: error("Layer 必须有非空 id")
        requireStyle().addLayerBelow(layer, beforeLayerId)
        trackedLayers.add(id)
        return id
    }

    fun removeLayer(layerId: String) {
        if (closed) return
        if (trackedLayers.remove(layerId)) {
            runCatching { MapRuntime.currentStyle?.removeLayer(layerId) }
        }
    }

    // ---------- Marker ----------

    fun addMarker(marker: Marker): Marker {
        check(!closed)
        marker.addTo(requireMap())
        trackedMarkers.add(marker)
        return marker
    }

    fun addMarker(position: LatLng): Marker =
        addMarker(Marker(requireMap()).position(position))

    fun removeMarker(marker: Marker) {
        if (closed) return
        if (trackedMarkers.remove(marker)) {
            runCatching { marker.remove() }
        }
    }

    // ---------- 监听器 ----------

    fun onMapClick(listener: (LatLng) -> Boolean) {
        check(!closed)
        val wrapped = MapLibreMap.OnMapClickListener { p -> listener(p) }
        requireMap().addOnMapClickListener(wrapped)
        trackedMapClickListeners.add(wrapped)
    }

    fun onMapLongClick(listener: (LatLng) -> Boolean) {
        check(!closed)
        val wrapped = MapLibreMap.OnMapLongClickListener { p -> listener(p) }
        requireMap().addOnMapLongClickListener(wrapped)
        trackedMapLongClickListeners.add(wrapped)
    }

    fun onLayerClick(layerId: String, listener: (Feature) -> Unit) {
        check(!closed)
        val map = requireMap()
        val wrapped = MapLibreMap.OnMapClickListener { latLng ->
            val screenPoint: PointF = map.projection.toScreenLocation(latLng)
            val features = map.queryRenderedFeatures(screenPoint, layerId)
            val feature = features?.firstOrNull() ?: return@OnMapClickListener false
            listener(feature)
            true
        }
        map.addOnMapClickListener(wrapped)
        trackedMapClickListeners.add(wrapped)
    }

    /** 通用追踪：任意监听器都能注册并自动清理 */
    fun <T : Any> track(listener: T, attach: (T) -> Unit, detach: (T) -> Unit) {
        check(!closed)
        attach(listener)
        cleanupActions.add { runCatching { detach(listener) } }
    }

    // ---------- 一键清理 ----------

    fun close() {
        if (closed) return
        closed = true

        // 1. 递归关闭子 session
        val childSnapshot = synchronized(children) {
            children.toList().also { children.clear() }
        }
        childSnapshot.forEach { runCatching { it.close() } }

        val map = MapRuntime.currentMap
        val style = MapRuntime.currentStyle

        // 2. 通用清理
        cleanupActions.forEach { runCatching { it() } }
        cleanupActions.clear()

        // 3. 监听器
        if (map != null) {
            trackedMapClickListeners.forEach { runCatching { map.removeOnMapClickListener(it) } }
            trackedMapLongClickListeners.forEach { runCatching { map.removeOnMapLongClickListener(it) } }
        }
        trackedMapClickListeners.clear()
        trackedMapLongClickListeners.clear()

        // 4. Marker
        trackedMarkers.forEach { runCatching { it.remove() } }
        trackedMarkers.clear()

        // 5. Layer（先于 Source 移除）
        if (style != null) {
            trackedLayers.forEach { runCatching { style.removeLayer(it) } }
        }
        trackedLayers.clear()

        // 6. Source
        if (style != null) {
            trackedSources.forEach { runCatching { style.removeSource(it) } }
        }
        trackedSources.clear()

        // 7. 取消协程
        scope.cancel()

        // 8. 从父节点移除自己
        parent?.let { p ->
            synchronized(p.children) { p.children.remove(this) }
        }
    }

    // ---------- 辅助 ----------

    private fun requireStyle(): Style =
        MapRuntime.currentStyle ?: error("地图尚未就绪")

    private fun requireMap(): MapLibreMap =
        MapRuntime.currentMap ?: error("地图尚未就绪")
}
```

### 3.3 `LocalMapSession.kt`

```kotlin
package org.kori.lib.geokori

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Compose 场景下的插件级 Session 入口。
 *
 * 插件入口在渲染 UI 前注入：
 * ```
* CompositionLocalProvider(LocalMapSession provides pluginSession) {
*     // 插件 UI 树
* }
* ```
*
* 任意 Composable（对话框、菜单、按钮等）都能通过 `LocalMapSession.current` 获取。
  */
  val LocalMapSession: ProvidableCompositionLocal<MapSession?> =
  staticCompositionLocalOf { null }
```

---

## 四、`geokori` 地图插件

### 4.1 `MapStyle.kt`

```kotlin
package org.kori.plugin.geo.map

enum class MapStyle(val uri: String) {
    LTIANDITU("https://your-tile-server/tianditu-style.json"),
    // 可扩展
}
```

### 4.2 `MapLibreMapView.kt`

```kotlin
package org.kori.plugin.geo.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.kori.lib.geokori.MapRuntime
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

@Composable
fun GeokoriMapScreen(
    modifier: Modifier = Modifier,
    styleUrl: String = MapStyle.LTIANDITU.uri,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(null) }
    }

    // 生命周期转发（ON_DESTROY 由 onDispose 统一收尾）
    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onStop()
            mapView.onDestroy()
            // ★ 地图销毁时，通知 MapRuntime
            MapRuntime.detach()
        }
    }

    // 样式与初始相机；就绪后交给 MapRuntime
    LaunchedEffect(mapView) {
        mapView.getMapAsync { map ->
            map.setStyle(Style.Builder().fromUri(styleUrl)) { style ->
                map.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(39.909, 116.397))
                    .zoom(5.0)
                    .build()
                // ★ 地图就绪后，暴露给所有依赖方插件
                MapRuntime.attach(style, map)
            }
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}
```

### 4.3 `PluginEntryClass.kt`

```kotlin
package org.kori.plugin.geo.map

import androidx.compose.runtime.Composable
import com.combo.core.api.IPluginEntryClass
import com.combo.core.model.Module
import com.combo.core.model.PluginContext

class PluginEntryClass : IPluginEntryClass {

    override val pluginModule: List<Module>
        get() = emptyList()

    @Composable
    override fun Content() {
        GeokoriMapScreen()
    }

    override fun onLoad(context: PluginContext) {
        // 地图状态通过 MapRuntime 单例暴露，此处无需额外逻辑
    }

    override fun onUnload() {
        // 地图销毁由 Compose 生命周期触发 MapRuntime.detach()
    }
}
```

---

## 五、`home` 依赖方插件

### 5.1 `PluginEntryClass.kt`

```kotlin
package org.kori.plugin.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.combo.core.api.IPluginEntryClass
import com.combo.core.model.Module
import com.combo.core.model.PluginContext
import org.kori.lib.geokori.LocalMapSession
import org.kori.lib.geokori.MapRuntime
import org.kori.lib.geokori.MapSession

class PluginEntryClass : IPluginEntryClass {

    override val pluginModule: List<Module>
        get() = emptyList()

    private lateinit var pluginSession: MapSession
    private lateinit var pluginId: String

    override fun onLoad(context: PluginContext) {
        // 从 PluginContext 取 pluginId（按你的实际字段名调整）
        pluginId = context.pluginId

        // 打开插件级 Session，注册到 MapRuntime.pluginSessions
        pluginSession = MapRuntime.openPluginSession(pluginId)
    }

    override fun onUnload() {
        // 关闭并注销插件级 Session（递归关闭所有子 session）
        MapRuntime.closePluginSession(pluginId)
    }

    @Composable
    override fun Content() {
        // 将插件级 Session 注入到 Compose 树
        CompositionLocalProvider(LocalMapSession provides pluginSession) {
            HomeScreen()
        }
    }
}
```

### 5.2 `HomeScreen.kt`

```kotlin
package org.kori.plugin.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.kori.lib.geokori.LocalMapSession
import org.maplibre.android.geometry.LatLng

@Composable
fun HomeScreen() {
    // 从 CompositionLocal 拿到插件级 Session
    val session = LocalMapSession.current ?: return

    var dialogVisible by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Button(onClick = {
            // 直接用插件级 session 添加要素
            session.addMarker(LatLng(39.909, 116.397))
        }) {
            Text("添加一个 Marker")
        }

        Button(onClick = {
            HomeActions.addHomeRoute(session)
        }) {
            Text("添加数据源 + 图层")
        }

        Button(onClick = { dialogVisible = true }) {
            Text("打开对话框")
        }
    }

    if (dialogVisible) {
        DemoDialog(onDismiss = { dialogVisible = false })
    }
}
```

### 5.3 `DemoDialog.kt`（子 Session 示例）

```kotlin
package org.kori.plugin.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.kori.lib.geokori.LocalMapSession
import org.maplibre.android.geometry.LatLng

@Composable
fun DemoDialog(onDismiss: () -> Unit) {
    val parentSession = LocalMapSession.current ?: return

    // 使用 child session，隔离本次对话框添加的要素
    val dialogSession = remember { parentSession.newChild("demo-dialog") }

    // 对话框关闭时自动清理 child session 添加的所有要素
    DisposableEffect(Unit) {
        onDispose { dialogSession.close() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("地图操作对话框") },
        text = {
            Column(modifier = Modifier.padding(8.dp)) {
                Button(onClick = {
                    // 只属于对话框的 Marker，对话框关闭时被移除
                    dialogSession.addMarker(LatLng(31.23, 121.47))
                }) {
                    Text("添加临时 Marker")
                }
                Button(onClick = {
                    dialogSession.addMarker(LatLng(23.13, 113.26))
                }) {
                    Text("添加另一个临时 Marker")
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("关闭") }
        }
    )
}
```

### 5.4 `HomeActions.kt`（非 Compose 对象示例）

```kotlin
package org.kori.plugin.home

import android.graphics.Color
import org.kori.lib.geokori.MapRuntime
import org.kori.lib.geokori.MapSession
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * 普通业务对象（非 Composable）。
 *
 * 使用方式：
 * - 方式 A：直接传入 Session（推荐）
 * - 方式 B：通过 MapRuntime.pluginSession(pluginId) 查询
 */
object HomeActions {

    private const val HOME_GEOJSON = """
        {"type":"FeatureCollection","features":[
          {"type":"Feature","geometry":{"type":"LineString",
            "coordinates":[[116.39,39.9],[116.41,39.9]]},
            "properties":{"name":"测试线"}}
        ]}
    """

    /** 方式 A：显式传入 Session */
    fun addHomeRoute(session: MapSession) {
        session.onReady { _, _ ->
            session.addSource(GeoJsonSource("home-src", HOME_GEOJSON))
            session.addLayer(
                LineLayer("home-line", "home-src").withProperties(
                    PropertyFactory.lineColor(Color.RED),
                    PropertyFactory.lineWidth(3f)
                )
            )
        }
    }

    /** 方式 B：通过 pluginId 查询 */
    fun addHomeRouteByPluginId(pluginId: String) {
        val session = MapRuntime.pluginSession(pluginId) ?: return
        addHomeRoute(session)
    }
}
```

### 5.5 `HomeViewModel.kt`（ViewModel 场景示例）

```kotlin
package org.kori.plugin.home

import androidx.lifecycle.ViewModel
import org.kori.lib.geokori.MapSession
import org.maplibre.android.geometry.LatLng

/**
 * ViewModel 场景：通过构造注入 MapSession。
 *
 * 由插件在 Koin 中注册：
 * ```
* single { HomeViewModel(get()) }
* single { MapRuntime.pluginSession(pluginId)!! }
* ```
*/
class HomeViewModel(
private val session: MapSession
) : ViewModel() {

    fun addMarkerAt(lat: Double, lng: Double) {
        session.onReady { _, _ ->
            session.addMarker(LatLng(lat, lng))
        }
    }
}
```

---

## 六、使用方式速查

| 场景 | 代码 |
|------|------|
| **插件入口** | `onLoad`: `MapRuntime.openPluginSession(pluginId)`；`onUnload`: `MapRuntime.closePluginSession(pluginId)` |
| **Compose UI（按钮、菜单）** | `LocalMapSession.current` → 直接 `session.addXxx(...)` |
| **对话框 / 临时面板** | `LocalMapSession.current?.newChild("xxx")` + `DisposableEffect { onDispose { child.close() } }` |
| **ViewModel** | 构造注入 `MapSession`（由 Koin 或手动创建） |
| **普通业务对象** | 构造注入 `MapSession`，或 `MapRuntime.pluginSession(pluginId)` |
| **一次性操作** | `MapRuntime.openDetachedSession("op")` + `session.close()` |

### Session API 一览

| 方法 | 说明 |
|------|------|
| `onReady { style, map -> }` | 地图就绪时执行一次 |
| `addSource(source)` / `addSource(id, source)` | 添加数据源（自动追踪） |
| `addLayer(layer)` / `addLayerBelow(layer, beforeId)` | 添加图层（自动追踪） |
| `addMarker(marker)` / `addMarker(latLng)` | 添加 Marker（自动追踪） |
| `onMapClick { latLng -> }` | 地图点击监听（自动追踪） |
| `onMapLongClick { latLng -> }` | 地图长按监听（自动追踪） |
| `onLayerClick(layerId) { feature -> }` | 图层点击，回调返回 Feature |
| `track(listener, attach, detach)` | 通用监听器追踪 |
| `removeSource(id)` / `removeLayer(id)` / `removeMarker(marker)` | 显式移除（同步追踪表） |
| `newChild(name)` | 创建子 Session |
| `close()` | 一键清理所有追踪内容 + 递归关闭子 Session |

---

## 七、生命周期时序

### 7.1 插件加载

```
PluginEntryClass.onLoad
└── MapRuntime.openPluginSession(pluginId)
└── 创建 pluginSession，注册到 pluginSessions

PluginEntryClass.Content
└── CompositionLocalProvider(LocalMapSession provides pluginSession)
└── HomeScreen / DemoDialog / 任意子 Composable
└── LocalMapSession.current 获取 session
```

### 7.2 对话框打开 / 关闭

```
打开对话框
└── val dialogSession = remember { parentSession.newChild("demo-dialog") }

对话框内添加临时要素
└── dialogSession.addMarker(...)   // 追踪在 dialogSession 内

关闭对话框
└── DisposableEffect.onDispose
└── dialogSession.close()
└── 只移除 dialogSession 添加的 Marker，不影响插件级 session
```

### 7.3 插件卸载

```
PluginEntryClass.onUnload
└── MapRuntime.closePluginSession(pluginId)
└── pluginSession.close()
├── 递归关闭所有 child session
├── 移除所有 source/layer/marker/listener
└── 从 pluginSessions 表中移除
```

### 7.4 插件热更新

```
1. 旧实例 onUnload → closePluginSession → 所有 session 关闭，要素全清
2. 新实例 onLoad → openPluginSession → 创建全新 session
3. Compose 树重建 → CompositionLocalProvider 注入新 session
4. 所有组件通过 LocalMapSession.current 拿到新 session
```

### 7.5 地图插件加载 / 卸载

```
加载：
PluginEntryClass.Content → GeokoriMapScreen
└── setStyle 完成 → MapRuntime.attach(style, map)

卸载：
Compose 树销毁 → GeokoriMapScreen.onDispose
├── mapView.onDestroy()
└── MapRuntime.detach()
```

---

## 八、关键设计点总结

| 关注点 | 方案 |
|--------|------|
| **跨插件共享 MapLibre 类型** | 宿主 `implementation`，插件 `compileOnly` |
| **依赖方直接使用 MapLibre 对象** | `session.addSource(GeoJsonSource(...))` / `addLayer(LineLayer(...))` / `addMarker(...)` |
| **自动追踪 + 一键清理** | `MapSession` 记录每次 add，`close()` 按依赖顺序清理 |
| **子 Session 隔离** | `parent.newChild("xxx")`，`close()` 只清理自己 |
| **级联关闭** | 父 `close()` 递归关闭所有子 |
| **任意对象访问** | Compose: `LocalMapSession`；非 Compose: 构造注入 / `MapRuntime.pluginSession(pluginId)` |
| **地图就绪异步** | `session.onReady { style, map -> }` |
| **图层点击便捷 API** | `session.onLayerClick(layerId) { feature -> ... }` |
| **通用监听器** | `session.track(listener, attach, detach)` |
| **热更新兼容** | 插件卸载 → 关闭所有 session；加载 → 重建 |
| **ClassLoader 安全** | 宿主统一加载 MapLibre + lib-geokori，插件全部 `compileOnly` |

---

## 九、ClassLoader 三条铁律

### 9.1 三条规则

1. **宿主 `implementation`**：MapLibre、`lib-geokori`、`core` 必须由宿主 `implementation` 依赖
2. **插件 `compileOnly`**：上述所有跨插件共享依赖，插件必须用 `compileOnly`
3. **版本严格一致**：所有模块引用相同版本的 MapLibre 和 Compose

### 9.2 依赖类型速查表

| 依赖 | 宿主 | 插件 | 原因 |
|------|------|------|------|
| MapLibre SDK | `implementation` | `compileOnly` | 跨插件传递 `Marker`/`Style`/`Layer` 等对象 |
| `lib-geokori` | `implementation` | `compileOnly` | `MapRuntime`/`MapSession` 需要全局单例 |
| `core` | `implementation` | `compileOnly` | `PluginManager`/`IPluginEntryClass` 由宿主提供 |
| Compose Runtime | `implementation` | `compileOnly` | `CompositionLocal` 需要共享 |
| 插件内部工具（gson 等） | — | `implementation` | 不跨插件传递 |
| 公共库模块（`lib-geokori`） | `api` | — | 供依赖方传递使用 |

### 9.3 判断标准

- **类的实例要跨插件传递** → `compileOnly`
- **类需要全局单例共享** → `compileOnly`
- **插件内部专用** → `implementation`
- **公共库供下游使用** → `api`

### 9.4 常见错误

| 错误做法 | 后果 |
|---------|------|
| 插件用 `implementation` 引入 MapLibre | `ClassCastException`：两个不同 ClassLoader 的 `GeoJsonSource` |
| 插件用 `implementation` 引入 `lib-geokori` | `MapRuntime` 单例不共享，每个插件一份 |
| 宿主未 `implementation` 依赖 | `ClassNotFoundException`：运行时找不到类 |
| 版本不一致 | `NoSuchMethodError` / `NoClassDefFoundError` |

### 9.5 验证方法

```kotlin
// 在任意插件中打印 ClassLoader
Log.d("CL", "GeoJsonSource CL: ${GeoJsonSource::class.java.classLoader}")
Log.d("CL", "MapRuntime CL: ${MapRuntime::class.java.classLoader}")
Log.d("CL", "当前插件 CL: ${this::class.java.classLoader}")
```

预期：`GeoJsonSource` 和 `MapRuntime` 的 ClassLoader **相同**（都是宿主 ClassLoader），与当前插件 ClassLoader **不同**。

---

## 附录：快速开始清单

新建一个使用地图的插件，只需：

1. **`build.gradle.kts`** 声明 `compileOnly` 依赖（MapLibre、lib-geokori、core、Compose）
2. **`PluginEntryClass`**：
    - `onLoad` → `MapRuntime.openPluginSession(pluginId)`
    - `onUnload` → `MapRuntime.closePluginSession(pluginId)`
    - `Content` → `CompositionLocalProvider(LocalMapSession provides pluginSession) { ... }`
3. **UI 组件**：`LocalMapSession.current` 获取 session，直接 `addSource` / `addLayer` / `addMarker` / `onLayerClick`
4. **临时场景**（对话框等）：`parentSession.newChild("xxx")` + `DisposableEffect { onDispose { child.close() } }`
5. **普通对象**：构造注入 `MapSession`，或 `MapRuntime.pluginSession(pluginId)`

---

**文档结束**

> 本设计基于 ComboLite 插件化框架 + MapLibre Native 11.x + Jetpack Compose 构建，核心思路是**利用宿主统一 ClassLoader 加载 MapLibre 与 lib-geokori，使跨插件传递复杂 SDK 对象安全无虞；通过 Session 会话机制自动追踪要素与监听器，实现一键清理；通过插件级 Session + CompositionLocal + 子 Session 三级结构，让任意对象都能便捷访问地图**。