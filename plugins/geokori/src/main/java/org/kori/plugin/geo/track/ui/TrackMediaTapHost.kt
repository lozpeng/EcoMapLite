package org.kori.plugin.geo.track.ui

import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File

/**
 * 地图媒体标记点击宿主。
 *
 * 处理地图 SymbolLayer 媒体标记的点击（配合 `MapLibreMapView` 的 `onMediaClick`）：
 *
 *  · **PHOTO** → 全屏查看照片（[FeatureAttrSheet.FullscreenViewer]：双指缩放/双击放大）
 *  · **VIDEO** → 全屏播放视频（[FeatureAttrSheet.FullscreenViewer]：进度条/静音/横竖屏旋转）
 *  · **AUDIO** → 就地播放（底部小条，不全屏）
 *
 * 用法（放在调用 MapLibreMapView 的 Box 内容内任意位置）：
 *
 * ```kotlin
 * val onMediaTap = TrackMediaTapHost()
 * MapLibreMapView(
 *     ...,
 *     onMediaClick = onMediaTap,
 * )
 * ```
 *
 * @return 点击处理器：`(type, filePath) -> Unit`，直接传给 [org.kori.plugin.geo.map.MapLibreMapView]
 */
@Composable
fun TrackMediaTapHost(): (String, String) -> Unit {
    var photo by remember { mutableStateOf<File?>(null) }
    var video by remember { mutableStateOf<File?>(null) }
    var audio by remember { mutableStateOf<File?>(null) }

    // ---- 照片：全屏查看（缩放/翻页/旋转，复用 FeatureAttrSheet） ----
    photo?.let { file ->
        org.cwcc.open.geokori.map.FeatureAttrSheet.FullscreenViewer(
            attachments = listOf(
                org.cwcc.open.geokori.map.FeatureAttrSheet.Attachment(
                    url = file.absolutePath,
                    isVideo = false,
                ),
            ),
            initialPage = 0,
            onClose = { photo = null },
        )
    }

    // ---- 视频：全屏播放（进度条/静音/旋转，复用 FeatureAttrSheet） ----
    video?.let { file ->
        org.cwcc.open.geokori.map.FeatureAttrSheet.FullscreenViewer(
            attachments = listOf(
                org.cwcc.open.geokori.map.FeatureAttrSheet.Attachment(
                    url = file.absolutePath,
                    isVideo = true,
                ),
            ),
            initialPage = 0,
            onClose = { video = null },
        )
    }

    // ---- 录音：就地播放（底部小条，不全屏） ----
    audio?.let { file ->
        Dialog(
            onDismissRequest = { audio = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { audio = null }, // 点遮罩关闭
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(
                    modifier = Modifier
                        .padding(24.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable { /* 拦截：点小条本身不关闭 */ }
                        .padding(horizontal = 8.dp),
                ) {
                    AudioPlayRow(
                        file = file,
                        onClose = { audio = null },
                    )
                }
            }
        }
    }

    return remember {
        { type, path ->
            val f = File(path)
            if (f.exists()) {
                when (type.uppercase()) {
                    "PHOTO" -> photo = f
                    "VIDEO" -> video = f
                    "AUDIO" -> audio = f
                }
            }
        }
    }
}

/** 录音就地播放条：播放/暂停 + 状态文案 + 关闭。关闭即 release。 */
@Composable
private fun AudioPlayRow(file: File, onClose: () -> Unit) {
    val player = remember(file) { MediaPlayer() }
    var prepared by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    DisposableEffect(file) {
        val mp = player
        try {
            mp.setDataSource(file.absolutePath)
            mp.setOnPreparedListener { prepared = true }
            mp.setOnCompletionListener { playing = false }
            mp.setOnErrorListener { _, _, _ -> failed = true; true }
            mp.prepareAsync()
        } catch (e: Exception) {
            failed = true
        }
        onDispose { runCatching { mp.release() } }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = {
                if (playing) {
                    runCatching { player.pause() }
                    playing = false
                } else if (prepared) {
                    runCatching { player.start() }
                    playing = true
                }
            },
            enabled = prepared && !failed,
        ) {
            Icon(
                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (playing) "暂停" else "播放",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = when {
                failed -> "无法播放"
                !prepared -> "加载中..."
                else -> "录音播放中"
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.weight(1f))
        IconButton(onClick = onClose) {
            Icon(Icons.Filled.Close, contentDescription = "关闭")
        }
    }
}