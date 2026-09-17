/*
 * Copyright (c) 2025, 贵州君城网络科技有限公司
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.combo.core.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.combo.core.model.PluginCrashInfo
import com.combo.core.ui.component.InfoRow
import com.combo.core.ui.component.PrimaryButton
import com.combo.core.ui.component.SecondaryButton
import com.combo.core.ui.component.StackTraceTextViewer
import com.combo.core.ui.theme.FrameworkTheme

@Composable
fun CrashScreen(
    crashInfo: PluginCrashInfo,
    onCloseApp: () -> Unit,
    onRestartApp: (disablePlugin: Boolean) -> Unit
) {
    var disablePlugin by remember { mutableStateOf(true) }
    var detailsExpanded by remember { mutableStateOf(true) }
    val interactionSource = remember { MutableInteractionSource() }

    FrameworkTheme {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(80.dp))

                Box(
                    Modifier
                        .size(80.dp)
                        .background(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = CircleShape
                        )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Warning,
                        contentDescription = null,
                        modifier = Modifier
                            .size(40.dp)
                            .align(Alignment.Center),
                        tint = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "插件运行异常",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )

                Spacer(modifier = Modifier.height(32.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalAlignment = Alignment.Start
                ) {
                    InfoRow("异常插件", crashInfo.culpritPluginId ?: "未知")
                    Spacer(modifier = Modifier.height(12.dp))
                    InfoRow("简要原因", crashInfo.defaultMessage)
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null
                            ) { detailsExpanded = !detailsExpanded }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "错误日志",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Icon(
                            imageVector = Icons.Rounded.ArrowDropDown,
                            contentDescription = "展开/收起",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.graphicsLayer(rotationZ = if (detailsExpanded) 180f else 0f)
                        )
                    }

                    AnimatedVisibility(visible = detailsExpanded) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight()
                                .background(
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                                    RoundedCornerShape(10.dp)
                                ),
                            contentPadding = PaddingValues(12.dp)
                        ) {
                            item {
                                SelectionContainer {
                                    StackTraceTextViewer(
                                        stackTrace = crashInfo.throwable.stackTraceToString()
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (crashInfo.culpritPluginId != null && crashInfo.culpritPluginId != "未知") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null
                            ) { disablePlugin = !disablePlugin },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = disablePlugin,
                            onCheckedChange = { disablePlugin = it },
                            colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "禁用此可能导致应用异常的插件",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SecondaryButton(
                        onClick = { onCloseApp() },
                        modifier = Modifier.weight(1f),
                        text = "关闭应用"
                    )
                    PrimaryButton(
                        onClick = { onRestartApp(disablePlugin) },
                        modifier = Modifier.weight(1f),
                        text = if (disablePlugin && (crashInfo.culpritPluginId != null && crashInfo.culpritPluginId != "未知")) "禁用并重启" else "重启应用"
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Preview(showBackground = true, name = "Light Mode")
@Composable
fun CrashScreenLightPreview() {
    val mockException = IllegalStateException("这是一个用于预览的模拟异常。 \n at com.example.MainActivity.kt:15 \n Caused by: java.lang.NullPointerException")
    val mockCrashInfo = PluginCrashInfo(
        throwable = mockException,
        culpritPluginId = "com.example.payment_plugin",
        defaultMessage = "功能模块 '支付插件' 似乎未能完全更新，导致组件冲突。"
    )
    CrashScreen(
        crashInfo = mockCrashInfo,
        onCloseApp = {},
        onRestartApp = {}
    )
}