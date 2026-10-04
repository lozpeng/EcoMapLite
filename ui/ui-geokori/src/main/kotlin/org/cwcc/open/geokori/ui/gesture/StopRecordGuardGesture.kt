package org.cwcc.open.geokori.ui.gesture

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "防误触结束"手势 Modifier。
 *
 * 结束轨迹记录是高危操作，需要以下两种方式之一确认：
 *  1. **长按 5 秒**：持续充能（通过 [onChargeProgress] 汇报 0..1 进度，
 *     提前松手取消）；
 *  2. **快速连击 4 次**：800ms 窗口内点满 4 次（前 3 次通过
 *     [onTapHint] 提示"再点 N 次"，轻碰不误触发）。
 *
 * ★ 使用约束：本 Modifier **不能**与 clickable 叠加在同一节点上——
 *   detectTapGestures 的 onPress 阶段不消费 down，clickable 会同时响应。
 *   正确做法（HUD / GeoKoriCenterToolBar 均如此）：
 *   stopGuard = true  → 只挂本手势，不挂 clickable（互斥）；
 *   stopGuard = false → 只挂 clickable，不挂本手势。
 *
 * 由 geokori 的 HUD 结束按钮和宿主 home 插件的 PUBLISH 按钮共用，
 * 保证两处交互完全一致。
 *
 * @param enabled  false 时手势完全关闭（Modifier 原样返回）
 * @param onStop   确认结束后的回调（HUD 调 onToggle，PUBLISH 发 ACTION_TOGGLE 广播）
 */
fun Modifier.stopRecordGuardGesture(
    enabled: Boolean,
    onStop: () -> Unit,
    onChargingChange: (Boolean) -> Unit = {},
    onChargeProgress: (Float) -> Unit = {},
    onTapHint: (remainingTaps: Int) -> Unit = {},
): Modifier = composed {
    // rememberUpdatedState：调用方传入的新 lambda 永远能被手势块读到，
    // 同时 pointerInput(Unit) 不随重组重启（手势进行中不中断）
    val onStopState by rememberUpdatedState(onStop)
    val onChargingState by rememberUpdatedState(onChargingChange)
    val onProgressState by rememberUpdatedState(onChargeProgress)
    val onHintState by rememberUpdatedState(onTapHint)

    if (!enabled) return@composed this

    this.pointerInput(Unit) {
        var tapCount = 0
        var lastTapMs = 0L
        detectTapGestures(
            onPress = {
                coroutineScope {
                    val t0 = System.nanoTime()
                    onChargingState(true)
                    val ticker = launch {
                        while (true) {
                            onProgressState(
                                ((System.nanoTime() - t0) / 1_000_000_000f / 5f)
                                    .coerceAtMost(1f),
                            )
                            withFrameMillis { }
                        }
                    }
                    val releasedEarly = try {
                        // 5 秒内松开返回 true；超时返回 null → 视为长按成功
                        withTimeoutOrNull(5000.milliseconds) { tryAwaitRelease() } != null
                    } finally {
                        ticker.cancel()
                    }
                    val pressMs = (System.nanoTime() - t0) / 1_000_000
                    when {
                        // 长按满 5 秒 → 结束
                        !releasedEarly -> {
                            onHintState(0)
                            onStopState()
                        }
                        // 快速点按 → 连击计数
                        pressMs < 400 -> {
                            val nowMs = System.currentTimeMillis()
                            if (nowMs - lastTapMs > 800) tapCount = 0
                            lastTapMs = nowMs
                            tapCount++
                            if (tapCount >= 4) {
                                tapCount = 0
                                onHintState(0)
                                onStopState()
                            } else {
                                onHintState(4 - tapCount)
                                launch {
                                    delay(900.milliseconds)
                                    onHintState(0)
                                }
                            }
                        }
                    }
                    onChargingState(false)
                    onProgressState(0f)
                }
            },
        )
    }
}

/**
 * 全屏"结束记录"充能环覆盖层（屏幕正中，半透明压暗背景）。
 *
 * 长按 [stopRecordGuardGesture] 期间由调用方放在 Popup 中展示；
 * geokori 的 HUD 与宿主 home 的 PUBLISH 按钮共用同一样式。
 *
 * @param progress 充能进度 0..1（由手势的 onChargeProgress 驱动）
 */
@Composable
fun StopChargingOverlay(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
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
                    sweepAngle = progress * 360f,
                    useCenter = false,
                    topLeft = Offset(6.dp.toPx(), 6.dp.toPx()),
                    size = Size(r * 2, r * 2),
                    style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round),
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("结束记录", color = Color.White, fontSize = 14.sp)
                Text(
                    text = (5f * (1f - progress)).let {
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