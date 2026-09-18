package org.cwcc.open.geokori.ui

import android.app.Activity
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri

/**
 * 带全屏功能的 VideoView 播放器（修复透明与播放逻辑）
 */
@Composable
fun VideoViewPlayerWithFullscreen(
    url: String,
    modifier: Modifier = Modifier,
    autoPlay: Boolean = true,
    title: String = "视频"
) {
  val context = LocalContext.current
  var isFullscreen by remember { mutableStateOf(false) }

  var normalIsPlaying by remember { mutableStateOf(autoPlay) }
  var fullscreenIsPlaying by remember { mutableStateOf(false) }
  var isPrepared by remember { mutableStateOf(false) }
  var currentPosition by remember { mutableStateOf(0) }

  // 普通播放器
  val normalPlayer = remember {
    android.widget.VideoView(context).apply {
      layoutParams = ViewGroup.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.MATCH_PARENT
      )
      setZOrderOnTop(true)
    }
  }

  // 全屏播放器（独立实例）
  val fullscreenPlayer = remember {
    android.widget.VideoView(context).apply {
      layoutParams = ViewGroup.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.MATCH_PARENT
      )
      setBackgroundColor(android.graphics.Color.BLACK)
      // 全屏时使用 OnTop 确保不被其他窗口/系统栏遮挡
      setZOrderOnTop(true)
    }
  }

  // 初始化普通播放器
  DisposableEffect(url) {
    normalPlayer.apply {
      setVideoURI(url.toUri())
      setOnPreparedListener {
        isPrepared = true
        if (autoPlay) {
          start()
          normalIsPlaying = true
        }
      }
      setOnCompletionListener {
        normalIsPlaying = false
      }
      setOnErrorListener { _, _, _ ->
        false
      }
    }

    onDispose {
      normalPlayer.apply {
        stopPlayback()
        pause()
        clearFocus()
      }
    }
  }

  // 全屏播放器：仅在进入全屏时初始化，退出全屏时释放
  DisposableEffect(isFullscreen, url) {
    if (isFullscreen) {
      fullscreenPlayer.apply {
        setVideoURI(url.toUri())
        setOnPreparedListener {
          // 同步进度并恢复播放状态
          if (currentPosition > 0) {
            seekTo(currentPosition)
          }
          if (fullscreenIsPlaying) {
            start()
          }
        }
        setOnCompletionListener {
          fullscreenIsPlaying = false
        }
        setOnErrorListener { _, _, _ ->
          false
        }
      }
    }

    onDispose {
      if (!isFullscreen) {
        fullscreenPlayer.apply {
          stopPlayback()
          pause()
          clearFocus()
        }
      }
    }
  }

  // 进入/退出全屏时同步进度与播放状态
  DisposableEffect(isFullscreen) {
    if (isFullscreen) {
      // 记录普通播放器状态
      currentPosition = normalPlayer.currentPosition
      val wasPlaying = normalIsPlaying
      fullscreenIsPlaying = wasPlaying

      // 暂停普通播放器
      normalPlayer.pause()
      normalIsPlaying = false

      // 全屏播放器在 onPrepared 中处理 seek/start
    } else {
      // 退出全屏：同步进度
      currentPosition = fullscreenPlayer.currentPosition
      val wasPlaying = fullscreenIsPlaying

      // 暂停全屏播放器
      fullscreenPlayer.pause()
      fullscreenIsPlaying = false

      // 恢复普通播放器
      if (normalPlayer.duration > 0) {
        normalPlayer.seekTo(currentPosition)
      }
      if (wasPlaying) {
        normalPlayer.start()
        normalIsPlaying = true
      }
    }
    onDispose { }
  }

  // ==================== 普通播放器 ====================
  Box(
      modifier = modifier
          //.background(Color.Black) // 最外层 Box 黑色兜底
  ) {
    // AndroidView 也加黑色背景，防止 VideoView 透明时穿透
    AndroidView(
        factory = { normalPlayer },
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        update = { view ->
          // 确保重组时背景不被重置为透明
          //view.setBackgroundColor(android.graphics.Color.BLACK)
        }
    )

    // 加载占位（黑色背景 + 转圈）
    if (!isPrepared) {
      Box(
          modifier = Modifier
              .fillMaxSize(),
              //.background(Color.Black),
          contentAlignment = Alignment.Center
      ) {
        CircularProgressIndicator(
            modifier = Modifier.size(48.dp),
            color = Color.White
        )
      }
    }

    // 播放/暂停控制层
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable {
              if (normalIsPlaying) {
                normalPlayer.pause()
                normalIsPlaying = false
              } else {
                normalPlayer.start()
                normalIsPlaying = true
              }
            },
        contentAlignment = Alignment.Center
    ) {
      if (!normalIsPlaying && isPrepared) {
        Icon(
            imageVector = Icons.Default.PlayArrow,
            contentDescription = "播放",
            modifier = Modifier.size(64.dp),
            tint = Color.White.copy(alpha = 0.8f)
        )
      }
    }

    // 全屏按钮
    if (isPrepared) {
      Box(
          modifier = Modifier
              .fillMaxSize()
              .padding(16.dp),
          contentAlignment = Alignment.BottomEnd
      ) {
        IconButton(
            onClick = {
              (context as? Activity)?.window?.addFlags(
                  WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
              )
              isFullscreen = true
            },
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.5f))
        ) {
          Icon(
              imageVector = Icons.Default.Fullscreen,
              contentDescription = "全屏",
              tint = Color.White
          )
        }
      }
    }
  }

  // ==================== 全屏 Dialog ====================
  if (isFullscreen) {
    Dialog(
        onDismissRequest = { isFullscreen = false },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
      Box(
          modifier = Modifier
              .fillMaxSize()
              .background(Color.Black)
      ) {
        AndroidView(
            factory = { fullscreenPlayer },
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            update = { view ->
              view.setBackgroundColor(android.graphics.Color.BLACK)
            }
        )

        // 加载占位
        if (!isPrepared) {
          Box(
              modifier = Modifier
                  .fillMaxSize()
                  .background(Color.Black),
              contentAlignment = Alignment.Center
          ) {
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp),
                color = Color.White
            )
          }
        }

        // 全屏播放控制
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable {
                  if (fullscreenIsPlaying) {
                    fullscreenPlayer.pause()
                    fullscreenIsPlaying = false
                  } else {
                    fullscreenPlayer.start()
                    fullscreenIsPlaying = true
                  }
                },
            contentAlignment = Alignment.Center
        ) {
          if (!fullscreenIsPlaying && isPrepared) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "播放",
                modifier = Modifier.size(72.dp),
                tint = Color.White.copy(alpha = 0.8f)
            )
          }
        }

        // 退出全屏按钮
        IconButton(
            onClick = { isFullscreen = false },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
                .background(Color.Black.copy(alpha = 0.5f))
        ) {
          Icon(
              imageVector = Icons.Default.Close,
              contentDescription = "退出全屏",
              tint = Color.White
          )
        }

        Text(
            text = "点击屏幕切换播放/暂停  |  点击左上角退出全屏",
            color = Color.White.copy(alpha = 0.3f),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        )
      }
    }
  }

  // 退出全屏时清除屏幕常亮
  DisposableEffect(isFullscreen) {
    onDispose {
      if (!isFullscreen) {
        (context as? Activity)?.window?.clearFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
      }
    }
  }
}

/**
 * 基础 VideoView 播放器
 */
@Composable
fun VideoViewPlayer(
    url: String,
    modifier: Modifier = Modifier,
    autoPlay: Boolean = true,
    showFullscreenButton: Boolean = true,
    showCloseButton: Boolean = false,
    onFullscreenClick: (() -> Unit)? = null,
    onCloseClick: (() -> Unit)? = null
) {
  val context = LocalContext.current
  var videoView by remember { mutableStateOf<android.widget.VideoView?>(null) }
  var isPlaying by remember { mutableStateOf(autoPlay) }
  var isPrepared by remember { mutableStateOf(false) }

  DisposableEffect(url) {
    onDispose {
      videoView?.apply {
        stopPlayback()
        pause()
        clearFocus()
      }
    }
  }

  Box(
      modifier = modifier
          .background(Color.Black),
  ) {
    AndroidView(
        factory = { ctx ->
          android.widget.VideoView(ctx).apply {
            videoView = this
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(android.graphics.Color.BLACK)
            // 普通模式不使用 z-order 设置

            setVideoURI(url.toUri())

            setOnPreparedListener { mediaPlayer ->
              isPrepared = true
              if (autoPlay) {
                start()
                isPlaying = true
              }
            }

            setOnCompletionListener {
              isPlaying = false
            }

            setOnErrorListener { _, what, extra ->
              false
            }
          }
        },
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        update = { view ->
          view.setBackgroundColor(android.graphics.Color.BLACK)
        }
    )

    // 加载占位
    if (!isPrepared) {
      Box(
          modifier = Modifier
              .fillMaxSize()
              .background(Color.Black),
          contentAlignment = Alignment.Center
      ) {
        CircularProgressIndicator(
            modifier = Modifier.size(48.dp),
            color = Color.White
        )
      }
    }

    // 播放/暂停控制
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable {
              videoView?.let {
                if (isPlaying) {
                  it.pause()
                  isPlaying = false
                } else {
                  it.start()
                  isPlaying = true
                }
              }
            },
        contentAlignment = Alignment.Center,
    ) {
      if (!isPlaying && isPrepared) {
        Icon(
            imageVector = Icons.Default.PlayArrow,
            contentDescription = "播放",
            modifier = Modifier.size(64.dp),
            tint = Color.White.copy(alpha = 0.8f),
        )
      }
    }

    // 顶部关闭按钮
    if (showCloseButton) {
      Row(
          modifier = Modifier
              .fillMaxSize()
              .padding(16.dp),
          horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
      ) {
        IconButton(
            onClick = { onCloseClick?.invoke() },
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.5f)),
        ) {
          Icon(
              imageVector = Icons.Default.Close,
              contentDescription = "关闭",
              tint = Color.White,
          )
        }
      }
    }

    // 底部全屏按钮
    if (showFullscreenButton) {
      Box(
          modifier = Modifier
              .fillMaxSize()
              .padding(16.dp),
          contentAlignment = Alignment.BottomEnd,
      ) {
        IconButton(
            onClick = { onFullscreenClick?.invoke() },
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.5f)),
        ) {
          Icon(
              imageVector = Icons.Default.Fullscreen,
              contentDescription = "全屏",
              tint = Color.White,
          )
        }
      }
    }
  }
}

/**
 * 全屏视频播放器（独立使用）
 */
@Composable
fun FullscreenVideoPlayer(
    url: String,
    title: String = "视频",
    onDismiss: () -> Unit
) {
  val context = LocalContext.current

  DisposableEffect(Unit) {
    (context as? Activity)?.window?.addFlags(
        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
    )
    onDispose {
      (context as? Activity)?.window?.clearFlags(
          WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
      )
    }
  }

  Dialog(
      onDismissRequest = onDismiss,
      properties = DialogProperties(
          usePlatformDefaultWidth = false,
          decorFitsSystemWindows = false,
      ),
  ) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
      VideoViewPlayer(
          url = url,
          modifier = Modifier.fillMaxSize(),
          autoPlay = true,
          showFullscreenButton = false,
          showCloseButton = true,
          onCloseClick = onDismiss,
      )
    }
  }
}
