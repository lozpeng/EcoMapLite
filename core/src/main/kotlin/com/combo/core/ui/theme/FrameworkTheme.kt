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

package com.combo.core.ui.theme


import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme(
    primary = BluePrimary,
    background = BlueBackground,
    surface = BlueBackground,
    onSurface = BlueTextPrimary,
    onSurfaceVariant = BlueTextSecondary,
    error = BlueError,
    errorContainer = BlueErrorContainer,
    tertiary = BlueWarning,
    tertiaryContainer = BlueWarningContainer,
    onTertiaryContainer = BlueTextPrimary
)

private val DarkColorScheme = darkColorScheme(
    primary = ModernDarkPrimary,
    background = ModernDarkBackground,
    surface = ModernDarkSurface,
    onSurface = ModernDarkTextPrimary,
    onSurfaceVariant = ModernDarkTextSecondary,
    error = ModernDarkError,
    errorContainer = ModernDarkErrorContainer,
    tertiary = ModernDarkWarning,
    tertiaryContainer = ModernDarkWarningContainer,
    onTertiaryContainer = ModernDarkTextPrimary
)

@Composable
fun FrameworkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = MaterialTheme.typography,
        content = content
    )
}