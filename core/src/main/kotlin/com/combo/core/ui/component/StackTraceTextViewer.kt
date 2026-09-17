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

package com.combo.core.ui.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import com.combo.core.ui.theme.ModernDarkPrimary
import com.combo.core.ui.theme.ModernDarkTextSecondary

/**
 * 定义堆栈文本中不同部分的高亮颜色
 *
 * @param exceptionColor 异常类型 (e.g., NullPointerException) 的颜色.
 * @param atColor "at" 关键字和 "Caused by:" 的颜色.
 * @param packageNameColor 包名 (e.g., com.example.app) 的颜色.
 * @param classNameColor 类名 (e.g., MainActivity) 的颜色.
 * @param methodNameColor 方法名 (e.g., onCreate) 的颜色.
 * @param sourceFileColor 源文件名和行号 (e.g., MainActivity.kt:15) 的颜色.
 */
data class StackTraceColors(
    val exceptionColor: Color,
    val atColor: Color,
    val packageNameColor: Color,
    val classNameColor: Color,
    val methodNameColor: Color,
    val sourceFileColor: Color
)

/**
 * 提供默认的亮色和暗色主题下的堆栈高亮颜色方案
 */
object StackTraceDefaults {
    @Composable
    fun lightColors(
        exceptionColor: Color = MaterialTheme.colorScheme.error,
        atColor: Color = MaterialTheme.colorScheme.tertiary,
        packageNameColor: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        classNameColor: Color = MaterialTheme.colorScheme.onSurface,
        methodNameColor: Color = MaterialTheme.colorScheme.primary,
        sourceFileColor: Color = MaterialTheme.colorScheme.secondary
    ) = StackTraceColors(
        exceptionColor = exceptionColor,
        atColor = atColor,
        packageNameColor = packageNameColor,
        classNameColor = classNameColor,
        methodNameColor = methodNameColor,
        sourceFileColor = sourceFileColor
    )

    @Composable
    fun darkColors(
        exceptionColor: Color = MaterialTheme.colorScheme.error,
        atColor: Color = MaterialTheme.colorScheme.tertiary,
        packageNameColor: Color = ModernDarkTextSecondary,
        classNameColor: Color = MaterialTheme.colorScheme.onSurface,
        methodNameColor: Color = ModernDarkPrimary,
        sourceFileColor: Color = MaterialTheme.colorScheme.secondary
    ) = StackTraceColors(
        exceptionColor = exceptionColor,
        atColor = atColor,
        packageNameColor = packageNameColor,
        classNameColor = classNameColor,
        methodNameColor = methodNameColor,
        sourceFileColor = sourceFileColor
    )
}

/**
 * 一个专门用于显示和高亮Java/Kotlin堆栈跟踪信息的Composable组件
 *
 * @param stackTrace 完整的堆栈字符串.
 * @param modifier Modifier.
 * @param colors 自定义的高亮颜色方案.
 * @param onTextLayout (可选) 文本布局结果的回调.
 */
@Composable
fun StackTraceTextViewer(
    stackTrace: String,
    modifier: Modifier = Modifier,
    colors: StackTraceColors = if (MaterialTheme.colorScheme.background.luminance() > 0.5) StackTraceDefaults.lightColors() else StackTraceDefaults.darkColors(),
    onTextLayout: (TextLayoutResult) -> Unit = {}
) {
    val annotatedString = buildAnnotatedString {
        val lineRegex = """^\s*(at\s+)?(([\w.$<>]+)\.)?([\w$<>]+)\.([\w$<>]+)(\((.*\.kt|.*\.java)?:(\d+)?\))?$""".toRegex()
        val exceptionRegex = """^([\w.$]+(?:Exception|Error)):?(.*)?""".toRegex()
        val causedByRegex = """^Caused by: ([\w.$]+(?:Exception|Error)):?(.*)?""".toRegex()

        stackTrace.lines().forEach { line ->
            var matched = false

            causedByRegex.find(line.trim())?.let {
                append("Caused by: ")
                withStyle(style = SpanStyle(color = colors.exceptionColor, fontWeight = FontWeight.Bold)) {
                    append(it.groupValues[1])
                }
                if (it.groupValues[2].isNotEmpty()) {
                    append(": ${it.groupValues[2]}")
                }
                append("\n")
                matched = true
            }

            if (!matched) {
                lineRegex.find(line.trim())?.let {
                    withStyle(style = SpanStyle(color = colors.atColor)) {
                        append("  at ")
                    }
                    withStyle(style = SpanStyle(color = colors.packageNameColor)) {
                        append(it.groupValues[2])
                    }
                    withStyle(style = SpanStyle(color = colors.classNameColor, fontWeight = FontWeight.Bold)) {
                        append(it.groupValues[4])
                    }
                    append(".")
                    withStyle(style = SpanStyle(color = colors.methodNameColor, fontStyle = FontStyle.Italic)) {
                        append(it.groupValues[5])
                    }
                    withStyle(style = SpanStyle(color = colors.sourceFileColor, textDecoration = TextDecoration.Underline)) {
                        append(it.groupValues[6])
                    }
                    append("\n")
                    matched = true
                }
            }

            if (!matched) {
                exceptionRegex.find(line.trim())?.let {
                    withStyle(style = SpanStyle(color = colors.exceptionColor, fontWeight = FontWeight.Bold)) {
                        append(it.groupValues[1])
                    }
                    if (it.groupValues[2].isNotEmpty()) {
                        append(": ${it.groupValues[2]}")
                    }
                    append("\n")
                    matched = true
                }
            }

            if (!matched) {
                append(line)
                append("\n")
            }
        }
    }

    Text(
        text = annotatedString,
        style = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace,
            lineHeight = 16.sp,
        ),
        modifier = modifier,
        onTextLayout = onTextLayout
    )
}

/**
 * 扩展函数，用于计算Color的亮度
 */
fun Color.luminance(): Float {
    return (0.2126f * red + 0.7152f * green + 0.0722f * blue)
}