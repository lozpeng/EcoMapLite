package org.kori.plugin.geo.map

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * [MapLibreMapView] 的完整配置。
 *
 * 所有字段都有合理默认值，直接 `MapLibreMapView(modifier = ...)` 即可使用。
 * 需要定制时用 `MapConfig(satelliteOn = false, ...)` 覆盖个别字段。
 *
 * 用 `List<String>` 而非 `Array<String>`：data class 的 equals/hashCode 依赖元素比较，
 * Array 是引用比较会导致 LaunchedEffect key 失效。
 */
data class MapConfig(
    // ---------- 底图 ----------
    val styleUrl: String = MapStyle.LTIANDITU.uri,

    // ---------- 用户位置 ----------
    /** 首次显示时是否启用位置并跳转到用户处 */
    val showUserLocation: Boolean = true,

    // ---------- 卫星影像 ----------
    /** 初始是否开启卫星影像 */
    val satelliteOn: Boolean = true,
    /** 样式无内置卫星图层时，用于动态创建基础卫星层的瓦片源 */
    val satelliteTiles: List<String> = TiandiTuStatellite.tiles().toList(),
    val satelliteMaxZoom: Float = 18f,
    /** 是否启用 z18.5+ 深度兜底 */
    val satelliteFallbackOn: Boolean = true,
    /** 深度兜底的瓦片源 */
    val satelliteFallbackTiles: List<String> = EsriSatellite.tiles().toList(),
    val satelliteFallbackMaxZoom: Float = 19f,

    // ---------- 地形阴影 ----------
    val hillshadeOn: Boolean = false,
    val demTiles: List<String> = TerrariumDemTiles.tiles().toList(),
    val demMaxZoom: Float = 15f,
    val hillshadeExaggeration: Float = 0.32f,

    // ---------- 等高线 ----------
    val contourOn: Boolean = false,
    val contourUrl: String = ContourTiles.url(),
    val contourSourceLayer: String = "contour",
    val contourMinZoom: Float = 10f,
    val contourMaxZoom: Float = 16f,

    // ---------- 主题 ----------
    val darkTheme: Boolean = false,

    // ---------- 浮动按钮 ----------
    val showLocationButton: Boolean = true,
    val showLayerButton: Boolean = true,
    val locationButtonAlignment: Alignment = Alignment.BottomEnd,
    val layerButtonAlignment: Alignment = Alignment.TopEnd,
    val buttonPadding: Dp = 16.dp,

    // ---------- 初始视角 ----------
    val initialCenterLat: Double = 35.0,
    val initialCenterLng: Double = 105.0,
    val initialZoom: Double = 3.5,
) {
    companion object {
        /** 默认配置，可直接使用。 */
        val Default = MapConfig()
    }
}