package org.kori.plugin.geo.map.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 跟随相机的朝向模式（定位按钮四态循环驱动）。
 *
 *  · [NORTH]：朝北但不强制——采纳用户手势的旋转角（初始为北）
 *  · [GPS_BEARING]：转向模式——地图缓动到运动方位角（导航风格）
 *  · [COMPASS]：罗盘模式——地图缓动到手机顶部朝向（[compassIconDeg] 驱动）
 */
enum class BearingMode {
    NORTH,
    GPS_BEARING,
    COMPASS,
}

/**
 * 定位浮动按钮（独立组件）。
 *
 * ## 状态 → 视觉
 *
 *  | 状态 | 图标 | 颜色 |
 *  |---|---|---|
 *  | 关闭（locationEnabled=false） | 📍 LocationOn（灰） | surface/onSurface |
 *  | 跟随（朝北 / 转向） | ◎ MyLocation（醒目靶心） | primary/onPrimary |
 *  | 罗盘模式 | ➤ Navigation（随 [compassIconDeg] 旋转，指向手机朝向） | tertiaryContainer |
 *
 * 开启定位后用 MyLocation + 实心 primary 底色，与关闭态的灰底 📍 形成强对比，
 * 一眼可辨定位是否激活。
 *
 * ## 手势
 *
 *  · **单击** → [onClick]（四态循环：开定位 → 回正北 → 罗盘 → 关闭）
 *  · **长按** → [onLongClick]（卫星状态屏等附加业务；null = 无长按功能）
 *
 * ## 实现要点
 *
 * 用 Box 自绘而非 FloatingActionButton：[combinedClickable] 需要独占手势
 * 管道——与 FAB 内部 clickable 并存会互相消费事件（实测单击或长按失效）。
 * 水波纹由 combinedClickable 自动提供。
 *
 * ## 使用
 *
 * ```kotlin
 * Box(Modifier.fillMaxSize()) {          // BoxScope 环境
 *     LocationFab(
 *         locationEnabled = enabled,
 *         bearingMode = mode,
 *         northResetDone = resetDone,
 *         compassIconDeg = heading,
 *         onClick = { /* 四态循环 */ },
 *         onLongClick = { /* 卫星状态 */ },
 *         modifier = Modifier.matchParentSize(),
 *     )
 * }
 * ```
 *
 * @param locationEnabled 定位是否开启
 * @param bearingMode 当前朝向模式（决定图标与配色）
 * @param northResetDone 状态 1 内部子标记（仅影响无障碍描述）
 * @param compassIconDeg 罗盘模式下箭头图标的旋转角（= 手机朝向，度）
 * @param onClick 单击回调（四态循环）
 * @param onLongClick 长按回调（null = 无长按功能）
 * @param modifier 外部修饰符（典型：在 BoxScope 里传 Modifier.matchParentSize()）
 * @param alignment 按钮在地图上的对齐方式
 * @param offsetX / offsetY 按钮偏移（负值向上/向左）
 * @param padding 按钮外边距
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LocationFab(
    locationEnabled: Boolean,
    bearingMode: BearingMode,
    northResetDone: Boolean,
    compassIconDeg: Float,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.BottomEnd,
    offsetX: Dp = 0.dp,
    offsetY: Dp = 0.dp,
    padding: Dp = 16.dp,
) {
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .align(alignment)
                .offset(x = offsetX, y = offsetY)
                .padding(padding)
                .size(56.dp)
                .shadow(6.dp, CircleShape)
                .background(
                    when {
                        !locationEnabled -> MaterialTheme.colorScheme.surface
                        bearingMode == BearingMode.COMPASS ->
                            MaterialTheme.colorScheme.tertiaryContainer
                        // ★ 跟随态用实心 primary，醒目提示"定位已激活"
                        else -> MaterialTheme.colorScheme.primary
                    },
                    CircleShape,
                )
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = when {
                    !locationEnabled -> Icons.Filled.LocationOn
                    bearingMode == BearingMode.COMPASS -> Icons.Filled.Navigation
                    // ★ 开启定位后切换为靶心图标，与关闭态的 LocationOn 区分
                    else -> Icons.Filled.MyLocation
                },
                contentDescription = when {
                    !locationEnabled -> "打开定位"
                    bearingMode == BearingMode.COMPASS -> "罗盘模式，点击关闭定位"
                    northResetDone -> "点击切换到罗盘模式"
                    else -> "点击恢复地图朝北"
                },
                tint = when {
                    !locationEnabled -> MaterialTheme.colorScheme.onSurface
                    bearingMode == BearingMode.COMPASS ->
                        MaterialTheme.colorScheme.onTertiaryContainer
                    // ★ 实心底色上用 onPrimary 保证对比度
                    else -> MaterialTheme.colorScheme.onPrimary
                },
                modifier = Modifier
                    .size(24.dp)
                    .then(
                        if (locationEnabled && bearingMode == BearingMode.COMPASS) {
                            Modifier.rotate(compassIconDeg)
                        } else {
                            Modifier
                        },
                    ),
            )
        }
    }
}