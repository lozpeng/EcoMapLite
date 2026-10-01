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
 * DEM 源通过 [injectDemSourceIntoStyleJson] 动态注入到样式 JSON 中。
 *
 * ## 等高线
 * 用预生成的 MVT 矢量瓦片。
 *
 * 所有方法幂等。
 */
object TerrainSupport {

    const val DEM_SRC = "vela-dem-src"
    const val HILLSHADE_LAYER = "vela-hillshade-layer"
    const val CONTOUR_SRC = "vela-contour-src"
    const val CONTOUR_LINE_LAYER = "vela-contour-line-layer"
    const val CONTOUR_LABEL_LAYER = "vela-contour-label-layer"

    private const val DEFAULT_DEM_TILES =
        "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"

    // =========================================================================================
    // 样式 JSON 动态注入
    // =========================================================================================

    /**
     * 把 DEM 源定义动态注入到样式 JSON 字符串中（幂等）。
     */
    fun injectDemSourceIntoStyleJson(
        json: String,
        demTiles: List<String> = listOf(DEFAULT_DEM_TILES),
        maxZoom: Float = 15f,
    ): String {
        if (json.contains("\"$DEM_SRC\"")) return json

        val sourcesKey = "\"sources\""
        val sourcesIdx = json.indexOf(sourcesKey)
        if (sourcesIdx < 0) {
            Timber.tag("TerrainSupport").w("样式 JSON 缺少 sources 字段，跳过 DEM 注入")
            return json
        }
        val braceIdx = json.indexOf('{', sourcesIdx)
        if (braceIdx < 0) {
            Timber.tag("TerrainSupport").w("sources 字段格式异常，跳过 DEM 注入")
            return json
        }

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

        return json.substring(0, braceIdx + 1) + demSourceJson + json.substring(braceIdx + 1)
    }

    // =========================================================================================
    // 山体阴影
    // =========================================================================================

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
            android.util.Log.w("TerrainSupport", "DEM 源 '$DEM_SRC' 未找到")
            return
        }

        val layer = HillshadeLayer(HILLSHADE_LAYER, DEM_SRC).withProperties(
            PropertyFactory.hillshadeExaggeration(exaggeration),
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

        val lineLayer = LineLayer(CONTOUR_LINE_LAYER, CONTOUR_SRC).apply {
            setSourceLayer(sourceLayer)
            setMinZoom(minZoomValue)
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