package org.kori.plugin.geo.track

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 历史轨迹浏览界面（全屏覆盖层）。
 *
 * ## 功能
 *
 *  · **会话列表**：名称、时间、时长 / 距离 / 点数
 *  · **显示**：把单条（或全部）轨迹加载到地图历史叠加层（灰色线），并关闭本界面
 *  · **导出**：选格式（GPX/KML/GeoJSON/CSV）→ 导出并调起系统分享（可发微信聊天）
 *  · **删除**：删除会话（含媒体文件，二次确认）
 *
 * ## 使用
 *
 * ```kotlin
 * var show by remember { mutableStateOf(false) }
 * if (show) TrackHistoryScreen(onClose = { show = false })
 * ```
 */
@Composable
fun TrackHistoryScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** ★ 回放回调：由调用方启动沉浸式回放屏（见 [TrackPlaybackScreen]）。 */
    onPlay: (TrackSession) -> Unit = {},
    /** ★ 时间线回调：由调用方打开时间线界面（见 [TrackTimelineScreen]）。 */
    onTimeline: (TrackSession) -> Unit = {},
) {
    val context = LocalContext.current
    val state by TrackRecordingEngine.state.collectAsState()

    // 进入时刷新会话列表
    LaunchedEffect(Unit) {
        TrackRecordingEngine.refreshSessions(context)
    }

    // 导出格式选择对话框的目标会话
    var exportTarget by remember { mutableStateOf<TrackSession?>(null) }
    // 删除确认对话框的目标会话
    var deleteTarget by remember { mutableStateOf<TrackSession?>(null) }

    // ★ 系统返回键关闭（导出/删除对话框打开时由对话框自身处理）
    BackHandler { onClose() }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            // ---- 标题栏 ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "历史轨迹",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭")
                }
            }

            // ---- 顶部操作：显示全部 / 清除叠加 ----
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = {
                        if (state.sessions.isEmpty()) {
                            Toast.makeText(context, "暂无历史轨迹", Toast.LENGTH_SHORT).show()
                        } else {
                            TrackRecordingEngine.loadHistoryOnMap(state.sessions)
                            Toast.makeText(context, "已加载全部轨迹到地图", Toast.LENGTH_SHORT).show()
                            onClose()
                        }
                    },
                ) { Text("全部显示到地图") }
                TextButton(
                    onClick = { TrackRecordingEngine.clearHistoryOnMap() },
                ) { Text("清除地图叠加") }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (state.loadingSessions) {
                Text("加载中…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (state.sessions.isEmpty()) {
                Text(
                    "暂无历史轨迹\n记录并结束后会出现在这里",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.sessions, key = { it.id }) { session ->
                        SessionRow(
                            session = session,
                            onShow = {
                                TrackRecordingEngine.loadHistoryOnMap(listOf(session))
                                Toast.makeText(context, "已加载到地图", Toast.LENGTH_SHORT).show()
                                onClose()
                            },
                            onPlay = {
                                onPlay(session)
                                onClose()
                            },
                            onTimeline = {
                                onTimeline(session)
                                onClose()
                            },
                            onExport = { exportTarget = session },
                            onDelete = { deleteTarget = session },
                        )
                    }
                }
            }
        }
    }

    // ---- 导出格式对话框 ----
    exportTarget?.let { session ->
        // ★ 按会话记忆格式选择，避免在会话之间泄漏上一个选择
        var format by remember(session.id) { mutableStateOf(TrackExporter.Format.GPX) }
        AlertDialog(
            onDismissRequest = { exportTarget = null },
            title = { Text("导出轨迹") },
            text = {
                Column {
                    Text(
                        session.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    @Suppress("DEPRECATION")
                    TrackExporter.Format.values().forEach { f ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            RadioButton(
                                selected = format == f,
                                onClick = { format = f },
                            )
                            Text(f.name)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    exportTarget = null
                    TrackShare.exportAndShare(context, session, format)
                }) { Text("导出并分享") }
            },
            dismissButton = {
                TextButton(onClick = { exportTarget = null }) { Text("取消") }
            },
        )
    }

    // ---- 删除确认 ----
    deleteTarget?.let { session ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除轨迹") },
            text = { Text("确定删除“${session.name}”吗？\n轨迹文件和媒体附件将一并删除，不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    TrackRecordingEngine.deleteSession(session)
                    Toast.makeText(context, "已删除", Toast.LENGTH_SHORT).show()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }
}

/** 单个会话卡片。 */
@Composable
private fun SessionRow(
    session: TrackSession,
    onShow: () -> Unit,
    onPlay: () -> Unit,
    onTimeline: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        session.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                    Text(
                        formatTime(session.startedMs) +
                                " · ${session.durationText} · ${session.distanceText}" +
                                " · ${session.totalRawPoints} 点",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onShow) { Text("显示") }
                TextButton(onClick = onPlay) { Text("回放") }
                TextButton(onClick = onTimeline) { Text("时间线") }
                TextButton(onClick = onExport) { Text("导出") }
                TextButton(onClick = onDelete) { Text("删除", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

private fun formatTime(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))