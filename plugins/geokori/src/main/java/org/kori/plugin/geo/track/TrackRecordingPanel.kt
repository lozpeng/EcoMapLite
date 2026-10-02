package org.kori.plugin.geo.track

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 轨迹记录面板。
 *
 * ## 布局
 *
 * ```
 * ┌────────────────────────────────────────────┐
 * │  ┌──┐   ● 记录中                📷  🎤  🎥 │
 * │  │■ │   00:45:23 · 245 点 · 2.13 km        │
 * │  └──┘                                      │
 * └────────────────────────────────────────────┘
 * ```
 *
 * ## 状态显示
 *
 *  | 状态 | 主按钮 | 状态行 | 副行 | 媒体按钮 |
 * |---|---|---|---|---|
 * | 未记录 | ▶（主色） | ○ 未记录 | 点击 ▶ 开始记录 | 隐藏 |
 * | 记录中 | ■（红色） | ● 记录中 | 时长 · 点数 · 距离 | 显示 |
 *
 * ## 回调
 *
 * 所有交互通过 [TrackMapCallbacks] 传出。Composable 本身**无状态**——
 * 不持有 `mutableStateOf`，不修改任何数据。
 *
 * ## 使用
 *
 * ```kotlin
 * TrackRecordingPanel(
 *     state = TrackServiceState(recording = true, points = 245, ...),
 *     callbacks = TrackMapCallbacks(
 *         onToggle = { viewModel.toggleRecording() },
 *         onPhoto = { openCamera() },
 *         onAudio = { openRecorder() },
 *         onVideo = { openVideoCapture() },
 *         onOpenDetail = { navController.navigate("tracks") },
 *     ),
 *     modifier = Modifier
 *         .align(Alignment.BottomCenter)
 *         .padding(12.dp),
 * )
 * ```
 *
 * @param state 当前记录状态（时长、点数、距离、段数）
 * @param callbacks 所有交互回调
 * @param modifier 外部修饰符
 */
@Composable
fun TrackRecordingPanel(
    state: TrackServiceState,
    callbacks: TrackMapCallbacks,
    modifier: Modifier = Modifier,
) {
    // 主按钮颜色：记录中红色，未记录主色
    val buttonColor by animateColorAsState(
        targetValue = if (state.recording) {
            Color(0xFFD32F2F) // Material Red 700
        } else {
            MaterialTheme.colorScheme.primary
        },
        label = "recordButtonColor",
    )

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // === 主按钮：开始 / 停止 ===
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
                    contentDescription = if (state.recording) "停止记录" else "开始记录",
                    modifier = Modifier.size(22.dp),
                )
            }

            // === 状态文本 ===
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // 状态标题
                Text(
                    text = if (state.recording) "● 记录中" else "○ 未记录",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (state.recording) {
                        Color(0xFFD32F2F)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )

                // 状态详情
                Text(
                    text = if (state.recording) {
                        buildRecordingSummary(state)
                    } else {
                        "点击 ▶ 开始记录"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }

            // === 媒体按钮（仅记录中显示）===
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