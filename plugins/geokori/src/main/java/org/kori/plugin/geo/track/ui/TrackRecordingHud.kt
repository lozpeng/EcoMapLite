package org.kori.plugin.geo.track.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.roundToInt
import org.cwcc.open.geokori.ui.gesture.stopRecordGuardGesture
import org.kori.plugin.geo.track.TrackMapCallbacks
import org.kori.plugin.geo.track.di.TrackServiceState

/**
 * 沉浸式轨迹记录 HUD v2 —— 与地图融合的半透明悬浮界面。
 *
 * ## 本次变更
 *
 *  · ★ **showToggleButton 参数**：开始/结束主按钮可整体隐藏。
 *    宿主（home 插件）把结束交互迁移到自己的 PUBLISH 按钮后，
 *    HUD 内不再显示结束按钮，但暂停 / 媒体 / 历史等功能保持完整
 *  · ★ **手势逻辑复用**：长按 5 秒充能 / 4 连击的防误触手势改为
 *    [stopRecordGuardGesture]（ui-geokori 公共 Modifier），
 *    与宿主 PUBLISH 按钮的交互完全一致
 *
 * ## 布局
 *
 * ```text
 * ┌────────────────────────────────────────┐
 * │   (● REC 00:12:34  1.23 KM  245 PT)    │  ← 顶部胶囊（可拖动）
 * │      12.4 km/h · 配速 4'50"            │
 * │                                        │
 * │              地 图 区 域                │
 * │                                        │
 * │         ⏸ 📷 🎤 🎥  📋               │  ← 底部簇（可拖动）
 * └────────────────────────────────────────┘
 * ```
 */
@Composable
fun TrackRecordingHud(
    state: TrackServiceState,
    callbacks: TrackMapCallbacks,
    modifier: Modifier = Modifier,
    /** false 时隐藏开始/结束主按钮（结束操作由宿主按钮承担），其余功能完整保留 */
    showToggleButton: Boolean = true,
) {
    val accent: Color = when {
        !state.recording -> Color(0xFF8B949E)
        state.paused -> Color(0xFFFFD600)
        else -> Color(0xFF00E676)
    }
    val pulse by rememberInfiniteTransition(label = "hudPulse").animateFloat(
        initialValue = 0.4f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "hudPulseA",
    )

    // ★ 拖放偏移（px 直存，窗口坐标系）
    val pillDragState = remember { mutableStateOf(Offset.Zero) }
    val clusterDragState = remember { mutableStateOf(Offset.Zero) }
    val rootSize = remember { mutableStateOf(IntSize.Zero) }

    val view = LocalView.current
    val statusBarInsetPx = remember { mutableFloatStateOf(0f) }
    val rootWinRect = remember { mutableStateOf(Rect.Zero) }
    val mapAreaWinRect = remember { mutableStateOf(Rect.Zero) }
    val pillWinRect = remember { mutableStateOf(Rect.Zero) }
    val clusterWinRect = remember { mutableStateOf(Rect.Zero) }

    /** ★ 长按结束：是否正在充能 + 充能进度 0..1 */
    var stopping by remember { mutableStateOf(false) }
    var stopProgress by remember { mutableFloatStateOf(0f) }

    /** ★ 连击结束：还需点击次数（0 = 不显示提示） */
    var hintTaps by remember { mutableIntStateOf(0) }

    fun clampOffset(
        offsetState: androidx.compose.runtime.MutableState<Offset>,
        elemRect: Rect,
        rootRect: Rect,
    ) {
        if (rootRect.width <= 0f || rootRect.height <= 0f) return
        if (elemRect.width <= 0f || elemRect.height <= 0f) return
        val minX = rootRect.left - elemRect.left
        val maxX = (rootRect.right - elemRect.right).coerceAtLeast(minX)
        val minY = rootRect.top + statusBarInsetPx.floatValue - elemRect.top
        val maxY = (rootRect.bottom - elemRect.bottom).coerceAtLeast(minY)
        offsetState.value = Offset(
            offsetState.value.x.coerceIn(minX, maxX),
            offsetState.value.y.coerceIn(minY, maxY),
        )
    }

    DisposableEffect(view) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            statusBarInsetPx.floatValue =
                insets.getInsets(WindowInsetsCompat.Type.statusBars()).top.toFloat()
            clampOffset(pillDragState, pillWinRect.value, mapAreaWinRect.value)
            clampOffset(clusterDragState, clusterWinRect.value, mapAreaWinRect.value)
            insets
        }
        ViewCompat.requestApplyInsets(view)
        onDispose {
            ViewCompat.setOnApplyWindowInsetsListener(view, null)
        }
    }

    fun Modifier.draggableBounded(
        offsetState: androidx.compose.runtime.MutableState<Offset>,
        elemRectState: androidx.compose.runtime.MutableState<Rect>,
    ): Modifier =
        this
            .onGloballyPositioned { coords ->
                elemRectState.value = coords.boundsInWindow()
                clampOffset(offsetState, elemRectState.value, mapAreaWinRect.value)
            }
            .offset {
                IntOffset(
                    offsetState.value.x.roundToInt(),
                    offsetState.value.y.roundToInt(),
                )
            }
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    clampOffset(
                        offsetState,
                        elemRect = elemRectState.value,
                        rootRect = mapAreaWinRect.value,
                    )
                    offsetState.value += drag
                    clampOffset(
                        offsetState,
                        elemRect = elemRectState.value,
                        rootRect = mapAreaWinRect.value,
                    )
                }
            }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(modifier)
            .onSizeChanged { rootSize.value = it }
            .onGloballyPositioned { coords ->
                rootWinRect.value = coords.boundsInWindow()
                mapAreaWinRect.value =
                    coords.findRootCoordinates().boundsInWindow()
                ViewCompat.getRootWindowInsets(view)?.let { wic ->
                    statusBarInsetPx.floatValue =
                        wic.getInsets(WindowInsetsCompat.Type.statusBars()).top.toFloat()
                }
            },
    ) {
        // =========================== 顶部状态胶囊（可拖动） ===========================
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 48.dp)
                .draggableBounded(pillDragState, pillWinRect)
                .clip(RoundedCornerShape(50))
                .background(Color(0x99000000))
                .padding(horizontal = 14.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(accent.copy(alpha = pulse), CircleShape),
                )
                Text(
                    text = when {
                        !state.recording -> "READY"
                        state.paused -> "HOLD"
                        else -> "REC"
                    },
                    color = accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = buildString {
                        append(formatDuration(state.elapsedMs))
                        append("  ")
                        append("%.2f".format(state.distanceM / 1000))
                        append(" KM  ")
                        append(state.points)
                        append(" PT")
                    },
                    color = Color(0xFFE6EDF3),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            val v = state.currentSpeedMps
            if (state.recording && v != null && v > 0.1f) {
                Text(
                    text = buildString {
                        append("%.1f".format(v * 3.6f))
                        append(" km/h")
                        if (v > 0.3f) {
                            append(" · 配速 ")
                            append(paceOf(v))
                        }
                    },
                    color = Color(0xFF00E5FF),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (state.currentLat != null && state.currentLng != null) {
                Text(
                    text = "%.6f, %.6f".format(state.currentLat, state.currentLng),
                    color = Color(0xFF8B949E),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        // =========================== 底部按钮簇（可拖动） ===========================
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 14.dp)
                .draggableBounded(clusterDragState, clusterWinRect)
                .clip(RoundedCornerShape(40.dp))
                .background(Color(0x73000000))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ★ 主按钮：开始 / 结束（可通过 showToggleButton 整体隐藏）。
            // 手势：未记录=单击开始；记录中=防误触手势（公共 stopRecordGuardGesture）
            val btnColor = when {
                state.paused -> Color(0xFFFFD600)
                state.recording -> Color(0xFFFF1744)
                else -> Color(0xFF00E676)
            }
            if (showToggleButton) {
                Box(contentAlignment = Alignment.Center) {
                    if (state.recording) {
                        Box(
                            modifier = Modifier
                                .size(60.dp)
                                .background(Color(0xFFFF1744).copy(alpha = pulse * 0.45f), CircleShape),
                        )
                    }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(66.dp)
                            .background(btnColor, CircleShape)
                            // 未记录：单击开始
                            .pointerInput(state.recording) {
                                if (!state.recording) {
                                    detectTapGestures(onTap = { callbacks.onToggle() })
                                }
                            }
                            // 记录中：防误触结束手势（与宿主 PUBLISH 按钮一致）
                            .stopRecordGuardGesture(
                                enabled = state.recording,
                                onStop = callbacks.onToggle,
                                onChargingChange = { stopping = it },
                                onChargeProgress = { stopProgress = it },
                                onTapHint = { hintTaps = it },
                            ),
                    ) {
                        if (state.recording) {
                            Canvas(Modifier.size(66.dp)) {
                                drawCircle(
                                    color = Color.White.copy(alpha = 0.25f),
                                    radius = (size.minDimension / 2f) - 2.dp.toPx(),
                                    style = Stroke(width = 2.dp.toPx()),
                                )
                            }
                        }
                        if (!stopping) {
                            Icon(
                                imageVector = if (state.recording) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                                contentDescription = if (state.recording) "结束（长按）" else "开始",
                                tint = Color.White,
                                modifier = Modifier.size(26.dp),
                            )
                        }
                    }
                }
            }

            if (state.recording) {
                HudFab(onClick = callbacks.onPauseToggle, container = Color(0x44FFFFFF), size = 40) {
                    Text(
                        text = if (state.paused) "▶" else "⏸",
                        color = if (state.paused) Color(0xFF00E676) else Color(0xFFFFD600),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    )
                }
                HudFab(callbacks.onPhoto, Color(0x44FFFFFF), 40) {
                    Icon(Icons.Filled.CameraAlt, "拍照", tint = Color.White, modifier = Modifier.size(18.dp))
                }
                HudFab(callbacks.onAudio, Color(0x44FFFFFF), 40) {
                    Icon(Icons.Filled.Mic, "录音", tint = Color.White, modifier = Modifier.size(18.dp))
                }
                HudFab(callbacks.onVideo, Color(0x44FFFFFF), 40) {
                    Icon(Icons.Filled.Videocam, "录像", tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }

            HudFab(callbacks.onOpenHistory, Color(0x44FFFFFF), 40) {
                Icon(Icons.AutoMirrored.Filled.List, "历史轨迹", tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }

        // ======================= 屏幕正中：长按充能环 =======================
        if (stopping) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x40000000)),
                contentAlignment = Alignment.Center,
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(150.dp)) {
                    Canvas(Modifier.size(150.dp)) {
                        val r = size.minDimension / 2f - 6.dp.toPx()
                        drawCircle(
                            color = Color.White.copy(alpha = 0.25f),
                            radius = r,
                            style = Stroke(width = 8.dp.toPx()),
                        )
                        drawArc(
                            color = Color(0xFFFF1744),
                            startAngle = -90f,
                            sweepAngle = stopProgress * 360f,
                            useCenter = false,
                            topLeft = Offset(6.dp.toPx(), 6.dp.toPx()),
                            size = Size(r * 2, r * 2),
                            style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round),
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("结束记录", color = Color.White, fontSize = 14.sp)
                        Text(
                            text = (5f * (1f - stopProgress)).let {
                                if (it < 0.05f) "✓" else "${kotlin.math.ceil(it.toDouble()).toInt()}s"
                            },
                            color = Color.White,
                            fontSize = 38.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text("松开取消", color = Color(0xFF8B949E), fontSize = 12.sp)
                    }
                }
            }
        }

        // ======================= 连击提示（再点 N 次结束） =======================
        if (hintTaps > 0 && !stopping) {
            Text(
                text = "再点 $hintTaps 次结束记录",
                color = Color(0xFFFFD600),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 108.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xCC000000))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

/** 半透明圆形按钮。 */
@Composable
private fun HudFab(
    onClick: () -> Unit,
    container: Color,
    size: Int,
    content: @Composable () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .size(size.dp)
            .background(container, CircleShape),
    ) { content() }
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/** 配速（分'秒" / 公里）。 */
private fun paceOf(speedMps: Float): String {
    val secPerKm = 1000.0 / speedMps
    return "%d'%02d\"".format((secPerKm / 60).toInt(), (secPerKm % 60).toInt())
}