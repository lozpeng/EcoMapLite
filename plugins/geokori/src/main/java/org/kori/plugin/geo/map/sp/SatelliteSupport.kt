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
 * 卫星影像支持（自动兜底）。
 *
 * 三种工作模式，由 [apply] 自动选择：
 *  1. 样式内已内置卫星图层 → 只切可见性。
 *  2. 样式内没有内置 → 动态创建 Raster 层。
 *  3. 深度兜底（正交）→ z18.5 → z19.5 之间淡入高清影像。
 *
 * 所有方法幂等。
 */
object SatelliteSupport {

    const val BASE_SRC = "vela-sat-base-src"
    const val BASE_LAYER = "vela-sat-base-layer"

    const val DEEP_SRC = "vela-sat-deep-src"
    const val DEEP_LAYER = "vela-sat-deep-layer"
    const val DEEP_MIN_ZOOM = 18.5f

    fun hasAnyBuiltinLayer(style: Style, ids: List<String>): Boolean =
        ids.any { style.getLayer(it) != null }

    fun setBuiltinVisible(style: Style, ids: List<String>, visible: Boolean) {
        val v = if (visible) Property.VISIBLE else Property.NONE
        ids.forEach { id ->
            runCatching { style.getLayer(id)?.setProperties(PropertyFactory.visibility(v)) }
        }
    }

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
        if (style.getLayer(BASE_LAYER) != null) return

        if (style.getSource(BASE_SRC) == null) {
            val tileSet = TileSet("2.2.0", *tiles).apply { this.maxZoom = maxZoom }
            attribution?.let { tileSet.attribution = it }
            runCatching { style.addSource(RasterSource(BASE_SRC, tileSet, 256)) }
        }

        val layer = RasterLayer(BASE_LAYER, BASE_SRC).withProperties(
            PropertyFactory.rasterBrightnessMax(0.90f),
            PropertyFactory.rasterSaturation(-0.05f),
        )

        runCatching {
            val anchor = anchorLayerId?.takeIf { style.getLayer(it) != null }
            if (anchor != null) style.addLayerAbove(layer, anchor) else style.addLayer(layer)
        }
    }

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
        layer.setMinZoom(DEEP_MIN_ZOOM)

        runCatching {
            val anchor = anchorLayerId?.takeIf { style.getLayer(it) != null }
            if (anchor != null) style.addLayerAbove(layer, anchor) else style.addLayer(layer)
        }
    }

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
        baseAnchorLayerId: String? = null,
    ) {
        val hasBuiltin = hasAnyBuiltinLayer(style, builtinLayerIds)

        if (hasBuiltin) {
            setBuiltinVisible(style, builtinLayerIds, on)
            runCatching { style.getLayer(BASE_LAYER)?.let { style.removeLayer(it) } }
            runCatching { style.getSource(BASE_SRC)?.let { style.removeSource(it) } }
        } else {
            ensureBase(
                style = style,
                on = on,
                tiles = fallbackBaseTiles,
                maxZoom = fallbackBaseMaxZoom,
                anchorLayerId = baseAnchorLayerId,
            )
        }

        val deepAnchor = when {
            hasBuiltin && deepAnchorLayerId != null -> deepAnchorLayerId
            !hasBuiltin -> BASE_LAYER
            else -> null
        }
        ensureDeep(
            style = style,
            on = on && enableDeep,
            tiles = deepTiles,
            maxZoom = deepMaxZoom,
            anchorLayerId = deepAnchor,
        )
    }
}