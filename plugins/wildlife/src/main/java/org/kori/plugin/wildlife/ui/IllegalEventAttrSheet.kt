package org.kori.plugin.wildlife.layers

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.maplibre.geojson.Feature
import org.json.JSONObject
import android.widget.VideoView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.collectAsState
import org.cwcc.open.geokori.ui.material3.bottomsheet.FlexibleBottomSheet
import org.cwcc.open.geokori.ui.material3.bottomsheet.core.rememberFlexibleBottomSheetState
import kotlin.time.Duration.Companion.milliseconds

/**
 * 盗猎事件属性弹窗：底部弹窗 + 附件跑马灯 + 全屏浏览。
 *
 * 布局分流（折叠屏/平板适配）：
 *  · 窄屏（width < 600dp，手机/折叠屏合盖）：FlexibleBottomSheet 底部弹窗；
 *  · 宽屏（>= 600dp，折叠屏展开/平板）：居中对话框，左右双栏 ——
 *    左附件跑马灯、右属性列表，避免宽屏底部弹窗被横向拉垮。
 *
 * 数据来源：服务端 properties —
 *  · 常规字段按传入的 [fields]（label → value 有序对）展示；
 *  · 附件：att_ids 与 img_types 按下标一一配对（长度不一致按短侧截断），
 *    任一缺失视为无附件；
 *  · 附件地址：http://8.152.157.180/api/illegal/getimg?cmd=oop&rowid={att_id}
 *  · img_types 取值 jpg/png/webp 等按图片，mp4 按视频（默认不播放，点击全屏）。
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

    private val HIDDEN_FIELDS = setOf("att_ids", "img_types")

    /** 构建属性行（label → value，按配置顺序；隐藏附件元数据） */
    fun buildFields(feature: Feature): List<Pair<String, String>> {
        val props = feature.properties() ?: return emptyList()
        val json = JSONObject(props.toString())
        return ATTR_FIELD_MAP.mapNotNull { (key, label) ->
            if (key in HIDDEN_FIELDS || !json.has(key)) null
            else label to json.opt(key)?.toString().orEmpty()
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
        onDismiss: () -> Unit,
    ) {
        val isWide = LocalConfiguration.current.screenWidthDp >= WIDE_SCREEN_WIDTH_DP
        var viewerPage by remember { mutableStateOf<Int?>(null) }

        if (isWide) {
            WideAttrDialog(
                fields = fields,
                attachments = attachments,
                onDismiss = onDismiss,
                onOpenViewer = { viewerPage = it },
            )
        } else {
            NarrowBottomSheet(
                fields = fields,
                attachments = attachments,
                onDismiss = onDismiss,
                onOpenViewer = { viewerPage = it },
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
        onDismiss: () -> Unit,
        onOpenViewer: (Int) -> Unit,
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
                    AttachmentCarousel(attachments, onClick = onOpenViewer)
                    Spacer(Modifier.height(12.dp))
                }
                AttrFieldList(fields, Modifier.fillMaxWidth())
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
        onDismiss: () -> Unit,
        onOpenViewer: (Int) -> Unit,
    ) {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x66000000))
                    .clickable(onClick = onDismiss),   // 点遮罩关闭
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    modifier = Modifier
                        .width(WIDE_DIALOG_WIDTH)
                        .padding(24.dp)
                        .clickable(enabled = false) {},  // 拦截穿透
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
                                    AttachmentCarousel(attachments, onClick = onOpenViewer)
                                }
                                Spacer(Modifier.width(16.dp))
                            }
                            AttrFieldList(
                                fields = fields,
                                modifier = Modifier
                                    .weight(1f)
                                    .verticalScroll(rememberScrollState()),
                            )
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
    // 附件跑马灯
    // =============================================================================================

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun AttachmentCarousel(
        attachments: List<Attachment>,
        onClick: (Int) -> Unit,
    ) {
        val pagerState = rememberPagerState(pageCount = { attachments.size })

        // 多附件 3s 自动轮播（跑马灯）
        if (attachments.size > 1) {
            LaunchedEffect(pagerState.currentPage) {
                delay(3000.milliseconds)
                val next = (pagerState.currentPage + 1) % attachments.size
                pagerState.animateScrollToPage(next)
            }
        }

        Box(modifier = Modifier.fillMaxWidth()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(12.dp)),
            ) { page ->
                AttachmentThumb(
                    attachment = attachments[page],
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { onClick(page) },
                )
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
    }

    /** 缩略图：图片 Coil 加载；视频显示占位 + 播放按钮（不预载视频） */
    @Composable
    private fun AttachmentThumb(attachment: Attachment, modifier: Modifier = Modifier) {
        Box(modifier = modifier.background(Color(0xFF1A1A1A))) {
            if (attachment.isVideo) {
                Box(Modifier.fillMaxSize().background(Color(0xFF262626)))
            } else {
                AsyncImage(
                    model = attachment.url,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            if (attachment.isVideo) {
                PlayOverlay(Modifier.align(Alignment.Center))
            }
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
    // 全屏浏览（图片 + 视频；黑底，不存在未播放透明问题）
    // =============================================================================================

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun FullscreenViewer(
        attachments: List<Attachment>,
        initialPage: Int,
        onClose: () -> Unit,
    ) {
        val pagerState = rememberPagerState(
            initialPage = initialPage.coerceIn(0, attachments.lastIndex),
            pageCount = { attachments.size },
        )

        Dialog(
            onDismissRequest = onClose,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
            ) {
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                    val att = attachments[page]
                    if (att.isVideo) {
                        val selected = pagerState.currentPage == page
                        VideoPlayer(
                            url = att.url,
                            active = selected,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        AsyncImage(
                            model = att.url,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    }
                }

                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
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
     * 全屏视频播放：默认暂停（显示播放按钮），点击画面播放/暂停。
     * 黑底兜底未首帧透明；播放意图早于 prepare 时就绪后补播；切走自动暂停。
     */
    @Composable
    private fun VideoPlayer(url: String, active: Boolean, modifier: Modifier = Modifier) {
        var playing by remember { mutableStateOf(false) }
        var videoView by remember { mutableStateOf<VideoView?>(null) }

        LaunchedEffect(active) {
            if (!active) {
                videoView?.pause()
                playing = false
            }
        }

        Box(
            modifier = modifier.background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        setVideoPath(url)
                        setOnPreparedListener { if (playing) it.start() }
                        setOnCompletionListener { playing = false }
                        videoView = this
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { vv ->
                    if (!playing) vv.pause() else vv.start()
                },
            )

            if (!playing) {
                PlayOverlay(
                    Modifier
                        .align(Alignment.Center)
                        .clickable {
                            videoView?.let {
                                if (it.isPlaying) {
                                    it.pause()
                                    playing = false
                                } else {
                                    it.start()
                                    playing = true
                                }
                            }
                        },
                )
            }
        }

        DisposableEffect(Unit) {
            onDispose { videoView?.stopPlayback() }
        }
    }
}