package org.kori.plugin.wildlife.ui

import android.content.Context
import android.content.Intent
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
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point
import kotlin.time.Duration.Companion.milliseconds

/**
 * 盗猎事件属性弹窗：底部弹窗 + 附件跑马灯 + 全屏浏览。
 *
 * 布局分流（折叠屏/平板适配）：
 *  · 窄屏（width < 600dp）：FlexibleBottomSheet 底部弹窗；
 *  · 宽屏（>= 600dp）：居中对话框，左右双栏 —— 左附件跑马灯、右属性列表。
 *
 * 附件行为：
 *  · 多附件：圆点指示器 + 页码角标 + 左右滑动 + 3s 自动轮播（打开全屏时暂停轮播）；
 *  · 图片：缩略图点击进全屏浏览（全屏支持双指缩放 / 双击放大 / 左右滑动切页）；
 *  · 视频：预览态点按画面播放/暂停，右上角"[ 全屏 ]"进入全屏播放；
 *  · 全屏播放：默认暂停，圆圈进度条（可拖动 seek）。
 *
 * 视频渲染：
 *  · 使用 TextureView + MediaPlayer（而不是 VideoView）——
 *    VideoView 内部是 SurfaceView，会在窗口上"打洞"独立合成，Compose 的覆盖层
 *    物理上盖不住，会出现"播放中透明、暂停时不透明"的穿透问题；
 *    TextureView 是普通 View，走正常窗口合成，谁后画谁在上，不再穿透。
 *  · 不再使用 setZOrderOnTop —— 它会把 surface 顶到窗口之上，是穿透的元凶。
 *  · 使用 FitTextureView 按视频宽高比做 letterbox 适配，避免全屏拉伸。
 *  · "未播放覆盖层"仅作为"尚未显示首帧"的视觉占位，不再是防穿透的救命稻草。
 *  · 全屏 Dialog 窗口仍强制不透明黑底（decorFitsSystemWindows=false + ColorDrawable(BLACK)），
 *    用于保证图片 Fit 留白区域与系统栏区域为纯黑。
 */
object IllegalEventAttrSheet {

    private const val IMG_BASE =
        "http://8.152.157.180/api/illegal/getimg?cmd=oop&rowid="

    // =========================================================================================
    // 展示总线（Compose 范式：状态驱动，谁组合谁渲染 —— 无需 Activity / ComposeView / holder）
    // 图层侧 show(feature) 发状态；UI 侧 collect currentFeature 声明式渲染 Content；
    // 图层 onDetach / UI onDismiss 调 dismiss()。单槽位，重复 show 直接替换。
    // =========================================================================================

    private val _currentFeature = MutableStateFlow<Feature?>(null)

    /** 当前待展示的要素（null = 无弹窗） */
    val currentFeature: StateFlow<Feature?> = _currentFeature.asStateFlow()

    /** 图层侧：请求展示属性弹窗 */
    fun show(feature: Feature) {
        _currentFeature.value = feature
    }

    /** 关闭弹窗（UI onDismiss / 图层 onDetach 调用） */
    fun dismiss() {
        _currentFeature.value = null
    }

    /** 属性字段展示配置（label ← properties key），附件元数据不参与展示 */
    private val ATTR_FIELD_MAP = linkedMapOf(
        "name" to "名称",
        "illegal" to "违法行为",
        "ani_type" to "动物类型",
        "prov" to "省份",
        "city" to "城市",
        "county" to "区县",
        "time" to "时间",
        "source" to "来源",
        "rowid" to "编号",
    )

    /** 构建属性行（label → value，按配置顺序；空值跳过） */
    fun buildFields(feature: Feature): List<Pair<String, String>> {
        val props = feature.properties() ?: return emptyList()
        val json = JSONObject(props.toString())
        return ATTR_FIELD_MAP.mapNotNull { (key, label) ->
            val value = json.opt(key)?.toString()?.takeIf { it.isNotBlank() }
            if (value == null) null else label to value
        }
    }

    /** 宽屏阈值（dp）：达到即视为折叠屏展开/平板 */
    private const val WIDE_SCREEN_WIDTH_DP = 600

    /** 宽屏对话框最大宽度 */
    private val WIDE_DIALOG_WIDTH = 720.dp

    /** 附件条目 */
    data class Attachment(val id: String, val type: String) {
        val url: String get() = IMG_BASE + id
        val isVideo: Boolean get() = type.lowercase() == "mp4"
    }

    /** 从 Feature properties 解析附件列表（无 att_ids/img_types 返回空） */
    fun parseAttachments(feature: Feature): List<Attachment> {
        val props = feature.properties() ?: return emptyList()
        val json = JSONObject(props.toString())
        val idsRaw = json.optString("att_ids", "").trim()
        val typesRaw = json.optString("img_types", "").trim()
        if (idsRaw.isEmpty() || typesRaw.isEmpty()) return emptyList()
        val ids = idsRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val types = typesRaw.split(",").map { it.trim() }
        // 按下标配对；img_types 缺失/少于 att_ids 的项默认按图片处理，不丢附件
        return ids.mapIndexed { index, id ->
            Attachment(id, types.getOrNull(index)?.takeIf { it.isNotEmpty() } ?: "jpg")
        }
    }

    /**
     * 常驻渲染宿主：任何包含它的组合都会响应 [show] 请求。
     *
     * 注意：全局只挂一处（通常 = 地图所在的常驻界面）。挂在临时的
     * WildLifeScreen 上会在其离开组合后无法渲染 —— 状态仍在，但没人展示。
     */
    @Composable
    fun Host() {
        val f by currentFeature.collectAsState()
        f?.let { feature ->
            Content(
                fields = buildFields(feature),
                attachments = parseAttachments(feature),
                feature = feature,
                onDismiss = { dismiss() },
            )
        }
    }

    /**
     * 属性弹窗入口：按屏幕宽度自动选择 底部弹窗 / 居中双栏对话框。
     */
    @Composable
    fun Content(
        fields: List<Pair<String, String>>,
        attachments: List<Attachment>,
        feature: Feature? = null,
        onDismiss: () -> Unit,
    ) {
        val isWide = LocalConfiguration.current.screenWidthDp >= WIDE_SCREEN_WIDTH_DP
        var viewerPage by remember { mutableStateOf<Int?>(null) }

        // 打开全屏浏览期间暂停底部跑马灯自动轮播
        val carouselAutoScroll = viewerPage == null

        if (isWide) {
            WideAttrDialog(
                fields = fields,
                attachments = attachments,
                feature = feature,
                onDismiss = onDismiss,
                onOpenViewer = { viewerPage = it },
                carouselAutoScroll = carouselAutoScroll,
            )
        } else {
            NarrowBottomSheet(
                fields = fields,
                attachments = attachments,
                feature = feature,
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
        fields: List<Pair<String, String>>,
        attachments: List<Attachment>,
        feature: Feature?,
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
                SheetActionBar(feature)
            }
        }
    }

    // =============================================================================================
    // 宽屏：居中双栏对话框（左附件 / 右属性）
    // =============================================================================================

    @Composable
    private fun WideAttrDialog(
        fields: List<Pair<String, String>>,
        attachments: List<Attachment>,
        feature: Feature?,
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
                                SheetActionBar(feature)
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
    private fun AttrFieldList(fields: List<Pair<String, String>>, modifier: Modifier = Modifier) {
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
                        // 视频：预览态可播放，"[ 全屏 ]"进全屏
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
                    Text(
                        text = "${pagerState.currentPage + 1}/${attachments.size}",
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
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
    // 视频渲染：FitTextureView —— 按视频宽高比 letterbox 适配的 TextureView
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
    // 预览态视频播放（FitTextureView + MediaPlayer）：点按画面播放/暂停；右上角"[ 全屏 ]"
    // =============================================================================================

    @Composable
    private fun PreviewVideoPlayer(
        url: String,
        active: Boolean,
        onOpenFullscreen: () -> Unit,
        modifier: Modifier = Modifier,
    ) {
        var playing by remember { mutableStateOf(false) }
        var prepared by remember { mutableStateOf(false) }
        var player by remember { mutableStateOf<MediaPlayer?>(null) }
        // 播放意图：用户可能在 prepare 完成前就点了播放，记下来，onPrepared 补播
        var wantPlay by remember { mutableStateOf(false) }

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
                                    mp.setDataSource(url)
                                    mp.setSurface(Surface(st))

                                    // ★ 视频尺寸就绪后回填，触发 letterbox 适配
                                    mp.setOnVideoSizeChangedListener { _, vw, vh ->
                                        setVideoSize(vw, vh)
                                    }

                                    mp.setOnPreparedListener { m ->
                                        prepared = true
                                        if (wantPlay) {
                                            runCatching { m.start() }
                                            playing = true
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

            // 未播放时的视觉遮罩 + 播放按钮（TextureView 无渲染孔，这里纯视觉）
            if (!playing) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0xFF1A1A1A)),
                    contentAlignment = Alignment.Center,
                ) {
                    PlayOverlay()
                }
            }

            // ★ "[ 全屏 ]" 小提示：点击暂停预览并进入全屏播放
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
        }
    }

    @Composable
    private fun PlayOverlay(modifier: Modifier = Modifier) {
        Box(
            modifier = modifier
                .size(56.dp)
                .background(Color(0x99000000), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = "播放",
                tint = Color.White,
                modifier = Modifier.size(36.dp),
            )
        }
    }

    // =============================================================================================
    // 全屏浏览（图片 + 视频；窗口级纯黑兜底，图片外区域绝不为透明）
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

                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(12.dp)
                        .background(Color(0x66000000), CircleShape),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Color.White)
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
     * 默认暂停（显示播放按钮），点按画面播放/暂停；
     * 黑底兜底未首帧；播放意图早于 prepare 时就绪后补播；切走自动暂停。
     */
    @Composable
    private fun VideoPlayer(url: String, active: Boolean, modifier: Modifier = Modifier) {
        var playing by remember { mutableStateOf(false) }
        var prepared by remember { mutableStateOf(false) }
        var player by remember { mutableStateOf<MediaPlayer?>(null) }
        var wantPlay by remember { mutableStateOf(false) }

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
                                    mp.setDataSource(url)
                                    mp.setSurface(Surface(st))

                                    // ★ 视频尺寸就绪后回填，触发 letterbox 适配
                                    mp.setOnVideoSizeChangedListener { _, vw, vh ->
                                        setVideoSize(vw, vh)
                                    }

                                    mp.setOnPreparedListener { m ->
                                        prepared = true
                                        if (wantPlay) {
                                            runCatching { m.start() }
                                            playing = true
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

            // 未播放时的黑底遮罩 + 播放按钮
            if (!playing) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    PlayOverlay()
                }
            }

            // ★ 底部控制条：播放/暂停 + 圆圈进度条（可拖动）+ 时间
            VideoControls(
                player = player,
                playing = playing,
                onTogglePlay = ::toggle,
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

    @Composable
    private fun VideoControls(
        player: MediaPlayer?,
        playing: Boolean,
        onTogglePlay: () -> Unit,
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
            Text(
                text = "${formatTime(positionMs)}/${formatTime(durationMs)}",
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 8.dp),
            )
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

    /** 从要素几何取坐标（非点要素返回 null） */
    private fun featureLatLng(feature: Feature?): Pair<Double, Double>? =
        (feature?.geometry() as? Point)?.let { it.latitude() to it.longitude() }

    /** 要素显示名（properties.name，缺省"目标点"） */
    private fun featureName(feature: Feature?): String {
        val props = feature?.properties() ?: return "目标点"
        return runCatching {
            JSONObject(props.toString()).optString("name").ifBlank { "目标点" }
        }.getOrDefault("目标点")
    }

    @Composable
    private fun SheetActionBar(feature: Feature?) {
        val latLng = featureLatLng(feature) ?: return   // 无坐标不渲染工具栏
        val context = LocalContext.current
        val name = featureName(feature)

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