package org.kori.plugin.geo.track.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.font.FontFamily
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
 * 轨迹时间线界面 v2 —— 以时间线为轴展示会话全过程。
 *
 * 内部管线分四步（与文档卡片一一对应）：
 *
 *  1. **MERGE / DEDUPE** —— IO 线程合并去重原始轨迹点（[loadMergedPoints]）
 *  2. **EVENTS / PAUSE** —— PAUSE / RESUME 事件对精确计算停留（[TimelineBuilder.pausesFromEvents]）
 *  3. **BUCKET / DISTANCE** —— 5 分钟桶聚合点 + 媒体混排，
 *     线段高度按累计里程成比例（[TimelineBuilder.build]）
 *  4. **RENDER / VERIFY** —— 倒序渲染，线段终于起点；导出分享同一链路
 *
 * ## 条目类型
 *
 *  · **起点 / 终点**：绿色 / 红色旗标，含完整时间
 *  · **暂停**：由录制的 PAUSE/RESUME 事件对精确计算，琥珀色节点，显示停留时长
 *  · **聚合轨迹点**：无媒体的点按 [BUCKET_MINUTES] 聚合，显示 点数 + 起止时间 + 耗时
 *  · **媒体**：照片缩略图（点击放大）、音视频（点击播放），逐个列出
 *
 * 全部按时间倒序。顶部含 **分享/导出** 按钮（与历史界面同一导出链路）。
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
    // 第二个返回值：每条目下方线段的高度（dp，按与下一条目之间的"轨迹距离"换算）
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
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(timeline.items, key = { _, item -> item.key }) { index, item ->
                    // 行高 = 与下一条目之间的轨迹距离（比例轴）；媒体行保证缩略图/播放控件空间
                    val next = timeline.items.getOrNull(index + 1)
                    val rowHeight: Dp? = if (next == null) {
                        null // 末行（起点）以下没有线 —— 线段终于起点
                    } else {
                        val minH = if (item is TlItem.Media) 96.dp else 44.dp
                        timeline.lineHeightsDp[index].dp.coerceAtLeast(minH)
                    }
                    TimelineRow(
                        item = item,
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

    /** 聚合的轨迹点段。 */
    data class Points(val fromMs: Long, val toMs: Long, val count: Int) : TlItem() {
        override val timeMs get() = toMs
        override val key get() = "pts-$fromMs"
        val durationMs: Long get() = (toMs - fromMs).coerceAtLeast(0L)
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
        val items = ArrayList<TlItem>()

        if (points.isNotEmpty()) {
            items.add(TlItem.Start(points.first().timestampMs))
            items.add(TlItem.End(points.last().timestampMs))
        }

        items.addAll(pausesFromEvents(events, points.lastOrNull()?.timestampMs))
        items.addAll(bucketPoints(points))
        media.forEach { items.add(TlItem.Media(it)) }

        val sorted = items.sortedByDescending { it.timeMs }

        // ---- 相邻条目间的轨迹距离 → 线段高度 ----
        val cumAt = cumulativeDistance(points)
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

    /** 无媒体的点按 [BUCKET_MINUTES] 桶聚合成 [TlItem.Points]。 */
    fun bucketPoints(points: List<TrackPoint>): List<TlItem.Points> {
        if (points.isEmpty()) return emptyList()
        val bucketMs = BUCKET_MINUTES * 60_000L
        val buckets = LinkedHashMap<Long, MutableList<TrackPoint>>()
        for (p in points) {
            buckets.getOrPut(p.timestampMs / bucketMs) { mutableListOf() }.add(p)
        }
        return buckets.values.filter { it.isNotEmpty() }
            .map { pts -> TlItem.Points(pts.first().timestampMs, pts.last().timestampMs, pts.size) }
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
// 4. UI 行
// =============================================================================================

@Composable
private fun TimelineRow(
    item: TlItem,
    isLast: Boolean,
    rowHeight: Dp?,
    photoFile: File?,
    onMediaClick: (TrackMediaRecord) -> Unit,
) {
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val dateTimeFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 行高即线段长度（轨迹距离比例）；末行随内容
            .then(if (rowHeight != null) Modifier.height(rowHeight) else Modifier),
    ) {
        // ---- 左侧：时间 + 节点轴 ----
        // 轴线用 weight(1f) 填满本行剩余高度：不同高度（媒体/标记）的行无缝衔接，
        // 形成一条连续线段；isLast 行不画线 —— 线段终于起点，不是射线
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(58.dp)) {
            Text(
                timeFmt.format(Date(item.timeMs)),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Box(modifier = Modifier.size(12.dp).background(nodeColor(item), CircleShape))
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .weight(1f)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
        }

        // ---- 右侧：内容 ----
        when (item) {
            is TlItem.Start -> MarkerRow(
                icon = Icons.Filled.Flag,
                color = Color(0xFF00E676),
                title = "起点",
                detail = dateTimeFmt.format(Date(item.atMs)),
            )

            is TlItem.End -> MarkerRow(
                icon = Icons.Filled.Flag,
                color = Color(0xFFFF1744),
                title = "终点",
                detail = dateTimeFmt.format(Date(item.atMs)),
            )

            is TlItem.Pause -> MarkerRow(
                icon = null,
                color = Color(0xFFFFD600),
                title = "⏸ 暂停",
                detail = "停留 ${formatSpan(item.pausedMs)}",
            )

            is TlItem.Points -> Row(
                modifier = Modifier.padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("轨迹点 ×${item.count}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "  ${timeFmt.format(Date(item.fromMs))}–${timeFmt.format(Date(item.toMs))}" +
                            " · 耗时 ${formatSpan(item.durationMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            is TlItem.Media -> MediaCard(item.record, photoFile, onMediaClick)
        }
    }
}

/** 起终点 / 暂停标记行。 */
@Composable
private fun MarkerRow(
    icon: ImageVector?,
    color: Color,
    title: String,
    detail: String,
) {
    Row(
        modifier = Modifier.padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = title, tint = color, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = color,
        )
        Text(
            "  $detail",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun nodeColor(item: TlItem): Color = when (item) {
    is TlItem.Start -> Color(0xFF00E676)
    is TlItem.End -> Color(0xFFFF1744)
    is TlItem.Pause -> Color(0xFFFFD600)
    is TlItem.Points -> Color(0xFF00E5FF)
    is TlItem.Media -> Color(0xFF9334E6)
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

/** 媒体卡片：照片缩略图 / 音视频图标 + 时长。 */
@Composable
private fun MediaCard(
    record: TrackMediaRecord,
    photoFile: File?,
    onClick: (TrackMediaRecord) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onClick(record) }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (record.type) {
            TrackMediaRecord.Type.PHOTO -> {
                Box(
                    modifier = Modifier
                        .size(64.dp)
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

            TrackMediaRecord.Type.VIDEO -> MediaBadge(Icons.Filled.PlayArrow, "视频", Color(0xFF9334E6))
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
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            record.durationSec?.let {
                Text(
                    "%.1f 秒".format(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Text(
            if (record.type == TrackMediaRecord.Type.PHOTO) "查看" else "播放",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun MediaBadge(icon: ImageVector, label: String, color: Color) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.2f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(28.dp))
    }
}