package org.kori.plugin.geo

import android.app.Activity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.kori.plugin.geo.map.MapLibreMapView

/**
 * 插件化框架使用指南主界面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Preview
@Composable
fun GeokoriMapScreen() {
    val view = LocalView.current
    val window = (view.context as Activity).window

    // 在进入组合时隐藏系统栏
    DisposableEffect(Unit) {
        val controller = WindowCompat.getInsetsController(window, view)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            // 离开时恢复系统栏（可选）
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // MapLibre 地图：铺满全屏
        MapLibreMapView(
            modifier = Modifier.fillMaxSize()
        )
        // 如果有其他控件，用 windowInsetsPadding 避让系统栏
        // 例如：
        // FloatingActionButton(
        //     modifier = Modifier
        //         .align(Alignment.BottomEnd)
        //         .windowInsetsPadding(WindowInsets.safeDrawing)
        // ) { ... }
    }
}