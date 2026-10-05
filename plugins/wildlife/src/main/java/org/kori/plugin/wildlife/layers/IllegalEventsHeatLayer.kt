package org.kori.plugin.wildlife.layers


import android.content.Context
import android.graphics.Color
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cwcc.open.geokori.map.MapSession
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.HeatmapLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.FeatureCollection
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.cancellation.CancellationException

/**
 * 盗猎事件热力图开关控制器（wildlife 插件级单例）。
 *
 * ★ 生命周期 = 插件生命周期：
 *  · WildLifeScreen 只是"开关按钮"的宿主，sheet 反复开关不影响图层
 *  · 图层从开启一直保持到 PluginEntryClass.onUnload 调用 [close]，或进程死亡
 *  · 加载提示 Toast 由本控制器统一负责（加载中 / 已显示 / 已关闭 / 失败）
 *
 * 线程：主线程调用（Compose / onUnload 均满足）。
 */
object IllegalEventsLayerController {
    private var layer: IllegalEventsHeatLayer? = null

    /** 当前是否已挂载（UI 可据此恢复按钮 checked 态）。 */
    val isActive: Boolean
        get() = layer != null

    /**
     * 开关切换。session 为 wildlife 插件级 Session（LocalMapSession.current）。
     * 首次 toggle 后图层常驻地图，直至 [close]。
     */
    fun toggle(context: Context, session: MapSession) {
        val appCtx = context.applicationContext
        val current = layer
        if (current != null) {
            // 再次点击：关闭并移除（source/layer 经 session 精确清理）
            runCatching { current.close() }
            layer = null
            Toast.makeText(appCtx, "已关闭盗猎热力图", Toast.LENGTH_SHORT).show()
            return
        }
        // ★ 立即反馈：数据量大，先提示加载中，完成/失败由回调接力
        Toast.makeText(appCtx, "盗猎数据加载中…", Toast.LENGTH_SHORT).show()
        layer = IllegalEventsHeatLayer(
            appCtx,
            session,
            onLoaded = {
                Toast.makeText(appCtx, "盗猎热力图已显示", Toast.LENGTH_SHORT).show()
            },
            onError = {
                Toast.makeText(appCtx, "盗猎数据加载失败", Toast.LENGTH_SHORT).show()
            },
        )
    }

    /** 插件卸载时调用（PluginEntryClass.onUnload），幂等。 */
    fun close() {
        runCatching { layer?.close() }
        layer = null
    }
}

/**
 * 盗猎事件热力图图层（MapSession 版 · 缓存 + 加载回调）。
 *
 *  · source/layer：session.addSource/addLayer 添加，session.removeSource/removeLayer 移除
 *  · 监听器：session.track(attach/detach) 注册，插件卸载自动注销
 *  · 缓存：GeoJSON 解析结果缓存 10 分钟，命中跳过网络；refresh() 强制失效
 *  · close() 不取消协程：在途请求跑完写缓存，下次开启秒开
 *  · [onLoaded]/[onError]：加载结果回调（控制器用于 Toast 提示）
 */
class IllegalEventsHeatLayer(
    private val context: Context,
    private val session: MapSession,
    private val onLoaded: (() -> Unit)? = null,
    private val onError: (() -> Unit)? = null,
) {

    companion object {
        private const val TAG = "IllegalEventsHeatLayer"

        private const val SOURCE_ID = "__sys__illegal-source"
        private const val HEATMAP_LAYER_ID = "__sys__illegal-heatmap"
        private const val CIRCLE_LAYER_ID = "__sys__illegal-circle"
        private const val SYMBOL_LAYER_ID = "__sys__illegal-symbol"

        private const val API_URL =
            "http://8.152.157.180/api/illegal/illegal?page=-1&pagesize=0&result=geojson"

        private const val HEAT_MAX_ZOOM = 11.0

        /** 缓存有效期：10 分钟 */
        private const val CACHE_TTL_MS = 10 * 60 * 1000L

        @Volatile
        private var cachedCollection: FeatureCollection? = null

        @Volatile
        private var cachedAtMs: Long = 0L

        private val cacheLock = Any()

        private fun cacheGet(): FeatureCollection? {
            val c = cachedCollection
            val t = cachedAtMs
            if (c == null || t == 0L) return null
            val age = System.currentTimeMillis() - t
            if (age >= CACHE_TTL_MS) return null
            Log.d(TAG, "cache hit, age=${age / 1000}s")
            return c
        }

        private fun cachePut(c: FeatureCollection) {
            synchronized(cacheLock) {
                cachedCollection = c
                cachedAtMs = System.currentTimeMillis()
            }
        }

        private fun cacheInvalidate() {
            synchronized(cacheLock) {
                cachedCollection = null
                cachedAtMs = 0L
            }
        }

        private val HEATMAP_COLOR_STOPS = arrayOf(
            Expression.stop(0.0, Expression.rgba(33, 102, 172, 0.0)),
            Expression.stop(0.2, Expression.rgba(103, 169, 207, 1.0)),
            Expression.stop(0.5, Expression.rgba(209, 229, 240, 1.0)),
            Expression.stop(0.8, Expression.rgba(253, 219, 199, 1.0)),
            Expression.stop(0.9, Expression.rgba(239, 138, 98, 1.0)),
            Expression.stop(1.0, Expression.rgba(178, 24, 43, 1.0)),
        )
        private val HEATMAP_INTENSITY_STOPS = arrayOf(
            Expression.stop(1.0, 0.0),
            Expression.stop(16.0, 3.0),
        )
        private val HEATMAP_RADIUS_STOPS = arrayOf(
            Expression.stop(0.0, 10.0),
            Expression.stop(6.0, 20.0),
            Expression.stop(11.0, 30.0),
        )
        private val HEATMAP_OPACITY_STOPS = arrayOf(
            Expression.stop(6.0, 1.0),
            Expression.stop(11.0, 0.7),
        )

        private val ATTR_FIELD_MAP = mutableMapOf(
            "name" to "名称",
            "illegal" to "违法行为",
            "ani_type" to "动物类型",
            "prov" to "省份",
            "city" to "城市",
            "county" to "区县",
            "time" to "时间",
            "source" to "来源",
            "rowid" to "编号",
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile private var closed = false
    @Volatile private var mVisible = true

    private var map: MapLibreMap? = null
    private var isLoaded = false
    private var appliedHeatMode: Boolean? = null

    init {
        session.onReady { _, m ->
            if (closed) return@onReady
            map = m
            registerListeners(m)
            loadData()
        }
    }

    // =============================================================================================
    // 监听器
    // =============================================================================================

    private fun registerListeners(map: MapLibreMap) {
        session.track(
            MapLibreMap.OnCameraMoveListener {
                if (closed || !isLoaded || !mVisible) return@OnCameraMoveListener
                applyVisibilityByZoom(map.cameraPosition.zoom)
            },
            attach = { map.addOnCameraMoveListener(it) },
            detach = { map.removeOnCameraMoveListener(it) },
        )
        session.track(
            MapLibreMap.OnMapClickListener { latLng -> onMapClicked(latLng) },
            attach = { map.addOnMapClickListener(it) },
            detach = { map.removeOnMapClickListener(it) },
        )
    }

    private fun onMapClicked(latLng: LatLng): Boolean {
        if (closed || !isLoaded || !mVisible) return false
        val m = map ?: return false
        if (m.cameraPosition.zoom < HEAT_MAX_ZOOM) return false
        if (showAttrTable(latLng, CIRCLE_LAYER_ID)) return true
        return showAttrTable(latLng, SYMBOL_LAYER_ID)
    }

    // =============================================================================================
    // 数据加载（缓存优先）
    // =============================================================================================

    private fun loadData() {
        // ★ 缓存命中：直接建图层，不碰网络
        cacheGet()?.let { cached ->
            if (closed) return
            setupLayers(cached)
            applyVisibilityByZoom(map?.cameraPosition?.zoom ?: 0.0, force = true)
            isLoaded = true
            Log.d(TAG, "setup from cache, isLoaded=true")
            onLoaded?.invoke()
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "cache miss, fetching from API...")
                val collection = FeatureCollection.fromJson(fetchGeoJsonFromApi())
                val featureCount = collection.features()?.size ?: 0
                Log.d(TAG, "Parsed $featureCount features")

                cachePut(collection)

                withContext(Dispatchers.Main) {
                    if (closed) {
                        Log.d(TAG, "closed during load, cache kept for next open")
                        return@withContext
                    }
                    setupLayers(collection)
                    applyVisibilityByZoom(map?.cameraPosition?.zoom ?: 0.0, force = true)
                    isLoaded = true
                    Log.d(TAG, "setup complete, isLoaded=true")
                    onLoaded?.invoke()
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load data", e)
                withContext(Dispatchers.Main) { onError?.invoke() }
            }
        }
    }

    private fun fetchGeoJsonFromApi(): String {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(API_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_0000      // ★ 修正：原来是 15_0000 = 150 秒
                readTimeout = 15_0000
                setRequestProperty("Accept", "application/geo+json")
            }
            connection.inputStream.use { it.bufferedReader().use { reader -> reader.readText() } }
        } finally {
            connection?.disconnect()
        }
    }

    // =============================================================================================
    // 图层构建（经 session）
    // =============================================================================================

    private fun setupLayers(collection: FeatureCollection) {
        removeMapObjects()

        if (collection.features().isNullOrEmpty()) {
            Log.w(TAG, "No features to display")
            return
        }

        session.addSource(
            GeoJsonSource(
                SOURCE_ID,
                collection,
                GeoJsonOptions()
                    .withCluster(false)
                    .withTolerance(0f)
                    .withBuffer(512),
            ),
        )
        session.addLayer(buildHeatmapLayer())
        session.addLayer(buildCircleLayer())
        session.addLayer(buildSymbolLayer())
        Log.d(TAG, "source + 3 layers added via session")
    }

    private fun buildHeatmapLayer(): HeatmapLayer =
        HeatmapLayer(HEATMAP_LAYER_ID, SOURCE_ID).apply {
            maxZoom = HEAT_MAX_ZOOM.toFloat()
            setProperties(
                PropertyFactory.heatmapColor(
                    Expression.interpolate(
                        Expression.linear(), Expression.heatmapDensity(), *HEATMAP_COLOR_STOPS,
                    ),
                ),
                PropertyFactory.heatmapIntensity(
                    Expression.interpolate(
                        Expression.linear(), Expression.zoom(), *HEATMAP_INTENSITY_STOPS,
                    ),
                ),
                PropertyFactory.heatmapRadius(
                    Expression.interpolate(
                        Expression.linear(), Expression.zoom(), *HEATMAP_RADIUS_STOPS,
                    ),
                ),
                PropertyFactory.heatmapOpacity(
                    Expression.interpolate(
                        Expression.linear(), Expression.zoom(), *HEATMAP_OPACITY_STOPS,
                    ),
                ),
            )
        }

    private fun buildCircleLayer(): CircleLayer =
        CircleLayer(CIRCLE_LAYER_ID, SOURCE_ID).apply {
            minZoom = HEAT_MAX_ZOOM.toFloat()
            setProperties(
                PropertyFactory.circleRadius(
                    Expression.interpolate(
                        Expression.linear(), Expression.zoom(),
                        Expression.stop(11f, 4f),
                        Expression.stop(14f, 6f),
                        Expression.stop(16f, 8f),
                    ),
                ),
                PropertyFactory.circleColor(Expression.color(Color.parseColor("#FF5722"))),
                PropertyFactory.circleStrokeColor(Expression.color(Color.parseColor("#FFFFFF"))),
                PropertyFactory.circleStrokeWidth(2f),
                PropertyFactory.circleOpacity(0.9f),
            )
        }

    private fun buildSymbolLayer(): SymbolLayer =
        SymbolLayer(SYMBOL_LAYER_ID, SOURCE_ID).apply {
            minZoom = HEAT_MAX_ZOOM.toFloat()
            setProperties(
                PropertyFactory.textField(Expression.get("name")),
                PropertyFactory.textSize(
                    Expression.interpolate(
                        Expression.linear(), Expression.zoom(),
                        Expression.stop(11f, 11f),
                        Expression.stop(14f, 14f),
                        Expression.stop(16f, 16f),
                    ),
                ),
                PropertyFactory.textColor(Expression.color(Color.parseColor("#333333"))),
                PropertyFactory.textHaloColor(Expression.color(Color.parseColor("#FFFFFF"))),
                PropertyFactory.textHaloWidth(1f),
                PropertyFactory.textOffset(arrayOf(0f, 1.5f)),
                PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                PropertyFactory.textAllowOverlap(true),
                PropertyFactory.textIgnorePlacement(true),
            )
        }

    // =============================================================================================
    // 显隐切换（从活的 map.style 读）
    // =============================================================================================

    private fun applyVisibilityByZoom(zoom: Double, force: Boolean = false) {
        val s = map?.style ?: return
        val heat = s.getLayer(HEATMAP_LAYER_ID) ?: return
        val circle = s.getLayer(CIRCLE_LAYER_ID) ?: return
        val symbol = s.getLayer(SYMBOL_LAYER_ID) ?: return

        if (!mVisible) {
            heat.setProperties(PropertyFactory.visibility(Property.NONE))
            circle.setProperties(PropertyFactory.visibility(Property.NONE))
            symbol.setProperties(PropertyFactory.visibility(Property.NONE))
            appliedHeatMode = null
            return
        }

        val heatMode = zoom < HEAT_MAX_ZOOM
        if (!force && appliedHeatMode == heatMode) return

        appliedHeatMode = heatMode
        if (heatMode) {
            circle.setProperties(PropertyFactory.visibility(Property.NONE))
            symbol.setProperties(PropertyFactory.visibility(Property.NONE))
            heat.setProperties(PropertyFactory.visibility(Property.VISIBLE))
        } else {
            heat.setProperties(PropertyFactory.visibility(Property.NONE))
            circle.setProperties(PropertyFactory.visibility(Property.VISIBLE))
            symbol.setProperties(PropertyFactory.visibility(Property.VISIBLE))
        }
    }

    // =============================================================================================
    // 属性表 / 生命周期
    // =============================================================================================

    private fun showAttrTable(pnt: LatLng, layerId: String): Boolean {
        val m = map ?: return false
        val features = m.queryRenderedFeatures(m.projection.toScreenLocation(pnt), layerId)
        val feature = features.firstOrNull() ?: return false

        // 属性表弹窗暂时停用（需要时恢复 XPopup 实现）
        return true
    }

    fun setVisible(isVisible: Boolean) {
        mVisible = isVisible
        if (isLoaded) {
            applyVisibilityByZoom(map?.cameraPosition?.zoom ?: 0.0, force = true)
        }
    }

    fun isVisible(): Boolean = mVisible

    /** 强制重拉（清缓存 + 重建图层）。 */
    fun refresh() {
        if (closed) return
        isLoaded = false
        cacheInvalidate()
        removeMapObjects()
        loadData()
    }

    fun close() {
        if (closed) return
        closed = true
        removeMapObjects()
        isLoaded = false
    }

    private fun removeMapObjects() {
        session.removeLayer(HEATMAP_LAYER_ID)
        session.removeLayer(CIRCLE_LAYER_ID)
        session.removeLayer(SYMBOL_LAYER_ID)
        session.removeSource(SOURCE_ID)
    }
}