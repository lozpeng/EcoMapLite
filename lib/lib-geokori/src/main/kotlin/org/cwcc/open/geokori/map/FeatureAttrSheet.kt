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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
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
 * 视频渲染：
 *  · 使用 TextureView + MediaPlayer（而不是 VideoView）——
 *    VideoView 内部是 SurfaceView，会在窗口上"打洞"独立合成，Compose 的覆盖层
 *    物理上盖不住，会出现"播放中透明、暂停时不透明"的穿透问题；
 *    TextureView 是普通 View，走正常窗口合成，谁后画谁在上，不再穿透。
 *  · 使用 FitTextureView 按视频宽高比做 letterbox 适配，避免全屏拉伸。
 *  · 未播放时自动 prepareAsync 并触发首帧渲染（start → MEDIA_INFO_VIDEO_RENDERING_START
 *    → pause + seekTo(0)）；加载完成前用黑底 + 环形进度占位，加载完成后让首帧透出，
 *    遮罩退化为浅色半透明、居中一个播放按钮。
 *  · 播放按钮外包环形进度条（无限旋转），用户点击到首帧就绪之间给出反馈。
 *    MediaPlayer 在 prepare 阶段不提供真实进度百分比，故用无限环形；
 *    若需真实百分比需切换 ExoPlayer（Player.Listener.onPlaybackStateChanged）。
 *  · 静音按钮：
 *      - 预览态：右下角浮动圆形按钮（图标 28dp、触摸区 48dp）；
 *      - 全屏态：底部控制条右侧（进度条 + 时间之后）。
 *    预览页码角标让位到左下角，避免与静音按钮重叠。
 *  · 全屏 Dialog 窗口强制不透明黑底（decorFitsSystemWindows=false + ColorDrawable(BLACK)），
 *    用于保证图片 Fit 留白区域与系统栏区域为纯黑。
 *
 * 数据源共享：
 *  · 预览与全屏不能共享同一个 MediaPlayer 实例 —— Surface 是独占资源，切换 setSurface
 *    会黑帧；两个 Composable 生命周期也不同步。
 *  · 但可以共享底层本地缓存文件：[VideoCache] 首次播放静默下载到 cacheDir；
 *    预览与全屏每次挂载时若命中缓存则从本地文件加载（秒开），否则退回 HTTP URL。
 *
 * 全屏方向：
 *  · 右上角提供旋转按钮，点击在横/竖屏之间切换（本地状态驱动，可反复切换）；
 *  · 进入全屏时记录宿主 Activity 的 requestedOrientation，退出时恢复，
 *    保证 App 原有的竖屏锁定 / 自动旋转策略不被破坏；
 *  · 用 SENSOR_LANDSCAPE / SENSOR_PORTRAIT，横屏时可左右手换握（仅横屏内部翻转）；
 *  · ★ 宿主 Activity 必须声明
 *      configChanges="orientation|screenSize|screenLayout|keyboardHidden|smallestScreenSize"，
 *    否则旋转会触发 Activity 重建，方向记录会被覆盖、关闭时反向旋转。
 */
object FeatureAttrSheet {

    /** 宽屏阈值（dp） */
    private const val WIDE_SCREEN_WIDTH_DP = 600

    /** 宽屏对话框最大宽度 */
    private val WIDE_DIALOG_WIDTH = 720.dp

    // =========================================================================================
    // 公开 API
    // =========================================================================================

    /**
     * 多媒体附件条目。
     *
     * @param url      最终可加载地址（调用方负责拼接 base）
     * @param isVideo  是否是视频（true → 用视频播放器渲染；false → 用 AsyncImage 渲染）
     */
    data class Attachment(
        val url: String,
        val isVideo: Boolean = false,
    )

    /**
     * 面板展示配置。
     *
     * 所有行为都是函数——按需从 Feature 派生，不同面板给不同实现即可复用同一套 UI。
     *
     * @param fields        表格式属性：label → value 列表，按顺序展示；空值请自行过滤
     * @param attachments   附件列表：图片 / 视频；空列表则不渲染附件区
     * @param displayName   展示名：用于导航 / 分享的文案
     * @param position      位置：导航 / 分享使用；返回 null 则不渲染操作栏
     * @param showActions   是否展示操作栏（高德导航 + 微信分享）
     */
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

    /**
     * 无状态渲染入口。
     *
     * @param feature   要展示的要素（非空；面板宿主自己保证只在有 feature 时调用）
     * @param config    派生配置
     * @param onDismiss 关闭回调（遮罩点击 / 关闭按钮 / 返回键 / 手势）
     */
    @Composable
    fun Content(
        feature: Feature,
        config: Config,
        onDismiss: () -> Unit,
    ) {
        val isWide = LocalConfiguration.current.screenWidthDp >= WIDE_SCREEN_WIDTH_DP

        // 每次重组从 feature 派生；纯函数，代价低
        val fields = config.fields(feature)
        val attachments = config.attachments(feature)

        var viewerPage by remember { mutableStateOf<Int?>(null) }
        val carouselAutoScroll = viewerPage == null

        if (isWide) {
            WideAttrDialog(
                feature = feature,
                fields = fields,
                attachments = attachments,
                config = config,
                onDismiss = onDismiss,
                onOpenViewer = { viewerPage = it },
                carouselAutoScroll = carouselAutoScroll,
            )
        } else {
            NarrowBottomSheet(
                feature = feature,
                fields = fields,
                attachments = attachments,
                config = config,
                onDismiss = onDismiss,
                onOpenViewer = { viewerPage = it },
                carouselAutoScroll = carouselAutoScroll,
            )
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
    // 窄屏：底部弹窗
    // =============================================================================================

    @Composable
    private fun NarrowBottomSheet(
        feature: Feature,
        fields: List<Pair<String, String>>,
        attachments: List<Attachment>,
        config: Config,
        onDismiss: () -> Unit,
        onOpenViewer: (Int) -> Unit,
        carouselAutoScroll: Boolean,
    ) {
        FlexibleBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberFlexibleBottomSheetState(isModal = true),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
            ) {
                SheetHandle()

                if (attachments.isNotEmpty()) {
                    AttachmentCarousel(
                        attachments = attachments,
                        onOpenViewer = onOpenViewer,
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

    // =============================================================================================
    // 宽屏：居中双栏对话框（左附件 / 右属性）
    // =============================================================================================

    @Composable
    private fun WideAttrDialog(
        feature: Feature,
        fields: List<Pair<String, String>>,
        attachments: List<Attachment>,
        config: Config,
        onDismiss: () -> Unit,
        onOpenViewer: (Int) -> Unit,
        carouselAutoScroll: Boolean,
    ) {
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
                                        onOpenViewer = onOpenViewer,
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
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.align(Alignment.TopEnd),
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "关闭")
                        }
                    }
                }
            }
        }
    }

    // =============================================================================================
    // 公共部件
    // =============================================================================================

    @Composable
    private fun SheetHandle() {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFFD0D0D0)),
            )
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
    // 附件跑马灯：圆点指示器 + 左右滑动 + 自动轮播；视频预览态可播放
    // =============================================================================================

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun AttachmentCarousel(
        attachments: List<Attachment>,
        onOpenViewer: (Int) -> Unit,
        autoScroll: Boolean,
    ) {
        val pagerState = rememberPagerState(pageCount = { attachments.size })

        // 多附件 3s 自动轮播（打开全屏时由 autoScroll=false 暂停）
        LaunchedEffect(pagerState.currentPage, autoScroll) {
            if (!autoScroll || attachments.size <= 1) return@LaunchedEffect
            delay(3000.milliseconds)
            pagerState.animateScrollToPage((pagerState.currentPage + 1) % attachments.size)
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
                            model = att.url,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    // tap 进全屏；detectTapGestures 不消费拖动，左右滑动不受影响
                                    detectTapGestures(onTap = { onOpenViewer(page) })
                                },
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
                if (attachments.size > 1) {
                    // ★ 页码角标挪到左下角，把右下角让给视频静音按钮（若当前页是视频）
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
            // ★ 圆点指示器：让用户知道有多张附件、当前在第几张
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
    // VideoCache：预览与全屏共享的本地文件缓存
    // =============================================================================================

    /**
     * 视频本地缓存：预览与全屏共用一份下载好的文件。
     *
     * 行为：
     *  · [cached] 命中（文件存在且非空）时返回本地 File，调用方 setDataSource 本地路径；
     *  · [preload] 后台静默下载到 cacheDir（系统可回收），已缓存/正在下载则跳过；
     *  · 首次播放时 MediaPlayer 仍走 HTTP URL（快启），同时后台下载；下次挂载命中缓存。
     *
     * 生命周期管理：
     *  · 每次 [preload] 前先跑一次 [trimIfNeeded]（IO 线程，不阻塞主线程）；
     *  · 淘汰策略：总大小 ≤ [MAX_TOTAL_BYTES] 且文件数 ≤ [MAX_FILE_COUNT]，
     *    任一超标按 mtime 从旧到新删除；最近 [PROTECT_RECENT_MS] 内修改过的跳过
     *    （可能正被 MediaPlayer 读取）；
     *  · 清理超过 [PART_TTL_MS] 未完成的 .part 半成品（下载中断残留）；
     *  · 不做 MD5 校验。cacheDir 由系统管理，叠加本淘汰策略已足够。
     */
    private object VideoCache {

        private const val DIR_NAME = "feature_video_cache"

        /** 缓存目录总大小上限（字节）。超限按最旧优先淘汰。 */
        private const val MAX_TOTAL_BYTES = 300L * 1024 * 1024  // 300 MB

        /** 缓存文件数量上限。与大小上限同时生效，取先到者。 */
        private const val MAX_FILE_COUNT = 30

        /** 最近修改过的文件不参与淘汰（视为可能正在被 MediaPlayer 读取） */
        private const val PROTECT_RECENT_MS = 5 * 60_000L

        /** .part 半成品的最大保留时长（超过视为下载中断垃圾） */
        private const val PART_TTL_MS = 60 * 60_000L

        private val inflight = mutableSetOf<String>()
        private val trimLock = Any()

        private fun dir(context: Context): File =
            File(context.cacheDir, DIR_NAME).apply { mkdirs() }

        private fun fileFor(context: Context, url: String): File =
            File(dir(context), Integer.toHexString(url.hashCode()) + ".cache")

        /** 已缓存则返回本地文件；否则 null */
        fun cached(context: Context, url: String): File? =
            fileFor(context, url).takeIf { it.exists() && it.length() > 0 }

        /** 后台预下载；已缓存 / 正在下载则跳过。下载前会触发一次淘汰。 */
        fun preload(context: Context, url: String) {
            // 主线程：仅判重
            synchronized(inflight) {
                if (inflight.contains(url) || cached(context, url) != null) return
                inflight.add(url)
            }
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    trimIfNeeded(context)   // 下载前先清，给新文件腾空间
                    downloadTo(context, url)
                } finally {
                    synchronized(inflight) { inflight.remove(url) }
                }
            }
        }

        /**
         * 外部可主动触发的清理（App 启动、退出属性面板等）。
         * 非必须——[preload] 内部已调用。
         */
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
                    // 主动刷新 mtime：部分机型 renameTo 后 mtime 不更新
                    target.setLastModified(System.currentTimeMillis())
                } else {
                    tmp.delete()
                }
            } catch (_: Exception) {
                tmp.delete()
            }
        }

        /**
         * 缓存淘汰：
         *  1. 清掉超期的 .part 半成品（上次下载中断）；
         *  2. 按 mtime 从旧到新淘汰 .cache，直到同时满足：
         *     - 总大小 ≤ [MAX_TOTAL_BYTES]
         *     - 文件数 ≤ [MAX_FILE_COUNT]
         *  3. 最近 [PROTECT_RECENT_MS] 内修改过的文件跳过（可能正在被 MediaPlayer 读取）。
         */
        private fun trimIfNeeded(context: Context) {
            synchronized(trimLock) {
                val root = dir(context)
                val files = root.listFiles() ?: return
                val now = System.currentTimeMillis()

                // 1. 清理过期的 .part
                files.filter { it.name.endsWith(".part") }.forEach { p ->
                    if (now - p.lastModified() > PART_TTL_MS) runCatching { p.delete() }
                }

                // 2. 收集 .cache 并按 mtime 升序
                val caches = files
                    .filter { it.name.endsWith(".cache") && it.isFile }
                    .sortedBy { it.lastModified() }

                var totalBytes = caches.sumOf { it.length() }
                var totalCount = caches.size

                // 3. 从最旧开始淘汰
                for (f in caches) {
                    if (totalBytes <= MAX_TOTAL_BYTES && totalCount <= MAX_FILE_COUNT) break
                    // 保护：最近修改过的不删
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
    // FitTextureView：按视频宽高比 letterbox 适配的 TextureView
    // =============================================================================================

    /**
     * 按视频宽高比做 letterbox 适配的 TextureView。
     *
     * TextureView 是普通 View，本身不感知视频宽高比，直接填满会被拉伸。
     * 这里在视频尺寸 / View 尺寸变化时用 setTransform(Matrix) 把内容缩放到
     * Fit 居中显示 —— 宽高比不符时上下或左右留黑边，绝不拉伸。
     */
    private class FitTextureView(context: Context) : TextureView(context) {

        private var videoW = 0
        private var videoH = 0

        /** MediaPlayer 汇报视频尺寸时调用；重复值会被忽略 */
        fun setVideoSize(w: Int, h: Int) {
            if (w == videoW && h == videoH) return
            videoW = w
            videoH = h
            applyFit()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            // View 尺寸变化（进入全屏 / 旋转）后重新适配
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
                // 视频比 View 更宽：水平撑满，垂直缩放（上下留黑边）
                matrix.setScale(1f, viewRatio / videoRatio, cx, cy)
            } else {
                // 视频比 View 更窄：垂直撑满，水平缩放（左右留黑边）
                matrix.setScale(videoRatio / viewRatio, 1f, cx, cy)
            }
            setTransform(matrix)
        }
    }

    // =============================================================================================
    // 预览态视频播放（FitTextureView + MediaPlayer；自动 prepare + 显示首帧；右下角静音按钮）
    // =============================================================================================

    @Composable
    private fun PreviewVideoPlayer(
        url: String,
        active: Boolean,
        onOpenFullscreen: () -> Unit,
        modifier: Modifier = Modifier,
    ) {
        val context = LocalContext.current

        // ★ 数据源决策：命中本地缓存走文件，否则走 HTTP（同时触发后台下载）
        val localFile = remember(url) { VideoCache.cached(context, url) }
        LaunchedEffect(url) { VideoCache.preload(context, url) }

        var playing by remember { mutableStateOf(false) }
        var prepared by remember { mutableStateOf(false) }
        var player by remember { mutableStateOf<MediaPlayer?>(null) }
        // 播放意图：用户可能在 prepare 完成前就点了播放，记下来，onPrepared 补播
        var wantPlay by remember { mutableStateOf(false) }
        // ★ 静音状态：属于本播放器实例，两个附件页各自独立
        var muted by remember { mutableStateOf(true) }

        fun toggle() {
            val mp = player
            if (mp == null || !prepared) {
                wantPlay = !wantPlay
                return
            }
            if (playing) {
                runCatching { mp.pause() }
                playing = false
                wantPlay = false
            } else {
                runCatching { mp.start() }
                playing = true
                wantPlay = true
            }
        }

        fun toggleMute() {
            muted = !muted
            val v = if (muted) 0f else 1f
            player?.let { runCatching { it.setVolume(v, v) } }
        }

        // 切走自动暂停
        LaunchedEffect(active) {
            if (!active) {
                player?.let { runCatching { it.pause() } }
                playing = false
                wantPlay = false
            }
        }

        // 组合销毁兜底释放（onSurfaceTextureDestroyed 已经释放过一次，此处幂等）
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
                                // MediaPlayer 只能在 Surface 就绪后创建
                                player?.let { runCatching { it.release() } }
                                val mp = MediaPlayer()
                                player = mp
                                runCatching {
                                    mp.setDataSource(localFile?.absolutePath ?: url)
                                    mp.setSurface(Surface(st))

                                    // ★ 视频尺寸就绪后回填，触发 letterbox 适配
                                    mp.setOnVideoSizeChangedListener { _, vw, vh ->
                                        setVideoSize(vw, vh)
                                    }

                                    // ★ 首帧渲染完成时，若用户尚未点播放，则暂停并回到 0 位，
                                    //   使画面停在静止首帧；若用户已点播放，则保持播放。
                                    mp.setOnInfoListener { m, what, _ ->
                                        if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                                            if (!wantPlay && !playing) {
                                                runCatching {
                                                    m.pause()
                                                    m.seekTo(0)
                                                }
                                            }
                                            true
                                        } else {
                                            false
                                        }
                                    }

                                    mp.setOnPreparedListener { m ->
                                        prepared = true
                                        // ★ 沿用当前静音状态：某些场景 Surface 重建后需重设
                                        val v = if (muted) 0f else 1f
                                        runCatching { m.setVolume(v, v) }
                                        if (wantPlay) {
                                            runCatching { m.start() }
                                            playing = true
                                        } else {
                                            // 未点播放：start 触发首帧渲染，
                                            // onInfoListener 会在首帧就绪后自动 pause + seekTo(0)
                                            runCatching { m.start() }
                                        }
                                    }
                                    mp.setOnCompletionListener {
                                        playing = false
                                        wantPlay = false
                                    }
                                    mp.setOnErrorListener { _, _, _ ->
                                        playing = false
                                        prepared = false
                                        wantPlay = false
                                        true
                                    }
                                    mp.prepareAsync()
                                }.onFailure {
                                    runCatching { mp.release() }
                                    player = null
                                    prepared = false
                                }
                            }

                            override fun onSurfaceTextureSizeChanged(
                                st: SurfaceTexture, w: Int, h: Int,
                            ) = Unit

                            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                player?.let { runCatching { it.release() } }
                                player = null
                                playing = false
                                prepared = false
                                wantPlay = false
                                return true
                            }

                            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            // 点按画面播放/暂停（不消费拖动手势，pager 左右滑动不受影响）
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures(onTap = { toggle() }) },
            )

            // 未播放时的遮罩 + 播放按钮：
            //  · !prepared → 全黑遮罩 + 环形进度（数据加载中）
            //  · prepared → 浅色遮罩，让首帧透出（点击画面即可播放）
            if (!playing) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            if (prepared) Color(0x33000000) else Color(0xFF1A1A1A),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    PlayOverlay(loading = wantPlay && !prepared)
                }
            }

            // ★ "[ 全屏 ]" 小提示：点击暂停预览并进入全屏播放（右上角）
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
                            playing = false
                            wantPlay = false
                            onOpenFullscreen()
                        })
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )

            // ★ 静音按钮：右下角浮动（页码角标已让位到左下角）
            //    IconButton 触摸区默认 48dp、图标 28dp，便于点击
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

    /**
     * 播放按钮 + 可选环形加载。
     *
     * @param loading true 时在按钮外圈叠加无限旋转的环形进度，并让播放图标半透明。
     *                MediaPlayer 在 prepare 阶段不提供真实百分比，故只能用无限环形。
     */
    @Composable
    private fun PlayOverlay(
        modifier: Modifier = Modifier,
        loading: Boolean = false,
    ) {
        Box(
            modifier = modifier.size(72.dp),
            contentAlignment = Alignment.Center,
        ) {
            // 加载中：外圈环形进度
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.White.copy(alpha = 0.85f),
                    strokeWidth = 3.dp,
                )
            }
            // 内圈播放按钮；加载中半透明
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
    // 全屏浏览（图片 + 视频；窗口级纯黑兜底；右上角旋转 + 关闭）
    // =============================================================================================

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun FullscreenViewer(
        attachments: List<Attachment>,
        initialPage: Int,
        onClose: () -> Unit,
    ) {
        if (attachments.isEmpty()) {
            onClose()
            return
        }

        val pagerState = rememberPagerState(
            initialPage = initialPage.coerceIn(0, attachments.lastIndex.coerceAtLeast(0)),
            pageCount = { attachments.size },
        )

        // ---- 屏幕方向管理 ----
        val context = LocalContext.current
        val activity = remember(context) { findActivity(context) }

        // ★ 本地方向状态：进入全屏时按当前实际方向初始化一次，之后由按钮翻转。
        //   为什么不每次读 resources.configuration.orientation？
        //   因为宿主 Activity 声明了 configChanges="orientation" 后，该值在
        //   requestedOrientation 被显式修改时不保证实时刷新，会出现"读到的还是旧值，
        //   于是永远切同一个方向，看起来按钮失效"。
        //   一旦设置了显式 SENSOR_LANDSCAPE/SENSOR_PORTRAIT，系统即锁定该方向范围、
        //   本地状态与真实方向恒一致，不会漂移。
        var isLandscape by remember {
            mutableStateOf(
                context.resources.configuration.orientation ==
                        Configuration.ORIENTATION_LANDSCAPE,
            )
        }

        // 进入全屏时记录原始 requestedOrientation；退出时恢复。
        // 依赖宿主 Activity 配了 configChanges="orientation|screenSize|..."
        // —— 否则旋转会重建 Activity，导致"记录值被覆盖 + 关闭时反向旋转"。
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
                // ★ 内容延伸至系统栏区域，窗口尺寸 = 全屏
                decorFitsSystemWindows = false,
            ),
        ) {
            // ★ 把 Dialog 窗口背景改为不透明黑。
            // Compose Dialog 创建时会把窗口背景设为透明；图片 Fit 留白区、系统栏
            // 区域在未填充内容时会透出下层 Activity UI。窗口黑底保证这些区域始终纯黑。
            // 沿 parent 链向上查找 DialogWindowProvider（不同 compose 版本层级不同，
            // 只查直接父级可能静默失败），并给 decorView 再加一层黑底双保险。
            val view = LocalView.current
            LaunchedEffect(Unit) {
                val window = generateSequence(view.parent) { it.parent }
                    .filterIsInstance<DialogWindowProvider>()
                    .firstOrNull()
                    ?.window
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

                // ★ 顶部按钮行：屏幕旋转 + 关闭（同一行、右对齐、横向 8dp 间距）
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // ★ 屏幕旋转：翻转本地状态，用它驱动 requestedOrientation
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

    /**
     * 全屏视频播放（FitTextureView + MediaPlayer）：
     * 默认暂停、显示首帧（prepareAsync 完成后由 onInfoListener 停在 0 位）；
     * 点按画面播放/暂停；播放意图早于 prepare 时就绪后补播；切走自动暂停。
     * 底部控制条右侧提供静音按钮。
     */
    @Composable
    private fun VideoPlayer(url: String, active: Boolean, modifier: Modifier = Modifier) {
        val context = LocalContext.current

        // ★ 数据源决策：命中本地缓存走文件（预览阶段可能已下载完成），否则走 HTTP
        val localFile = remember(url) { VideoCache.cached(context, url) }
        LaunchedEffect(url) { VideoCache.preload(context, url) }

        var playing by remember { mutableStateOf(false) }
        var prepared by remember { mutableStateOf(false) }
        var player by remember { mutableStateOf<MediaPlayer?>(null) }
        var wantPlay by remember { mutableStateOf(false) }
        // ★ 静音状态：属于本播放器实例，两个附件页各自独立
        var muted by remember { mutableStateOf(true) }

        fun toggle() {
            val mp = player
            if (mp == null || !prepared) {
                wantPlay = !wantPlay
                return
            }
            if (playing) {
                runCatching { mp.pause() }
                playing = false
                wantPlay = false
            } else {
                runCatching { mp.start() }
                playing = true
                wantPlay = true
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
                playing = false
                wantPlay = false
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

                                    // ★ 视频尺寸就绪后回填，触发 letterbox 适配
                                    mp.setOnVideoSizeChangedListener { _, vw, vh ->
                                        setVideoSize(vw, vh)
                                    }

                                    // ★ 首帧渲染完成时，若用户尚未点播放，则暂停并回到 0 位
                                    mp.setOnInfoListener { m, what, _ ->
                                        if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                                            if (!wantPlay && !playing) {
                                                runCatching {
                                                    m.pause()
                                                    m.seekTo(0)
                                                }
                                            }
                                            true
                                        } else {
                                            false
                                        }
                                    }

                                    mp.setOnPreparedListener { m ->
                                        prepared = true
                                        // ★ 沿用当前静音状态
                                        val v = if (muted) 0f else 1f
                                        runCatching { m.setVolume(v, v) }
                                        if (wantPlay) {
                                            runCatching { m.start() }
                                            playing = true
                                        } else {
                                            // 未点播放：start 触发首帧渲染，
                                            // onInfoListener 会在首帧就绪后自动 pause + seekTo(0)
                                            runCatching { m.start() }
                                        }
                                    }
                                    mp.setOnCompletionListener {
                                        playing = false
                                        wantPlay = false
                                    }
                                    mp.setOnErrorListener { _, _, _ ->
                                        playing = false
                                        prepared = false
                                        wantPlay = false
                                        true
                                    }
                                    mp.prepareAsync()
                                }.onFailure {
                                    runCatching { mp.release() }
                                    player = null
                                    prepared = false
                                }
                            }

                            override fun onSurfaceTextureSizeChanged(
                                st: SurfaceTexture, w: Int, h: Int,
                            ) = Unit

                            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                player?.let { runCatching { it.release() } }
                                player = null
                                playing = false
                                prepared = false
                                wantPlay = false
                                return true
                            }

                            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            // 点按画面播放/暂停
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures(onTap = { toggle() }) },
            )

            // 未播放时的遮罩 + 播放按钮：
            //  · !prepared → 全黑遮罩 + 环形进度
            //  · prepared → 浅色遮罩，让首帧透出
            if (!playing) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            if (prepared) Color(0x33000000) else Color.Black,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    PlayOverlay(loading = wantPlay && !prepared)
                }
            }

            // ★ 底部控制条：播放/暂停 + 圆圈进度条（可拖动）+ 时间 + 静音
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
    // 全屏图片：双指缩放（1x~5x）+ 拖动平移 + 双击放大/还原
    // 1x 时手势交给外层 Pager 左右滑动；放大后拖动手势归图片平移
    // =============================================================================================

    @Composable
    private fun ZoomableImage(model: Any, modifier: Modifier = Modifier) {
        val scope = rememberCoroutineScope()
        // Animatable：手势中 snapTo 逐帧跟手，双击 animateTo 弹性动画
        val scale = remember { Animatable(1f) }
        val offset = remember { Animatable(Offset.Zero, Offset.VectorConverter) }

        BoxWithConstraints(
            modifier = modifier
                .clipToBounds()
                // ★ 常驻手势块（key = Unit，永不重启）：
                //    手势起始已放大，或本手势出现过捏合 → 消费事件归图片（缩放/平移）；
                //    否则不消费 → 事件透传给外层 Pager 左右切页。
                .pointerInput(Unit) {
                    awaitEachGesture {
                        // ★ requireUnconsumed = false 是关键：
                        //   同一节点上的 detectTapGestures 会先消费 down 事件，
                        //   若用默认值 true，此处永远等不到手势起点，
                        //   双指缩放 / 放大后拖动会全部失效。
                        awaitFirstDown(requireUnconsumed = false)
                        var takeover = scale.value > 1.001f
                        // 手势内的运行值：awaitEachGesture 是受限协程作用域，
                        // 不能直接调用 Animatable.snapTo（挂起函数），改用外层 scope.launch 执行
                        var curScale = scale.value
                        var curOffset = offset.value
                        do {
                            val event = awaitPointerEvent()
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            if (!takeover && zoomChange != 1f) takeover = true
                            if (takeover) {
                                // 消费本次变化，阻止 Pager 抢手势
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
                                // 不写泛型，让编译器在 scale(Float)/offset(Offset)
                                // 各自的调用点推断出正确类型
                                val stiffness = Spring.StiffnessMediumLow
                                if (scale.value > 1f) {
                                    // 还原
                                    scale.animateTo(1f, spring(stiffness = stiffness))
                                    offset.animateTo(Offset.Zero, spring(stiffness = stiffness))
                                } else {
                                    // 放大到 2.5x
                                    scale.animateTo(2.5f, spring(stiffness = stiffness))
                                }
                            }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            // 平移限位：不超过放大后多出的半幅
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
    // 圆圈进度条：白线 + 圆形滑块；按住圆圈/轨道拖动 seek，点击轨道直接跳转
    // =============================================================================================

    @Composable
    private fun CircleSeekBar(
        positionMs: Int,
        durationMs: Int,
        onDragProgress: (Int) -> Unit,   // 拖动中：实时回显（毫秒）
        onSeekCommit: (Int) -> Unit,     // 松手/点击：提交 seek（毫秒）
        modifier: Modifier = Modifier,
    ) {
        // <0 表示未在拖动；拖动期间进度由拖动位置决定，不被播放进度覆盖
        var dragFraction by remember { mutableFloatStateOf(-1f) }
        val fraction = if (dragFraction >= 0f) {
            dragFraction
        } else if (durationMs > 0) {
            (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        } else {
            0f
        }

        Box(
            modifier = modifier
                .height(32.dp)
                .padding(horizontal = 10.dp)   // 两端留白，保证圆圈滑块不被裁切
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
                // 底槽
                drawLine(
                    color = Color(0x66FFFFFF),
                    start = Offset(0f, cy),
                    end = Offset(w, cy),
                    strokeWidth = stroke,
                )
                // 已播放
                drawLine(
                    color = Color.White,
                    start = Offset(0f, cy),
                    end = Offset(x, cy),
                    strokeWidth = stroke,
                )
                // ★ 圆圈滑块（无竖线）
                drawCircle(
                    color = Color.White,
                    radius = thumbRadius,
                    center = Offset(x, cy),
                )
            }
        }
    }

    /**
     * 全屏底部控制条：
     *  [播放/暂停] [进度条] [时间] [静音]
     *
     * 静音按钮放在进度条右侧（时间之后），触摸区 48dp、图标 28dp（比默认 24dp 略大，
     * 便于点击）。按钮状态由外层 [muted] 驱动，回调交给 [onToggleMute]。
     */
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

        // 播放中轮询进度（拖动中不覆盖，由拖动回显接管）；
        // key = player，切页 / 重建时重启循环；MediaPlayer 非法状态下取值会抛异常，用 runCatching 防御
        LaunchedEffect(player) {
            while (true) {
                delay(500)
                val mp = player ?: continue
                if (!dragging) {
                    positionMs = runCatching { mp.currentPosition }
                        .getOrDefault(0).coerceAtLeast(0)
                    durationMs = runCatching { mp.duration }
                        .getOrDefault(0).coerceAtLeast(0)
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
            // 播放 / 暂停
            IconButton(onClick = onTogglePlay) {
                // 核心图标包无 Pause，用文字符号表达暂停，零额外依赖
                if (playing) {
                    Text(
                        text = "❚❚",
                        color = Color.White,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "播放",
                        tint = Color.White,
                    )
                }
            }
            // 进度条
            CircleSeekBar(
                positionMs = positionMs,
                durationMs = durationMs,
                onDragProgress = { ms ->
                    dragging = true
                    positionMs = ms   // 拖动时时间与滑块实时跟随
                },
                onSeekCommit = { ms ->
                    player?.let { runCatching { it.seekTo(ms) } }
                    positionMs = ms
                    dragging = false
                },
                modifier = Modifier.weight(1f),
            )
            // 时间
            Text(
                text = "${formatTime(positionMs)}/${formatTime(durationMs)}",
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 8.dp),
            )
            // ★ 静音：图标 28dp（默认 24dp 略大），触摸区 48dp；用状态驱动
            IconButton(
                onClick = onToggleMute,
                modifier = Modifier.padding(start = 4.dp),
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

    private fun formatTime(ms: Int): String {
        if (ms <= 0) return "00:00"
        val totalSec = ms / 1000
        return "%02d:%02d".format(totalSec / 60, totalSec % 60)
    }

    // =============================================================================================
    // 底部工具栏：高德导航 / 微信分享
    // =============================================================================================

    @Composable
    private fun SheetActionBar(feature: Feature, config: Config) {
        if (!config.showActions) return
        val latLng = config.position(feature) ?: return   // 无坐标不渲染工具栏
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
    // 辅助：从 Context 向上找 Activity
    // =============================================================================================

    /**
     * 从任意 Context 向上找 Activity。
     * Compose 里 LocalContext 常常是 ContextWrapper 包裹的 Activity，
     * 单次 `context as? Activity` 转换会漏，需要沿 baseContext 链上溯。
     */
    private fun findActivity(context: Context): Activity? {
        var c: Context? = context
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    /**
     * 唤起高德地图导航至 [lat],[lng]。
     * 已装高德 → amapuri 直达；未装 → 降级高德 Web 标记页（自带导航按钮）。
     */
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
            // 未安装高德 → Web 兜底
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

    /**
     * 分享位置到微信好友：文本 + 高德 Web 链接（callnative=1，
     * 好友点击链接可直接唤起高德导航）。
     * 已装微信 → 直达微信会话选择；未装 → 系统分享面板兜底。
     */
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