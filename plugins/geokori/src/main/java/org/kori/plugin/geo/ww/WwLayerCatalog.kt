package org.kori.plugin.geo.ww

import earth.worldwind.WorldWindow
import earth.worldwind.layer.Layer
import earth.worldwind.layer.mercator.WebMercatorLayerFactory

/**
 * WorldWind 版图层目录（API 已对 worldwind-android 2.1.1 源码核对）。
 *
 * ★ 本版本 WW 的 Layer 接口只有 displayName（无 name），图层 key 统一用 displayName。
 * ★ 天地图 XYZ 层用 WebMercatorLayerFactory.createLayer：
 *   URL 模板支持 {x}/{y}/{z} 与 {rand=0,1,...}（多子域轮询，正好对应天地图 t0~t7）。
 *
 * 职责：
 *  · 幂等创建标准图层：天地图底图（影像 + 注记）、等高线叠加层
 *  · 底图互斥切换（enable 一个、disable 其余）
 *  · 叠加层开关
 *  · 收集"用户可开关图层"（排除内部层与底图）
 */
object WwLayerCatalog {

    // ---- 图层 displayName（同时作为 key）----
    const val BASE_SATELLITE = "卫星影像"
    const val BASE_SATELLITE_LABEL = "地名注记"
    const val OVERLAY_CONTOUR = "等高线"

    /** 底图组（互斥切换时整组启停；注记跟随影像）。 */
    private val BASE_MAP_GROUPS = mapOf(
        BASE_SATELLITE to listOf(BASE_SATELLITE, BASE_SATELLITE_LABEL),
    )

    /** ★ 替换为你们自己的天地图 token */
    private const val TDT_TOKEN = "YOUR_TIANDITU_TOKEN"
    private const val TDT_IMG_URL =
        "https://t{rand=0,1,2,3,4,5,6,7}.tianditu.gov.cn/DataServer?T=img_w&x={x}&y={y}&l={z}&tk=$TDT_TOKEN"
    private const val TDT_LABEL_URL =
        "https://t{rand=0,1,2,3,4,5,6,7}.tianditu.gov.cn/DataServer?T=cia_w&x={x}&y={y}&l={z}&tk=$TDT_TOKEN"
    private const val CONTOUR_URL = "https://your-contour-server/tiles/{z}/{x}/{y}.png"

    /** 可开关图层条目（UI 用）。 */
    data class Entry(
        val id: String,
        val name: String,
        val visible: Boolean,
    )

    // =============================================================================================
    // 标准图层创建
    // =============================================================================================

    /** 幂等创建标准图层（天地图影像/注记 + 等高线），挂到 WorldWindow。 */
    fun ensureStandardLayers(worldWindow: WorldWindow) {
        addLayerIfAbsent(worldWindow, BASE_SATELLITE) {
            WebMercatorLayerFactory.createLayer(TDT_IMG_URL, name = BASE_SATELLITE, maxZoom = 18)
        }
        addLayerIfAbsent(worldWindow, BASE_SATELLITE_LABEL) {
            WebMercatorLayerFactory.createLayer(TDT_LABEL_URL, name = BASE_SATELLITE_LABEL, maxZoom = 18)
        }
        addLayerIfAbsent(worldWindow, OVERLAY_CONTOUR) {
            WebMercatorLayerFactory.createLayer(
                CONTOUR_URL, name = OVERLAY_CONTOUR,
                transparent = true, maxZoom = 15,
            ).apply { isEnabled = false }   // 叠加层默认关
        }
    }

    private inline fun addLayerIfAbsent(
        worldWindow: WorldWindow,
        displayName: String,
        create: () -> Layer,
    ) {
        if (findLayer(worldWindow, displayName) != null) return
        val layer = create()
        layer.displayName = displayName
        worldWindow.engine.layers.addLayer(layer)
    }

    /** 按 displayName 查找（LayerList 遍历）。 */
    fun findLayer(worldWindow: WorldWindow, displayName: String): Layer? =
        worldWindow.engine.layers.firstOrNull { it.displayName == displayName }

    // =============================================================================================
    // 底图 / 叠加层控制
    // =============================================================================================

    /** 底图互斥切换：enable 目标组，disable 其他底图组。 */
    fun setBaseMap(worldWindow: WorldWindow, baseMapId: String) {
        BASE_MAP_GROUPS.forEach { (groupId, memberNames) ->
            val enable = groupId == baseMapId
            memberNames.forEach { name ->
                findLayer(worldWindow, name)?.isEnabled = enable
            }
        }
    }

    /** 当前激活的底图 ID（无匹配返回 null）。 */
    fun currentBaseMap(worldWindow: WorldWindow): String? =
        BASE_MAP_GROUPS.keys.firstOrNull { id ->
            findLayer(worldWindow, id)?.isEnabled == true
        }

    /** 叠加层开关。 */
    fun setOverlayVisible(worldWindow: WorldWindow, overlayName: String, visible: Boolean) {
        findLayer(worldWindow, overlayName)?.isEnabled = visible
    }

    fun isOverlayVisible(worldWindow: WorldWindow, overlayName: String): Boolean =
        findLayer(worldWindow, overlayName)?.isEnabled == true

    // =============================================================================================
    // 用户可开关图层
    // =============================================================================================

    /**
     * 收集可开关图层（对应 MapLibre 版 collectSwitchableLayers）。
     *
     * 排除：内部层（`ww-` 前缀）、底图组成员、标准叠加层。
     */
    fun collectSwitchableLayers(worldWindow: WorldWindow): List<Entry> {
        val standard = BASE_MAP_GROUPS.values.flatten() + OVERLAY_CONTOUR
        return worldWindow.engine.layers
            .mapNotNull { layer ->
                val dn = layer.displayName ?: return@mapNotNull null
                if (dn.startsWith("ww-") || dn in standard) return@mapNotNull null
                Entry(id = dn, name = dn, visible = layer.isEnabled)
            }
    }

    /** 开关单个图层。 */
    fun setLayerVisible(worldWindow: WorldWindow, displayName: String, visible: Boolean) {
        findLayer(worldWindow, displayName)?.isEnabled = visible
    }

    /** 重置：全部可开关图层恢复可见。 */
    fun resetLayers(worldWindow: WorldWindow) {
        collectSwitchableLayers(worldWindow).forEach {
            setLayerVisible(worldWindow, it.id, true)
        }
    }
}