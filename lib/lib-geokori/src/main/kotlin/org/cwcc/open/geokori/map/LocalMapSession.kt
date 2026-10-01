package org.cwcc.open.geokori.map

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Compose 场景下的插件级 Session 入口。
 *
 * 插件入口在渲染 UI 前注入：
 * ```
 * CompositionLocalProvider(LocalMapSession provides pluginSession) {
 *     // 插件 UI 树
 * }
 * ```
 *
 * 任意 Composable（对话框、菜单、按钮等）都能通过 `LocalMapSession.current` 获取。
 */
val LocalMapSession: ProvidableCompositionLocal<MapSession?> =
    staticCompositionLocalOf { null }