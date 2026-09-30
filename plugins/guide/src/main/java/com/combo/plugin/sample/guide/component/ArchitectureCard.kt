

package com.combo.plugin.sample.guide.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp

/**
 * 技术架构卡片
 */
@Composable
fun ArchitectureCard() {
    GuideSectionCard(
        title = "架构概览",
        icon = Icons.Rounded.Settings,
        iconTint = MaterialTheme.colorScheme.primary,
    ) {
        val archText = buildAnnotatedString {
            val points = mapOf(
                "PluginManager:" to " 框架的中心协调器，负责插件的加载、卸载、重启和生命周期管理。\n",
                "InstallerManager:" to " 负责插件的安装、更新和合法性校验。\n",
                "ResourceManager:" to " 负责插件资源的加载与管理，兼容新旧 Android 版本。\n",
                "ProxyManager:" to " 负责 Android 四大组件的代理和生命周期分发。\n",
                "DependencyManager:" to " 负责维护插件间的动态依赖关系图和类索引。",
            )
            points.forEach { (keyword, description) ->
                withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.onSurface)) {
                    append("• ")
                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(keyword)
                    }
                }
                withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                    append(description)
                }
            }
        }
        Text(archText, style = MaterialTheme.typography.bodyMedium, lineHeight = 22.sp)
    }
}