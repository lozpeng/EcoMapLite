package org.kori.plugin.geo.ww

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import earth.worldwind.WorldWindow

/**
 * WorldWind 版图层控制面板（对应 MapLibre 版 MapLayersControl）。
 *
 * 结构一致：
 *  · 悬浮按钮（Layers 图标）展开/收起面板
 *  · 底图单选（来自 [WwLayerCatalog.BASE_MAP_GROUPS]）
 *  · 叠加层开关（等高线）
 *  · 可开关图层列表（WW LayerList，排除内部层/底图）
 *  · 重置按钮
 *
 * 所有操作直接改 WorldWindow.layers 的 isEnabled，WW 下一帧生效。
 */
@Composable
fun WwLayerControl(
    worldWindow: WorldWindow,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.TopEnd,
    padding: PaddingValues = PaddingValues(16.dp),
    offsetX: Dp = 0.dp,
    offsetY: Dp = 0.dp,
) {
    var expanded by remember { mutableStateOf(false) }

    var selectedBaseMap by remember {
        mutableStateOf(WwLayerCatalog.currentBaseMap(worldWindow) ?: WwLayerCatalog.BASE_SATELLITE)
    }
    var contourEnabled by remember {
        mutableStateOf(WwLayerCatalog.isOverlayVisible(worldWindow, WwLayerCatalog.OVERLAY_CONTOUR))
    }
    // 图层可见性变化 → 刷新列表
    var refreshTick by remember { mutableStateOf(0) }
    val switchableLayers = remember(refreshTick, expanded) {
        WwLayerCatalog.collectSwitchableLayers(worldWindow)
    }

    fun refresh() {
        refreshTick++
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(padding),
    ) {
        // =========================== 悬浮按钮 ===========================
        FloatingActionButton(
            onClick = { expanded = !expanded },
            modifier = Modifier
                .align(alignment)
                .offset(offsetX, offsetY),
        ) {
            Icon(
                imageVector = if (expanded) Icons.Default.Close else Icons.Default.Layers,
                contentDescription = "图层控制",
            )
        }

        // =========================== 展开面板 ===========================
        if (expanded) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 4.dp,
                shadowElevation = 8.dp,
                modifier = Modifier
                    .align(alignment)
                    .offset(offsetX, offsetY + 56.dp)
                    .width(240.dp),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    // ---------- 底图 ----------
                    Text("底图", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(4.dp))
                    BaseMapRow(
                        label = "卫星影像",
                        selected = selectedBaseMap == WwLayerCatalog.BASE_SATELLITE,
                        onSelect = {
                            selectedBaseMap = WwLayerCatalog.BASE_SATELLITE
                            WwLayerCatalog.setBaseMap(worldWindow, WwLayerCatalog.BASE_SATELLITE)
                        },
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    // ---------- 叠加层 ----------
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("等高线")
                        Switch(
                            checked = contourEnabled,
                            onCheckedChange = { enabled ->
                                contourEnabled = enabled
                                WwLayerCatalog.setOverlayVisible(
                                    worldWindow, WwLayerCatalog.OVERLAY_CONTOUR, enabled,
                                )
                            },
                        )
                    }

                    // ---------- 图层列表 ----------
                    if (switchableLayers.isNotEmpty()) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        Text("图层", style = MaterialTheme.typography.titleSmall)
                        Spacer(modifier = Modifier.height(4.dp))
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 220.dp),
                        ) {
                            items(switchableLayers, key = { it.id }) { entry ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(entry.name, modifier = Modifier.weight(1f))
                                    Switch(
                                        checked = entry.visible,
                                        onCheckedChange = { visible ->
                                            WwLayerCatalog.setLayerVisible(worldWindow, entry.id, visible)
                                            refresh()
                                        },
                                    )
                                }
                            }
                        }
                        TextButton(
                            onClick = {
                                WwLayerCatalog.resetLayers(worldWindow)
                                refresh()
                            },
                            modifier = Modifier.align(Alignment.End),
                        ) {
                            Text("重置")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BaseMapRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(modifier = Modifier.width(4.dp))
        Text(label)
    }
}