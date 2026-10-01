package org.kori.plugin.geo.map.sp

import org.kori.plugin.geo.map.EsriSatellite
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet

/**
 * 卫星影像支持（VelaMapView 式的自动兜底）。
 *
 * 三种工作模式：
 *  1. **样式内已内置**（raster_style_tdt.json 里有 "天地图卫星影像"/"天地图注记" 图层）：
 *     [setBuiltinVisible] 只切换这些图层的 visibility，不添加任何图层。
 *  2. **样式内没有内置**：调用 [ensureBase] 用 [MapStyles] 里的源动态创建一层 Raster。
 *  3. **深度兜底**（始终生效，与上面两种模式无关）：[ensureDeep] 在 z18.5 → z19.5 之间
 *     淡入一层高清影像（Esri 默认），把天地图 z18 之后的拉伸接上。
 *
 * 所有方法都是幂等的：同名 layer/source 已存在就跳过，不会重复添加。
 */
object SatelliteSupport {
    // ---- 动态创建的图层 id（只在样式无内置时使用） ----
    const val BASE_SRC = "geokori-sat-base-src"
    const val BASE_LAYER = "geokori-sat-base-layer"

    // ---- 深度兜底层 id（始终存在，由 on 参数控制显隐） ----
    const val DEEP_SRC = "geokori-sat-deep-src"
    const val DEEP_LAYER = "geokori-sat-deep-layer"
    const val DEEP_MIN_ZOOM = 18.5f

    // ========================================================================================
    // 探测
    // ========================================================================================

    /** 样式里是否内置了任一卫星图层（用于选择"切可见性"还是"动态添加"路径）。 */
    fun hasAnyBuiltinLayer(style: Style, ids: List<String>): Boolean =
        ids.any { style.getLayer(it) != null }

    // ========================================================================================
    // 模式 1：内置图层可见性切换
    // ========================================================================================

    /**
     * 切换样式内置的卫星图层可见性。幂等。
     * @param ids 内置卫星图层的 id 列表（如 ["天地图卫星影像", "天地图注记"]）
     */
    fun setBuiltinVisible(style: Style, ids: List<String>, visible: Boolean) {
        val v = if (visible) Property.VISIBLE else Property.NONE
        ids.forEach { id ->
            runCatching { style.getLayer(id)?.setProperties(PropertyFactory.visibility(v)) }
        }
    }

    // ========================================================================================
    // 模式 2：动态创建基础卫星层（样式无内置时）
    // ========================================================================================

    /**
     * 幂等创建 / 移除基础卫星栅格层。
     *
     * @param on            true = 创建并显示；false = 移除
     * @param tiles         瓦片 URL 模板数组（已在 [MapStyles] 中定义）
     * @param maxZoom       源原生支持的最大 zoom（例如天地图 18）
     * @param anchorLayerId 插入到哪个图层之上；null = 顶层
     * @param attribution   版权字符串，显示在系统 attribution 里；null = 不设置
     */
    fun ensureBase(
        style: Style,
        on: Boolean,
        tiles: Array<String>,
        maxZoom: Float = 18f,
        anchorLayerId: String? = null,
        attribution: String? = null,
    ) {
        if (!on) {
            runCatching { style.getLayer(BASE_LAYER)?.let { style.removeLayer(it) } }
            runCatching { style.getSource(BASE_SRC)?.let { style.removeSource(it) } }
            return
        }
        if (style.getLayer(BASE_LAYER) != null) return // 幂等

        if (style.getSource(BASE_SRC) == null) {
            val tileSet = TileSet("2.2.0", *tiles).apply { this.maxZoom = maxZoom }
            attribution?.let { tileSet.attribution = it }
            runCatching { style.addSource(RasterSource(BASE_SRC, tileSet, 256)) }
        }

        val layer = RasterLayer(BASE_LAYER, BASE_SRC).withProperties(
            // 略压暗 + 轻去饱和，白字标签在亮屋顶上仍可读
            PropertyFactory.rasterBrightnessMax(0.90f),
            PropertyFactory.rasterSaturation(-0.05f),
        )

        runCatching {
            val anchor = anchorLayerId?.takeIf { style.getLayer(it) != null }
            if (anchor != null) style.addLayerAbove(layer, anchor) else style.addLayer(layer)
        }
    }

    // ========================================================================================
    // 模式 3：深度兜底层（z18.5+ 淡入）
    // ========================================================================================

    /**
     * 幂等创建 / 移除深度补偿层：z18.5 开始淡入，z19.5 全量接管。
     *
     * 用途：把天地图 z18 之后的拉伸接上一家高清源（Esri 默认）。视觉上是"越放大越清晰"，
     * 用户不会看到"地图忽然变糊"。
     *
     * @param anchorLayerId 一般传 "天地图注记"（在注记之上，让注记盖住影像）；null = 顶层
     */
    fun ensureDeep(
        style: Style,
        on: Boolean,
        tiles: Array<String>,
        maxZoom: Float = 19f,
        anchorLayerId: String? = null,
    ) {
        if (!on) {
            runCatching { style.getLayer(DEEP_LAYER)?.let { style.removeLayer(it) } }
            runCatching { style.getSource(DEEP_SRC)?.let { style.removeSource(it) } }
            return
        }
        if (style.getLayer(DEEP_LAYER) != null) return

        if (style.getSource(DEEP_SRC) == null) {
            val tileSet = TileSet("2.2.0", *tiles).apply { this.maxZoom = maxZoom }
            runCatching { style.addSource(RasterSource(DEEP_SRC, tileSet, 256)) }
        }

        val layer = RasterLayer(DEEP_LAYER, DEEP_SRC).withProperties(
            PropertyFactory.rasterBrightnessMax(0.80f),
            PropertyFactory.rasterSaturation(-0.1f),
            PropertyFactory.rasterOpacity(
                Expression.interpolate(
                    Expression.linear(), Expression.zoom(),
                    Expression.stop(DEEP_MIN_ZOOM, 0f),
                    Expression.stop(DEEP_MIN_ZOOM + 1f, 1f),
                ),
            ),
        )
        layer.minZoom = DEEP_MIN_ZOOM

        runCatching {
            val anchor = anchorLayerId?.takeIf { style.getLayer(it) != null }
            if (anchor != null) style.addLayerAbove(layer, anchor) else style.addLayer(layer)
        }
    }

    // ========================================================================================
    // 一站式入口
    // ========================================================================================

    /**
     * 一次调用完成"探测 + 应用"：
     *
     *  - 若样式里存在 [builtinLayerIds] 中任一图层 → 切它的可见性；
     *  - 否则 → 用 [fallbackBaseTiles] 动态创建基础卫星层。
     *  - 无论走哪条路，深度兜底层都由 [enableDeep] 控制。
     *
     * 这是给 Composable 调用的便捷方法，把所有分支收在一处，简化调用方。
     */
    fun apply(
        style: Style,
        on: Boolean,
        builtinLayerIds: List<String>,
        fallbackBaseTiles: Array<String>,
        fallbackBaseMaxZoom: Float = 18f,
        enableDeep: Boolean = true,
        deepTiles: Array<String> = EsriSatellite.tiles(),
        deepMaxZoom: Float = 19f,
        deepAnchorLayerId: String? = null,
        // 当样式没有内置图层时，若 [on]=true 则创建的基础层锚点
        baseAnchorLayerId: String? = null,
    ) {
        val hasBuiltin = hasAnyBuiltinLayer(style, builtinLayerIds)

        if (hasBuiltin) {
            // 模式 1：切内置图层的可见性
            setBuiltinVisible(style, builtinLayerIds, on)
            // 此时不应存在我们动态加的基础层（避免重复）；若存在则清掉
            runCatching { style.getLayer(BASE_LAYER)?.let { style.removeLayer(it) } }
            runCatching { style.getSource(BASE_SRC)?.let { style.removeSource(it) } }
        } else {
            // 模式 2：动态创建基础卫星层
            ensureBase(
                style = style,
                on = on,
                tiles = fallbackBaseTiles,
                maxZoom = fallbackBaseMaxZoom,
                anchorLayerId = baseAnchorLayerId,
            )
        }

        // 模式 3：深度兜底（无论哪种模式都叠加）
        ensureDeep(
            style = style,
            on = on && enableDeep,
            tiles = deepTiles,
            maxZoom = deepMaxZoom,
            anchorLayerId = deepAnchorLayerId.takeIf { hasBuiltin && it != null }
                ?: if (hasBuiltin) null else BASE_LAYER,
        )
    }
}