package org.kori.plugin.geo.map

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.kori.plugin.geo.map.layer.LayerFilter

/**
 * [MapLibreMapView] 的完整配置。
 *
 * 所有字段都有合理默认值，直接 `MapLibreMapView(modifier = ...)` 即可使用。
 */
data class MapConfig(
    // ---------- 底图 ----------
    val styleUrl: String = MapStyle.LTIANDITU.uri,

    // ---------- 用户位置 ----------
    val showUserLocation: Boolean = true,

    // ---------- 卫星影像 ----------
    val satelliteOn: Boolean = true,
    val satelliteTiles: List<String> = TiandiTuStatellite.tiles().toList(),
    val satelliteMaxZoom: Float = 18f,
    val satelliteFallbackOn: Boolean = true,
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

    // ---------- 图层过滤器 ----------
    /**
     * 图层控制组件展示哪些图层。
     *
     * 默认 [LayerFilter.Default]：按 [LayerIdConvention] 命名协议自动判定，
     * 只显示符合大类规范且非副本 / 装饰变体的图层。
     */
    val layerFilter: LayerFilter = LayerFilter.Default,
) {
    companion object {
        val Default = MapConfig()
    }
}