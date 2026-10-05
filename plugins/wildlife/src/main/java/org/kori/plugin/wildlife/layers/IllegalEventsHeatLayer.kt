package org.kori.plugin.wildlife.layers

import android.content.Context
import android.graphics.Color
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cwcc.open.geokori.map.LibreMapLayer
import org.cwcc.open.geokori.map.MapLayerManager
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

/**
 * 盗猎事件热力图图层（合并版：渲染实现 + 框架 [MapLayer] 生命周期一体）。
 *
 * 相对拆分版的变化：
 *  · 直接继承 [MapLayer] —— 不再需要 IllegalEventsMapLayer 转发壳
 *  · Session 由 [MapLayerManager] 在 toggle 时自动注入 [onAttach]
 *  · 加载结果直接调 notifyLoaded()/notifyFailed()，回调参数删除
 *  · onDetach 负责精确移除 source/layer；close() 由基类收尾
 *
 * 渲染行为不变：API GeoJSON → 热力/圆点/标注三层，zoom 11 分界切换；
 * 10 分钟缓存；close 不取消在途请求（写缓存供下次秒开）。
 *
 * 注册随类加载完成（伴生对象 init），插件加载即就绪。
 */
class IllegalEventsHeatLayer : LibreMapLayer() {

    companion object {
        const val OWNER_PLUGIN_ID = "org.kori.plugin.wildlife"
        const val LAYER_ID = "illegal-events"
        val FULL_ID: String = MapLayerManager.fullId(OWNER_PLUGIN_ID, LAYER_ID)

        init {
            MapLayerManager.register(OWNER_PLUGIN_ID, LAYER_ID) { IllegalEventsHeatLayer() }
        }

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

    // =============================================================================================
    // 状态
    // =============================================================================================

    override val displayName: String = "盗猎情况"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile private var mVisible = true

    private var context: Context? = null
    private var map: MapLibreMap? = null
    private var isLoaded = false
    private var appliedHeatMode: Boolean? = null

    // =============================================================================================
    // MapLayer 生命周期（Session 由管理器自动注入）
    // =============================================================================================

    override fun onAttach(session: MapSession, context: Context) {
        this.context = context
        // Session 已注入；地图可能未就绪，onReady 等待
        session.onReady { _, m ->
            if (!isActive) return@onReady
            map = m
            registerListeners(m)
            loadData()
        }
    }

    override fun onDetach() {
        removeMapObjects()
        isLoaded = false
    }

    // =============================================================================================
    // 监听器（session.track：插件卸载自动注销）
    // =============================================================================================

    private fun registerListeners(map: MapLibreMap) {
        currentSession()?.track(
            MapLibreMap.OnCameraMoveListener {
                if (!isActive || !isLoaded || !mVisible) return@OnCameraMoveListener
                applyVisibilityByZoom(map.cameraPosition.zoom)
            },
            attach = { map.addOnCameraMoveListener(it) },
            detach = { map.removeOnCameraMoveListener(it) },
        )
        currentSession()?.track(
            MapLibreMap.OnMapClickListener { latLng -> onMapClicked(latLng) },
            attach = { map.addOnMapClickListener(it) },
            detach = { map.removeOnMapClickListener(it) },
        )
    }

    private fun onMapClicked(latLng: LatLng): Boolean {
        if (!isActive || !isLoaded || !mVisible) return false
        val m = map ?: return false
        if (m.cameraPosition.zoom < HEAT_MAX_ZOOM) return false
        if (showAttrTable(latLng, CIRCLE_LAYER_ID)) return true
        return showAttrTable(latLng, SYMBOL_LAYER_ID)
    }

    // =============================================================================================
    // 数据加载（缓存优先）
    // =============================================================================================

    private fun loadData() {
        // 缓存命中：直接建图层
        cacheGet()?.let { cached ->
            if (!isActive) return
            setupLayers(cached)
            applyVisibilityByZoom(map?.cameraPosition?.zoom ?: 0.0, force = true)
            isLoaded = true
            Log.d(TAG, "setup from cache, isLoaded=true")
            notifyLoaded()
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
                    // 已卸载（close/detach）就只留缓存，不动地图
                    if (!isActive) {
                        Log.d(TAG, "detached during load, cache kept for next open")
                        return@withContext
                    }
                    setupLayers(collection)
                    applyVisibilityByZoom(map?.cameraPosition?.zoom ?: 0.0, force = true)
                    isLoaded = true
                    Log.d(TAG, "setup complete, isLoaded=true")
                    notifyLoaded()
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load data", e)
                withContext(Dispatchers.Main) { notifyFailed() }
            }
        }
    }

    private fun fetchGeoJsonFromApi(): String {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(API_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_0000
                readTimeout = 15_0000
                setRequestProperty("Accept", "application/geo+json")
            }
            connection.inputStream.use { it.bufferedReader().use { reader -> reader.readText() } }
        } finally {
            connection?.disconnect()
        }
    }

    // =============================================================================================
    // 图层构建（全部经 session）
    // =============================================================================================

    private fun setupLayers(collection: FeatureCollection) {
        removeMapObjects()

        if (collection.features().isNullOrEmpty()) {
            Log.w(TAG, "No features to display")
            return
        }

        currentSession()?.addSource(
            GeoJsonSource(
                SOURCE_ID,
                collection,
                GeoJsonOptions()
                    .withCluster(false)
                    .withTolerance(0f)
                    .withBuffer(512),
            ),
        )
        currentSession()?.addLayer(buildHeatmapLayer())
        currentSession()?.addLayer(buildCircleLayer())
        currentSession()?.addLayer(buildSymbolLayer())
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
    // 显隐切换
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
    // 属性表 / 公开 API
    // =============================================================================================

    private fun showAttrTable(pnt: LatLng, layerId: String): Boolean {
        val m = map ?: return false
        val features = m.queryRenderedFeatures(m.projection.toScreenLocation(pnt), layerId)
        val feature = features.firstOrNull() ?: return false
        // 属性表弹窗（XPopup）需要时在此恢复实现
        return true
    }

    fun setVisible(isVisible: Boolean) {
        mVisible = isVisible
        if (isLoaded) {
            applyVisibilityByZoom(map?.cameraPosition?.zoom ?: 0.0, force = true)
        }
    }

    fun isVisible(): Boolean = mVisible

    /** 强制重拉（清缓存 + 重建）。 */
    fun refresh() {
        if (!isActive) return
        isLoaded = false
        cacheInvalidate()
        removeMapObjects()
        loadData()
    }

    private fun removeMapObjects() {
        currentSession()?.removeLayer(HEATMAP_LAYER_ID)
        currentSession()?.removeLayer(CIRCLE_LAYER_ID)
        currentSession()?.removeLayer(SYMBOL_LAYER_ID)
        currentSession()?.removeSource(SOURCE_ID)
    }
}