package org.kori.plugin.geo.track.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.kori.plugin.geo.track.TrackMapCallbacks
import org.kori.plugin.geo.track.di.TrackServiceState
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

/**
 * 沉浸式轨迹记录 HUD v2 —— 与地图融合的半透明悬浮界面。
 *
 * ## 修复/增强（相对 v1）
 *
 *  · ★ **布局修复**：根 Box 撑满全屏（v1 高度包裹内容，导致顶部胶囊被压到
 *    底部按钮区）。顶部胶囊真正悬浮在地图顶部
 *  · ★ **顶部统计增强**：录制中显示两行——`REC 00:15  0.00 KM  1 PT` +
 *    当前速度 / 当前配速（等宽数字，回放 HUD 同款风格）
 *  · ★ **手动拖放**：顶部胶囊与底部按钮簇均可手指拖动 reposition，
 *    松手即停（不持久化，重进恢复默认）
 *
 * ## 修复（v2.3 + v2.4）
 *
 *  · ★ **拖动边界修复（v2.3）**：v2"居中对称"与 v2.1/v2.2"锚点/坐标反推"
 *    都依赖对布局链的假设，实测均会失效。根因：`positionInParent` 的"父"
 *    是布局链上最近的布局节点（这里是 padding 节点），`align` 放置整链的
 *    信息完全丢失 → 基准位置错误 → 钳制区间错位。
 *    现改为 **`boundsInWindow()` 绝对窗口矩形**钳制，与布局层级/对齐/边距
 *    全部无关：
 *
 *    ```
 *    dx ∈ [ 根.left − 元素.left , 根.right − 元素.right ]
 *    dy ∈ [ 根.top − 元素.top , 根.bottom − 元素.bottom ]
 *    ```
 *
 *    测量点挂在 `offset` 之前——offset 只移动其内部内容，测量点自身窗口
 *    矩形不受拖动影响，读到的永远是静置位置。布局变化（旋转/分屏）后自动
 *    重测并把已拖偏移重新钳回合法区间。
 *  · ★ **钳制容器修正（v2.4）**：HUD 根容器被外部 `padding(bottom =
 *    trackPanelBottomPadding)` 缩小（本意是抬高**默认位置**避让宿主底栏），
 *    若以其为界，面板拖不到根底边以下、而那里地图仍可见（"拖到某处拖不动、
 *    下方留大片空白"）。现钳制容器改用**组合根窗口矩形**（`findRootCoordinates`）
 *    = 整张地图：默认位置不变，可拖范围 = 全图。
 *
 * ## 布局
 *
 * ```
 * ┌────────────────────────────────────────┐
 * │   (● REC 00:12:34  1.23 KM  245 PT)    │  ← 顶部胶囊（可拖动）
 * │      12.4 km/h · 配速 4'50"            │
 * │                                        │
 * │              地 图 区 域                │
 * │                                        │
 * │      ⏺ ●REC  ⏸ 📷 🎤 🎥  📋          │  ← 底部簇（可拖动）
 * └────────────────────────────────────────┘
 * ```
 */

@Composable
fun TrackRecordingHud(
    state: TrackServiceState,
    callbacks: TrackMapCallbacks,
    modifier: Modifier = Modifier,
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

    // ★ 拖放偏移（px 直存，窗口坐标系）。
    // 注意：必须读写 MutableState 对象本身——pointerInput(Unit) 块不随重组重启，
    // 若闭包捕获"当时的值"会永远基于旧值累加（表现为抖动拖不走）。
    val pillDragState = remember { mutableStateOf(Offset.Zero) }
    val clusterDragState = remember { mutableStateOf(Offset.Zero) }
    /** 根容器尺寸（px），onSizeChanged 留存（其余逻辑已改走窗口矩形） */
    val rootSize = remember { mutableStateOf(IntSize.Zero) }
    /** ★ v2.3：HUD 根容器/两元素的"静置"窗口矩形，boundsInWindow 实测 */
    val rootWinRect = remember { mutableStateOf(Rect.Zero) }
    /**
     * ★ v2.4：钳制容器 = 组合根窗口矩形（整张地图区域）。
     *
     * HUD 根容器被外部 `padding(bottom = trackPanelBottomPadding)` 缩小——
     * 该 padding 的本意只是把面板**默认位置**抬到宿主底栏上方，但前几版
     * 把它当成了**可拖范围**，导致面板拖不到根容器底边以下、而那里地图
     * 依然可见（"拖到某处就拖不动、下方留大片空白"）。
     * 现改为以组合根（findRootCoordinates）为界：默认位置不变，可拖范围 =
     * 整张地图。
     */
    val mapAreaWinRect = remember { mutableStateOf(Rect.Zero) }
    val pillWinRect = remember { mutableStateOf(Rect.Zero) }
    val clusterWinRect = remember { mutableStateOf(Rect.Zero) }

    /** ★ 长按结束：是否正在充能 + 充能进度 0..1 */
    var stopping by remember { mutableStateOf(false) }
    var stopProgress by remember { mutableFloatStateOf(0f) }

    /** ★ 连击结束：还需点击次数（0 = 不显示提示） */
    var hintTaps by remember { mutableIntStateOf(0) }
    var tapCount = 0
    var lastTapMs = 0L

    /**
     * 把 [offsetState] 钳回 [elemRect] 相对 [rootRect] 的合法区间。
     *
     * 合法条件：元素窗口矩形（静置矩形 + 偏移）完整落在根容器窗口矩形内：
     * ```
     * dx ∈ [ root.left − elem.left , root.right − elem.right ]
     * dy ∈ [ root.top − elem.top , root.bottom − elem.bottom ]
     * ```
     * 元素比根宽/高时退化为不越界的一侧（coerceAtLeast 保证区间合法）。
     */
    fun clampOffset(
        offsetState: androidx.compose.runtime.MutableState<Offset>,
        elemRect: Rect,
        rootRect: Rect,
    ) {
        if (rootRect.width <= 0f || rootRect.height <= 0f) return
        if (elemRect.width <= 0f || elemRect.height <= 0f) return
        val minX = rootRect.left - elemRect.left
        val maxX = (rootRect.right - elemRect.right).coerceAtLeast(minX)
        val minY = rootRect.top - elemRect.top
        val maxY = (rootRect.bottom - elemRect.bottom).coerceAtLeast(minY)
        offsetState.value = Offset(
            offsetState.value.x.coerceIn(minX, maxX),
            offsetState.value.y.coerceIn(minY, maxY),
        )
    }

    /**
     * 可拖动，且**元素窗口矩形（静置矩形 + 偏移）严格不越出根容器窗口矩形**。
     *
     * ★ v2.3：用 `boundsInWindow()` 绝对坐标，与布局链（align/padding 层级）
     * 完全无关；测量点挂在 `offset` 之前，读到的永远是静置矩形；
     * 每次重新布局（旋转/分屏）后自动重测并重新钳制当前偏移。
     */
    fun Modifier.draggableBounded(
        offsetState: androidx.compose.runtime.MutableState<Offset>,
        elemRectState: androidx.compose.runtime.MutableState<Rect>,
    ): Modifier =
        this
            // 必须挂在 offset 之前：测量点自身矩形不随拖动变化 = 静置矩形
            .onGloballyPositioned { coords ->
                elemRectState.value = coords.boundsInWindow()
                // 布局变化后把已拖偏移重新钳回合法区间（防旋转后卡死界外）
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

    // ★ 根 Box 撑满全屏：fillMaxSize 在前，外部 modifier（BottomCenter 对齐 +
    //    底部避让内边距）在后——padding 内缩整个面板的可用区域
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(modifier)
            .onSizeChanged { rootSize.value = it }
            .onGloballyPositioned { coords ->
                rootWinRect.value = coords.boundsInWindow()
                // ★ 钳制容器 = 组合根（整张地图），而非被外部 padding 缩小的 HUD 根
                mapAreaWinRect.value =
                    coords.findRootCoordinates().boundsInWindow()
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
            // ★ 第二行：当前速度 / 配速（录制中且有速度数据时显示）
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
            // ★ 第三行：当前经纬度（定位可用即显示，与是否记录无关）
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
            // 主按钮：开始 / 结束 —— 单层自定义按钮（Box + 自管手势）。
            // ★ 不用 TextButton：它的 clickable 会消费按下事件，饿死同区的
            //    长按手势（主传递后声明者先消费）。点按/长按都由本层 pointerInput 处理。
            val btnColor = when {
                state.paused -> Color(0xFFFFD600)
                state.recording -> Color(0xFFFF1744)
                else -> Color(0xFF00E676)
            }
            Box(contentAlignment = Alignment.Center) {
                // 呼吸底环（录制中）
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
                        .pointerInput(state.recording) {
                            if (state.recording) {
                                // ★ 结束两种途径：
                                //   1) 长按 5 秒（屏幕正中充能环倒计时，提前松手取消）
                                //   2) 快速连击 4 次（800ms 窗口，防误触；轻碰 1~3 次仅提示）
                                detectTapGestures(
                                    onPress = {
                                        kotlinx.coroutines.coroutineScope {
                                            val t0 = System.nanoTime()
                                            stopping = true
                                            val ticker = launch {
                                                while (true) {
                                                    stopProgress =
                                                        ((System.nanoTime() - t0) / 1_000_000_000f / 5f)
                                                            .coerceAtMost(1f)
                                                    withFrameMillis { }
                                                }
                                            }
                                            val releasedEarly = try {
                                                kotlinx.coroutines.withTimeoutOrNull(5000.milliseconds) {
                                                    tryAwaitRelease()
                                                } != null
                                            } finally {
                                                ticker.cancel()
                                            }
                                            val pressMs = (System.nanoTime() - t0) / 1_000_000
                                            when {
                                                // 长按满 5 秒 → 结束
                                                !releasedEarly -> callbacks.onToggle()
                                                // 快速点按 → 连击计数
                                                pressMs < 400 -> {
                                                    val nowMs = System.currentTimeMillis()
                                                    if (nowMs - lastTapMs > 800) tapCount = 0
                                                    lastTapMs = nowMs
                                                    tapCount++
                                                    if (tapCount >= 4) {
                                                        tapCount = 0
                                                        hintTaps = 0
                                                        callbacks.onToggle()
                                                    } else {
                                                        hintTaps = 4 - tapCount
                                                        launch {
                                                            kotlinx.coroutines.delay(900.milliseconds)
                                                            hintTaps = 0
                                                        }
                                                    }
                                                }
                                            }
                                            stopping = false
                                            stopProgress = 0f
                                        }
                                    },
                                )
                            } else {
                                detectTapGestures(onTap = { callbacks.onToggle() })
                            }
                        },
                ) {
                    // 常驻提示环（录制中表明可长按）
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
                        // 底环
                        drawCircle(
                            color = Color.White.copy(alpha = 0.25f),
                            radius = r,
                            style = Stroke(width = 8.dp.toPx()),
                        )
                        // 充能弧
                        drawArc(
                            color = Color(0xFFFF1744),
                            startAngle = -90f,
                            sweepAngle = stopProgress * 360f,
                            useCenter = false,
                            topLeft = Offset( 6.dp.toPx(), 6.dp.toPx()),
                            size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
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