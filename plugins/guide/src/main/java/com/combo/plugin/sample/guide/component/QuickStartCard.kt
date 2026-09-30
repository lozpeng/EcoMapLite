

package com.combo.plugin.sample.guide.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * 快速开始卡片
 */
@Composable
fun QuickStartCard() {
    GuideSectionCard(
        title = "快速开始",
        icon = Icons.Rounded.Send,
        iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
        cardColors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        val steps = listOf(
            "1. 添加框架依赖到宿主的 `build.gradle.kts`。",
            "2. 在宿主中定义代理组件(`HostActivity` 等) 并在 `AndroidManifest.xml` 中注册。",
            "3. 初始化框架并配置代理，推荐继承 `BaseHostApplication`。",
            "4. 创建插件模块，实现 `IPluginEntryClass` 并配置元数据。",
            "5. 管理与使用插件，通过 API 或扩展函数进行安装、启动和调用。"
        )
        steps.forEach { step ->
            CodeSnippetText(text = step, color = MaterialTheme.colorScheme.onTertiaryContainer)
        }
    }
}
