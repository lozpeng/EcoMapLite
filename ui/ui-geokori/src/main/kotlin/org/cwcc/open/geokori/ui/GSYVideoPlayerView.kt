package org.cwcc.open.geokori.ui

import android.app.Activity
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import com.shuyu.gsyvideoplayer.GSYVideoManager
import com.shuyu.gsyvideoplayer.builder.GSYVideoOptionBuilder
import com.shuyu.gsyvideoplayer.listener.GSYSampleCallBack
import com.shuyu.gsyvideoplayer.utils.GSYVideoType
import com.shuyu.gsyvideoplayer.utils.OrientationUtils
import com.shuyu.gsyvideoplayer.video.StandardGSYVideoPlayer

/**
 * GSYVideoPlayer 完整封装（针对 MP4 字节流优化）
 * 基于 v13.2.1 版本 API
 */
@Composable
fun GSYVideoPlayerFull(
    url: String,
    modifier: Modifier = Modifier,
    autoPlay: Boolean = true,
    title: String = "视频",
    videoScaleType: Int = GSYVideoType.SCREEN_TYPE_16_9,
    showLoadingOverlay: Boolean = true,
    onPrepared: (() -> Unit)? = null,
    onError: (() -> Unit)? = null,
    onComplete: (() -> Unit)? = null,
    onStartPrepared: (() -> Unit)? = null,
    onClickStartIcon: (() -> Unit)? = null,
    onClickStop: (() -> Unit)? = null,
    onClickResume: (() -> Unit)? = null,
    onEnterFullscreen: (() -> Unit)? = null,
    onQuitFullscreen: (() -> Unit)? = null
) {
  val context = LocalContext.current
  var orientationUtils by remember { mutableStateOf<OrientationUtils?>(null) }
  var videoPlayer by remember { mutableStateOf<StandardGSYVideoPlayer?>(null) }

  var isPrepared by remember { mutableStateOf(false) }
  var isPlaying by remember { mutableStateOf(false) }
  var hasError by remember { mutableStateOf(false) }

  DisposableEffect(Unit) {
    onDispose {
      orientationUtils?.releaseListener()
      videoPlayer?.let {
        try {
          it.onVideoPause()
        } catch (e: Exception) {
          // 忽略
        }
      }
      GSYVideoManager.releaseAllVideos()
    }
  }

  Box(modifier = modifier) {
    AndroidView(
        factory = { ctx ->
          StandardGSYVideoPlayer(ctx).apply {
            videoPlayer = this

            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )

            // 设置视频缩放模式
            GSYVideoType.setShowType(videoScaleType)

            val optionBuilder = GSYVideoOptionBuilder()
                .setUrl(url)
                .setVideoTitle(title)
                .setCacheWithPlay(false)
                .setRotateWithSystem(true)
                .setRotateViewAuto(true)
                .setAutoFullWithSize(true)
                .setVideoAllCallBack(
                    object : GSYSampleCallBack() {
                      override fun onStartPrepared(url: String?, vararg objects: Any?) {
                        super.onStartPrepared(url, *objects)
                        isPrepared = false
                        hasError = false
                        onStartPrepared?.invoke()
                      }

                      override fun onPrepared(url: String?, vararg objects: Any?) {
                        super.onPrepared(url, *objects)
                        isPrepared = true
                        hasError = false
                        onPrepared?.invoke()

                        if (autoPlay) {
                          startPlayLogic()
                        }
                      }

                      override fun onClickStartIcon(url: String?, vararg objects: Any?) {
                        super.onClickStartIcon(url, *objects)
                        isPlaying = true
                        isPrepared = true
                        onClickStartIcon?.invoke()
                      }

                      override fun onClickStop(url: String?, vararg objects: Any?) {
                        super.onClickStop(url, *objects)
                        isPlaying = false
                        onClickStop?.invoke()
                      }

                      override fun onClickResume(url: String?, vararg objects: Any?) {
                        super.onClickResume(url, *objects)
                        isPlaying = true
                        isPrepared = true
                        onClickResume?.invoke()
                      }

                      override fun onAutoComplete(url: String?, vararg objects: Any?) {
                        super.onAutoComplete(url, *objects)
                        isPlaying = false
                        isPrepared = false
                        onComplete?.invoke()
                      }

                      override fun onComplete(url: String?, vararg objects: Any?) {
                        super.onComplete(url, *objects)
                        isPlaying = false
                        isPrepared = false
                        onComplete?.invoke()
                      }

                      override fun onPlayError(url: String?, vararg objects: Any?) {
                        super.onPlayError(url, *objects)
                        isPrepared = false
                        hasError = true
                        isPlaying = false
                        onError?.invoke()
                      }

                      override fun onEnterFullscreen(url: String?, vararg objects: Any?) {
                        super.onEnterFullscreen(url, *objects)
                        onEnterFullscreen?.invoke()
                      }

                      override fun onQuitFullscreen(url: String?, vararg objects: Any?) {
                        super.onQuitFullscreen(url, *objects)
                        onQuitFullscreen?.invoke()
                      }
                    },
                )

            optionBuilder.build(this)

            setSpeed(1.0f)
            GSYVideoManager.instance().setTimeOut(30000, true)

            val activity = context as? Activity
            if (activity != null) {
              orientationUtils = OrientationUtils(activity, this)
            }

            if (autoPlay) {
              postDelayed(
                  {
                    if (!isPrepared && !hasError) {
                      startPlayLogic()
                    }
                  },
                  300,
              )
            }
          }
        },
        modifier = Modifier.fillMaxSize(),
        onRelease = {
          orientationUtils?.releaseListener()
          try {
            videoPlayer?.onVideoPause()
          } catch (e: Exception) {
            // 忽略
          }
          GSYVideoManager.releaseAllVideos()
        },
    )

    // 加载状态覆盖层
    if (showLoadingOverlay) {
      when {
        !isPrepared && !hasError -> {
          Box(
              modifier = Modifier
                  .fillMaxSize()
                  .background(Color.Black.copy(alpha = 0.3f)),
              contentAlignment = Alignment.Center,
          ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
              CircularProgressIndicator(
                  modifier = Modifier.size(48.dp),
                  color = Color.White,
                  strokeWidth = 3.dp,
              )
              Text(
                  text = "加载中...",
                  color = Color.White,
                  style = MaterialTheme.typography.bodySmall,
                  modifier = Modifier.padding(top = 8.dp),
              )
            }
          }
        }

        hasError -> {
          Box(
              modifier = Modifier
                  .fillMaxSize()
                  .background(Color.Black.copy(alpha = 0.5f))
                  .clickable {
                    hasError = false
                    isPrepared = false
                    videoPlayer?.startPlayLogic()
                  },
              contentAlignment = Alignment.Center,
          ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
              Icon(
                  imageVector = Icons.Default.Refresh,
                  contentDescription = "重试",
                  modifier = Modifier.size(48.dp),
                  tint = Color.White,
              )
              Text(
                  text = "播放失败，点击重试",
                  color = Color.White,
                  style = MaterialTheme.typography.bodyMedium,
                  modifier = Modifier.padding(top = 8.dp),
              )
            }
          }
        }

        isPrepared && !autoPlay && !isPlaying -> {
          Box(
              modifier = Modifier
                  .fillMaxSize()
                  .background(Color.Black.copy(alpha = 0.1f))
                  .clickable {
                    videoPlayer?.startPlayLogic()
                  },
              contentAlignment = Alignment.Center,
          ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
              Icon(
                  imageVector = Icons.Default.PlayArrow,
                  contentDescription = "播放",
                  modifier = Modifier.size(64.dp),
                  tint = Color.White.copy(alpha = 0.8f),
              )
              Text(
                  text = "点击播放",
                  color = Color.White.copy(alpha = 0.8f),
                  style = MaterialTheme.typography.bodySmall,
                  modifier = Modifier.padding(top = 4.dp),
              )
            }
          }
        }
      }
    }
  }
}
