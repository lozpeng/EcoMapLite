package org.kori.plugin.geo.track.ui

import android.media.MediaPlayer
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import org.kori.plugin.geo.math.GeoMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.kori.plugin.geo.track.TrackEvent
import org.kori.plugin.geo.track.TrackEventType
import org.kori.plugin.geo.track.TrackExporter
import org.kori.plugin.geo.track.TrackMerger
import org.kori.plugin.geo.track.TrackShare
import org.kori.plugin.geo.track.di.TrackMediaRecord
import org.kori.plugin.geo.track.di.TrackPoint
import org.kori.plugin.geo.track.di.TrackSession
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

/**
 * 轨迹时间线界面 v8 —— 左轴分段 + 右侧信息卡。
 * 分段号角标挂在节点圆点左上角（白底深色数字，任何速度色下清晰可辨）。
 *
 * ## 布局
 *
 *  · **左轴**（40dp）：分段号角标挂在彩色节点左上角，下方为同色半透明线段；
 *    线段高度 ∝ 相邻条目的轨迹距离
 *  · **右侧信息卡**：标题即时间（HH:mm，起终点为完整日期时间），卡内按类型展示内容
 *
 * ## 颜色体系
 *
 *  · 起点 绿 / 终点 红 / 暂停 琥珀 / 媒体 紫
 *  · **轨迹点按均速着色**：越慢越紫、越快越青绿（[speedColor] 线性插值）
 *  · 节点、分段徽章、线段、信息卡底色全部同源同色，视觉一致
 *
 * ## 信息卡内容
 *
 *  · **起点 / 终点**：旗标 + 完整时间
 *  · **暂停**：停留时长
 *  · **轨迹点**：距离 · 耗时 · 均速（无媒体时的默认信息栏）
 *  · **媒体**：经纬度显示在标题行（**长按复制坐标**）；照片点击放大；
 *    **音频就地播放**（内嵌播放条）；**视频小窗预览**（默认待播不透底，中央按钮播放/暂停，
 *    点击全屏角标全屏——与照片查看同一交互）
 *
 * 全部按时间倒序渲染，分段号按时间正序编号（起点为 #1）。顶部含 **分享/导出** 按钮。
 */
@Composable
fun TrackTimelineScreen(
    session: TrackSession,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // ===== 1. MERGE / DEDUPE =====
    var points by remember { mutableStateOf<List<TrackPoint>>(emptyList()) }
    LaunchedEffect(session.id) {
        points = loadMergedPoints(session)
    }

    // ===== 2+3. EVENTS / PAUSE + BUCKET / DISTANCE =====
    val timeline = remember(points, session.media, session.events) {
        TimelineBuilder.build(points, session.media, session.events)
    }

    var viewerPhoto by remember { mutableStateOf<TrackMediaRecord?>(null) }
    var viewerVideo by remember { mutableStateOf<TrackMediaRecord?>(null) }
    var exportTarget by remember { mutableStateOf(false) }

    val dateFmt = remember { SimpleDateFormat("yyyy年MM月dd日 HH:mm", Locale.getDefault()) }

    // 系统返回键关闭（对话框打开时由对话框自身处理返回）
    BackHandler { onClose() }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            // ---- 标题 + 导出 + 关闭 ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "轨迹时间线",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "${session.name} · ${dateFmt.format(Date(session.startedMs))}" +
                                " · ${session.distanceText} · ${session.durationText}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                IconButton(onClick = { exportTarget = true }) {
                    Icon(
                        Icons.Filled.Share,
                        contentDescription = "导出分享",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ===== 4. RENDER / VERIFY =====
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(timeline.items, key = { _, item -> item.key }) { index, item ->
                    // 行高 = 与下一条目之间的轨迹距离（比例轴）；媒体行保证缩略图/播放控件空间
                    val next = timeline.items.getOrNull(index + 1)
                    val rowHeight: Dp? = if (next == null) {
                        null // 末行（起点）以下没有线 —— 线段终于起点
                    } else {
                        val minH = when (item) {
                            is TlItem.Media -> when (item.record.type) {
                                TrackMediaRecord.Type.VIDEO -> 224.dp // 小窗预览 + 标题行
                                TrackMediaRecord.Type.AUDIO -> 132.dp // 播放条 + 标题行
                                TrackMediaRecord.Type.PHOTO -> 108.dp
                            }
                            else -> 104.dp
                        }
                        timeline.lineHeightsDp[index].dp.coerceAtLeast(minH)
                    }
                    TimelineRow(
                        item = item,
                        // 分段号按时间正序编号：倒序列表中越靠后（越早）号越小，起点为 #1
                        segNo = timeline.items.size - index,
                        isLast = index == timeline.items.lastIndex,
                        rowHeight = rowHeight,
                        mediaFile = if (item is TlItem.Media) session.resolve(item.record.filePath) else null,
                        onViewPhoto = { viewerPhoto = it },
                        onExpandVideo = { viewerVideo = it },
                    )
                }
            }
        }
    }

    // ---- 照片放大查看 ----
    viewerPhoto?.let { record ->
        val file = session.resolve(record.filePath)
        Dialog(onDismissRequest = { viewerPhoto = null }) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black)
                    .clickable { viewerPhoto = null },
            ) {
                AsyncImage(
                    model = file,
                    contentDescription = record.filePath,
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }

    // ---- 视频全屏播放（与照片查看同一交互：点击画面关闭） ----
    viewerVideo?.let { record ->
        VideoViewerDialog(
            file = session.resolve(record.filePath),
            onDismiss = { viewerVideo = null },
        )
    }

    // ---- 导出格式对话框 ----
    if (exportTarget) {
        ExportFormatDialog(
            sessionName = session.name,
            onDismiss = { exportTarget = false },
            onExport = { format ->
                exportTarget = false
                TrackShare.exportAndShare(context, session, format)
            },
        )
    }
}

// =============================================================================================
// 颜色体系
// =============================================================================================

private val C_START = Color(0xFF00E676) // 起点 · 绿
private val C_END = Color(0xFFFF1744)   // 终点 · 红
private val C_PAUSE = Color(0xFFFFB300) // 暂停 · 琥珀
private val C_MEDIA = Color(0xFF9334E6) // 媒体 · 紫
private val C_SLOW = Color(0xFF7C4DFF)  // 轨迹点最慢 · 紫
private val C_FAST = Color(0xFF00E5A0)  // 轨迹点最快 · 青绿

/**
 * 均速（km/h）→ 颜色：0 或更慢取 [C_SLOW]，20 km/h 及以上取 [C_FAST]，线性插值。
 * 停着不动的路段呈紫色，骑得快的路段呈青绿色。
 */
private fun speedColor(kmh: Double): Color {
    val t = (kmh / 20.0).coerceIn(0.0, 1.0).toFloat()
    return Color(
        red = C_SLOW.red + (C_FAST.red - C_SLOW.red) * t,
        green = C_SLOW.green + (C_FAST.green - C_SLOW.green) * t,
        blue = C_SLOW.blue + (C_FAST.blue - C_SLOW.blue) * t,
    )
}

/** 颜色加深（乘系数），用于浅色底上的文字，保证对比度。 */
private fun darkened(color: Color, factor: Float = 0.68f): Color =
    Color(color.red * factor, color.green * factor, color.blue * factor)

/** 条目主色：节点、徽章、线段、信息卡底色同源。 */
private fun itemColor(item: TlItem): Color = when (item) {
    is TlItem.Start -> C_START
    is TlItem.End -> C_END
    is TlItem.Pause -> C_PAUSE
    is TlItem.Points -> speedColor(item.speedKmh)
    is TlItem.Media -> C_MEDIA
}

// =============================================================================================
// 1. MERGE / DEDUPE
// =============================================================================================

/** IO 线程合并并去重会话的原始轨迹点。 */
private suspend fun loadMergedPoints(session: TrackSession): List<TrackPoint> =
    withContext(Dispatchers.IO) {
        TrackMerger.mergeRawDeduped(session)
    }

// =============================================================================================
// 条目模型
// =============================================================================================

private const val BUCKET_MINUTES = 5L

private sealed class TlItem {
    abstract val timeMs: Long
    abstract val key: String

    /** 起点。 */
    data class Start(val atMs: Long) : TlItem() {
        override val timeMs get() = atMs
        override val key get() = "start-$atMs"
    }

    /** 终点。 */
    data class End(val atMs: Long) : TlItem() {
        override val timeMs get() = atMs
        override val key get() = "end-$atMs"
    }

    /** 暂停/停留（由录制的 PAUSE/RESUME 事件对精确计算）。 */
    data class Pause(val atMs: Long, val pausedMs: Long) : TlItem() {
        override val timeMs get() = atMs
        override val key get() = "pause-$atMs"
    }

    /**
     * 聚合的轨迹点段。
     *
     * @param distanceM 该桶的轨迹距离（米），由累计里程插值计算
     */
    data class Points(
        val fromMs: Long,
        val toMs: Long,
        val count: Int,
        val distanceM: Double = 0.0,
    ) : TlItem() {
        override val timeMs get() = toMs
        override val key get() = "pts-$fromMs"
        val durationMs: Long get() = (toMs - fromMs).coerceAtLeast(0L)

        /** 平均速度 km/h；无耗时视为 0。 */
        val speedKmh: Double
            get() = if (durationMs > 0) distanceM / durationMs * 3600_000.0 / 1000.0 else 0.0
    }

    /** 媒体条目。 */
    data class Media(val record: TrackMediaRecord) : TlItem() {
        override val timeMs get() = record.timestampMs
        override val key get() = "media-${record.timestampMs}-${record.filePath}"
    }
}

// =============================================================================================
// 2+3. 时间线构建（纯计算，可单测）
// =============================================================================================

/** 构建完成的时间线：倒序条目 + 每条目下方线段高度（dp，最后一条为 0）。 */
private class Timeline(
    val items: List<TlItem>,
    val lineHeightsDp: List<Float>,
)

private object TimelineBuilder {

    /**
     * 构建时间线：起终点 + 暂停（事件对精确计算）+ 聚合点 + 媒体，倒序。
     *
     * heights[i] = 条目 i 到下一条目之间的 **轨迹距离** 换算的线段高度：
     * 跑得快的路段线段长，原地停留的路段线段短（落到行最小高度）。
     */
    fun build(
        points: List<TrackPoint>,
        media: List<TrackMediaRecord>,
        events: List<TrackEvent>,
    ): Timeline {
        // 累计里程插值先算好：桶距离与线段高度都要用
        val cumAt = cumulativeDistance(points)

        val items = ArrayList<TlItem>()
        if (points.isNotEmpty()) {
            items.add(TlItem.Start(points.first().timestampMs))
            items.add(TlItem.End(points.last().timestampMs))
        }
        items.addAll(pausesFromEvents(events, points.lastOrNull()?.timestampMs))
        items.addAll(bucketPoints(points, cumAt))
        media.forEach { items.add(TlItem.Media(it)) }

        val sorted = items.sortedByDescending { it.timeMs }

        // ---- 相邻条目间的轨迹距离 → 线段高度 ----
        val heights = sorted.mapIndexed { i, item ->
            val next = sorted.getOrNull(i + 1)
            if (next == null) {
                0f // 起点：以下无线
            } else {
                // 本条时间 ≤ 下一条时间（倒序），距离 = 里程差
                val stretchM = (cumAt(item.timeMs) - cumAt(next.timeMs)).coerceAtLeast(0.0)
                distToLineDp(stretchM)
            }
        }
        return Timeline(sorted, heights)
    }

    /**
     * 暂停：由录制的 PAUSE/RESUME 事件对精确计算（不做间隙启发式）。
     * 暂停中结束（无配对 RESUME）→ 用 [lastPointMs] 作为暂停结束。
     */
    fun pausesFromEvents(events: List<TrackEvent>, lastPointMs: Long?): List<TlItem.Pause> {
        val sortedEvents = events.sortedBy { it.timestampMs }
        val pauses = ArrayList<TlItem.Pause>()
        var i = 0
        while (i < sortedEvents.size) {
            val e = sortedEvents[i]
            if (e.type != TrackEventType.PAUSE) {
                i++
                continue
            }
            var j = i + 1
            while (j < sortedEvents.size && sortedEvents[j].type != TrackEventType.RESUME) j++
            val endMs = if (j < sortedEvents.size) sortedEvents[j].timestampMs
            else lastPointMs ?: e.timestampMs
            pauses.add(
                TlItem.Pause(
                    atMs = e.timestampMs,
                    pausedMs = (endMs - e.timestampMs).coerceAtLeast(0L),
                ),
            )
            i = j + 1 // 跳过已配对的 RESUME
        }
        return pauses
    }

    /** 无媒体的点按 [BUCKET_MINUTES] 桶聚合成 [TlItem.Points]，并填入桶内轨迹距离。 */
    fun bucketPoints(points: List<TrackPoint>, cumAt: (Long) -> Double): List<TlItem.Points> {
        if (points.isEmpty()) return emptyList()
        val bucketMs = BUCKET_MINUTES * 60_000L
        val buckets = LinkedHashMap<Long, MutableList<TrackPoint>>()
        for (p in points) {
            buckets.getOrPut(p.timestampMs / bucketMs) { mutableListOf() }.add(p)
        }
        return buckets.values.filter { it.isNotEmpty() }.map { pts ->
            val fromMs = pts.first().timestampMs
            val toMs = pts.last().timestampMs
            val distanceM = (cumAt(toMs) - cumAt(fromMs)).coerceAtLeast(0.0)
            TlItem.Points(fromMs, toMs, pts.size, distanceM)
        }
    }

    /**
     * 累计里程表：返回 `时间点 → 累计距离（米）` 的插值查询函数。
     * 区间外取端点值，区间内线性插值。
     */
    fun cumulativeDistance(points: List<TrackPoint>): (Long) -> Double {
        if (points.isEmpty()) return { 0.0 }
        val cumList = ArrayList<Pair<Long, Double>>(points.size)
        var acc = 0.0
        for (i in points.indices) {
            if (i > 0) {
                acc += GeoMath.haversineMeters(
                    points[i - 1].lat, points[i - 1].lng,
                    points[i].lat, points[i].lng,
                )
            }
            cumList.add(points[i].timestampMs to acc)
        }
        return { ms ->
            when {
                ms <= cumList.first().first -> cumList.first().second
                ms >= cumList.last().first -> cumList.last().second
                else -> {
                    // 二分：找第一个 time >= ms 的点，线性插值
                    var lo = 0
                    var hi = cumList.lastIndex
                    while (lo < hi) {
                        val mid = (lo + hi) ushr 1
                        if (cumList[mid].first < ms) lo = mid + 1 else hi = mid
                    }
                    val b = cumList[lo]
                    val a = cumList[(lo - 1).coerceAtLeast(0)]
                    val span = (b.first - a.first).toDouble()
                    val frac = if (span <= 0) 0.0 else (ms - a.first) / span
                    a.second + (b.second - a.second) * frac.coerceIn(0.0, 1.0)
                }
            }
        }
    }

    /** 轨迹距离 → 线段高度：1 公里 ≈ 48dp，上限 200dp。 */
    fun distToLineDp(meters: Double): Float =
        (meters / 1000.0 * 48.0).toFloat().coerceIn(0f, 200f)
}

// =============================================================================================
// 4. UI 行：左轴分段 + 右侧信息卡
// =============================================================================================

@Composable
private fun TimelineRow(
    item: TlItem,
    segNo: Int,
    isLast: Boolean,
    rowHeight: Dp?,
    mediaFile: File?,
    onViewPhoto: (TrackMediaRecord) -> Unit,
    onExpandVideo: (TrackMediaRecord) -> Unit,
) {
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val dateTimeFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    val color = itemColor(item)
    // 信息卡标题即时间：起终点用完整日期时间，其余用时分
    val titleTime = when (item) {
        is TlItem.Start -> dateTimeFmt.format(Date(item.atMs))
        is TlItem.End -> dateTimeFmt.format(Date(item.atMs))
        else -> timeFmt.format(Date(item.timeMs))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 行高即线段长度（轨迹距离比例）；末行随内容
            .then(if (rowHeight != null) Modifier.height(rowHeight) else Modifier),
    ) {
        // ---- 左轴：分段号角标挂在节点左上角，下方接同色线段 ----
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(40.dp)) {
            NodeWithBadge(no = segNo, color = color)
            if (!isLast) {
                // 线段与节点同源着色（半透明），不同高度的行无缝衔接成一条彩带
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .weight(1f)
                        .background(color.copy(alpha = 0.45f)),
                )
            }
        }

        Spacer(modifier = Modifier.width(10.dp))

        // ---- 右侧信息卡：标题 = 时间 ----
        InfoCard(
            item = item,
            color = color,
            titleTime = titleTime,
            mediaFile = mediaFile,
            onViewPhoto = onViewPhoto,
            onExpandVideo = onExpandVideo,
        )
    }
}
@Composable
private fun NodeWithBadge(no: Int, color: Color) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .background(color, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "$no",
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** 右侧信息卡：浅色圆角底（条目主色 10%），标题行 = 时间 + 类型标签。 */
@Composable
private fun RowScope.InfoCard(
    item: TlItem,
    color: Color,
    titleTime: String,
    mediaFile: File?,
    onViewPhoto: (TrackMediaRecord) -> Unit,
    onExpandVideo: (TrackMediaRecord) -> Unit,
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.10f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        // ---- 标题行：时间 + 类型标签（+ 起终点旗标） ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                titleTime,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.width(8.dp))
            TypeChip(label = typeLabel(item), color = color)
            // 媒体条目：标题行内显示经纬度（不换行），长按复制坐标
            if (item is TlItem.Media) {
                Spacer(modifier = Modifier.width(8.dp))
                MediaCoordText(item.record)
            }
            Spacer(modifier = Modifier.weight(1f))
            when (item) {
                is TlItem.Start ->
                    Icon(Icons.Filled.Flag, "起点", tint = color, modifier = Modifier.size(16.dp))
                is TlItem.End ->
                    Icon(Icons.Filled.Flag, "终点", tint = color, modifier = Modifier.size(16.dp))
                else -> Unit
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // ---- 卡体：按类型展示 ----
        when (item) {
            is TlItem.Start -> CardNote("从这里出发", color)
            is TlItem.End -> CardNote("到这里结束", color)

            is TlItem.Pause -> StatLine("停留", formatSpan(item.pausedMs))

            // 无媒体信息时的默认信息栏：距离 / 耗时 / 均速
            is TlItem.Points -> {
                StatLine("距离", formatDistance(item.distanceM))
                StatLine("耗时", formatSpan(item.durationMs))
                StatLine("均速", "%.1f km/h".format(item.speedKmh))
            }

            is TlItem.Media -> MediaContent(item.record, mediaFile, onViewPhoto, onExpandVideo)
        }
    }
}

/** 类型标签：彩色小胶囊。 */
@Composable
private fun TypeChip(label: String, color: Color) {
    Text(
        label,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = darkened(color),
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** 信息卡附注行（起终点）。 */
@Composable
private fun CardNote(text: String, color: Color) {
    Text(
        text,
        fontSize = 13.sp,
        color = darkened(color),
        fontWeight = FontWeight.Medium,
    )
}

/** 统计行：灰色小标签 + 深色数值，即无媒体时的信息栏行。 */
@Composable
private fun StatLine(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(40.dp),
        )
        Text(
            value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 媒体卡体（信息卡内使用）：
 *  · 顶部统一显示经纬度坐标（[MediaCoordLine]，点击复制到剪贴板）
 *  · **照片**：缩略图，点击进入全屏查看器（[onViewPhoto]）
 *  · **音频**：[AudioPlayerBar] 就地播放，不跳出页面
 *  · **视频**：[VideoPreviewTile] 小窗预览，默认待播，中央按钮播放/暂停，角标全屏
 */
@Composable
private fun MediaContent(
    record: TrackMediaRecord,
    mediaFile: File?,
    onViewPhoto: (TrackMediaRecord) -> Unit,
    onExpandVideo: (TrackMediaRecord) -> Unit,
) {
    when (record.type) {
        TrackMediaRecord.Type.PHOTO -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                .clickable { onViewPhoto(record) }
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black),
            ) {
                if (mediaFile != null) {
                    AsyncImage(
                        model = mediaFile,
                        contentDescription = "照片",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Icon(
                        Icons.Filled.Image,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                "照片",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                "查看",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        TrackMediaRecord.Type.AUDIO -> AudioPlayerBar(file = mediaFile, accent = Color(0xFF00897B))

        TrackMediaRecord.Type.VIDEO -> VideoPreviewTile(
            file = mediaFile,
            onExpand = { onExpandVideo(record) },
        )
    }
}

/**
 * 标题行经纬度：不换行跟在类型标签后；**长按**复制 `lat,lng`（仅坐标，不含其他文字），
 * 复制成功后文字短暂变主题色作为反馈。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaCoordText(record: TrackMediaRecord) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1200)
            copied = false
        }
    }

    val lat = record.lat
    val lng = record.lng
    // 字段非空 Double；0,0 是"未定位"哨兵值（几内亚湾），视为无效坐标
    val hasCoord = !(lat == 0.0 && lng == 0.0)

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (hasCoord) "%.5f, %.5f".format(lat, lng) else "无坐标",
            fontSize = 11.sp,
            maxLines = 1,
            color = if (copied) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.combinedClickable(
                enabled = hasCoord,
                onClick = { /* 标题行不可点，避免误触 */ },
                onLongClick = {
                    clipboard.setText(AnnotatedString("%.6f,%.6f".format(lat, lng)))
                    copied = true
                },
            ),
        )
        // 上一版同款"已复制"提示
        if (copied) {
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                "已复制",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * 就地音频播放条：播放/暂停 + 进度 + 已播/总时长。
 *
 * [MediaPlayer] 随条目 remember，滑出 LazyColumn 即 release（[DisposableEffect]）；
 * 重进列表重新加载。播放进度 200ms 轮询刷新。
 */
@Composable
private fun AudioPlayerBar(file: File?, accent: Color) {
    if (file == null) {
        Text("音频文件缺失", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }

    val player = remember(file) { MediaPlayer() }
    var prepared by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var posMs by remember { mutableLongStateOf(0L) }
    var durMs by remember { mutableLongStateOf(0L) }

    DisposableEffect(file) {
        val mp = player
        try {
            mp.setDataSource(file.absolutePath)
            mp.setOnPreparedListener { pos -> durMs = pos.duration.toLong(); prepared = true }
            mp.setOnCompletionListener { playing = false; posMs = 0L }
            mp.setOnErrorListener { _, _, _ -> failed = true; true }
            mp.prepareAsync()
        } catch (e: Exception) {
            failed = true
        }
        onDispose { runCatching { mp.release() } }
    }

    // 播放中轮询进度
    LaunchedEffect(playing) {
        while (playing) {
            posMs = runCatching { player.currentPosition.toLong() }.getOrDefault(0L)
            delay(200.milliseconds)
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        if (failed) {
            Icon(
                Icons.Filled.Mic,
                contentDescription = "播放失败",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("无法播放", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Row
        }

        // 播放 / 暂停
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(accent)
                .clickable(enabled = prepared) {
                    if (playing) {
                        player.pause()
                        playing = false
                    } else {
                        if (durMs > 0 && posMs >= durMs) {
                            player.seekTo(0)
                            posMs = 0L
                        }
                        player.start()
                        playing = true
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (!prepared) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = Color.White,
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "暂停" else "播放",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // 进度条
        Slider(
            value = if (durMs > 0) posMs.toFloat() / durMs else 0f,
            onValueChange = { v ->
                val target = (v * durMs).toLong()
                runCatching { player.seekTo(target.toInt()) }
                posMs = target
            },
            modifier = Modifier.weight(1f),
            enabled = prepared,
        )

        Spacer(modifier = Modifier.width(8.dp))

        // 已播 / 总时长
        Text(
            "${formatClock(posMs)} / ${formatClock(durMs)}",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 视频小窗预览：黑底圆角窗格，**默认不播放**——就绪后中央显示播放按钮；
 * 点中央按钮播放/暂停，点右上角"⛶ 全屏"角标全屏播放（与照片查看同一交互）。
 *
 * 用 [TextureView] 而非 SurfaceView：TextureView 是正常视图合成，
 * **不会把下层地图透出来**（SurfaceView 打孔机制在部分机型上 setFormat(OPAQUE) 不生效）。
 * 滑出 LazyColumn 即 release。
 */
@Composable
private fun VideoPreviewTile(file: File?, onExpand: () -> Unit) {
    if (file == null) {
        Text("视频文件缺失", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }

    val player = remember(file) { MediaPlayer() }
    var prepared by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    // 用户点播放时纹理尚未就绪：先记下，surface 可用后自动 start
    var pendingStart by remember { mutableStateOf(false) }
    // 当前绑定的 surface，纹理销毁时一并 release
    var playerSurface by remember { mutableStateOf<Surface?>(null) }

    DisposableEffect(file) {
        val mp = player
        try {
            mp.setDataSource(file.absolutePath)
            // 就绪后不自动播放，等用户点中央播放按钮
            mp.setOnPreparedListener {
                prepared = true
                // 用户在就绪前点了播放：surface 已就绪则补 start
                if (pendingStart && playerSurface != null) {
                    pendingStart = false
                    runCatching { mp.start() }
                    playing = true
                }
            }
            mp.setOnCompletionListener { playing = false }
            mp.setOnErrorListener { _, _, _ -> prepared = false; true }
            mp.prepareAsync()
        } catch (e: Exception) {
            prepared = false
        }
        onDispose {
            runCatching { mp.release() }
            playerSurface?.release()
            playerSurface = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(
                            st: SurfaceTexture,
                            width: Int,
                            height: Int,
                        ) {
                            val s = Surface(st)
                            playerSurface?.release()
                            playerSurface = s
                            runCatching { player.setSurface(s) }
                            if (pendingStart) {
                                pendingStart = false
                                runCatching { player.start() }
                                playing = true
                            }
                        }

                        override fun onSurfaceTextureSizeChanged(
                            st: SurfaceTexture,
                            width: Int,
                            height: Int,
                        ) = Unit

                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            runCatching { player.setSurface(null) }
                            playerSurface?.release()
                            playerSurface = null
                            return true
                        }

                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                    }
                }
            },
        )

        if (!prepared) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White,
            )
        } else {
            // 中央播放 / 暂停按钮
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable {
                        if (playing) {
                            runCatching { player.pause() }
                            playing = false
                        } else if (playerSurface != null && prepared) {
                            runCatching { player.start() }
                            playing = true
                        } else {
                            pendingStart = true // surface/解码未就绪，等回调里 start
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "暂停" else "播放",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }
        }

        // 全屏角标：独立点击，进全屏前暂停小窗播放
        Text(
            "⛶ 全屏",
            color = Color.White,
            fontSize = 11.sp,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable {
                    if (playing) {
                        runCatching { player.pause() }
                        playing = false
                    }
                    onExpand()
                }
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * 全屏视频查看器：与照片查看同一交互（点击画面关闭），自动播放。
 * 对话框全宽全高，关闭即 release。
 */
@Composable
private fun VideoViewerDialog(file: File, onDismiss: () -> Unit) {
    val player = remember { MediaPlayer() }
    var prepared by remember { mutableStateOf(false) }
    var playerSurface by remember { mutableStateOf<Surface?>(null) }

    DisposableEffect(Unit) {
        val mp = player
        try {
            mp.setDataSource(file.absolutePath)
            // ★ 修复黑屏：两个回调到达顺序不确定，任一个到达时都检查
            //   "已就绪 && surface 已绑定"，满足即 start
            mp.setOnPreparedListener {
                prepared = true
                if (playerSurface != null) runCatching { mp.start() }
            }
            mp.setOnErrorListener { _, _, _ -> true }
            mp.prepareAsync()
        } catch (e: Exception) {
            onDismiss()
        }
        onDispose {
            runCatching { mp.release() }
            playerSurface?.release()
            playerSurface = null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable { onDismiss() },
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    TextureView(ctx).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(
                                st: SurfaceTexture,
                                width: Int,
                                height: Int,
                            ) {
                                val s = Surface(st)
                                playerSurface?.release()
                                playerSurface = s
                                runCatching { player.setSurface(s) }
                                // ★ 修复黑屏：surface 晚于 prepared 就绪时，在这里补 start
                                if (prepared) runCatching { player.start() }
                            }

                            override fun onSurfaceTextureSizeChanged(
                                st: SurfaceTexture,
                                width: Int,
                                height: Int,
                            ) = Unit

                            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                runCatching { player.setSurface(null) }
                                playerSurface?.release()
                                playerSurface = null
                                return true
                            }

                            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                        }
                    }
                },
            )

            if (!prepared) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White,
                )
            }
        }
    }
}

@Composable
private fun MediaBadge(icon: ImageVector, label: String, color: Color) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.2f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(26.dp))
    }
}

/** 类型 → 标签文案。 */
private fun typeLabel(item: TlItem): String = when (item) {
    is TlItem.Start -> "起点"
    is TlItem.End -> "终点"
    is TlItem.Pause -> "暂停"
    is TlItem.Points -> "轨迹点 ×${item.count}"
    is TlItem.Media -> when (item.record.type) {
        TrackMediaRecord.Type.PHOTO -> "照片"
        TrackMediaRecord.Type.VIDEO -> "视频"
        TrackMediaRecord.Type.AUDIO -> "录音"
    }
}

// =============================================================================================
// 格式化与媒体分流
// =============================================================================================

/** 距离格式化：1 km 以上保留两位小数，以下用米。 */
private fun formatDistance(meters: Double): String =
    if (meters >= 1000.0) "%.2f km".format(meters / 1000.0)
    else "%d m".format(meters.toInt())

/** 播放条时钟：`mm:ss` / `h:mm:ss`。 */
private fun formatClock(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/** 时长格式化：`4分32秒` / `1时05分`。 */
private fun formatSpan(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return when {
        h > 0 -> "${h}时%02d分".format(m)
        m > 0 -> "${m}分${s}秒"
        else -> "${s}秒"
    }
}


/** 导出格式选择对话框。 */
@Composable
private fun ExportFormatDialog(
    sessionName: String,
    onDismiss: () -> Unit,
    onExport: (TrackExporter.Format) -> Unit,
) {
    var format by remember { mutableStateOf(TrackExporter.Format.GPX) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导出轨迹") },
        text = {
            Column {
                Text(
                    sessionName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                TrackExporter.Format.entries.forEach { f ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = format == f, onClick = { format = f })
                        Text(f.name)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onExport(format) }) { Text("导出并分享") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}