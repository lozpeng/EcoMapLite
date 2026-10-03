package org.kori.plugin.geo.track

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kori.plugin.geo.track.di.TrackPoint
import org.kori.plugin.geo.track.di.TrackSession
import java.io.File

/**
 * 轨迹回放控制条（叠加在地图底部，科技风）。
 *
 * ## 功能
 *
 *  · ▶ / ■：开始 / 停止回放（按原始时间戳 × 倍速推进）
 *  · 倍速：1x → 4x → 10x 循环切换
 *  · 进度：已播点数 / 总点数 + 进度条
 *  · 每个回放点通过 [onPoint] 上报（调用方把它喂给地图做标记移动）
 *
 * ## 实现
 *
 * 会话的多段先 [TrackMerger.mergeRawDeduped] 合并去重，写成一个临时 CSV，
 * 交给 [TrackPlayer]（它只认单文件）按 `elapsedNanos` 时间轴回放。
 *
 * 暂停语义 = 停止（再次播放从头开始）——[TrackPlayer] 无 seek 能力，
 * 如需"断点续播"需在其上加游标，属后续增强。
 *
 * @param session 要回放的会话
 * @param onPoint 回放点回调（驱动地图标记）
 * @param onClose 关闭（调用方应同时清掉地图上的回放标记）
 */
@Composable
fun TrackPlaybackBar(
    session: TrackSession,
    onPoint: (TrackPoint) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ---- 准备回放文件（合并多段 → 临时 CSV）----
    var playFile by remember { mutableStateOf<File?>(null) }
    var total by remember { mutableIntStateOf(0) }
    var prepareFailed by remember { mutableStateOf(false) }
    LaunchedEffect(session.id) {
        playFile = withContext(Dispatchers.IO) {
            val pts = TrackMerger.mergeRawDeduped(session)
            total = pts.size
            if (pts.size < 2) {
                prepareFailed = true
                return@withContext null
            }
            val f = File(context.cacheDir, "playback/${session.id}.csv")
            f.parentFile?.mkdirs()
            TrackStore.write(f, pts)
            f
        }
    }

    // ---- 回放状态 ----
    val speeds = remember { listOf(1f, 4f, 10f) }
    var speedIdx by remember { mutableIntStateOf(1) }   // 默认 4x
    var playing by remember { mutableStateOf(false) }
    var count by remember { mutableIntStateOf(0) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun stop() {
        job?.cancel(); job = null; playing = false
    }

    fun start() {
        val f = playFile ?: return
        job?.cancel()
        count = 0
        job = scope.launch {
            TrackPlayer(f, speeds[speedIdx]).play().collect { p ->
                count++
                onPoint(p)
            }
            playing = false
        }
        playing = true
    }

    // 离开组合时停止回放
    DisposableEffect(Unit) {
        onDispose { job?.cancel() }
    }

    // ---- UI：深色玻璃控制条 ----
    val accent = Color(0xFF00E5FF)
    Card(
        modifier = modifier.widthIn(max = 330.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xF0141A24)),
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // 标题 + 控制
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "回放 · ${session.name}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // 倍速
                TextButton(onClick = {
                    stop()
                    speedIdx = (speedIdx + 1) % speeds.size
                }) {
                    Text(
                        "${speeds[speedIdx].toInt()}x",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFFD600),
                    )
                }
                // 播放 / 停止
                TextButton(onClick = { if (playing) stop() else start() }) {
                    Icon(
                        imageVector = if (playing) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = if (playing) "停止回放" else "开始回放",
                        tint = if (playing) Color(0xFFFF1744) else Color(0xFF00E676),
                        modifier = Modifier.size(18.dp),
                    )
                }
                // 关闭
                IconButton(onClick = { stop(); onClose() }) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "关闭",
                        tint = Color(0xFF8B949E),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            // 进度
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .background(Color(0xFF21262F), RoundedCornerShape(2.dp)),
                ) {
                    val frac = if (total > 0) count.toFloat() / total else 0f
                    if (frac > 0f) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(frac.coerceIn(0f, 1f))
                                .height(4.dp)
                                .background(accent, RoundedCornerShape(2.dp)),
                        )
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "$count/$total",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF8B949E),
                )
            }

            if (prepareFailed) {
                Text(
                    "该会话轨迹点不足，无法回放",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFF5252),
                )
            }
        }
    }
}