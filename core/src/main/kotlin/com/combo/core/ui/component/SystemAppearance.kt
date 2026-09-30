

package com.combo.core.ui.component

import android.app.Activity
import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * 一个可组合函数，用于设置系统UI（如状态栏）的外观。
 *
 * @param isLightAppearance `true`表示状态栏图标和文字应为深色（用于亮色主题），
 * `false`表示应为浅色（用于暗色主题）。
 */
@Composable
fun SystemAppearance(isLightAppearance: Boolean) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.TRANSPARENT
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = isLightAppearance
        }
    }
}