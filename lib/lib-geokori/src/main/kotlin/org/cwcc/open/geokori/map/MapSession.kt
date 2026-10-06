package org.cwcc.open.geokori.map

import android.graphics.PointF
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.sources.Source
import org.maplibre.geojson.Feature


/**
 * 地图图层声明（可选）。配合框架的插件类扫描自动注册：
 * 插件 Session 打开时扫描插件 ClassLoader，凡 [LibreMapLayer] 非抽象子类自动注册。
 *
 * @param id 图层 id；空则默认取全限定类名（如 org.kori.plugin.wildlife.layers.IllegalEventsHeatLayer），
 * 跨插件天然唯一。需要短 id（如 "illegal-events"）时显式指定。
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class GeoKoriLayer(val id: String = "")
/**
 * 地图会话。
 *
 * - 通过 session 添加的要素/监听器都会被追踪，[close] 时一并移除。
 * - 支持父子嵌套：[newChild] 创建子 session；父 close 会递归 close 所有子。
 * - 所有 add/close 操作应在主线程调用（onReady 回调已在主线程）。
 *
 * @param owner    显示/路径名（子 session 会变为 "owner/child"）
 * @param pluginId 所属插件 id（根 session 由 MapRuntime.openPluginSession 写入，子 session 继承，全程不变）
 */
class MapSession internal constructor(
    val owner: String,
    val pluginId: String,
    private val parent: MapSession?,
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
        val child = MapSession(
            owner = "$owner/$childName",
            pluginId = pluginId,
            parent = this,
        )
        synchronized(children) { children.add(child) }
        return child
    }

    // ---------- 追踪列表 ----------

    private val trackedSources = mutableSetOf<String>()
    private val trackedLayers = mutableSetOf<String>()
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

        // 4. Layer（先于 Source 移除）
        if (style != null) {
            trackedLayers.forEach { runCatching { style.removeLayer(it) } }
        }
        trackedLayers.clear()

        // 5. Source
        if (style != null) {
            trackedSources.forEach { runCatching { style.removeSource(it) } }
        }
        trackedSources.clear()

        // 6. 取消协程
        scope.cancel()

        // 7. 从父节点移除自己
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