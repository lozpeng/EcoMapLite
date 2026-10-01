package org.kori.plugin.geo.map.sp
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.HillshadeLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.VectorSource
import timber.log.Timber

/**
 * 地形阴影 + 等高线支持。
 *
 * ## 山体阴影
 * 用 [HillshadeLayer] 从 Terrarium DEM 栅格渲染光照阴影。
 * DEM 源通过 [injectDemSourceIntoStyleJson] **动态注入**到样式 JSON 中——
 * MapLibre Android 13.6.1 的 `RasterDemSource` 没有公开的 `encoding` setter，
 * 只有通过样式 JSON 的 `"encoding": "terrarium"` 字段才能正确解析 Terrarium 编码。
 *
 * ## 等高线
 * 用预生成的 MVT 矢量瓦片（MapLibre Native 不支持从 raster-dem 实时生成等高线）。
 *
 * 所有方法幂等。
 */
object TerrainSupport {

    const val DEM_SRC = "vela-dem-src"
    const val HILLSHADE_LAYER = "vela-hillshade-layer"
    const val CONTOUR_SRC = "vela-contour-src"
    const val CONTOUR_LINE_LAYER = "vela-contour-line-layer"
    const val CONTOUR_LABEL_LAYER = "vela-contour-label-layer"

    /** 默认 DEM 瓦片模板（与 [TerrariumDemTiles] 一致，允许注入时替换）。 */
    private const val DEFAULT_DEM_TILES =
        "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"

    // =========================================================================================
    // 样式 JSON 动态注入
    // =========================================================================================

    /**
     * 把 DEM 源定义动态注入到样式 JSON 字符串中（幂等）。
     *
     * 检查 `"vela-dem-src"` 是否已存在，不存在则在 `"sources": {` 后插入源定义。
     * 这是 MapLibre Android 13.6.1 下唯一能为 raster-dem 指定 `terrarium` 编码的方式。
     *
     * @param json         原始样式 JSON
     * @param demTiles     DEM 瓦片 URL 模板列表
     * @param maxZoom      DEM 源最大 zoom
     */
    fun injectDemSourceIntoStyleJson(
        json: String,
        demTiles: List<String> = listOf(DEFAULT_DEM_TILES),
        maxZoom: Float = 15f,
    ): String {
        // 已经注入过 → 直接返回
        if (json.contains("\"$DEM_SRC\"")) return json

        // 查找 "sources": { 位置
        val sourcesKey = "\"sources\""
        val sourcesIdx = json.indexOf(sourcesKey)
        if (sourcesIdx < 0) {
            android.util.Log.w("TerrainSupport", "样式 JSON 缺少 sources 字段，跳过 DEM 注入")
            return json
        }
        val braceIdx = json.indexOf('{', sourcesIdx)
        if (braceIdx < 0) {
            android.util.Log.w("TerrainSupport", "sources 字段格式异常，跳过 DEM 注入")
            return json
        }

        // 构建源定义 JSON
        val tilesJson = demTiles.joinToString(",") { "\"$it\"" }
        val demSourceJson = buildString {
            append("\"$DEM_SRC\":{")
            append("\"type\":\"raster-dem\",")
            append("\"tiles\":[").append(tilesJson).append("],")
            append("\"encoding\":\"terrarium\",")
            append("\"tileSize\":256,")
            append("\"maxzoom\":").append(maxZoom.toInt())
            append("},")
        }

        // 在 sources 的 { 之后插入
        return json.substring(0, braceIdx + 1) + demSourceJson + json.substring(braceIdx + 1)
    }

    // =========================================================================================
    // 山体阴影
    // =========================================================================================

    /**
     * 幂等创建 / 移除山体阴影层。
     *
     * 前提：DEM 源已经通过 [injectDemSourceIntoStyleJson] 注入到样式 JSON。
     * 如果样式里没有 [DEM_SRC]，安全空转。
     */
    fun ensureHillshade(
        style: Style,
        on: Boolean,
        anchorLayerId: String? = null,
        exaggeration: Float = 0.32f,
        darkTheme: Boolean = false,
    ) {
        if (!on) {
            runCatching { style.getLayer(HILLSHADE_LAYER)?.let { style.removeLayer(it) } }
            return
        }
        if (style.getLayer(HILLSHADE_LAYER) != null) return
        if (style.getSource(DEM_SRC) == null) {
            Timber.tag("TerrainSupport").w("DEM 源 '$DEM_SRC' 未找到，请确认已注入样式 JSON")
            return
        }

        val layer = HillshadeLayer(HILLSHADE_LAYER, DEM_SRC).withProperties(
            PropertyFactory.hillshadeExaggeration(exaggeration),
            // ★ 颜色必须用 Expression.literal 包裹
            PropertyFactory.hillshadeShadowColor(
                Expression.literal(if (darkTheme) "#0a1018" else "#6b7280"),
            ),
            PropertyFactory.hillshadeHighlightColor(
                Expression.literal(if (darkTheme) "#3a4a68" else "#ffffff"),
            ),
            PropertyFactory.hillshadeAccentColor(
                Expression.literal(if (darkTheme) "#0a1018" else "#9aa0a6"),
            ),
        )
        // ★ 用 setter，不能直接赋值
        layer.setMaxZoom(16f)

        runCatching {
            val anchor = anchorLayerId?.takeIf { style.getLayer(it) != null }
            if (anchor != null) style.addLayerBelow(layer, anchor) else style.addLayer(layer)
        }
    }

    // =========================================================================================
    // 等高线
    // =========================================================================================

    fun ensureContour(
        style: Style,
        on: Boolean,
        url: String,
        sourceLayer: String = "contour",
        minZoomValue: Float = 10f,
        maxZoomValue: Float = 16f,
        anchorLayerId: String? = null,
        darkTheme: Boolean = false,
    ) {
        if (!on) {
            runCatching { style.getLayer(CONTOUR_LABEL_LAYER)?.let { style.removeLayer(it) } }
            runCatching { style.getLayer(CONTOUR_LINE_LAYER)?.let { style.removeLayer(it) } }
            runCatching { style.getSource(CONTOUR_SRC)?.let { style.removeSource(it) } }
            return
        }
        if (style.getLayer(CONTOUR_LINE_LAYER) != null) return
        if (style.getSource(CONTOUR_SRC) == null) {
            runCatching { style.addSource(VectorSource(CONTOUR_SRC, url)) }
        }

        // ---- 线层 ----
        val lineLayer = LineLayer(CONTOUR_LINE_LAYER, CONTOUR_SRC).apply {
            setSourceLayer(sourceLayer)
            setMinZoom(minZoomValue)   // ★ setter
            setMaxZoom(maxZoomValue)
            setProperties(
                PropertyFactory.lineColor(if (darkTheme) "#8A8F98" else "#A9A29A"),
                PropertyFactory.lineWidth(
                    Expression.interpolate(
                        Expression.linear(), Expression.zoom(),
                        Expression.stop(10f, 0.5f),
                        Expression.stop(14f, 0.9f),
                    ),
                ),
                PropertyFactory.lineOpacity(0.55f),
                PropertyFactory.lineDasharray(arrayOf(2f, 1.5f)),
            )
        }

        // ---- 标注层 ----
        val labelLayer = SymbolLayer(CONTOUR_LABEL_LAYER, CONTOUR_SRC).apply {
            setSourceLayer(sourceLayer)
            setMinZoom(minZoomValue + 2f)
            setMaxZoom(maxZoomValue)
            setProperties(
                PropertyFactory.textField(Expression.get("ele")),
                PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
                PropertyFactory.textSize(10f),
                PropertyFactory.textColor(if (darkTheme) "#C8CDD4" else "#6F6A62"),
                PropertyFactory.textHaloColor(if (darkTheme) "#0a1018" else "#ffffff"),
                PropertyFactory.textHaloWidth(1.2f),
                PropertyFactory.textOptional(true),
                PropertyFactory.symbolPlacement(Property.SYMBOL_PLACEMENT_LINE),
                PropertyFactory.symbolSpacing(350f),
                PropertyFactory.textAllowOverlap(false),
            )
        }

        runCatching {
            val anchor = anchorLayerId?.takeIf { style.getLayer(it) != null }
            if (anchor != null) {
                style.addLayerBelow(lineLayer, anchor)
                style.addLayerBelow(labelLayer, anchor)
            } else {
                style.addLayer(lineLayer)
                style.addLayer(labelLayer)
            }
        }
    }

    // =========================================================================================
    // 一站式
    // =========================================================================================

    fun apply(
        style: Style,
        hillshadeOn: Boolean,
        contourOn: Boolean,
        contourUrl: String,
        contourSourceLayer: String,
        contourMinZoom: Float,
        contourMaxZoom: Float,
        hillshadeExaggeration: Float,
        darkTheme: Boolean,
        anchorLayerId: String?,
    ) {
        ensureHillshade(
            style = style,
            on = hillshadeOn,
            anchorLayerId = anchorLayerId,
            exaggeration = hillshadeExaggeration,
            darkTheme = darkTheme,
        )
        ensureContour(
            style = style,
            on = contourOn,
            url = contourUrl,
            sourceLayer = contourSourceLayer,
            minZoomValue = contourMinZoom,
            maxZoomValue = contourMaxZoom,
            anchorLayerId = anchorLayerId,
            darkTheme = darkTheme,
        )
    }
}