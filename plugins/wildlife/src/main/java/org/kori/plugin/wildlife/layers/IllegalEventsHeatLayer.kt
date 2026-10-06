package org.kori.plugin.wildlife.layers

import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import org.cwcc.open.geokori.map.BaseBizeLibreLayer
import org.cwcc.open.geokori.map.GeoKoriLayer
import org.cwcc.open.geokori.map.MapRuntime
import org.json.JSONObject
import org.kori.plugin.wildlife.layers.IllegalEventsHeatLayer.Companion.LAYER_ID
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.FeatureCollection
import timber.log.Timber
import androidx.core.graphics.toColorInt
import org.kori.plugin.wildlife.ui.IllegalEventAttrSheet

/**
 * 盗猎事件热力图图层。
 *
 * 注册：零代码（@GeoKoriLayer + 继承 BaseWildLifeLayer）。
 * 通用数据管道/缓存/显隐/refresh 均在基类，本类只声明：
 *  · 接口地址与超时
 *  · summary 总次数解析（免全量校验）
 *  · 三层组装与 zoom 11 分界显隐
 *  · 点击要素弹属性底部弹窗（附件跑马灯 + 全屏浏览，见 IllegalEventAttrSheet）
 */
@GeoKoriLayer(LAYER_ID)
class IllegalEventsHeatLayer : BaseBizeLibreLayer() {

    companion object {
        private const val TAG = "IllegalEventsHeatLayer"

        /** 图层 id（唯一事实源：注册注解与业务 action 均引用本常量） */
        const val LAYER_ID = "illegal-events"

        private const val SOURCE_ID = "__sys__illegal-source"
        private const val HEATMAP_LAYER_ID = "__sys__illegal-heatmap"
        private const val CIRCLE_LAYER_ID = "__sys__illegal-circle"
        private const val SYMBOL_LAYER_ID = "__sys__illegal-symbol"

        private const val API_URL =
            "http://8.152.157.180/api/illegal/illegal?page=-1&pagesize=0&result=geojson"

        /** 轻量统计接口：用于快速判断全量数据是否有变化 */
        private const val SUMMARY_URL = "http://8.152.157.180/api/illegal/summary"

        private const val HEAT_MAX_ZOOM = 11.0

        /** summary 接口超时（轻量接口，短超时快速失败） */
        private const val SUMMARY_TIMEOUT_MS = 10_000

        /** 全量接口超时（数据量大，放宽到 60s） */
        private const val GEOJSON_TIMEOUT_MS = 60_000

        private const val SUMMARY_TOTAL_NAME = "总次数"

        private val ATTR_FIELD_MAP = linkedMapOf(
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

        /** 不参与属性列表展示的字段（附件元数据） */
        private val HIDDEN_FIELDS = setOf("att_ids", "img_types")
    }

    // =============================================================================================
    // 基类抽象点实现
    // =============================================================================================

    override val displayName: String = "盗猎情况"

    override val cacheDataFile: String = "illegal_events_cache.geojson"
    override val cacheMetaFile: String = "illegal_events_cache.meta"

    /** 全量 GeoJSON 拉取（IO 线程） */
    override fun fetchRemoteGeoJson(): String =
        httpGet(API_URL, GEOJSON_TIMEOUT_MS, "application/geo+json")

    /** summary “总次数”解析；失败返回 null（基类按"未知"处理，本地未过期仍直接用） */
    override fun fetchSummaryCount(): Int? {
        val body = try {
            httpGet(SUMMARY_URL, SUMMARY_TIMEOUT_MS)
        } catch (e: Exception) {
            return null
        }
        return try {
            val rows = JSONObject(body).getJSONArray("rows")
            var total: Int? = null
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                if (row.getString("name") == SUMMARY_TOTAL_NAME) {
                    total = row.getInt("value")
                    break
                }
            }
            total
        } catch (e: Exception) {
            null
        }
    }

    override fun onClearMapObjects() {
        removeMapObjects()
    }

    /** 数据就绪（主线程）：组装 热力/圆点/注记 三层 */
    override fun onCollectionLoaded(collection: FeatureCollection) {
        removeMapObjects()
        if (collection.features().isNullOrEmpty()) return

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

        // 数据重建后重放透明度与显隐（滑杆/相机状态可能已偏离默认值）
        onAlphaChanged(mAlpha)
        applyVisibilityByZoom(map?.cameraPosition?.zoom ?: 0.0, force = true)
    }

    override fun onAlphaChanged(alpha: Float) {
        val s = map?.style ?: return
        // 热力：zoom 必须保持顶层 interpolate 输入，alpha 折算进各 stop 输出
        s.getLayer(HEATMAP_LAYER_ID)?.setProperties(
            PropertyFactory.heatmapOpacity(
                Expression.interpolate(
                    Expression.linear(), Expression.zoom(),
                    Expression.stop(6.0, 1.0 * alpha.toDouble()),
                    Expression.stop(11.0, 0.7 * alpha.toDouble()),
                ),
            ),
        )
        // 圆点：原始 0.9
        s.getLayer(CIRCLE_LAYER_ID)?.setProperties(
            PropertyFactory.circleOpacity(0.9f * alpha),
        )
        // 注记：原始未设（=1.0）
        s.getLayer(SYMBOL_LAYER_ID)?.setProperties(
            PropertyFactory.textOpacity(alpha),
        )
    }

    // =============================================================================================
    // 生命周期（onAttach 基类已自动 loadCollection，无需覆盖）
    // =============================================================================================

    override fun onMapReady(map: MapLibreMap) {
        registerListeners(map)
        mountSheetHost()
        // 数据先到、地图后就绪时补一次显隐
        if (isLoaded) {
            applyVisibilityByZoom(map.cameraPosition.zoom, force = true)
        }
    }

    override fun onDetach() {
        super.onDetach()
        appliedHeatMode = null
        IllegalEventAttrSheet.dismiss()
        unmountSheetHost()
    }

    // =============================================================================================
    // 图层构建（zoom 11 分界：低倍热力、高倍圆点+注记）
    // =============================================================================================

    private fun buildHeatmapLayer() =
        heatmapLayer(HEATMAP_LAYER_ID, SOURCE_ID, maxZoom = HEAT_MAX_ZOOM.toFloat())

    private fun buildCircleLayer() =
        circleLayer(CIRCLE_LAYER_ID, SOURCE_ID, minZoom = HEAT_MAX_ZOOM.toFloat()).apply {
            // 覆盖基类默认样式：盗猎事件用警示橙
            setProperties(
                PropertyFactory.circleRadius(
                    Expression.interpolate(
                        Expression.linear(), Expression.zoom(),
                        Expression.stop(11f, 4f),
                        Expression.stop(14f, 6f),
                        Expression.stop(16f, 8f),
                    ),
                ),
                PropertyFactory.circleColor(Expression.color("#FF5722".toColorInt())),
                PropertyFactory.circleStrokeColor(Expression.color("#FFFFFF".toColorInt())),
                PropertyFactory.circleStrokeWidth(2f),
                PropertyFactory.circleOpacity(0.9f),
            )
        }

    private fun buildSymbolLayer() =
        symbolLayer(SYMBOL_LAYER_ID, SOURCE_ID, minZoom = HEAT_MAX_ZOOM.toFloat()).apply {
            // 覆盖基类默认样式：字段/字号/描边按盗猎数据定制
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
                PropertyFactory.textColor(Expression.color("#333333".toColorInt())),
                PropertyFactory.textHaloColor(Expression.color("#FFFFFF".toColorInt())),
                PropertyFactory.textHaloWidth(1f),
                PropertyFactory.textOffset(arrayOf(0f, 1.5f)),
                PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                PropertyFactory.textAllowOverlap(true),
                PropertyFactory.textIgnorePlacement(true),
            )
        }

    // =============================================================================================
    // 显隐（zoom 分界切换，基类 setVisible 经 applyLayerVisibility 进入）
    // =============================================================================================

    private var appliedHeatMode: Boolean? = null

    override fun applyLayerVisibility() {
        applyVisibilityByZoom(map?.cameraPosition?.zoom ?: 0.0, force = true)
    }

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
    // 监听器 / 点击查询 → 属性底部弹窗
    // =============================================================================================

    private fun registerListeners(map: MapLibreMap) {
        trackMapListener(
            MapLibreMap.OnCameraMoveListener {
                if (!isActive || !isLoaded || !mVisible) return@OnCameraMoveListener
                applyVisibilityByZoom(map.cameraPosition.zoom)
            },
            attach = { map.addOnCameraMoveListener(it) },
            detach = { map.removeOnCameraMoveListener(it) },
        )
        trackMapListener(
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

    private fun showAttrTable(pnt: LatLng, layerId: String): Boolean {
        val m = map ?: return false
        val feature = m.queryRenderedFeatures(m.projection.toScreenLocation(pnt), layerId)
            .firstOrNull() ?: return false
        // 发状态：由图层自持的渲染宿主（mountSheetHost）展示，与业务屏幕无关
        IllegalEventAttrSheet.show(feature)
        return true
    }

    // =============================================================================================
    // 属性弹窗：状态总线（图层发状态，宿主渲染）+ 图层自持渲染宿主（挂 MapView）
    // =============================================================================================

    private var sheetHostView: ComposeView? = null

    /** 图层自持弹窗渲染宿主：onMapReady 挂载、onDetach 摘除，与图层同生命周期。
     *  挂在 MapView 上（而非 Activity decorView），不依赖任何业务屏幕是否存活。 */
    private fun mountSheetHost() {
        if (sheetHostView != null) return
        val parent = MapRuntime.currentMapView ?: run {
            Timber.w("mountSheetHost: MapRuntime.currentMapView 为空（地图插件 attach 时未传 mapView?）")
            return
        }
        val view = ComposeView(parent.context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { IllegalEventAttrSheet.Host() }
        }
        parent.addView(
            view,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        sheetHostView = view
    }

    private fun unmountSheetHost() {
        sheetHostView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        sheetHostView = null
    }

    private fun removeMapObjects() {
        currentSession()?.removeLayer(HEATMAP_LAYER_ID)
        currentSession()?.removeLayer(CIRCLE_LAYER_ID)
        currentSession()?.removeLayer(SYMBOL_LAYER_ID)
        currentSession()?.removeSource(SOURCE_ID)
    }
}