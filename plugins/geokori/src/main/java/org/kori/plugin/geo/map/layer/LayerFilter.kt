package org.kori.plugin.geo.map.layer

import org.kori.plugin.geo.map.ui.LayerEntry


/**
 * 单条图层匹配规则。
 */
sealed interface LayerRule {
    fun matches(entry: LayerEntry): Boolean

    data class IdPrefix(val prefix: String) : LayerRule {
        override fun matches(entry: LayerEntry) = entry.id.startsWith(prefix)
    }

    data class IdExact(val id: String) : LayerRule {
        override fun matches(entry: LayerEntry) = entry.id == id
    }

    data class IdContains(val substring: String) : LayerRule {
        override fun matches(entry: LayerEntry) = entry.id.contains(substring)
    }

    data class IdRegex(val pattern: Regex) : LayerRule {
        override fun matches(entry: LayerEntry) = pattern.matches(entry.id)
    }

    data class TypeIs(val type: String) : LayerRule {
        override fun matches(entry: LayerEntry) = entry.type == type
    }

    data class TypeIn(val types: Set<String>) : LayerRule {
        override fun matches(entry: LayerEntry) = entry.type in types
    }

    data class Custom(val predicate: (LayerEntry) -> Boolean) : LayerRule {
        override fun matches(entry: LayerEntry) = predicate(entry)
    }
}

/**
 * 图层过滤器 —— 白名单模式。
 *
 * 语义：
 *  1. **include 决定候选**：只有匹配任一 include 规则才进入列表；
 *     `include` 为空视为"全部通过"。
 *  2. **exclude 再从候选中剔掉**。
 *
 * 默认使用 [LayerIdConvention] 的命名协议自动判定。
 */
data class LayerFilter(
    val include: List<LayerRule> = emptyList(),
    val exclude: List<LayerRule> = emptyList(),
) {
    fun apply(layers: List<LayerEntry>): List<LayerEntry> =
        layers.filter { entry ->
            if (include.isNotEmpty() && include.none { it.matches(entry) }) {
                return@filter false
            }
            if (exclude.any { it.matches(entry) }) {
                return@filter false
            }
            true
        }

    fun plusInclude(rule: LayerRule) = copy(include = include + rule)
    fun plusInclude(prefix: String) = plusInclude(LayerRule.IdPrefix(prefix))
    fun plusExclude(rule: LayerRule) = copy(exclude = exclude + rule)
    fun plusExclude(prefix: String) = plusExclude(LayerRule.IdPrefix(prefix))

    operator fun plus(rule: LayerRule) = plusInclude(rule)
    operator fun minus(rule: LayerRule) = plusExclude(rule)

    companion object {
        /** 不做任何过滤，显示全部图层。 */
        val All = LayerFilter()

        /**
         * 默认白名单 —— 按 [LayerIdConvention] 命名协议自动判定。
         */
        val Default: LayerFilter by lazy {
            LayerFilter(
                include = listOf(
                    LayerRule.Custom { entry ->
                        LayerIdConvention.shouldInclude(entry.id)
                    },
                ),
            )
        }

        /** 极简白名单 —— 只保留最核心的骨架层。 */
        val Minimal: LayerFilter = LayerFilter(
            include = listOf(
                LayerRule.IdExact("background"),
                LayerRule.IdExact("water"),
                LayerRule.IdExact("park"),
                LayerRule.IdExact("landcover_grass"),
                LayerRule.IdExact("landcover_wood"),
                LayerRule.IdExact("building"),
                LayerRule.IdExact("highway-motorway"),
                LayerRule.IdExact("highway-trunk"),
                LayerRule.IdExact("highway-primary"),
                LayerRule.IdExact("highway-secondary-tertiary"),
                LayerRule.IdExact("highway-minor"),
                LayerRule.IdExact("railway"),
                LayerRule.IdExact("poi_r1"),
                LayerRule.IdExact("poi_r7"),
                LayerRule.IdPrefix("label_"),
                LayerRule.IdPrefix("highway-name-"),
            ),
        )

        /** DSL 构建器。 */
        private inline fun build(block: Builder.() -> Unit): LayerFilter =
            Builder().apply(block).build()
    }

    class Builder {
        private val includeRules = mutableListOf<LayerRule>()
        private val excludeRules = mutableListOf<LayerRule>()

        fun include(rule: LayerRule) { includeRules += rule }
        fun include(prefix: String) { includeRules += LayerRule.IdPrefix(prefix) }
        fun includeDefault() { includeRules += Default.include }
        fun includeMinimal() { includeRules += Minimal.include }

        fun exclude(rule: LayerRule) { excludeRules += rule }
        fun exclude(prefix: String) { excludeRules += LayerRule.IdPrefix(prefix) }

        internal fun build() = LayerFilter(
            include = includeRules.toList(),
            exclude = excludeRules.toList(),
        )
    }
}