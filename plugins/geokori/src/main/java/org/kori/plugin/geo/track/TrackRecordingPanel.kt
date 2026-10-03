package org.kori.plugin.geo.track

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.List
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 轨迹记录面板 —— 科技感/现代风设计版。
 *
 * ## 设计语言
 *
 *  · **深色玻璃拟态**：近黑蓝底色（#141A24，94% 不透明）+ 1dp 状态色描边 + 圆角
 *  · **呼吸指示灯**：状态圆点无限呼吸动画（绿=记录中 / 琥珀=暂停 / 灰=空闲）
 *  · **等宽数字**：时长/距离/点数用 Monospace 字体，仪表读数感
 *  · **霓虹主按钮**：记录红 / 空闲青，44dp 圆形
 *
 * ## 布局
 *
 * ```
 * ┌──────────────────────────────────────┐
 * │ (●) 记录中                    [📋]    │  ← 呼吸灯 + 状态（weight）+ 历史
 * │     00:45:23 · 245 点 · 2.13 km      │  ← 等宽数字
 * │ ┌──┐                 [⏸] 📷 🎤 🎥  │  ← 主按钮 + 操作
 * │ │■ │                                │
 * │ └──┘                                │
 * └──────────────────────────────────────┘
 * ```
 *
 * @param state 当前记录状态
 * @param callbacks 所有交互回调
 * @param modifier 外部修饰符
 */
@Composable
fun TrackRecordingPanel(
    state: TrackServiceState,
    callbacks: TrackMapCallbacks,
    modifier: Modifier = Modifier,
) {
    // ---- 状态色（科技感三色）----
    val accent: Color = when {
        !state.recording -> Color(0xFF8B949E)   // 灰：空闲
        state.paused -> Color(0xFFFFD600)       // 琥珀：暂停
        else -> Color(0xFF00E676)               // 霓虹绿：记录中
    }

    // ---- 呼吸灯动画 ----
    val pulse by rememberInfiniteTransition(label = "recPulse").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "recPulseAlpha",
    )

    // 深色玻璃底 + 状态色描边
    Card(
        modifier = modifier.widthIn(max = 310.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xF0141A24)),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.4f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // =========================================================================
            // 状态行：呼吸灯 + 标题/等宽详情（weight） + 历史按钮
            // =========================================================================
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 呼吸指示灯
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(accent.copy(alpha = pulse), CircleShape),
                )
                Spacer(modifier = Modifier.size(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when {
                            !state.recording -> "待机"
                            state.paused -> "已暂停"
                            else -> "记录中"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = accent,
                        maxLines = 1,
                    )
                    Text(
                        text = when {
                            !state.recording -> "READY · 点击开始记录"
                            state.paused ->
                                "HOLD · ${state.points} PT · ${"%.2f".format(state.distanceM / 1000)} KM"
                            else -> buildRecordingSummary(state)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF8B949E),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 历史入口（状态行右侧，永不被挤出屏幕）
                IconButton(onClick = callbacks.onOpenHistory) {
                    Icon(
                        imageVector = Icons.Filled.List,
                        contentDescription = "历史轨迹",
                        tint = Color(0xFF8B949E),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            // =========================================================================
            // 按钮行
            // =========================================================================
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // === 主按钮：开始 / 结束（霓虹色）===
                FloatingActionButton(
                    onClick = callbacks.onToggle,
                    containerColor = when {
                        state.paused -> Color(0xFFFFD600)
                        state.recording -> Color(0xFFFF1744)
                        else -> Color(0xFF00E5FF)
                    },
                    contentColor = Color.White,
                    shape = CircleShape,
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        imageVector = if (state.recording) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = if (state.recording) "结束记录" else "开始记录",
                        modifier = Modifier.size(22.dp),
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // === 暂停 / 继续 ===
                if (state.recording) {
                    TextButton(onClick = callbacks.onPauseToggle) {
                        Text(
                            text = if (state.paused) "▶" else "⏸",
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (state.paused) Color(0xFF00E676) else Color(0xFFFFD600),
                        )
                    }
                }

                // === 媒体按钮 ===
                if (state.recording) {
                    TechIconButton(callbacks.onPhoto, Icons.Filled.CameraAlt, "拍照")
                    TechIconButton(callbacks.onAudio, Icons.Filled.Mic, "录音")
                    TechIconButton(callbacks.onVideo, Icons.Filled.Videocam, "录像")
                }
            }
        }
    }
}

/** 等宽数字的状态摘要：`00:45:23 · 245 PT · 2.13 KM` */
private fun buildRecordingSummary(state: TrackServiceState): String {
    val duration = formatDuration(state.elapsedMs)
    val distanceKm = "%.2f".format(state.distanceM / 1000)
    return "$duration · ${state.points} PT · $distanceKm KM"
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s)
    else "%02d:%02d".format(m, s)
}

/** 暗色圆形图标按钮（媒体操作）。 */
@Composable
private fun TechIconButton(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
) {
    IconButton(
        onClick = onClick,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = Color(0xFF21262F),
            contentColor = Color(0xFFC9D1D9),
        ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(20.dp),
        )
    }
}