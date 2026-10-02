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
    // =============================================================================================
    // 底图
    // =============================================================================================
    val styleUrl: String = MapStyle.LTIANDITU.uri,

    // =============================================================================================
    // 用户位置 — 基础
    // =============================================================================================
    /** 首次显示时是否启用位置并跳转到用户处 */
    val showUserLocation: Boolean = true,

    /**
     * 位置源选择：
     *
     *  · false（默认）：用 MapLibre 的 `LocationComponent`。
     *    简单、开箱即用、自带 TRACKING 相机模式。
     *
     *  · true：用 [org.kori.plugin.geo.map.core.location.LocationTracker]。
     *    提供 outlier 拒绝、低速低通、速度门控、卡尔曼滤波、活动检测、
     *    dead reckoning（隧道）、轨迹记录。
     */
    val useCustomLocationPipeline: Boolean = false,

    /**
     * 自定义管线时，相机跟随的缩放级别。
     * MapLibre 的 TRACKING 内部约 16，这里保持一致。
     */
    val customLocationTrackingZoom: Double = 16.0,

    /**
     * 自定义管线时，用户平移后是否脱离跟随。
     * true（默认）：平移即脱离，点"定位"重新跟随。
     * false：始终跟随（不推荐，用户无法自由浏览）。
     */
    val customLocationDropFollowOnPan: Boolean = true,

    /**
     * 自定义管线跟随相机时，是否把地图旋转到运动方向（heading-up）。
     *
     *  · false（默认）：**朝北但不强制**——初始朝北；用户手动旋转地图后保持
     *    用户的视角，不会被纠正回北（每帧采纳相机当前 bearing，与 tilt 同理）。
     *    只有定位蓝点移动、蓝点箭头指示运动方向
     *  · true：导航风格——地图缓动转向行进方向，转弯时地图旋转
     *
     * 注意：无论哪种模式，蓝点的方向箭头都正常（由 forceLocationUpdate 的 bearing 驱动）。
     */
    val customLocationRotateToBearing: Boolean = false,

    // =============================================================================================
    // 卫星影像
    // =============================================================================================
    val satelliteOn: Boolean = true,
    val satelliteTiles: List<String> = TiandiTuStatellite.tiles().toList(),
    val satelliteMaxZoom: Float = 18f,
    val satelliteFallbackOn: Boolean = true,
    val satelliteFallbackTiles: List<String> = EsriSatellite.tiles().toList(),
    val satelliteFallbackMaxZoom: Float = 19f,

    // =============================================================================================
    // 地形阴影
    // =============================================================================================
    val hillshadeOn: Boolean = false,
    val demTiles: List<String> = TerrariumDemTiles.tiles().toList(),
    val demMaxZoom: Float = 15f,
    val hillshadeExaggeration: Float = 0.32f,

    // =============================================================================================
    // 等高线
    // =============================================================================================
    val contourOn: Boolean = false,
    val contourUrl: String = ContourTiles.url(),
    val contourSourceLayer: String = "contour",
    val contourMinZoom: Float = 10f,
    val contourMaxZoom: Float = 16f,

    // =============================================================================================
    // 主题
    // =============================================================================================
    val darkTheme: Boolean = false,

    // =============================================================================================
    // 浮动按钮
    // =============================================================================================
    val showLocationButton: Boolean = true,
    val showLayerButton: Boolean = true,

    val locationButtonAlignment: Alignment = Alignment.BottomEnd,
    val buttonPadding: Dp = 16.dp,
    /** 定位按钮偏移（正值向下 / 向右）。 */
    val locationButtonOffsetX: Dp = 0.dp,
    val locationButtonOffsetY: Dp = 0.dp,

    val layerButtonAlignment: Alignment = Alignment.TopEnd,
    /** 图层按钮偏移。默认 50dp 让按钮避开状态栏 / 顶部搜索框。 */
    val layerButtonOffsetX: Dp = 0.dp,
    val layerButtonOffsetY: Dp = 50.dp,

    // =============================================================================================
    // 初始视角
    // =============================================================================================
    val initialCenterLat: Double = 35.0,
    val initialCenterLng: Double = 105.0,
    val initialZoom: Double = 3.5,

    // =============================================================================================
    // 图层过滤器
    // =============================================================================================
    /**
     * 图层控制组件展示哪些图层。
     * 默认 [LayerFilter.Default]：按 [LayerIdConvention] 命名协议自动判定。
     */
    val layerFilter: LayerFilter = LayerFilter.Default,

    // =============================================================================================
    // 轨迹记录
    // =============================================================================================
    /**
     * 轨迹文件存储目录名（相对 `context.filesDir`）。
     * 会创建在 `context.filesDir/<name>/`，默认 "tracks"。
     */
    val trackStorageDirName: String = "tracks",

    /**
     * 打开时是否自动开始记录轨迹（仅 useCustomLocationPipeline = true 时生效）。
     * false（默认）：由调用方通过 `MapLibreMapView(trackRecording = ...)` 控制。
     */
    val autoStartTrackRecording: Boolean = false,

    /**
     * 记录面板距离地图底部的间距。
     *
     * ★ 宿主底部有导航栏 / 悬浮按钮时，把它抬高到导航栏之上，
     * 否则面板会被宿主 UI 遮住（默认 12.dp 只适合无底部栏的页面）。
     */
    val trackPanelBottomPadding: Dp = 30.dp,
) {
    companion object {
        val Default = MapConfig()
    }
}