package org.kori.plugin.geo

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import org.kori.plugin.geo.map.MapConfig
import org.kori.plugin.geo.map.MapLibreMapView

/**
 * 插件化框架使用指南主界面。
 *
 * ## ★ B5 修复：不再强转 `view.context as Activity`
 *
 * ComboLite 下插件 Compose 内容挂在宿主容器里，`LocalView.context`
 * 可能是 ContextThemeWrapper 或宿主 ApplicationContext，强转 Activity
 * 会抛 ClassCastException。改用 [findActivity] 逐级解包并判空。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Preview
@Composable
fun GeokoriMapScreen() {
    val view = LocalView.current
    val activity = view.context.findActivity()
    val window = activity?.window

    // 在进入组合时隐藏系统栏（仅当确实拿到了 window）
    DisposableEffect(window) {
        if (window == null) return@DisposableEffect onDispose { }
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
            modifier = Modifier.fillMaxSize(),
            config = MapConfig.Default,   // 或按需覆盖字段
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

/** 从任意 Context 向上查找最近的 Activity（解包 ContextThemeWrapper 等）。 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}