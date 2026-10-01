package org.kori.plugin.geo.map.layer

/**
 * 图层 id 命名协议 —— 自动判断图层是否应出现在图层控制 UI 中。
 *
 * ## 命名格式
 * ```
 * <category>[-<subcategory>][-<variant>][_<tier>]
 * ```
 *
 * ## 判定顺序（优先级从高到低）
 * 1. [EXPLICIT_INCLUDE]   显式包含
 * 2. [EXPLICIT_EXCLUDE]   显式排除
 * 3. [DUPLICATE_PREFIXES] 副本前缀 → 排除
 * 4. [DECORATIONS]        装饰变体段 → 排除
 * 5. [CATEGORIES]         大类白名单 → 包含
 * 6. 未匹配 → 排除
 */
object LayerIdConvention {

    /** 一级大类 —— 这些前缀的图层自动进入 UI 白名单。 */
    val CATEGORIES: Set<String> = setOf(
        // 基础地理
        "background",
        "water",
        "waterway",
        "landcover",
        "landuse",
        "park",
        // 建筑
        "building",
        // 交通
        "road",
        "highway",
        "railway",
        "rail",
        "aeroway",
        "airport",
        "ferry",
        "cablecar",
        // 兴趣点
        "poi",
        // 文字
        "label",
        "place",
        // 边界
        "boundary",
        // 地形（自建层也走这个前缀）
        "contour",
    )

    /**
     * 装饰变体段 —— id 按 `-` / `_` 切分后，任意一段等于这些值 → 排除。
     */
    val DECORATIONS: Set<String> = setOf(
        "casing",
        "outline",
        "hatching",
        "glow",
        "shadow",
        "pattern",
        "hatch",
    )

    /**
     * 副本前缀 —— 这些前缀的图层是主图层的"上下文副本"。
     */
    val DUPLICATE_PREFIXES: List<String> = listOf(
        "bridge-",
        "bridge_",
        "tunnel-",
        "tunnel_",
    )

    /** 显式排除 —— 即使 id 匹配大类，也从 UI 移除。 */
    val EXPLICIT_EXCLUDE: Set<String> = setOf(
        "road_oneway",
        "road_oneway_opposite",
    )

    /** 显式包含 —— 即使 id 不匹配大类，也强制加入 UI。 */
    val EXPLICIT_INCLUDE: Set<String> = emptySet()

    /**
     * 判断一个图层 id 是否应出现在 UI 白名单里。
     */
    fun shouldInclude(layerId: String): Boolean {
        if (layerId in EXPLICIT_INCLUDE) return true
        if (layerId in EXPLICIT_EXCLUDE) return false
        if (DUPLICATE_PREFIXES.any { layerId.startsWith(it) }) return false

        val segments = layerId.split('-', '_')
        if (segments.any { it in DECORATIONS }) return false

        val firstSegment = segments.firstOrNull() ?: return false
        if (firstSegment in CATEGORIES) return true

        return false
    }
}