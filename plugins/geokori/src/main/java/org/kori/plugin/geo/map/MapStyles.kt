package org.kori.plugin.geo.map

/**
 * 底图样式枚举。
 */
enum class MapStyle(val label: String, val uri: String) {
    LIBERTY("免费地图(Liberty风格)", "https://tiles.openfreemap.org/styles/liberty"),
    LTIANDITU("天地图", "asset://raster_style_tdt.json");

    companion object {
        val DEFAULT = LTIANDITU
    }
}

/** Google 稳定光栅 XYZ 端点。 */
object GoogleRasterTiles {
    fun tiles(layers: String = "m"): Array<String> =
        Array(4) { "https://mt$it.google.com/vt/lyrs=$layers&x={x}&y={y}&z={z}" }
}

/** Google 卫星瓦片（兜底源）。 */
object GoogleSatelliteTiles {
    fun tiles(layers: String = "s"): Array<String> =
        Array(4) { "https://mt$it.google.com/vt/lyrs=$layers&x={x}&y={y}&z={z}" }
}

/** Esri World Imagery（深度兜底，z19）。 */
object EsriSatellite {
    fun tiles(): Array<String> = arrayOf(
        "https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
    )
}

/** 天地图卫星瓦片（img_w），z18。 */
object TiandiTuStatellite {
    private const val TK = "982c56cebad276917fcc4b744bed5491"
    fun tiles(): Array<String> =
        Array(8) { i ->
            "https://t$i.tianditu.gov.cn/DataServer?T=img_w&x={x}&y={y}&l={z}&tk=$TK"
        }
}

// =============================================================================================
// 地形与等高线数据源
// =============================================================================================

/**
 * Terrarium 编码的 DEM 高程瓦片（AWS Open Data，免费无密钥）。
 * 高程公式：elevation = (R * 256 + G + B / 256) - 32768
 */
object TerrariumDemTiles {
    fun tiles(): Array<String> = arrayOf(
        "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png",
    )
}

/**
 * 等高线矢量瓦片源。
 *
 * MapLibre Native 不支持从 raster-dem 实时生成等高线，需用预生成的 MVT 瓦片。
 * 默认用 Maptoolkit（免费额度），可替换为自建服务或 MapTiler。
 */
object ContourTiles {
    fun url(): String = "https://api.maptoolkit.net/terrain/{z}/{x}/{y}.pbf"
}