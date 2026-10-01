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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
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

// =============================================================================================
// 组件
// =============================================================================================

/**
 * 图层控制组件。
 *
 * @param layerFilter  图层过滤器（白名单模式）。默认 [LayerFilter.Default]。
 */
@Composable
fun MapLayersControl(
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.TopEnd,
    padding: Dp = 16.dp,

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
    onLayerVisibilityChange: (String, Boolean) -> Unit = { _, _ -> },
    onLayersReset: (() -> Unit)? = null,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var layersExpanded by remember { mutableStateOf(false) }

    val filteredLayers = remember(layers, layerFilter) {
        layerFilter.apply(layers)
    }

    val anyOverlayOn = overlays.any { it.enabled }
    val anyLayerHidden = filteredLayers.any { !it.visible }
    val active = anyOverlayOn || anyLayerHidden

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .align(alignment)
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

                // ==================== 地图图层（可展开） ====================
                if (filteredLayers.isNotEmpty()) {
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
                        // Column + verticalScroll，不用 LazyColumn（避免 DropdownMenu 固有测量崩溃）
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 4.dp),
                        ) {
                            filteredLayers.forEach { entry ->
                                LayerVisibilityItem(
                                    entry = entry,
                                    onToggle = { visible ->
                                        onLayerVisibilityChange(entry.id, visible)
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
// 内部 Composable
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
            .padding(horizontal = 12.dp, vertical = 8.dp),
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
            modifier = Modifier.height(28.dp),
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