package org.kori.plugin.geo.track

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.kori.plugin.geo.location.ActivityProfile
import org.kori.plugin.geo.location.LocationTracker
import org.kori.plugin.geo.map.MapConfig
import org.kori.plugin.geo.map.MapLibreMapView
import org.kori.plugin.geo.track.di.TrackPoint
import org.kori.plugin.geo.track.di.TrackSession
import kotlin.math.roundToInt

/**
 * 沉浸式轨迹回放屏 —— 全屏地图 + 半透明 HUD（参考 Keep/悦跑圈运动详情）。
 *
 * ## 界面构成
 *
 *  · **全屏地图**：历史轨迹线 + 亮青回放标记，相机自动跟随回放位置
 *  · **顶部**：返回 + 标题；下方统计块 —— 大字距离 + 用时 / 平均配速 / 平均速度
 *  · **运动中数据**（回放进行中）：当前速度、当前配速、当前时刻、朝向箭头
 *    （轨迹点带 bearing 时显示，箭头随方位角旋转）
 *  · **底部**：进度条（可拖拽定位）、播放/暂停、倍速（1x/4x/10x）
 *
 * ## 回放引擎
 *
 * 虚拟时钟：真实帧间隔 × 倍速累积，映射到轨迹点的 `elapsedNanos` 时间轴。
 * 支持暂停 / 续播（从当前点继续）/ 拖拽进度条定位——不依赖 [TrackPlayer] 的
 * 延迟流，因此天然支持 seek。
 *
 * 相机跟随：把回放点合成为 [LocationTracker.Fix] 流喂给 [MapLibreMapView]
 * 的自定义管线（复用其缓动跟随，无需新相机逻辑）。
 */
@Composable
fun TrackPlaybackScreen(
    session: TrackSession,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // ---- 数据准备 ----
    var points by remember { mutableStateOf<List<TrackPoint>>(emptyList()) }
    var stats by remember { mutableStateOf<TrackMerger.Stats?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    LaunchedEffect(session.id) {
        withContext(Dispatchers.IO) {
            val pts = TrackMerger.mergeSmoothDeduped(session)
                .ifEmpty { TrackMerger.mergeRawDeduped(session) }
            points = pts
            stats = if (pts.size >= 2) TrackMerger.stats(pts) else null
            loadFailed = pts.size < 2
        }
    }

    // ---- 回放状态 ----
    var idx by remember(session.id) { mutableIntStateOf(0) }
    var playing by remember(session.id) { mutableStateOf(false) }
    var speed by remember(session.id) { mutableFloatStateOf(4f) }
    val current = points.getOrNull(idx)

    // 合成 Fix 流驱动相机跟随
    val fixFlow = remember { MutableSharedFlow<LocationTracker.Fix>(replay = 1, extraBufferCapacity = 16) }
    fun emitFix(p: TrackPoint) {
        fixFlow.tryEmit(
            LocationTracker.Fix(
                lat = p.lat, lng = p.lng,
                speedMps = p.speedMps,
                bearingDeg = p.bearingDeg,
                accuracyM = p.accuracyM,
                timestampMs = p.timestampMs,
                profile = ActivityProfile.WALKING,
            ),
        )
    }

    // ---- 虚拟时钟回放循环 ----
    LaunchedEffect(playing, speed, points.size) {
        val pts = points
        if (!playing || pts.size < 2) return@LaunchedEffect
        val t0 = pts.first().elapsedNanos
        var virtualNs = pts[idx.coerceIn(0, pts.lastIndex)].elapsedNanos - t0
        var lastFrame = withFrameNanos { it }
        while (currentCoroutineContext().isActive && idx < pts.lastIndex) {
            val now = withFrameNanos { it }
            virtualNs += ((now - lastFrame) * speed).toLong()
            lastFrame = now
            var advanced = false
            while (idx < pts.lastIndex && pts[idx + 1].elapsedNanos - t0 <= virtualNs) {
                idx++
                advanced = true
            }
            if (advanced) emitFix(pts[idx])
        }
        playing = false
    }

    // ★ 系统返回键：先停回放再关闭
    BackHandler { playing = false; onClose() }

    // ---- UI ----
    Box(modifier = modifier.fillMaxSize()) {
        // 全屏地图（隐藏自身按钮，轨迹线 + 回放标记 + 相机跟随）
        MapLibreMapView(
            modifier = Modifier.fillMaxSize(),
            config = MapConfig(
                useCustomLocationPipeline = true,
                customLocationTrackingZoom = 17.0,
                showLocationButton = false,
                showLayerButton = false,
            ),
            externalLocationFixes = fixFlow,
            historySegments = if (points.size >= 2) listOf(points) else emptyList(),
            playbackPoint = current,
        )

        // ======================= 顶部：返回 + 渐变压边 + 统计 =======================
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xCC000000), Color(0x66000000), Color.Transparent),
                    ),
                )
                .padding(horizontal = 8.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { playing = false; onClose() }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = Color.White,
                    )
                }
                Column {
                    Text("轨迹回放", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text(
                        session.name,
                        color = Color(0xFF8B949E),
                        fontSize = 11.sp,
                        maxLines = 1,
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 统计块：大字距离 + 三列数据
            val st = stats
            if (st != null) {
                Row(
                    verticalAlignment = Alignment.Bottom,
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    Text(
                        "%.2f".format(st.distanceM / 1000),
                        color = Color.White,
                        fontSize = 42.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        " 公里",
                        color = Color(0xFF8B949E),
                        fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    // 朝向箭头（当前点带方位角时）
                    val brg = current?.bearingDeg
                    if (brg != null) {
                        Spacer(modifier = Modifier.weight(1f))
                        Icon(
                            Icons.Filled.Navigation,
                            contentDescription = "朝向",
                            tint = Color(0xFF00E5FF),
                            modifier = Modifier
                                .size(30.dp)
                                .rotate(brg)
                                .padding(bottom = 4.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.padding(horizontal = 16.dp)) {
                    StatCell("用时", st.durationText, Modifier.weight(1f))
                    StatCell("平均配速", paceText(st.distanceM, st.durationMs), Modifier.weight(1f))
                    StatCell("平均速度", "%.1f km/h".format(st.averageSpeedKmh), Modifier.weight(1f))
                }
                // 当前运动数据（回放进行中）
                if (playing && current != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(modifier = Modifier.padding(horizontal = 16.dp)) {
                        val v = current.speedMps
                        StatCell(
                            "当前速度",
                            if (v != null) "%.1f km/h".format(v * 3.6f) else "--",
                            Modifier.weight(1f),
                            highlight = true,
                        )
                        StatCell(
                            "当前配速",
                            if (v != null && v > 0.3f) paceFromSpeed(v) else "--",
                            Modifier.weight(1f),
                            highlight = true,
                        )
                        StatCell(
                            "时刻",
                            timeOf(current.timestampMs),
                            Modifier.weight(1f),
                            highlight = true,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            } else if (loadFailed) {
                Text(
                    "该会话轨迹点不足，无法回放",
                    color = Color(0xFFFF5252),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }

        // ======================= 底部：进度 + 控制 =======================
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color(0x99000000)),
                    ),
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            if (points.size >= 2) {
                // 进度条（可拖拽 seek）
                val frac = if (points.size > 1) idx.toFloat() / points.lastIndex else 0f
                Slider(
                    value = frac.coerceIn(0f, 1f),
                    onValueChange = { v ->
                        idx = (v * points.lastIndex).roundToInt().coerceIn(0, points.lastIndex)
                        emitFix(points[idx])
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF00E5FF),
                        activeTrackColor = Color(0xFF00E5FF),
                        inactiveTrackColor = Color(0x44FFFFFF),
                    ),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${idx + 1}/${points.size}",
                        color = Color(0xFF8B949E),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    // 倍速
                    TextButton(onClick = {
                        speed = when (speed) {
                            1f -> 4f
                            4f -> 10f
                            else -> 1f
                        }
                    }) {
                        Text(
                            "${speed.toInt()}x",
                            color = Color(0xFFFFD600),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    // 播放 / 暂停
                    TextButton(
                        onClick = {
                            if (idx >= points.lastIndex) idx = 0   // 播完再按 = 重播
                            playing = !playing
                        },
                        modifier = Modifier
                            .size(46.dp)
                            .background(
                                if (playing) Color(0xFFFF1744) else Color(0xFF00E676),
                                CircleShape,
                            ),
                    ) {
                        Icon(
                            imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (playing) "暂停" else "播放",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        timeOf(current?.timestampMs ?: session.startedMs),
                        color = Color(0xFF8B949E),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

/** 统计单元格：标签 + 等宽数值。 */
@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier, highlight: Boolean = false) {
    Column(modifier = modifier) {
        Text(
            value,
            color = if (highlight) Color(0xFF00E5FF) else Color.White,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = if (highlight) 15.sp else 17.sp,
        )
        Text(
            label,
            color = Color(0xFF8B949E),
            fontSize = 11.sp,
        )
    }
}

/** 配速（分'秒" / 公里）：由总距离和总时长算平均配速。 */
private fun paceText(distanceM: Double, durationMs: Long): String {
    if (distanceM < 1.0) return "--"
    val secPerKm = (durationMs / 1000.0) / (distanceM / 1000.0)
    return "%d'%02d\"".format((secPerKm / 60).toInt(), (secPerKm % 60).toInt())
}

/** 由瞬时速度（m/s）算当前配速。 */
private fun paceFromSpeed(speedMps: Float): String {
    val secPerKm = 1000.0 / speedMps
    return "%d'%02d\"".format((secPerKm / 60).toInt(), (secPerKm % 60).toInt())
}

private fun timeOf(ms: Long): String =
    java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(ms))