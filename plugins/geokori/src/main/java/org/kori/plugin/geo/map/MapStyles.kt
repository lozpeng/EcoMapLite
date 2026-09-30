package org.kori.plugin.geo.map

/**
 * Base-layer styles. Default is **OpenFreeMap Liberty** — a full detailed OSM
 * vector style (roads, labels, POIs) served free with no API key, so the map
 * looks real out of the box. Positron is the light/minimal variant. The MapLibre
 * demo style (country outlines only) and a Protomaps slot (needs a key, the
 * "Google-Maps-ify" target) are kept as options. Styles are plain URLs, so they
 * can be swapped over-the-air without an app release.
 *
 * NOTE: OpenFreeMap is a free community service — fine for now, but self-host
 * tiles (or Protomaps PMTiles) before any real release.
 */
enum class MapStyle(val label: String, val uri: String) {
    LIBERTY("免费地图(Liberty风格)", "https://tiles.openfreemap.org/styles/liberty"),
    LTIANDITU("天地图","asset://raster_style_tdt.json");

    companion object {
        val DEFAULT = LIBERTY
    }
}

/**
 * Google's stable raster XYZ endpoint (mt0..mt3). Included for testing/parity
 * only — using it ships a Google-look map AND puts tile load back on Google,
 * both of which Vela deliberately avoids by using open tiles. lyrs: m=roads,
 * s=satellite, y=hybrid, t=terrain, h=transparent roads overlay.
 */
object GoogleRasterTiles {
    fun tiles(layers: String = "m"): List<String> =
        (0..3).map { "https://mt$it.google.com/vt/lyrs=$layers&x={x}&y={y}&z={z}" }
}

object GoogleSatelliteTiles {
    private const val SUBDOMAINS = 4
    fun tiles(layers: String = "s"): Array<String> =
        Array(SUBDOMAINS) { it ->
            "https://mt$it.google.com/vt/lyrs=$layers&x={x}&y={y}&z={z}"
        }
}
object EsriSatellite{
    fun tiles(layers: String = "s"): Array<String> =
        Array(1) { it ->
            "https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
        }
}

object TiandiTuStatellite{
    private const val TK = "982c56cebad276917fcc4b744bed5491"
    private const val SUBDOMAINS = 8
    fun tiles(): Array<String>  =
        Array(SUBDOMAINS) { i ->
            "https://t$i.tianditu.gov.cn/DataServer?T=img_w&x={x}&y={y}&l={z}&tk=$TK"
        }
}