package org.kori.plugin.geo.track

import android.util.Log

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
import androidx.compose.material3.ExperimentalMaterial3Api
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.kori.plugin.geo.location.ActivityProfile
import org.kori.plugin.geo.location.LocationTracker
import org.kori.plugin.geo.map.MapCameraCommand
import org.kori.plugin.geo.map.MapConfig
import org.kori.plugin.geo.map.MapLibreMapView
import org.kori.plugin.geo.track.di.TrackMediaRecord
import org.kori.plugin.geo.track.di.TrackPoint
import org.kori.plugin.geo.track.di.TrackSession
import org.kori.plugin.geo.track.ui.TrackMediaTapHost
import java.io.File
import kotlin.math.roundToInt

/**
 * 沉浸式轨迹回放屏 —— 全屏地图 + 半透明 HUD。
 *
 * ## 界面构成
 *
 *  · 全屏地图：历史轨迹线 + 亮青回放标记 + 媒体点位（回放到点弹出）
 *  · 顶部：返回 + 标题；统计块 —— 大字距离 + 用时 / 平均配速 / 平均速度
 *  · 运动中数据（回放进行中）：当前速度、当前配速、当前时刻、朝向箭头
 *  · 底部：播放/暂停 + 倍速（上）；进度条（下，可拖拽 seek）
 *
 * ## 回放引擎
 *
 * 虚拟时钟：真实帧间隔 × 倍速累积，映射到轨迹点的 elapsedNanos 时间轴。
 * 支持暂停/续播（从当前点继续）/ 拖拽进度条定位——不依赖 TrackPlayer 的
 * 延迟流，因此天然支持 seek。
 *
 * ## 相机策略
 *
 *  · 会话加载完成 → FitPoints 显示整条轨迹（概览）
 *  · 首次点播放 → 停留全景 1.2s，随后 ResumeFollow 缓入跟随
 *  · 拖动进度条 seek → 立即 ResumeFollow 到目标点
 *  · 用户手势平移地图 → MapLibre 的 DropFollowOnPan 生效，暂停跟随
 *
 * ## 媒体弹出动画
 *
 * 每个媒体在回放时间经过其 timestampMs 时，图标从 0 缩放到 1（带轻微
 * overshoot）。每帧推进的真实动画时间独立于倍速，暂停时冻结；seek 直接
 * 切状态不播动画。
 *
 * ## 媒体加载
 *
 * TrackSession.media 已由 TrackSessionStore.readSession 从
 * 会话目录 media 子目录下的 *.json 读好。默认直接使用；filePath 相对路径会在
 * 内部统一转绝对路径（toAbsolute），供 MapIconUtil 读取照片缩略图。
 *
 * @param loadMedia 会话媒体加载器。默认 `{ it.media }`，通常无需传入。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackPlaybackScreen(
    session: TrackSession,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    loadMedia: suspend (TrackSession) -> List<TrackMediaRecord> = { it.media },
) {
    // =============================================================================================
    // 数据准备
    // =============================================================================================
    var points by remember { mutableStateOf<List<TrackPoint>>(emptyList()) }
    var stats by remember { mutableStateOf<TrackMerger.Stats?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var media by remember { mutableStateOf<List<TrackMediaRecord>>(emptyList()) }

    LaunchedEffect(session.id) {
        withContext(Dispatchers.IO) {
            val pts = TrackMerger.mergeSmoothDeduped(session)
                .ifEmpty { TrackMerger.mergeRawDeduped(session) }
            points = pts
            stats = if (pts.size >= 2) TrackMerger.stats(pts) else null
            loadFailed = pts.size < 2

            // 媒体：默认取 session.media；filePath 统一转绝对路径
            // （session.media[i].filePath 是相对路径 "media/xxx.jpg"，
            //  而生成照片气泡针时要读这个文件，必须绝对路径）
            val raw = runCatching { loadMedia(session) }
                .getOrElse { session.media }
                .ifEmpty { session.media }
            media = raw.map { m -> m.toAbsolute(session) }
            Log.i("TrackPlayback", "media loaded: ${'$'}{media.size}, points=${'$'}{pts.size}")
        }
    }

    // =============================================================================================
    // 回放状态
    // =============================================================================================
    var idx by remember(session.id) { mutableIntStateOf(0) }
    var playing by remember(session.id) { mutableStateOf(false) }
    var speed by remember(session.id) { mutableFloatStateOf(4f) }
    val current = points.getOrNull(idx)

    // =============================================================================================
    // 媒体弹出动画状态
    // =============================================================================================
    var mediaScales by remember(session.id) { mutableStateOf<Map<String, Float>>(emptyMap()) }
    val animElapsedNs = remember(session.id) { mutableMapOf<String, Long>() }
    var animLastFrameNs by remember(session.id) { mutableStateOf(0L) }

    LaunchedEffect(media) {
        animElapsedNs.clear()
        animLastFrameNs = 0L
        mediaScales = media.associate { it.filePath to 0f }
    }

    // =============================================================================================
    // 相机命令流（概览 / 跟随）
    // =============================================================================================
    val cameraCommands = remember {
        MutableSharedFlow<MapCameraCommand>(extraBufferCapacity = 4)
    }

    // ★ 媒体标记点击：照片/视频全屏查看，录音就地播放
    val onMediaTap = TrackMediaTapHost()
    var followArmed by remember(session.id) { mutableStateOf(false) }

    LaunchedEffect(points.size) {
        if (points.size >= 2) {
            cameraCommands.tryEmit(
                MapCameraCommand.FitPoints(
                    points = points,
                    paddingPx = 120,
                    animateMs = 700,
                ),
            )
        }
    }

    // =============================================================================================
    // 合成 Fix 流驱动相机跟随
    // =============================================================================================
    val fixFlow = remember {
        MutableSharedFlow<LocationTracker.Fix>(replay = 1, extraBufferCapacity = 16)
    }
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

    // =============================================================================================
    // 媒体动画辅助
    // =============================================================================================

    /** seek：直接切到目标时刻状态，不播动画。 */
    fun snapMediaTo(timeMs: Long) {
        animElapsedNs.clear()
        media.forEach { m ->
            if (m.timestampMs <= timeMs) {
                animElapsedNs[m.filePath] = MEDIA_ANIM_DURATION_NS
            }
        }
        mediaScales = media.associate {
            it.filePath to if (it.timestampMs <= timeMs) 1f else 0f
        }
    }

    /** 重播：全部隐藏。 */
    fun resetMedia() {
        animElapsedNs.clear()
        animLastFrameNs = 0L
        mediaScales = media.associate { it.filePath to 0f }
    }

    /**
     * 每帧推进：新到达的媒体开启弹出动画。
     * dt 上限 100ms，长时间暂停 / 主线程卡顿不会造成动画时间跳跃。
     */
    fun advanceMediaAnimation(frameNs: Long) {
        if (media.isEmpty()) return
        val curMs = points.getOrNull(idx)?.timestampMs ?: return

        val dt = if (animLastFrameNs == 0L) 0L
        else (frameNs - animLastFrameNs).coerceIn(0L, 100_000_000L)
        animLastFrameNs = frameNs

        val dueIds = HashSet<String>(media.size)
        for (m in media) if (m.timestampMs <= curMs) dueIds += m.filePath
        animElapsedNs.keys.retainAll(dueIds)

        for (m in media) {
            if (m.timestampMs > curMs) continue
            val prev = animElapsedNs[m.filePath] ?: 0L
            animElapsedNs[m.filePath] = (prev + dt).coerceAtMost(MEDIA_ANIM_DURATION_NS)
        }

        val out = HashMap<String, Float>(media.size)
        for (m in media) {
            if (m.timestampMs > curMs) {
                out[m.filePath] = 0f
                continue
            }
            val elapsed = animElapsedNs[m.filePath] ?: 0L
            val p = (elapsed.toFloat() / MEDIA_ANIM_DURATION_NS).coerceIn(0f, 1f)
            out[m.filePath] = easeOutBack(p)
        }
        if (out != mediaScales) mediaScales = out
    }

    // =============================================================================================
    // 虚拟时钟回放循环
    // =============================================================================================
    LaunchedEffect(playing, speed, points.size, media) {
        val pts = points
        if (!playing || pts.size < 2) return@LaunchedEffect
        val t0 = pts.first().elapsedNanos
        var virtualNs = pts[idx.coerceIn(0, pts.lastIndex)].elapsedNanos - t0
        var lastFrame = withFrameNanos { it }
        animLastFrameNs = 0L
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
            advanceMediaAnimation(now)
        }
        playing = false
    }

    // 首次播放：短暂停留全景（1.2s），随后缓入跟随
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        if (followArmed) return@LaunchedEffect
        followArmed = true
        delay(1200)
        cameraCommands.tryEmit(MapCameraCommand.ResumeFollow)
    }

    BackHandler { playing = false; onClose() }

    // =============================================================================================
    // UI
    // =============================================================================================
    Box(modifier = modifier.fillMaxSize()) {
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
            liveTrackMedia = media,
            mediaScales = mediaScales,
            cameraCommands = cameraCommands,
            onMediaClick = onMediaTap,
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
                    Text(
                        "轨迹回放",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                    )
                    Text(
                        session.name,
                        color = Color(0xFF8B949E),
                        fontSize = 11.sp,
                        maxLines = 1,
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

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
                    StatCell(
                        "平均速度",
                        "%.1f km/h".format(st.averageSpeedKmh),
                        Modifier.weight(1f),
                    )
                }
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

        // ======================= 底部：控制行（上）+ 进度条（下）=======================
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
                // ---- 控制行：进度文本 | 倍速 | 播放/暂停 | 时刻（放上方）----
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

                    // 播放 / 暂停（放在进度条上方，避免被其它 UI 遮挡）
                    TextButton(
                        onClick = {
                            if (idx >= points.lastIndex) {
                                idx = 0
                                resetMedia()
                                followArmed = true
                                cameraCommands.tryEmit(MapCameraCommand.ResumeFollow)
                            }
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

                Spacer(modifier = Modifier.height(4.dp))

                // ---- 进度条（放下方，自定义实心圆 thumb）----
                val frac = if (points.size > 1) idx.toFloat() / points.lastIndex else 0f
                Slider(
                    value = frac.coerceIn(0f, 1f),
                    onValueChange = { v ->
                        val newIdx = (v * points.lastIndex).roundToInt()
                            .coerceIn(0, points.lastIndex)
                        if (newIdx != idx) {
                            idx = newIdx
                            emitFix(points[idx])
                            snapMediaTo(points[idx].timestampMs)
                            followArmed = true
                            cameraCommands.tryEmit(MapCameraCommand.ResumeFollow)
                        }
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF00E5FF),
                        activeTrackColor = Color(0xFF00E5FF),
                        inactiveTrackColor = Color(0x44FFFFFF),
                    ),
                    // 实心圆 thumb（替换 Material3 默认的细长条 handle）
                    thumb = {
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .background(Color(0xFF00E5FF), CircleShape),
                        )
                    },
                )
            }
        }
    }
}

// =============================================================================================
// 辅助
// =============================================================================================

@Composable
private fun StatCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
) {
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

private fun paceText(distanceM: Double, durationMs: Long): String {
    if (distanceM < 1.0) return "--"
    val secPerKm = (durationMs / 1000.0) / (distanceM / 1000.0)
    return "%d'%02d\"".format((secPerKm / 60).toInt(), (secPerKm % 60).toInt())
}

private fun paceFromSpeed(speedMps: Float): String {
    val secPerKm = 1000.0 / speedMps
    return "%d'%02d\"".format((secPerKm / 60).toInt(), (secPerKm % 60).toInt())
}

private fun timeOf(ms: Long): String =
    java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        .format(java.util.Date(ms))

/**
 * 把 [TrackMediaRecord.filePath] 统一转成绝对路径（幂等）：
 *  · 已经是绝对路径 → 原样返回
 *  · 相对路径 → 用 [session].dir 解析
 */
private fun TrackMediaRecord.toAbsolute(session: TrackSession): TrackMediaRecord {
    val f = File(filePath)
    val abs = if (f.isAbsolute) f else session.resolve(filePath)
    return if (abs.absolutePath == filePath) this else copy(filePath = abs.absolutePath)
}

// =============================================================================================
// 媒体弹出动画参数
// =============================================================================================

private const val MEDIA_ANIM_DURATION_NS = 350_000_000L

private fun easeOutBack(t: Float): Float {
    val c1 = 1.70158f
    val c3 = c1 + 1f
    val x = t - 1f
    return 1f + c3 * x * x * x + c1 * x * x
}