package org.kori.plugin.geo.track

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 轨迹记录面板（两行布局，含暂停 / 继续）。
 *
 * ## 布局（★ 改版：状态文字独立成行）
 *
 * ```
 * ┌────────────────────────────────┐
 * │ ● 记录中                        │  ← 状态提示（占满整行，不再被按钮挤压/竖排）
 * │ 00:45:23 · 245 点 · 2.13 km    │
 * │ ┌──┐              [⏸] 📷 🎤 🎥 │  ← 操作按钮行
 * │ │■ │   （Spacer 弹性占位）       │
 * │ └──┘                           │
 * └────────────────────────────────┘
 * ```
 *
 * 旧版是单行 Row（FAB + 文本 + 按钮），宽度收窄后文本列被挤压换行；
 * 现在状态提示独占上部一行（maxLines=1 + 省略号），按钮在下行靠右排列。
 *
 * ## 状态显示
 *
 *  | 状态 | 主按钮 | 暂停按钮 | 状态行 | 媒体按钮 |
 *  |---|---|---|---|---|
 *  | 未记录 | ▶（主色） | 隐藏 | ○ 未记录 | 隐藏 |
 *  | 记录中 | ■（红色=结束） | ⏸ 暂停 | ● 记录中 | 显示 |
 *  | 暂停中 | ■（红色=结束） | ▶ 继续 | ‖ 已暂停 | 显示 |
 *
 * ## 回调
 *
 * 所有交互通过 [TrackMapCallbacks] 传出。Composable 本身**无状态**。
 *
 *  · [TrackMapCallbacks.onToggle]：开始 / 结束
 *  · [TrackMapCallbacks.onPauseToggle]：暂停 / 继续
 *
 * @param state 当前记录状态（时长、点数、距离、段数、暂停标志）
 * @param callbacks 所有交互回调
 * @param modifier 外部修饰符
 */
@Composable
fun TrackRecordingPanel(
    state: TrackServiceState,
    callbacks: TrackMapCallbacks,
    modifier: Modifier = Modifier,
) {
    // 主按钮颜色：记录中（含暂停）红色，未记录主色
    val buttonColor by animateColorAsState(
        targetValue = if (state.recording) {
            Color(0xFFD32F2F) // Material Red 700
        } else {
            MaterialTheme.colorScheme.primary
        },
        label = "recordButtonColor",
    )

    Card(
        // ★ 限制最大宽度，避免横向遮挡右下角的定位 FAB
        modifier = modifier.widthIn(max = 300.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // =========================================================================
            // 第一行：状态提示（占满整行——宽度再窄也不会被按钮挤压竖排）
            // =========================================================================
            Text(
                text = when {
                    !state.recording -> "○ 未记录"
                    state.paused -> "‖ 已暂停"
                    else -> "● 记录中"
                },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = when {
                    !state.recording ->
                        MaterialTheme.colorScheme.onSurfaceVariant
                    state.paused ->
                        MaterialTheme.colorScheme.tertiary
                    else ->
                        Color(0xFFD32F2F)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = when {
                    !state.recording -> "点击 ▶ 开始记录"
                    state.paused ->
                        "已暂停 · ${state.points} 点 · ${"%.2f".format(state.distanceM / 1000)} km"
                    else -> buildRecordingSummary(state)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            // =========================================================================
            // 第二行：操作按钮（主按钮在左，其余靠右）
            // =========================================================================
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // === 主按钮：开始 / 结束 ===
                FloatingActionButton(
                    onClick = callbacks.onToggle,
                    containerColor = buttonColor,
                    contentColor = Color.White,
                    shape = CircleShape,
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        imageVector = if (state.recording) {
                            Icons.Filled.Stop
                        } else {
                            Icons.Filled.PlayArrow
                        },
                        contentDescription = if (state.recording) "结束记录" else "开始记录",
                        modifier = Modifier.size(22.dp),
                    )
                }

                // 弹性占位：把暂停/媒体按钮推到右侧
                Spacer(modifier = Modifier.weight(1f))

                // === 暂停 / 继续按钮（仅记录中显示）===
                // 用 TextButton 而非图标：material-icons-core 没有 Pause 图标，
                // 引入 extended-icons 会显著增大插件体积。
                if (state.recording) {
                    TextButton(onClick = callbacks.onPauseToggle) {
                        Text(
                            text = if (state.paused) "▶ 继续" else "⏸ 暂停",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                        )
                    }
                }

                // === 媒体按钮（仅记录中显示，含暂停中——暂停时也可以拍照标注）===
                if (state.recording) {
                    MediaIconButton(
                        onClick = callbacks.onPhoto,
                        icon = Icons.Filled.CameraAlt,
                        contentDescription = "拍照",
                    )
                    MediaIconButton(
                        onClick = callbacks.onAudio,
                        icon = Icons.Filled.Mic,
                        contentDescription = "录音",
                    )
                    MediaIconButton(
                        onClick = callbacks.onVideo,
                        icon = Icons.Filled.Videocam,
                        contentDescription = "录像",
                    )
                }
            }
        }
    }
}

/**
 * 记录状态摘要文本。
 *
 * 格式：`时长 · 点数 · 距离`
 * 例如：`00:45:23 · 245 点 · 2.13 km`
 */
private fun buildRecordingSummary(state: TrackServiceState): String {
    val duration = formatDuration(state.elapsedMs)
    val distanceKm = "%.2f".format(state.distanceM / 1000)
    return "$duration · ${state.points} 点 · $distanceKm km"
}

/**
 * 时长格式化。
 *
 * · < 1 小时：`MM:SS`
 * · ≥ 1 小时：`HH:MM:SS`
 */
private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) {
        "%d:%02d:%02d".format(h, m, s)
    } else {
        "%02d:%02d".format(m, s)
    }
}

/**
 * 媒体操作小图标按钮（拍照 / 录音 / 录像）。
 */
@Composable
private fun MediaIconButton(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
) {
    IconButton(
        onClick = onClick,
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(22.dp),
        )
    }
}