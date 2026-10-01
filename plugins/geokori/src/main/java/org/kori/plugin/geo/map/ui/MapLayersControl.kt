package org.kori.plugin.geo.map.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.kori.plugin.geo.map.layer.LayerFilter

// =============================================================================================
// 数据模型
// =============================================================================================

data class BaseMapOption(
    val id: String,
    val label: String,
)

data class OverlayToggle(
    val id: String,
    val label: String,
    val enabled: Boolean,
)

data class LayerEntry(
    val id: String,
    val type: String,
    val visible: Boolean,
)

/**
 * 一组相关图层（按 id 首段聚合）。
 */
data class LayerGroup(
    val key: String,
    val displayName: String,
    val entries: List<LayerEntry>,
) {
    /** 群组整体可见状态：全开 / 全关 / 部分开。 */
    val state: ToggleableState
        get() {
            if (entries.isEmpty()) return ToggleableState.Off
            val visibleCount = entries.count { it.visible }
            return when (visibleCount) {
                0 -> ToggleableState.Off
                entries.size -> ToggleableState.On
                else -> ToggleableState.Indeterminate
            }
        }
}

// =============================================================================================
// 组件
// =============================================================================================

/**
 * 图层控制组件。
 *
 * 支持图层群组：按 id 首段聚合，群组标题行提供三态复选框批量开关整组。
 *
 * @param groupKeyExtractor 群组 key 提取器；默认 [LayerGroupNaming.groupKeyOf]
 * @param groupDisplayName  群组显示名映射；默认 [LayerGroupNaming.displayName]（中文）
 */
@Composable
fun MapLayersControl(
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.TopEnd,
    padding: Dp = 16.dp,
    offsetX: Dp = 0.dp,
    offsetY: Dp = 0.dp,

    // ---------- 底图 ----------
    baseMapOptions: List<BaseMapOption> = emptyList(),
    selectedBaseMapId: String? = null,
    onBaseMapSelect: (String) -> Unit = {},

    // ---------- 叠加层 ----------
    overlays: List<OverlayToggle> = emptyList(),
    onOverlayToggle: (String, Boolean) -> Unit = { _, _ -> },

    // ---------- 地图图层 ----------
    layers: List<LayerEntry> = emptyList(),
    layerFilter: LayerFilter = LayerFilter.Default,

    // ---------- 分组配置 ----------
    groupKeyExtractor: (LayerEntry) -> String = LayerGroupNaming::groupKeyOf,
    groupDisplayName: (String) -> String = LayerGroupNaming::displayName,
    groupDefaultExpanded: Boolean = true,

    // ---------- 图层开关回调 ----------
    onLayerVisibilityChange: (String, Boolean) -> Unit = { _, _ -> },
    onLayersReset: (() -> Unit)? = null,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var layersExpanded by remember { mutableStateOf(false) }
    val groupExpandedStates = remember { mutableStateMapOf<String, Boolean>() }

    val filteredLayers = remember(layers, layerFilter) {
        layerFilter.apply(layers)
    }
    val groups = remember(filteredLayers, groupKeyExtractor, groupDisplayName) {
        groupLayers(filteredLayers, groupKeyExtractor, groupDisplayName)
    }

    val anyOverlayOn = overlays.any { it.enabled }
    val anyLayerHidden = filteredLayers.any { !it.visible }
    val active = anyOverlayOn || anyLayerHidden

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .align(alignment)
                .offset(x = offsetX, y = offsetY)
                .padding(padding),
        ) {
            FloatingActionButton(
                onClick = { menuExpanded = true },
                containerColor = if (active) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                },
                contentColor = if (active) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            ) {
                Icon(
                    imageVector = LayersIcon,
                    contentDescription = "图层设置",
                )
            }

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                modifier = Modifier.width(300.dp),
            ) {
                // ==================== 底图（互斥单选） ====================
                if (baseMapOptions.isNotEmpty()) {
                    MenuSectionHeader("底图")
                    baseMapOptions.forEach { option ->
                        BaseMapModeItem(
                            label = option.label,
                            selected = option.id == selectedBaseMapId,
                            onClick = { onBaseMapSelect(option.id) },
                        )
                    }
                }

                // ==================== 叠加层（独立开关） ====================
                if (overlays.isNotEmpty()) {
                    if (baseMapOptions.isNotEmpty()) SectionDivider()
                    MenuSectionHeader("叠加层")
                    overlays.forEach { overlay ->
                        LayerSwitchItem(
                            label = overlay.label,
                            checked = overlay.enabled,
                            onCheckedChange = { onOverlayToggle(overlay.id, it) },
                        )
                    }
                }

                // ==================== 地图图层（按群组组织） ====================
                if (groups.isNotEmpty()) {
                    if (baseMapOptions.isNotEmpty() || overlays.isNotEmpty()) SectionDivider()
                    ExpandableHeader(
                        title = "地图图层",
                        count = filteredLayers.size,
                        expanded = layersExpanded,
                        onToggle = { layersExpanded = !layersExpanded },
                        onReset = onLayersReset,
                        showReset = onLayersReset != null && anyLayerHidden,
                    )

                    if (layersExpanded) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 4.dp),
                        ) {
                            groups.forEach { group ->
                                LayerGroupItem(
                                    group = group,
                                    expanded = groupExpandedStates[group.key]
                                        ?: groupDefaultExpanded,
                                    onToggleExpand = {
                                        groupExpandedStates[group.key] =
                                            !(groupExpandedStates[group.key] ?: groupDefaultExpanded)
                                    },
                                    onToggleGroup = { targetVisible ->
                                        group.entries.forEach { entry ->
                                            if (entry.visible != targetVisible) {
                                                onLayerVisibilityChange(entry.id, targetVisible)
                                            }
                                        }
                                    },
                                    onToggleLayer = { id, visible ->
                                        onLayerVisibilityChange(id, visible)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// =============================================================================================
// 群组相关内部 Composable
// =============================================================================================

@Composable
private fun LayerGroupItem(
    group: LayerGroup,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onToggleGroup: (Boolean) -> Unit,
    onToggleLayer: (String, Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleExpand)
                .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = group.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${group.entries.count { it.visible }}/${group.entries.size}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TriStateCheckbox(
                state = group.state,
                onClick = {
                    val targetVisible = group.state != ToggleableState.On
                    onToggleGroup(targetVisible)
                },
                modifier = Modifier.size(36.dp),
            )
        }

        if (expanded) {
            group.entries.forEach { entry ->
                LayerVisibilityItem(
                    entry = entry,
                    onToggle = { visible -> onToggleLayer(entry.id, visible) },
                )
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(top = 4.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )
    }
}

// =============================================================================================
// 通用内部 Composable
// =============================================================================================

@Composable
private fun SectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 4.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun MenuSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(
            start = 16.dp,
            end = 16.dp,
            top = 12.dp,
            bottom = 4.dp,
        ),
    )
}

@Composable
private fun BaseMapModeItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Text(
                text = label,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        },
        leadingIcon = {
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
        onClick = onClick,
    )
}

@Composable
private fun LayerSwitchItem(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        trailingIcon = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
            )
        },
        onClick = { onCheckedChange(!checked) },
    )
}

@Composable
private fun ExpandableHeader(
    title: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    onReset: (() -> Unit)?,
    showReset: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        if (count > 0) {
            Text(
                text = "$count",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (showReset && onReset != null) {
            Spacer(Modifier.width(4.dp))
            IconButton(
                onClick = onReset,
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "重置图层",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun LayerVisibilityItem(
    entry: LayerEntry,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!entry.visible) }
            .padding(start = 32.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = entry.id,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = entry.type,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = entry.visible,
            onCheckedChange = onToggle,
            modifier = Modifier.height(24.dp),
        )
    }
}

// =============================================================================================
// 群组聚合
// =============================================================================================

/**
 * 按 [keyExtractor] 把过滤后的图层列表聚合为群组。
 * 保持图层在源列表中的出现顺序。
 */
private fun groupLayers(
    layers: List<LayerEntry>,
    keyExtractor: (LayerEntry) -> String,
    nameMapper: (String) -> String,
): List<LayerGroup> {
    val map = LinkedHashMap<String, MutableList<LayerEntry>>()
    for (entry in layers) {
        val key = keyExtractor(entry).ifBlank { entry.id }
        map.getOrPut(key) { mutableListOf() }.add(entry)
    }
    return map.map { (key, list) ->
        LayerGroup(
            key = key,
            displayName = nameMapper(key),
            entries = list,
        )
    }
}

// =============================================================================================
// 内联图标
// =============================================================================================

private val LayersIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Layers",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(11.99f, 18.54f)
            lineTo(4.62f, 12.81f)
            lineTo(3f, 14.07f)
            lineTo(12f, 21.07f)
            lineTo(21f, 14.07f)
            lineTo(19.37f, 12.81f)
            lineTo(11.99f, 18.54f)
            close()
            moveTo(12f, 16f)
            lineTo(19.36f, 10.27f)
            lineTo(21f, 9f)
            lineTo(12f, 2f)
            lineTo(3f, 9f)
            lineTo(4.63f, 10.27f)
            lineTo(12f, 16f)
            close()
        }
    }.build()
}