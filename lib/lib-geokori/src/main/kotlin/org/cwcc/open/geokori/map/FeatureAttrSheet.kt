package org.cwcc.open.geokori.map

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.drawable.ColorDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.cwcc.open.geokori.ui.material3.bottomsheet.FlexibleBottomSheet
import org.cwcc.open.geokori.ui.material3.bottomsheet.core.rememberFlexibleBottomSheetState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.time.Duration.Companion.milliseconds

/**
 * 通用要素属性面板：表格式属性 + 多媒体轮播 + 全屏浏览 + 导航分享。
 *
 * 设计：
 *  · 本对象是「无状态 UI 库」，只提供 [Content] 渲染入口，不持有全局状态；
 *  · 每个具体面板（IllegalEventAttrSheet / ElephantAttrSheet / GpkgAttrSheet）
 *    自己持有 MutableStateFlow<Feature?>，通过 [Config] 描述「字段/附件/名称/位置」
 *    如何从 Feature 派生，然后调用 [Content] 渲染。
 *
 * 布局：窄屏 = FlexibleBottomSheet；宽屏（>= 600dp）= 居中双栏对话框。
 * 附件：多附件 3s 自动轮播 + 圆点指示器 + 页码角标；点击进全屏（图片可缩放，
 * 视频可播放 + 拖动进度条）；全屏播放器用 TextureView + MediaPlayer，绝不穿透。
 *
 * 关闭入口：
 *  · 右上角 [FilledTonalIconButton]（带 ripple + 容器色反馈）；
 *  · 窄屏额外：点击遮罩 / 下滑 sheet；
 *  · 返回键 / 系统边缘返回手势被 [BackHandler] 空实现消费，避免滑图时误关。
 *
 * 视频渲染：
 *  · TextureView + MediaPlayer（非 VideoView，避免 SurfaceView 打洞穿透）；
 *  · FitTextureView 按视频宽高比 letterbox 适配，避免拉伸；
 *  · 未播放时自动 prepareAsync 并停在首帧；
 *  · 播放按钮外包环形进度条；
 *  · 默认静音；预览态静音按钮在右下角、全屏态在底部控制条右侧；
 *  · 全屏 Dialog 窗口强制不透明黑底。
 *
 * 图片渲染：
 *  · 关闭 Coil crossfade（避免切换时新旧图重叠，造成"拼接感"）；
 *  · 自动轮播用显式 tween(200ms)，缩短动画中间态的停留时间。
 *
 * 数据源共享：预览与全屏共用 [VideoCache] 本地缓存文件。
 *
 * 全屏方向：右上角旋转按钮；进入时记录 Activity 的 requestedOrientation，退出时恢复；
 * 宿主 Activity 必须声明 configChanges="orientation|screenSize|screenLayout|keyboardHidden|smallestScreenSize"。
 */
object FeatureAttrSheet {

    private const val WIDE_SCREEN_WIDTH_DP = 600
    private val WIDE_DIALOG_WIDTH = 720.dp

    // =========================================================================================
    // 公开 API
    // =========================================================================================

    /** 多媒体附件条目。 */
    data class Attachment(
        val url: String,
        val isVideo: Boolean = false,
    )

    /** 面板展示配置。 */
    class Config(
        val fields: (Feature) -> List<Pair<String, String>>,
        val attachments: (Feature) -> List<Attachment> = { emptyList() },
        val displayName: (Feature) -> String = { f ->
            runCatching { f.getStringProperty("name") }
                .getOrNull()?.takeIf { it.isNotBlank() } ?: "目标点"
        },
        val position: (Feature) -> Pair<Double, Double>? = { f ->
            (f.geometry() as? Point)?.let { it.latitude() to it.longitude() }
        },
        val showActions: Boolean = true,
    )

    /** 无状态渲染入口。 */
    @Composable
    fun Content(
        feature: Feature,
        config: Config,
        onDismiss: () -> Unit,
    ) {
        val isWide = LocalConfiguration.current.screenWidthDp >= WIDE_SCREEN_WIDTH_DP
        val fields = config.fields(feature)
        val attachments = config.attachments(feature)

        var viewerPage by remember { mutableStateOf<Int?>(null) }
        val carouselAutoScroll = viewerPage == null

        if (isWide) {
            // ====================== 宽屏：居中双栏对话框 ======================
            Dialog(
                onDismissRequest = onDismiss,
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0x66000000))
                        .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) },
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        modifier = Modifier
                            .width(WIDE_DIALOG_WIDTH)
                            .padding(24.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        Box {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                            ) {
                                if (attachments.isNotEmpty()) {
                                    Box(Modifier.weight(1.2f)) {
                                        AttachmentCarousel(
                                            attachments = attachments,
                                            onOpenViewer = { viewerPage = it },
                                            autoScroll = carouselAutoScroll,
                                        )
                                    }
                                    Spacer(Modifier.width(16.dp))
                                }
                                Column(
                                    Modifier
                                        .weight(1f)
                                        .verticalScroll(rememberScrollState()),
                                ) {
                                    AttrFieldList(fields)
                                    Spacer(Modifier.height(8.dp))
                                    SheetActionBar(feature, config)
                                }
                            }
                            FilledTonalIconButton(
                                onClick = onDismiss,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(8.dp)
                                    .size(40.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "关闭",
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // ====================== 窄屏：底部弹窗 ======================
            // ★ 抢占 FlexibleBottomSheet 内部注册的 BackHandler：Compose Effect 从内到外
            //   执行，返回事件的监听器后注册者优先触发。放在 sheet 外层 = 后注册 = 先触发，
            //   吃掉返回事件，避免用户从屏幕边缘滑图时被系统边缘手势误关。
            //   代价：返回键 + 边缘返回手势均失效；关闭入口 = 右上角按钮 / 遮罩点击 / 下滑。
            BackHandler(enabled = true) { /* 空实现：消费返回事件 */ }

            FlexibleBottomSheet(
                onDismissRequest = onDismiss,
                sheetState = rememberFlexibleBottomSheetState(isModal = true),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .navigationBarsPadding()
                        .padding(bottom = 24.dp),
                ) {
                    SheetTopBar(onClose = onDismiss)

                    if (attachments.isNotEmpty()) {
                        AttachmentCarousel(
                            attachments = attachments,
                            onOpenViewer = { viewerPage = it },
                            autoScroll = carouselAutoScroll,
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    AttrFieldList(fields, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    SheetActionBar(feature, config)
                }
            }
        }

        viewerPage?.let { initial ->
            FullscreenViewer(
                attachments = attachments,
                initialPage = initial,
                onClose = { viewerPage = null },
            )
        }
    }

    // =============================================================================================
    // 公共部件
    // =============================================================================================

    /**
     * Sheet 顶部栏：拖拽条居中 + 关闭按钮靠右。
     * 关闭按钮用 FilledTonalIconButton —— Material3 默认 ripple 叠在 secondaryContainer
     * 容器色上，形成"按下加深 → 松开恢复"的反馈；触摸区 40dp、图标 20dp。
     */
    @Composable
    private fun SheetTopBar(onClose: () -> Unit) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp),
        ) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFFD0D0D0)),
            )
            FilledTonalIconButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(40.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "关闭",
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }

    @Composable
    private fun AttrFieldList(
        fields: List<Pair<String, String>>,
        modifier: Modifier = Modifier,
    ) {
        Column(modifier) {
            fields.forEach { (label, value) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = label,
                        modifier = Modifier.width(88.dp),
                        fontSize = 14.sp,
                        color = Color(0xFF888888),
                    )
                    Text(
                        text = value,
                        modifier = Modifier.weight(1f),
                        fontSize = 14.sp,
                        color = Color(0xFF222222),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    // =============================================================================================
    // 附件跑马灯
    // =============================================================================================

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun AttachmentCarousel(
        attachments: List<Attachment>,
        onOpenViewer: (Int) -> Unit,
        autoScroll: Boolean,
    ) {
        val context = LocalContext.current
        val pagerState = rememberPagerState(pageCount = { attachments.size })

        // 自动轮播：显式 tween(200ms)，缩短动画中间态停留时间，
        // 避免用户看到"新旧两张图并排滑动"的过渡帧。
//        LaunchedEffect(pagerState.currentPage, autoScroll) {
//            if (!autoScroll || attachments.size <= 1) return@LaunchedEffect
//            delay(3000.milliseconds)
//            pagerState.animateScrollToPage(
//                (pagerState.currentPage + 1) % attachments.size,
//                animationSpec = tween(durationMillis = 200),
//            )
//        }
        LaunchedEffect(autoScroll, attachments.size) {
            if (!autoScroll || attachments.size <= 1) return@LaunchedEffect
            while (true) {
                delay(3000.milliseconds)
                // 每次循环实时读取 currentPage，用户手动滑动后从新位置继续
                val next = (pagerState.currentPage + 1) % attachments.size
                pagerState.animateScrollToPage(
                    next,
                    animationSpec = tween(durationMillis = 200),
                )
            }
        }

        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth()) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(12.dp)),
                ) { page ->
                    val att = attachments[page]
                    if (att.isVideo) {
                        PreviewVideoPlayer(
                            url = att.url,
                            active = pagerState.currentPage == page,
                            onOpenFullscreen = { onOpenViewer(page) },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        AsyncImage(
                            // ★ 关闭 crossfade：避免 Coil 在切换时把新旧图叠加淡入淡出，
                            //   否则抓帧会看到"两张图拼接"的过渡态。
                            model = ImageRequest.Builder(context)
                                .data(att.url)
                                .crossfade(false)
                                .build(),
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectTapGestures(onTap = { onOpenViewer(page) })
                                },
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
                if (attachments.size > 1) {
                    Text(
                        text = "${pagerState.currentPage + 1}/${attachments.size}",
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp)
                            .background(Color(0x99000000), RoundedCornerShape(10.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        color = Color.White,
                        fontSize = 12.sp,
                    )
                }
            }
            if (attachments.size > 1) {
                PagerDots(
                    count = attachments.size,
                    currentPage = pagerState.currentPage,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 8.dp),
                )
            }
        }
    }

    @Composable
    private fun PagerDots(count: Int, currentPage: Int, modifier: Modifier = Modifier) {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(count) { i ->
                val active = i == currentPage
                Box(
                    Modifier
                        .size(if (active) 8.dp else 6.dp)
                        .clip(CircleShape)
                        .background(if (active) Color(0xFF333333) else Color(0xFFCCCCCC)),
                )
            }
        }
    }

    // =============================================================================================
    // VideoCache：预览与全屏共享的本地文件缓存（带淘汰策略）
    // =============================================================================================

    private object VideoCache {

        private const val DIR_NAME = "feature_video_cache"
        private const val MAX_TOTAL_BYTES = 300L * 1024 * 1024   // 300 MB
        private const val MAX_FILE_COUNT = 30
        private const val PROTECT_RECENT_MS = 5 * 60_000L
        private const val PART_TTL_MS = 60 * 60_000L

        private val inflight = mutableSetOf<String>()
        private val trimLock = Any()

        private fun dir(context: Context): File =
            File(context.cacheDir, DIR_NAME).apply { mkdirs() }

        private fun fileFor(context: Context, url: String): File =
            File(dir(context), Integer.toHexString(url.hashCode()) + ".cache")

        fun cached(context: Context, url: String): File? =
            fileFor(context, url).takeIf { it.exists() && it.length() > 0 }

        fun preload(context: Context, url: String) {
            synchronized(inflight) {
                if (inflight.contains(url) || cached(context, url) != null) return
                inflight.add(url)
            }
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    trimIfNeeded(context)
                    downloadTo(context, url)
                } finally {
                    synchronized(inflight) { inflight.remove(url) }
                }
            }
        }

        fun evictIfNeeded(context: Context) {
            CoroutineScope(Dispatchers.IO).launch { trimIfNeeded(context) }
        }

        private fun downloadTo(context: Context, url: String) {
            val target = fileFor(context, url)
            val tmp = File(target.parentFile, target.name + ".part")
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                }
                conn.inputStream.use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                }
                if (tmp.length() > 0) {
                    tmp.renameTo(target)
                    target.setLastModified(System.currentTimeMillis())
                } else {
                    tmp.delete()
                }
            } catch (_: Exception) {
                tmp.delete()
            }
        }

        private fun trimIfNeeded(context: Context) {
            synchronized(trimLock) {
                val root = dir(context)
                val files = root.listFiles() ?: return
                val now = System.currentTimeMillis()

                files.filter { it.name.endsWith(".part") }.forEach { p ->
                    if (now - p.lastModified() > PART_TTL_MS) runCatching { p.delete() }
                }

                val caches = files
                    .filter { it.name.endsWith(".cache") && it.isFile }
                    .sortedBy { it.lastModified() }

                var totalBytes = caches.sumOf { it.length() }
                var totalCount = caches.size

                for (f in caches) {
                    if (totalBytes <= MAX_TOTAL_BYTES && totalCount <= MAX_FILE_COUNT) break
                    if (now - f.lastModified() < PROTECT_RECENT_MS) continue
                    val len = f.length()
                    if (runCatching { f.delete() }.getOrDefault(false)) {
                        totalBytes -= len
                        totalCount -= 1
                    }
                }
            }
        }
    }

    // =============================================================================================
    // FitTextureView：按视频宽高比 letterbox 适配
    // =============================================================================================

    private class FitTextureView(context: Context) : TextureView(context) {

        private var videoW = 0
        private var videoH = 0

        fun setVideoSize(w: Int, h: Int) {
            if (w == videoW && h == videoH) return
            videoW = w
            videoH = h
            applyFit()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            applyFit()
        }

        private fun applyFit() {
            if (videoW <= 0 || videoH <= 0 || width <= 0 || height <= 0) return
            val viewRatio = width.toFloat() / height.toFloat()
            val videoRatio = videoW.toFloat() / videoH.toFloat()
            val matrix = Matrix()
            val cx = width / 2f
            val cy = height / 2f
            if (videoRatio > viewRatio) {
                matrix.setScale(1f, viewRatio / videoRatio, cx, cy)
            } else {
                matrix.setScale(videoRatio / viewRatio, 1f, cx, cy)
            }
            setTransform(matrix)
        }
    }

    // =============================================================================================
    // 预览态视频播放
    // =============================================================================================

    @Composable
    private fun PreviewVideoPlayer(
        url: String,
        active: Boolean,
        onOpenFullscreen: () -> Unit,
        modifier: Modifier = Modifier,
    ) {
        val context = LocalContext.current
        val localFile = remember(url) { VideoCache.cached(context, url) }
        LaunchedEffect(url) { VideoCache.preload(context, url) }

        var playing by remember { mutableStateOf(false) }
        var prepared by remember { mutableStateOf(false) }
        var player by remember { mutableStateOf<MediaPlayer?>(null) }
        var wantPlay by remember { mutableStateOf(false) }
        var muted by remember { mutableStateOf(true) }  // 默认静音

        fun toggle() {
            val mp = player
            if (mp == null || !prepared) { wantPlay = !wantPlay; return }
            if (playing) {
                runCatching { mp.pause() }; playing = false; wantPlay = false
            } else {
                runCatching { mp.start() }; playing = true; wantPlay = true
            }
        }

        fun toggleMute() {
            muted = !muted
            val v = if (muted) 0f else 1f
            player?.let { runCatching { it.setVolume(v, v) } }
        }

        LaunchedEffect(active) {
            if (!active) {
                player?.let { runCatching { it.pause() } }
                playing = false; wantPlay = false
            }
        }

        DisposableEffect(Unit) {
            onDispose {
                player?.let { runCatching { it.release() } }
                player = null
            }
        }

        Box(modifier.background(Color(0xFF1A1A1A))) {
            AndroidView(
                factory = { ctx ->
                    FitTextureView(ctx).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(
                                st: SurfaceTexture, w: Int, h: Int,
                            ) {
                                player?.let { runCatching { it.release() } }
                                val mp = MediaPlayer()
                                player = mp
                                runCatching {
                                    mp.setDataSource(localFile?.absolutePath ?: url)
                                    mp.setSurface(Surface(st))

                                    mp.setOnVideoSizeChangedListener { _, vw, vh ->
                                        setVideoSize(vw, vh)
                                    }

                                    mp.setOnInfoListener { m, what, _ ->
                                        if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                                            if (!wantPlay && !playing) {
                                                runCatching { m.pause(); m.seekTo(0) }
                                            }
                                            true
                                        } else false
                                    }

                                    mp.setOnPreparedListener { m ->
                                        prepared = true
                                        val v = if (muted) 0f else 1f
                                        runCatching { m.setVolume(v, v) }
                                        if (wantPlay) {
                                            runCatching { m.start() }
                                            playing = true
                                        } else {
                                            runCatching { m.start() }
                                        }
                                    }
                                    mp.setOnCompletionListener { playing = false; wantPlay = false }
                                    mp.setOnErrorListener { _, _, _ ->
                                        playing = false; prepared = false; wantPlay = false; true
                                    }
                                    mp.prepareAsync()
                                }.onFailure {
                                    runCatching { mp.release() }
                                    player = null; prepared = false
                                }
                            }

                            override fun onSurfaceTextureSizeChanged(
                                st: SurfaceTexture, w: Int, h: Int,
                            ) = Unit

                            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                player?.let { runCatching { it.release() } }
                                player = null
                                playing = false; prepared = false; wantPlay = false
                                return true
                            }

                            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures(onTap = { toggle() }) },
            )

            if (!playing) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(if (prepared) Color(0x33000000) else Color(0xFF1A1A1A)),
                    contentAlignment = Alignment.Center,
                ) {
                    PlayOverlay(loading = wantPlay && !prepared)
                }
            }

            Text(
                text = "[ 全屏 ]",
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(Color(0x99000000), RoundedCornerShape(6.dp))
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = {
                            player?.let { runCatching { it.pause() } }
                            playing = false; wantPlay = false
                            onOpenFullscreen()
                        })
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )

            IconButton(
                onClick = { toggleMute() },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .background(Color(0x99000000), CircleShape),
            ) {
                Icon(
                    imageVector = if (muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                    contentDescription = if (muted) "取消静音" else "静音",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }

    @Composable
    private fun PlayOverlay(
        modifier: Modifier = Modifier,
        loading: Boolean = false,
    ) {
        Box(modifier = modifier.size(72.dp), contentAlignment = Alignment.Center) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.White.copy(alpha = 0.85f),
                    strokeWidth = 3.dp,
                )
            }
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(Color(0x99000000), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "播放",
                    tint = Color.White.copy(alpha = if (loading) 0.5f else 1f),
                    modifier = Modifier.size(36.dp),
                )
            }
        }
    }

    // =============================================================================================
    // 全屏浏览
    // =============================================================================================

    /**
     * ★ 公开给外部复用（如轨迹媒体标记点击）：图片缩放浏览 + 视频播放（进度/静音/旋转）。
     *
     * 本地文件直接传绝对路径即可——[VideoCache] 发现本地已存在不会走网络下载。
     */
    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    fun FullscreenViewer(
        attachments: List<Attachment>,
        initialPage: Int,
        onClose: () -> Unit,
    ) {
        if (attachments.isEmpty()) { onClose(); return }

        val pagerState = rememberPagerState(
            initialPage = initialPage.coerceIn(0, attachments.lastIndex.coerceAtLeast(0)),
            pageCount = { attachments.size },
        )

        val context = LocalContext.current
        val activity = remember(context) { findActivity(context) }

        var isLandscape by remember {
            mutableStateOf(
                context.resources.configuration.orientation ==
                        Configuration.ORIENTATION_LANDSCAPE,
            )
        }

        DisposableEffect(activity) {
            val original = activity?.requestedOrientation
            onDispose {
                if (activity != null && original != null) {
                    activity.requestedOrientation = original
                }
            }
        }

        Dialog(
            onDismissRequest = onClose,
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            val view = LocalView.current
            LaunchedEffect(Unit) {
                val window = generateSequence(view.parent) { it.parent }
                    .filterIsInstance<DialogWindowProvider>()
                    .firstOrNull()?.window
                if (window != null) {
                    window.setBackgroundDrawable(ColorDrawable(AndroidColor.BLACK))
                    window.decorView.setBackgroundColor(AndroidColor.BLACK)
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
            ) {
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                    val att = attachments[page]
                    if (att.isVideo) {
                        VideoPlayer(
                            url = att.url,
                            active = pagerState.currentPage == page,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        ZoomableImage(
                            model = att.url,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = {
                            val act = activity ?: return@IconButton
                            isLandscape = !isLandscape
                            act.requestedOrientation = if (isLandscape) {
                                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            } else {
                                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                            }
                        },
                        modifier = Modifier.background(Color(0x66000000), CircleShape),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "旋转屏幕",
                            tint = Color.White,
                        )
                    }

                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.background(Color(0x66000000), CircleShape),
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Color.White)
                    }
                }

                if (attachments.size > 1) {
                    Text(
                        text = "${pagerState.currentPage + 1}/${attachments.size}",
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(20.dp)
                            .background(Color(0x66000000), RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 3.dp),
                        color = Color.White,
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }

    @Composable
    private fun VideoPlayer(url: String, active: Boolean, modifier: Modifier = Modifier) {
        val context = LocalContext.current
        val localFile = remember(url) { VideoCache.cached(context, url) }
        LaunchedEffect(url) { VideoCache.preload(context, url) }

        var playing by remember { mutableStateOf(false) }
        var prepared by remember { mutableStateOf(false) }
        var player by remember { mutableStateOf<MediaPlayer?>(null) }
        var wantPlay by remember { mutableStateOf(false) }
        var muted by remember { mutableStateOf(true) }  // 默认静音

        fun toggle() {
            val mp = player
            if (mp == null || !prepared) { wantPlay = !wantPlay; return }
            if (playing) {
                runCatching { mp.pause() }; playing = false; wantPlay = false
            } else {
                runCatching { mp.start() }; playing = true; wantPlay = true
            }
        }

        fun toggleMute() {
            muted = !muted
            val v = if (muted) 0f else 1f
            player?.let { runCatching { it.setVolume(v, v) } }
        }

        LaunchedEffect(active) {
            if (!active) {
                player?.let { runCatching { it.pause() } }
                playing = false; wantPlay = false
            }
        }

        DisposableEffect(Unit) {
            onDispose {
                player?.let { runCatching { it.release() } }
                player = null
            }
        }

        Box(
            modifier = modifier.background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                factory = { ctx ->
                    FitTextureView(ctx).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(
                                st: SurfaceTexture, w: Int, h: Int,
                            ) {
                                player?.let { runCatching { it.release() } }
                                val mp = MediaPlayer()
                                player = mp
                                runCatching {
                                    mp.setDataSource(localFile?.absolutePath ?: url)
                                    mp.setSurface(Surface(st))

                                    mp.setOnVideoSizeChangedListener { _, vw, vh ->
                                        setVideoSize(vw, vh)
                                    }

                                    mp.setOnInfoListener { m, what, _ ->
                                        if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                                            if (!wantPlay && !playing) {
                                                runCatching { m.pause(); m.seekTo(0) }
                                            }
                                            true
                                        } else false
                                    }

                                    mp.setOnPreparedListener { m ->
                                        prepared = true
                                        val v = if (muted) 0f else 1f
                                        runCatching { m.setVolume(v, v) }
                                        if (wantPlay) {
                                            runCatching { m.start() }
                                            playing = true
                                        } else {
                                            runCatching { m.start() }
                                        }
                                    }
                                    mp.setOnCompletionListener { playing = false; wantPlay = false }
                                    mp.setOnErrorListener { _, _, _ ->
                                        playing = false; prepared = false; wantPlay = false; true
                                    }
                                    mp.prepareAsync()
                                }.onFailure {
                                    runCatching { mp.release() }
                                    player = null; prepared = false
                                }
                            }

                            override fun onSurfaceTextureSizeChanged(
                                st: SurfaceTexture, w: Int, h: Int,
                            ) = Unit

                            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                player?.let { runCatching { it.release() } }
                                player = null
                                playing = false; prepared = false; wantPlay = false
                                return true
                            }

                            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures(onTap = { toggle() }) },
            )

            if (!playing) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(if (prepared) Color(0x33000000) else Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    PlayOverlay(loading = wantPlay && !prepared)
                }
            }

            VideoControls(
                player = player,
                playing = playing,
                muted = muted,
                onTogglePlay = ::toggle,
                onToggleMute = ::toggleMute,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding(),
            )
        }
    }

    // =============================================================================================
    // 全屏图片缩放
    // =============================================================================================

    @Composable
    private fun ZoomableImage(model: Any, modifier: Modifier = Modifier) {
        val scope = rememberCoroutineScope()
        val scale = remember { Animatable(1f) }
        val offset = remember { Animatable(Offset.Zero, Offset.VectorConverter) }

        BoxWithConstraints(
            modifier = modifier
                .clipToBounds()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var takeover = scale.value > 1.001f
                        var curScale = scale.value
                        var curOffset = offset.value
                        do {
                            val event = awaitPointerEvent()
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            if (!takeover && zoomChange != 1f) takeover = true
                            if (takeover) {
                                event.changes.forEach { it.consume() }
                                curScale = (curScale * zoomChange).coerceIn(1f, 5f)
                                curOffset = if (curScale > 1f) curOffset + panChange
                                else Offset.Zero
                                val s = curScale
                                val o = curOffset
                                scope.launch {
                                    scale.snapTo(s)
                                    offset.snapTo(o)
                                }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            scope.launch {
                                val stiffness = Spring.StiffnessMediumLow
                                if (scale.value > 1f) {
                                    scale.animateTo(1f, spring(stiffness = stiffness))
                                    offset.animateTo(Offset.Zero, spring(stiffness = stiffness))
                                } else {
                                    scale.animateTo(2.5f, spring(stiffness = stiffness))
                                }
                            }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            val maxX = (constraints.maxWidth * (scale.value - 1f)) / 2f
            val maxY = (constraints.maxHeight * (scale.value - 1f)) / 2f
            val clamped = Offset(
                x = offset.value.x.coerceIn(-maxX, maxX),
                y = offset.value.y.coerceIn(-maxY, maxY),
            )
            AsyncImage(
                model = model,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale.value,
                        scaleY = scale.value,
                        translationX = clamped.x,
                        translationY = clamped.y,
                    ),
                contentScale = ContentScale.Fit,
            )
        }
    }

    // =============================================================================================
    // 进度条与控制条
    // =============================================================================================

    @Composable
    private fun CircleSeekBar(
        positionMs: Int,
        durationMs: Int,
        onDragProgress: (Int) -> Unit,
        onSeekCommit: (Int) -> Unit,
        modifier: Modifier = Modifier,
    ) {
        var dragFraction by remember { mutableFloatStateOf(-1f) }
        val fraction = if (dragFraction >= 0f) {
            dragFraction
        } else if (durationMs > 0) {
            (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        } else 0f

        Box(
            modifier = modifier
                .height(32.dp)
                .padding(horizontal = 10.dp)
                .pointerInput(durationMs) {
                    detectDragGestures(
                        onDragStart = { start ->
                            if (durationMs > 0) {
                                dragFraction = (start.x / size.width).coerceIn(0f, 1f)
                            }
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            if (durationMs > 0) {
                                dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                                onDragProgress((dragFraction * durationMs).toInt())
                            }
                        },
                        onDragEnd = {
                            if (durationMs > 0 && dragFraction >= 0f) {
                                onSeekCommit((dragFraction * durationMs).toInt())
                            }
                            dragFraction = -1f
                        },
                        onDragCancel = { dragFraction = -1f },
                    )
                }
                .pointerInput(durationMs) {
                    detectTapGestures { tap ->
                        if (durationMs > 0) {
                            val f = (tap.x / size.width).coerceIn(0f, 1f)
                            onDragProgress((f * durationMs).toInt())
                            onSeekCommit((f * durationMs).toInt())
                        }
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val cy = size.height / 2f
                val w = size.width
                val x = w * fraction
                val stroke = 3.dp.toPx()
                val thumbRadius = 7.dp.toPx()
                drawLine(Color(0x66FFFFFF), Offset(0f, cy), Offset(w, cy), stroke)
                drawLine(Color.White, Offset(0f, cy), Offset(x, cy), stroke)
                drawCircle(Color.White, thumbRadius, Offset(x, cy))
            }
        }
    }

    @Composable
    private fun VideoControls(
        player: MediaPlayer?,
        playing: Boolean,
        muted: Boolean,
        onTogglePlay: () -> Unit,
        onToggleMute: () -> Unit,
        modifier: Modifier = Modifier,
    ) {
        var durationMs by remember { mutableStateOf(0) }
        var positionMs by remember { mutableStateOf(0) }
        var dragging by remember { mutableStateOf(false) }

        LaunchedEffect(player) {
            while (true) {
                delay(500)
                val mp = player ?: continue
                if (!dragging) {
                    positionMs = runCatching { mp.currentPosition }.getOrDefault(0).coerceAtLeast(0)
                    durationMs = runCatching { mp.duration }.getOrDefault(0).coerceAtLeast(0)
                }
            }
        }

        Row(
            modifier = modifier
                .fillMaxWidth()
                .background(Color(0x66000000))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onTogglePlay) {
                if (playing) {
                    Text(
                        text = "❚❚",
                        color = Color.White,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                } else {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "播放", tint = Color.White)
                }
            }
            CircleSeekBar(
                positionMs = positionMs,
                durationMs = durationMs,
                onDragProgress = { ms -> dragging = true; positionMs = ms },
                onSeekCommit = { ms ->
                    player?.let { runCatching { it.seekTo(ms) } }
                    positionMs = ms
                    dragging = false
                },
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${formatTime(positionMs)}/${formatTime(durationMs)}",
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 8.dp),
            )
            IconButton(onClick = onToggleMute, modifier = Modifier.padding(start = 4.dp)) {
                Icon(
                    imageVector = if (muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                    contentDescription = if (muted) "取消静音" else "静音",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }

    private fun formatTime(ms: Int): String {
        if (ms <= 0) return "00:00"
        val totalSec = ms / 1000
        return "%02d:%02d".format(totalSec / 60, totalSec % 60)
    }

    // =============================================================================================
    // 底部操作栏：高德导航 / 微信分享
    // =============================================================================================

    @Composable
    private fun SheetActionBar(feature: Feature, config: Config) {
        if (!config.showActions) return
        val latLng = config.position(feature) ?: return
        val context = LocalContext.current
        val name = config.displayName(feature)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FilledTonalButton(
                onClick = { openAmapNavigation(context, latLng.first, latLng.second, name) },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.LocationOn, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("高德导航")
            }
            FilledTonalButton(
                onClick = { shareLocationToWeChat(context, latLng.first, latLng.second, name) },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.Share, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("分享给微信好友")
            }
        }
    }

    // =============================================================================================
    // 辅助
    // =============================================================================================

    private fun findActivity(context: Context): Activity? {
        var c: Context? = context
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    private fun openAmapNavigation(context: Context, lat: Double, lng: Double, name: String) {
        val naviUri = Uri.parse(
            "amapuri://route/plan?sourceApplication=wildlife" +
                    "&dlat=$lat&dlon=$lng&dname=${Uri.encode(name)}&dev=0&t=0",
        )
        val intent = Intent(Intent.ACTION_VIEW, naviUri).apply {
            setPackage("com.autonavi.minimap")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            val webUri = Uri.parse(
                "https://uri.amap.com/marker?position=$lng,$lat" +
                        "&name=${Uri.encode(name)}&callnative=1",
            )
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, webUri)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (e2: Exception) {
                Toast.makeText(context, "请安装高德地图", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun shareLocationToWeChat(context: Context, lat: Double, lng: Double, name: String) {
        val link = "https://uri.amap.com/marker?position=$lng,$lat" +
                "&name=${Uri.encode(name)}&callnative=1"
        val text = "$name\n$link"

        val direct = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            setPackage("com.tencent.mm")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(direct)
        } catch (e: Exception) {
            val chooser = Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                },
                "分享位置",
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(chooser)
            } catch (e2: Exception) {
                Toast.makeText(context, "未找到可用的分享应用", Toast.LENGTH_SHORT).show()
            }
        }
    }
}