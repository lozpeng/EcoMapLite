package org.kori.plugin.geo.map.ui

/**
 * 图层群组的命名与分组规则。
 *
 * 独立成对象的原因：
 *  1. **本地化**：默认映射是中文，可整体替换成英文或其他语言
 *  2. **可扩展**：外部可注入自定义映射覆盖默认项，或追加新 key
 *  3. **可测试**：纯函数，无 Compose 依赖，容易单测
 *
 * ## 用法
 *
 * ```kotlin
 * // 默认（中文）
 * MapLayersControl(...)
 *
 * // 覆盖部分
 * MapLayersControl(
 *     groupDisplayName = LayerGroupNaming.resolver(
 *         override = mapOf("highway" to "🛣️ 道路"),
 *     ),
 * )
 *
 * // 完整替换成英文
 * MapLayersControl(
 *     groupDisplayName = LayerGroupNaming.English::displayName,
 * )
 * ```
 */
object LayerGroupNaming {

    // =========================================================================================
    // 分组 key 提取
    // =========================================================================================

    /**
     * 从图层 id 提取群组 key。
     *
     * 规则：按 `-` / `_` 分割 id，取首段。若 id 为空或首段为空，回退到完整 id。
     *
     * 例如：
     *  - `water`            → `water`
     *  - `waterway_river`   → `waterway`
     *  - `highway-motorway` → `highway`
     *  - `poi_r1`           → `poi`
     *  - `building-3d`      → `building`
     *  - `label_city`       → `label`
     */
    fun groupKeyOf(entry: LayerEntry): String {
        val segments = entry.id.split('-', '_')
        return segments.firstOrNull()?.takeIf { it.isNotBlank() } ?: entry.id
    }

    // =========================================================================================
    // 显示名映射
    // =========================================================================================

    /**
     * 默认（中文）显示名映射表。
     *
     * 只列出常用 key；未列出的 key 会原样返回（如 `aeroway` → `aeroway`）。
     * 外部可通过 [resolver] 的 `override` 参数追加或覆盖。
     */
    val DefaultNames: Map<String, String> = mapOf(
        // 基础地理
        "background" to "背景",
        "water" to "水系",
        "waterway" to "水系",
        "water_name" to "水体名",
        "landcover" to "地表覆盖",
        "landuse" to "土地利用",
        "park" to "公园绿地",

        // 建筑
        "building" to "建筑",

        // 交通
        "road" to "道路",
        "highway" to "道路",
        "railway" to "铁路",
        "rail" to "铁路",
        "aeroway" to "机场设施",
        "airport" to "机场",
        "ferry" to "轮渡",
        "cablecar" to "缆车",

        // 兴趣点
        "poi" to "兴趣点",

        // 文字
        "label" to "地名标签",
        "place" to "地名",

        // 边界
        "boundary" to "边界",

        // 地形
        "contour" to "等高线",
    )

    /**
     * 把 key 解析成显示名。
     *
     * 回退链：默认映射 → key 原文
     */
    fun displayName(key: String): String = DefaultNames[key] ?: key

    /**
     * 生成一个自定义 resolver：先查 [override]，再查 [DefaultNames]，最后回退到 key。
     *
     * @param override 覆盖或追加的映射；为空时行为等同 [::displayName]
     */
    fun resolver(
        override: Map<String, String> = emptyMap(),
    ): (String) -> String = { key ->
        override[key] ?: DefaultNames[key] ?: key
    }

    /**
     * 生成一个合并映射表（默认 + 覆盖），保留所有条目。
     * 适用于需要完整读取映射内容的场景（如展示群组目录）。
     */
    fun mergedNames(override: Map<String, String> = emptyMap()): Map<String, String> =
        DefaultNames + override

    // =========================================================================================
    // 预置语言包
    // =========================================================================================

    /**
     * 英文显示名。
     */
    val English: LanguagePack = LanguagePack(
        mapOf(
            "background" to "Background",
            "water" to "Water",
            "waterway" to "Waterway",
            "water_name" to "Water Names",
            "landcover" to "Land Cover",
            "landuse" to "Land Use",
            "park" to "Parks",
            "building" to "Buildings",
            "road" to "Roads",
            "highway" to "Roads",
            "railway" to "Railways",
            "rail" to "Railways",
            "aeroway" to "Airport",
            "airport" to "Airport",
            "ferry" to "Ferries",
            "cablecar" to "Cable Cars",
            "poi" to "POIs",
            "label" to "Place Labels",
            "place" to "Places",
            "boundary" to "Boundaries",
            "contour" to "Contours",
        ),
    )

    /**
     * 简洁英文（用于紧凑 UI）。
     */
    val EnglishShort: LanguagePack = LanguagePack(
        mapOf(
            "background" to "BG",
            "water" to "Water",
            "waterway" to "Water",
            "landcover" to "Land",
            "landuse" to "Land",
            "park" to "Park",
            "building" to "Bldg",
            "highway" to "Road",
            "railway" to "Rail",
            "aeroway" to "Air",
            "poi" to "POI",
            "label" to "Label",
            "boundary" to "Border",
            "contour" to "Contour",
        ),
    )

    /**
     * 语言包：一组完整的 key → 显示名映射。
     *
     * 直接用 [displayName] 作为函数引用传给组件的 `groupDisplayName` 参数。
     */
    class LanguagePack(val names: Map<String, String>) {
        fun displayName(key: String): String = names[key] ?: key
    }
}