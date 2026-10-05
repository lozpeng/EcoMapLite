package org.kori.plugin.geo.track.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import org.kori.plugin.geo.math.GeoMath
import kotlinx.coroutines.Dispatchers
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

/**
 * 轨迹时间线界面 v4 —— 左轴分段 + 右侧信息卡。
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
 *  · **媒体**：照片缩略图（点击放大）、音视频（点击播放）
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
                        val minH = if (item is TlItem.Media) 108.dp else 104.dp
                        timeline.lineHeightsDp[index].dp.coerceAtLeast(minH)
                    }
                    TimelineRow(
                        item = item,
                        // 分段号按时间正序编号：倒序列表中越靠后（越早）号越小，起点为 #1
                        segNo = timeline.items.size - index,
                        isLast = index == timeline.items.lastIndex,
                        rowHeight = rowHeight,
                        photoFile = if (item is TlItem.Media && item.record.type == TrackMediaRecord.Type.PHOTO)
                            session.resolve(item.record.filePath) else null,
                        onMediaClick = { record -> openMedia(context, session, record) { viewerPhoto = it } },
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
    photoFile: File?,
    onMediaClick: (TrackMediaRecord) -> Unit,
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
            photoFile = photoFile,
            onMediaClick = onMediaClick,
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
/**
 * 节点 + 分段号角标：圆点居中，白底深色数字的小角标压在其左上角（类似通知角标）。
 * 角标用白底 + [darkened] 主色数字 —— 速度快时的青绿、慢时的紫，压深后都清晰可辨。
 */
@Composable
private fun NodeWithBadge2(no: Int, color: Color) {
    Box(modifier = Modifier.size(32.dp)) {
        // 节点圆点
        Box(
            modifier = Modifier
                .size(22.dp)
                .align(Alignment.Center)
                .background(color, CircleShape),
        )
        // 分段号角标：白底圆角小方块 + 加深主色数字
        Box(
            modifier = Modifier
                .size(17.dp)
                .align(Alignment.TopStart)
                .offset(x = (-1).dp, y = (-1).dp)
                .clip(RoundedCornerShape(5.dp))
                .background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "$no",
                color = darkened(color),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** 右侧信息卡：浅色圆角底（条目主色 10%），标题行 = 时间 + 类型标签。 */
@Composable
private fun RowScope.InfoCard(
    item: TlItem,
    color: Color,
    titleTime: String,
    photoFile: File?,
    onMediaClick: (TrackMediaRecord) -> Unit,
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

            is TlItem.Media -> MediaContent(item.record, photoFile, onMediaClick)
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

/** 媒体卡体：照片缩略图 / 音视频图标 + 时长（信息卡内使用，不带外框）。 */
@Composable
private fun MediaContent(
    record: TrackMediaRecord,
    photoFile: File?,
    onClick: (TrackMediaRecord) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .clickable { onClick(record) }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (record.type) {
            TrackMediaRecord.Type.PHOTO -> {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black),
                ) {
                    if (photoFile != null) {
                        AsyncImage(
                            model = photoFile,
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
            }

            TrackMediaRecord.Type.VIDEO -> MediaBadge(Icons.Filled.PlayArrow, "视频", C_MEDIA)
            TrackMediaRecord.Type.AUDIO -> MediaBadge(Icons.Filled.Mic, "录音", Color(0xFF00897B))
        }

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = when (record.type) {
                    TrackMediaRecord.Type.PHOTO -> "照片"
                    TrackMediaRecord.Type.VIDEO -> "视频"
                    TrackMediaRecord.Type.AUDIO -> "录音"
                },
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            record.durationSec?.let {
                Text(
                    "%.1f 秒".format(it),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Text(
            if (record.type == TrackMediaRecord.Type.PHOTO) "查看" else "播放",
            color = MaterialTheme.colorScheme.primary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
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

/** 媒体点击分流：照片进内置查看器，音视频交给系统播放器。 */
private fun openMedia(
    context: android.content.Context,
    session: TrackSession,
    record: TrackMediaRecord,
    onViewPhoto: (TrackMediaRecord) -> Unit,
) {
    when (record.type) {
        TrackMediaRecord.Type.PHOTO -> onViewPhoto(record)
        else -> {
            val file = session.resolve(record.filePath)
            val mime = when (record.type) {
                TrackMediaRecord.Type.VIDEO -> "video/mp4"
                else -> "audio/mp4"
            }
            TrackShare.openMedia(context, file, mime)
        }
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
                @Suppress("DEPRECATION")
                TrackExporter.Format.values().forEach { f ->
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