package org.cwcc.open.geokori.ui.material3.center

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import org.cwcc.open.geokori.broadcasts.MapClickBroadCastConst
import org.cwcc.open.geokori.ui.material3.bottomsheet.BottomSheetDefaults
import org.cwcc.open.geokori.ui.material3.bottomsheet.FlexibleBottomSheet
import org.cwcc.open.geokori.ui.material3.bottomsheet.core.FlexibleSheetState
import org.cwcc.open.geokori.ui.material3.bottomsheet.core.FlexibleSheetValue
import org.cwcc.open.geokori.ui.material3.bottomsheet.core.screenHeight
import org.cwcc.open.geokori.ui.material3.center.model.CollapsedQuickActions
import org.cwcc.open.geokori.ui.material3.center.model.ExpandedQuickActions
import org.cwcc.open.geokori.ui.material3.center.model.PoiDetailCardV2
import org.cwcc.open.geokori.ui.material3.center.model.PoiItem
import org.cwcc.open.geokori.ui.material3.center.model.PoiListItemV2
import org.cwcc.open.geokori.ui.material3.center.model.QuickActionSpec
import org.cwcc.open.geokori.ui.material3.center.model.SearchHeaderV2
import androidx.compose.animation.core.animateDpAsState

/**
 * ToolBar 相对于 Sheet 的位置
 */
enum class ToolbarPosition {
    /** ToolBar 在屏幕底部，Sheet 向上展开 */
    Bottom,
    /** ToolBar 在屏幕顶部，Sheet 向下展开 */
    Top
}

/**
 * GeoKori 中心整合组件：将 ToolBar 与 Sheet 统一管理，支持双向展开。
 *
 * 核心特性：
 * 1. ToolBar 与 Sheet 作为整体同步，可通过广播或外部参数控制显隐。
 * 2. 支持 ToolBar 位于底部（Sheet 向上展开）或顶部（Sheet 向下展开）。
 * 3. Sheet 支持三档展开（微展开 / 中度展开 / 全展开）。
 * 4. ToolBar 宽度、位置及 Sheet 宽度、位置均可独立定制。
 * 5. 选中 POI 时 Sheet 自动微展开，并弹出详情卡片。
 * 6. 底部模式下，主面板下拉到最底部后继续下拉，整个组件（Sheet + ToolBar）
 *    一起滑出屏幕并隐藏，仅保留一个小拖动栏；向上拖动（或点击）小拖动栏可整体恢复。
 * 7. ★ quickActions 参数类型放宽为 [QuickActionSpec]——插件自定义 Action
 *    （如 wildlife 的 WfBizAction）可直接接入，不再局限于框架 QuickAction。
 * 8. ★ 底部模式 + Sheet 已 Hidden 时，ToolBar 上滑唤出 Sheet，下滑隐藏整个组件；
 *    下滑判定同时支持距离阈值与速度阈值，手感与上滑一致。
 */
@Composable
fun GeoKoriCenter(
    modifier: Modifier = Modifier,
    toolbarPosition: ToolbarPosition = ToolbarPosition.Bottom,
    sheetState: FlexibleSheetState = rememberGeoKoriSheetState(),
    selectedToolbarItemId: String? = null,
    onToolbarItemSelected: (BottomToolbarItem) -> Unit = {},
    isVisible: Boolean = true,
    autoHideOnMapClick: Boolean = true,
    syncToolbarWithSheet: Boolean = true,
    shouldCollapseSheetOnPoiSelect: Boolean = true,
    toolbarWidth: Dp? = null,
    toolbarHorizontalAlignment: Alignment.Horizontal? = null,
    sheetWidth: Dp? = null,
    sheetHorizontalAlignment: Alignment.Horizontal? = null,
    enableDragToFullyHide: Boolean = true,
    destination: Any? = null,
    isDestinationSheetVisible: Boolean = false,
    destinationContent: @Composable () -> Unit = {},
    onPoiSelected: (PoiItem) -> Unit = {},
    onPoiNavigate: (PoiItem) -> Unit = {},
    onQuickActionClick: (QuickActionSpec) -> Unit = {},
    onPoiDetailClose: () -> Unit = {},
    onSheetDismiss: () -> Unit = {},
    onVisibilityChanged: ((Boolean) -> Unit)? = null,
    onToolbarHeightChanged: ((Dp) -> Unit)? = null,
    onFullyHiddenChanged: ((Boolean) -> Unit)? = null,
    /**
     * ★ 轨迹界面联动信号（录制 HUD / 回放 / 时间线 / 历史 / 卫星状态等全屏浮层是否激活）。
     *
     * 由调用方订阅 [org.kori.plugin.geo.track.TrackRecordingEngine.state] 及
     * 各浮层状态后传入（true = 有浮层覆盖）。
     *
     * 行为：
     *  · 信号 false → true：**只隐藏 Sheet**（ToolBar 保持可见），并记录"由本信号隐藏"
     *  · 信号 true → false：若 Sheet 仍隐藏且是**本信号**隐藏的 →
     *    恢复到 [FlexibleSheetValue.SlightlyExpanded]（轻度展开）
     *
     * 用专用通道而不是 isVisible 的原因：isVisible 隐藏的是整个组件（ToolBar 也没了），
     * 且依赖外部记得翻转；本通道自恢复，杜绝"录完 Sheet 呼不出"。
     */
    trackOverlayActive: Boolean = false,
    toolbarItems: List<BottomToolbarItem> = defaultToolbarItems(),
    quickActions: List<QuickActionSpec> = defaultQuickActions(),
    poiList: List<PoiItem> = defaultPoiList(),
) {
    val density = LocalDensity.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val windowInfo = LocalWindowInfo.current
    val containerWidthDp = with(density) { windowInfo.containerSize.width.toDp() }

    /* ---------- 屏幕适配 ---------- */
    val isWideScreen by remember(containerWidthDp) {
        derivedStateOf { containerWidthDp >= 600.dp }
    }

    // 统一 Sheet 和 ToolBar 的宽度和对齐方式
    val unifiedWidth = sheetWidth ?: toolbarWidth ?: when {
        isWideScreen -> containerWidthDp / 2
        else -> null
    }
    val unifiedAlignment = sheetHorizontalAlignment ?: toolbarHorizontalAlignment ?: when {
        isWideScreen -> Alignment.Start
        else -> Alignment.CenterHorizontally
    }

    val adaptiveSheetWidth = sheetWidth ?: unifiedWidth
    val adaptiveSheetAlignment = sheetHorizontalAlignment ?: unifiedAlignment
    val adaptiveToolbarWidth = toolbarWidth ?: unifiedWidth
    val adaptiveToolbarAlignment = toolbarHorizontalAlignment ?: unifiedAlignment

    /* ---------- 组件整体可见性状态（统一控制 ToolBar + Sheet） ---------- */
    var internalVisible by remember { mutableStateOf(isVisible) }
    LaunchedEffect(isVisible) {
        internalVisible = isVisible
        onVisibilityChanged?.invoke(internalVisible)
    }
    var sheetValueBeforeHide by remember { mutableStateOf<FlexibleSheetValue?>(null) }

    // 标记：接下来的 Sheet 隐藏是代码主动触发（点 ToolBar / 广播 / 返回键），
    // 不应联动整体隐藏；只有用户手动把 Sheet 拖到底（自然 settle 到 Hidden）才整体隐藏。
    var expectSheetHide by remember { mutableStateOf(false) }

    /* ---------- 广播监听：地图点击切换显隐 ---------- */
    val receiver = remember {
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    MapClickBroadCastConst.ACTION_MAP_CLICK -> {
                        if (autoHideOnMapClick) {
                            internalVisible = !internalVisible
                        }
                    }
                }
            }
        }
    }

    DisposableEffect(context, autoHideOnMapClick) {
        if (autoHideOnMapClick) {
            val filter = IntentFilter().apply {
                addAction(MapClickBroadCastConst.ACTION_MAP_CLICK)
            }
            ContextCompat.registerReceiver(
                context,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            onDispose {
                try {
                    context.unregisterReceiver(receiver)
                } catch (_: Exception) { }
            }
        } else {
            onDispose { }
        }
    }
    /* ---------- 可见性变化时同步控制 Sheet 的 hide / show ---------- */
    LaunchedEffect(internalVisible) {
        if (internalVisible) {
            if (sheetState.currentValue == FlexibleSheetValue.Hidden) {
                sheetValueBeforeHide = null
                // 显式动画恢复到微展开档位，避免 trySnapTo 静默失败
                sheetState.animateTo(FlexibleSheetValue.SlightlyExpanded)
            }
        } else {
            if (sheetState.currentValue != FlexibleSheetValue.Hidden) {
                sheetValueBeforeHide = sheetState.currentValue
                expectSheetHide = true
                sheetState.hide()
            }
        }
    }

    /* ---------- ToolBar 状态（与 Sheet 档位同步） ---------- */
// Sheet 处于中/全展开 → ToolBar 完整展开（72dp，图标+文字）
// Sheet 微展开/隐藏   → ToolBar 轻度展开（56dp，仅图标，减少对地图的遮挡）
    val toolbarExpanded by remember(syncToolbarWithSheet) {
        derivedStateOf {
            !syncToolbarWithSheet || when (sheetState.currentValue) {
                FlexibleSheetValue.IntermediatelyExpanded,
                FlexibleSheetValue.FullyExpanded -> true
                else -> false   // SlightlyExpanded / Hidden → 轻度展开
            }
        }
    }
    // 关键：Sheet 的留白与 ToolBar 共用同一份「动画中」的高度，
    // 保证两者在动画过程中逐帧对齐，不会一快一慢
    val toolbarHeight by animateDpAsState(
        targetValue = if (toolbarExpanded) 72.dp else 56.dp,
        animationSpec = tween(300),
        label = "toolbar_height_sync"
    )

    LaunchedEffect(toolbarHeight) {
        onToolbarHeightChanged?.invoke(toolbarHeight)
    }

    var currentSelectedToolbarId by remember {
        mutableStateOf(
            selectedToolbarItemId ?: toolbarItems.firstOrNull { it.isSelected }?.id ?: ""
        )
    }

    selectedToolbarItemId?.let { id ->
        if (id != currentSelectedToolbarId) {
            currentSelectedToolbarId = id
        }
    }

    /* ---------- POI 状态 ---------- */
    var selectedPoi by remember { mutableStateOf<PoiItem?>(null) }
    var previousSheetValue by remember { mutableStateOf<FlexibleSheetValue?>(null) }

    LaunchedEffect(selectedPoi) {
        if (shouldCollapseSheetOnPoiSelect && internalVisible) {
            if (selectedPoi != null) {
                if (previousSheetValue == null) {
                    previousSheetValue = sheetState.currentValue
                }
                if (sheetState.currentValue != FlexibleSheetValue.SlightlyExpanded) {
                    sheetState.slightlyExpand()
                }
            } else {
                previousSheetValue?.let { prev ->
                    if (sheetState.currentValue != prev) {
                        sheetState.animateTo(prev)
                    }
                    previousSheetValue = null
                }
            }
        }
    }

    /* ================================================================ */
    /* ========== 整体下拉隐藏（Sheet + ToolBar 一起滑出） ========== */
    /* ================================================================ */
    // 仅底部模式 + 开关打开时启用
    val dragToHideEnabled = enableDragToFullyHide && toolbarPosition == ToolbarPosition.Bottom

    var fullyHidden by remember { mutableStateOf(false) }
    // 隐藏前记住 Sheet 档位，恢复时还原
    var sheetValueBeforeFullHide by remember { mutableStateOf<FlexibleSheetValue?>(null) }

    LaunchedEffect(fullyHidden) {
        onFullyHiddenChanged?.invoke(fullyHidden)
        if (fullyHidden) {
            sheetValueBeforeFullHide = sheetState.currentValue
        }
    }

    // 监听 Sheet 档位：用户手动下拉到底（Hidden）时，整体滑出隐藏
    LaunchedEffect(sheetState.currentValue) {
        if (sheetState.currentValue == FlexibleSheetValue.Hidden &&
            dragToHideEnabled && internalVisible && !expectSheetHide
        ) {
            fullyHidden = true
        }
        expectSheetHide = false
    }

    // 外部强制隐藏/显示时，重置下拉隐藏状态，避免状态错乱
    LaunchedEffect(internalVisible) {
        if (!internalVisible) fullyHidden = false
    }

    /* ================================================================ */
    /* ========== ★ 轨迹界面联动：浮层激活隐藏 Sheet，结束后恢复轻度展开 ========== */
    /* ================================================================ */
    // 记录"经历过轨迹浮层激活"，用于退出时恢复
    var wasTrackOverlayActive by remember { mutableStateOf(false) }

    // ★ ToolBar 上拉唤出 Sheet / 下拉隐藏组件：拖动累计量（px）+ 触发阈值
    var toolbarDragAcc by remember { mutableFloatStateOf(0f) }
    // 上滑唤出 Sheet 的阈值（保持原值）
    val toolbarRevealThresholdPx = with(density) { 48.dp.toPx() }
    // ★ 下滑隐藏组件的阈值：ToolBar 高度仅 56dp，48dp 太苛刻 → 降到 20dp
    val toolbarHideThresholdPx = with(density) { 20.dp.toPx() }
    // ★ 下滑速度阈值（px/s）：快速下滑即使距离不足也触发
    val toolbarHideVelocityPx = with(density) { 800.dp.toPx() }


    // ★ key 同时观察 currentValue：隐藏动画 settle 到 Hidden 的瞬间会再触发本 effect，
    //   杜绝"flag 已消费但 Sheet 尚未 Hidden / 状态未到位的竞态"
    LaunchedEffect(trackOverlayActive, sheetState.currentValue) {
        if (trackOverlayActive) {
            wasTrackOverlayActive = true
            // 浮层激活：仅藏 Sheet，ToolBar 保持
            if (sheetState.currentValue != FlexibleSheetValue.Hidden) {
                expectSheetHide = true
                sheetState.hide()
            }
        } else if (wasTrackOverlayActive &&
            (sheetState.currentValue == FlexibleSheetValue.Hidden || !sheetState.isVisible)
        ) {
            wasTrackOverlayActive = false
            // ★ 浮层全部退出（如结束轨迹记录）：恢复 Sheet 到轻度展开（启动默认态）。
            //   不追究 Sheet 是谁藏的（本信号或外部旧逻辑）——隐藏即恢复。
            android.util.Log.d(
                "GeoKoriCenter",
                "[trackOverlay] restoring sheet: currentValue=${sheetState.currentValue}, isVisible=${sheetState.isVisible}",
            )
            try {
                // 与"显式动画恢复"同路径，避免 trySnapTo 静默失败
                sheetState.animateTo(FlexibleSheetValue.SlightlyExpanded)
            } catch (e: Throwable) {
                // 兜底：部分 FlexibleSheetState 实现 hide() 后置 isVisible=false，
                // animateTo 依赖可见性——改走 show()（内部会处理可见性 + 展开）
                android.util.Log.w("GeoKoriCenter", "[trackOverlay] animateTo failed: ${e.message}, fallback show()")
                runCatching { sheetState.show() }
            }
        }
    }

    val screenHeightDp = screenHeight()
    val sheetVisibleHeight: Dp = when (sheetState.currentValue) {
        FlexibleSheetValue.Hidden -> 0.dp
        FlexibleSheetValue.SlightlyExpanded ->
            screenHeightDp * sheetState.flexibleSheetSize.slightlyExpanded
        FlexibleSheetValue.IntermediatelyExpanded ->
            screenHeightDp * sheetState.flexibleSheetSize.intermediatelyExpanded
        FlexibleSheetValue.FullyExpanded ->
            screenHeightDp * sheetState.flexibleSheetSize.fullyExpanded
    }
    // 整体滑出距离 = 当前可见的 Sheet 高度 + ToolBar 高度 + 一点余量
    val hideDistancePx = with(density) {
        (sheetVisibleHeight + toolbarHeight + 32.dp).toPx()
    }
    val contentOffsetPx by animateFloatAsState(
        targetValue = if (fullyHidden) hideDistancePx else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "center_fully_hide_slide"
    )

    // 恢复：整体滑回，Sheet 显式恢复到微展开档位
    fun revealCenter() {
        fullyHidden = false
        sheetValueBeforeFullHide = null
        scope.launch {
            sheetState.animateTo(FlexibleSheetValue.SlightlyExpanded)
        }
    }

    /* ---------- 返回键（顶部模式需自行处理） ---------- */
    if (toolbarPosition == ToolbarPosition.Top && !sheetState.skipHiddenState) {
        BackHandler {
            val current = sheetState.currentValue
            when {
                current == FlexibleSheetValue.FullyExpanded && sheetState.hasIntermediatelyExpandedState -> {
                    scope.launch { sheetState.intermediatelyExpand() }
                }
                current == FlexibleSheetValue.IntermediatelyExpanded && sheetState.hasSlightlyExpandedState -> {
                    scope.launch { sheetState.slightlyExpand() }
                }
                else -> {
                    expectSheetHide = true
                    scope.launch { sheetState.hide() }.invokeOnCompletion { onSheetDismiss() }
                }
            }
        }
    }

    // 统一的 ToolBar 点击处理函数
    fun handleToolbarItemSelected(item: BottomToolbarItem) {
        currentSelectedToolbarId = item.id
        onToolbarItemSelected(item)
        // 点击 ToolBar 按钮时隐藏 Sheet（代码主动隐藏，不联动整体滑出）
        if (sheetState.currentValue != FlexibleSheetValue.Hidden) {
            expectSheetHide = true
            scope.launch {
                sheetState.hide()
            }
        }
    }
    // ⭐ 主内容：ToolBar + Sheet（仅在 internalVisible 为 true 时渲染）
    if (internalVisible) {
        // 注意：整个 Box 上不再挂任何 pointerInput，保证底下地图可正常触摸
        Box(
            modifier = modifier
                .fillMaxSize()
                // 整体滑出/滑入的位移
                .offset { IntOffset(0, contentOffsetPx.toInt()) }
        ) {
            when (toolbarPosition) {
                ToolbarPosition.Bottom -> {
                    /* ===== Sheet 容器：底部对齐，底部留出 toolbar 高度 ===== */
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .padding(bottom = toolbarHeight)
                    ) {
                        FlexibleBottomSheet(
                            sheetState = sheetState,
                            containerColor = Color.White,
                            onDismissRequest = onSheetDismiss,
                            dragHandle = null,
                            windowInsets = WindowInsets.systemBars,
                            sheetWidth = adaptiveSheetWidth,
                            sheetHorizontalAlignment = adaptiveSheetAlignment,
                        ) {
                            SheetContentHost(
                                sheetState = sheetState,
                                isDestinationSheetVisible = isDestinationSheetVisible,
                                destination = destination,
                                destinationContent = destinationContent,
                                quickActions = quickActions,
                                poiList = poiList,
                                onPoiClick = { poi ->
                                    selectedPoi = poi
                                    onPoiSelected(poi)
                                },
                                onQuickActionClick = onQuickActionClick,
                            )
                        }
                    }

                    /* ===== ToolBar（与 Sheet 保持相同的对齐方式和宽度） ===== */
                    GeoKoriCenterToolBar(
                        modifier = Modifier
                            .align(
                                when (adaptiveToolbarAlignment) {
                                    Alignment.Start -> Alignment.BottomStart
                                    Alignment.End -> Alignment.BottomEnd
                                    else -> Alignment.BottomCenter
                                }
                            )
                            .then(
                                if (adaptiveToolbarWidth != null) Modifier.width(adaptiveToolbarWidth)
                                else Modifier.fillMaxWidth()
                            )
                            // ★ Sheet 隐藏时，ToolBar 区域上滑 → 唤出轻度展开；
                            //   下滑 → 整体隐藏（距离或速度任一达标即触发，手感与上滑一致）。
                            .draggable(
                                state = rememberDraggableState { d -> toolbarDragAcc += -d },
                                orientation = Orientation.Vertical,
                                enabled = sheetState.currentValue == FlexibleSheetValue.Hidden,
                                onDragStopped = { velocity ->
                                    // draggable 的 velocity 方向与手势同向：
                                    //   向下滑动 → velocity > 0
                                    //   向上滑动 → velocity < 0
                                    val slidingDown = velocity > toolbarHideVelocityPx
                                    val slidingUp = velocity < -toolbarHideVelocityPx

                                    when {
                                        // 上滑：距离或速度任一达标 → 唤出 Sheet
                                        sheetState.currentValue == FlexibleSheetValue.Hidden &&
                                                (toolbarDragAcc > toolbarRevealThresholdPx || slidingUp) -> {
                                            scope.launch { sheetState.slightlyExpand() }
                                        }
                                        // 下滑：距离或速度任一达标 → 整体隐藏
                                        sheetState.currentValue == FlexibleSheetValue.Hidden &&
                                                dragToHideEnabled &&
                                                (toolbarDragAcc < -toolbarHideThresholdPx || slidingDown) -> {
                                            fullyHidden = true
                                        }
                                    }
                                    toolbarDragAcc = 0f
                                },
                            ),
                        items = toolbarItems,
                        selectedItemId = currentSelectedToolbarId,
                        onItemSelected = ::handleToolbarItemSelected,
                        isExpanded = toolbarExpanded,
                        toolbarWidth = adaptiveToolbarWidth,
                        toolbarHorizontalAlignment = adaptiveToolbarAlignment,
                        isVisible = internalVisible,
                        autoHideOnMapClick = false,
                    )
                }

                ToolbarPosition.Top -> {
                    /* ===== ToolBar（与 Sheet 保持相同的对齐方式和宽度） ===== */
                    GeoKoriCenterToolBar(
                        modifier = Modifier
                            .align(
                                when (adaptiveToolbarAlignment) {
                                    Alignment.Start -> Alignment.TopStart
                                    Alignment.End -> Alignment.TopEnd
                                    else -> Alignment.TopCenter
                                }
                            )
                            .then(
                                if (adaptiveToolbarWidth != null) Modifier.width(adaptiveToolbarWidth)
                                else Modifier.fillMaxWidth()
                            )
                            // ★ Sheet 隐藏时，ToolBar 区域下拉（顶部模式向下展开）→ 唤出
                            .draggable(
                                state = rememberDraggableState { d -> toolbarDragAcc += d },
                                orientation = Orientation.Vertical,
                                enabled = sheetState.currentValue == FlexibleSheetValue.Hidden,
                                onDragStopped = {
                                    if (toolbarDragAcc > toolbarRevealThresholdPx &&
                                        sheetState.currentValue == FlexibleSheetValue.Hidden
                                    ) {
                                        scope.launch { sheetState.slightlyExpand() }
                                    }
                                    toolbarDragAcc = 0f
                                },
                            ),
                        items = toolbarItems,
                        selectedItemId = currentSelectedToolbarId,
                        onItemSelected = ::handleToolbarItemSelected,
                        isExpanded = toolbarExpanded,
                        toolbarWidth = adaptiveToolbarWidth,
                        toolbarHorizontalAlignment = adaptiveToolbarAlignment,
                        isVisible = internalVisible,
                        autoHideOnMapClick = false,
                    )

                    /* ===== Sheet 容器：顶部对齐，顶部留出 toolbar 高度 ===== */
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter)
                            .padding(top = toolbarHeight)
                    ) {
                        TopSheet(
                            sheetState = sheetState,
                            onDismissRequest = onSheetDismiss,
                            sheetWidth = adaptiveSheetWidth,
                            sheetHorizontalAlignment = adaptiveSheetAlignment,
                        ) {
                            SheetContentHost(
                                sheetState = sheetState,
                                isDestinationSheetVisible = isDestinationSheetVisible,
                                destination = destination,
                                destinationContent = destinationContent,
                                quickActions = quickActions,
                                poiList = poiList,
                                onPoiClick = { poi ->
                                    selectedPoi = poi
                                    onPoiSelected(poi)
                                },
                                onQuickActionClick = onQuickActionClick,
                            )
                        }
                    }
                }
            }

            /* ===== 整体隐藏后保留的小拖动栏：上拉或点击恢复 ===== */
            if (dragToHideEnabled && fullyHidden) {
                HiddenDragHandle(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        // 关键1：拖动栏位于随容器一起下滑的 Box 内部，
                        // 需要反向抵消整体滑出位移，才能停留在屏幕上
                        // 关键2：横向居中于 GeoKoriCenter 自身宽度范围内——
                        // 宽屏时 GeoKoriCenter 只占左/右半屏，拖动栏应跟随其宽度居中，
                        // 而不是贴到屏幕角落
                        .offset {
                            // align(BottomCenter) 已将拖动栏中心置于容器中心（W/2）。
                            // GeoKoriCenter 宽 w：
                            //   居左 → 区域 [0, w]，中心 w/2，dx = w/2 - W/2 = (w - W)/2
                            //   居右 → 区域 [W-w, W]，中心 W-w/2，dx = (W - w)/2
                            //   全宽/居中 → dx = 0（w = W 时两式也收敛为 0）
                            val dx = when (adaptiveToolbarAlignment) {
                                Alignment.Start ->
                                    adaptiveToolbarWidth?.let {
                                        with(density) { ((it - containerWidthDp) / 2).toPx() }.toInt()
                                    } ?: 0

                                Alignment.End ->
                                    adaptiveToolbarWidth?.let {
                                        with(density) { ((containerWidthDp - it) / 2).toPx() }.toInt()
                                    } ?: 0

                                else -> 0
                            }
                            IntOffset(dx, (-contentOffsetPx).toInt())
                        },
                    onReveal = ::revealCenter
                )
            }
        }
    }

    /* ================================================================ */
    /* ========== POI 详情弹窗（独立在最外层，不受 internalVisible 影响） ========== */
    /* ================================================================ */
    if (selectedPoi != null) {
        val cardWidth = (containerWidthDp * 0.85f).coerceAtMost(360.dp)

        Popup(
            alignment = Alignment.Center,
            properties = PopupProperties(
                focusable = true,
                dismissOnClickOutside = true,
                dismissOnBackPress = true,
            ),
            onDismissRequest = {
                selectedPoi = null
                onPoiDetailClose()
            }
        ) {
            var visible by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { visible = true }

            val offsetY by animateIntOffsetAsState(
                targetValue = if (visible) IntOffset(0, 0)
                else IntOffset(0, with(density) { 80.dp.toPx().toInt() }),
                animationSpec = spring(
                    dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy
                ),
                label = "poi_card_slide"
            )

            Box(
                modifier = Modifier
                    .width(cardWidth)
                    .offset { offsetY }
            ) {
                PoiDetailCardV2(
                    poi = selectedPoi!!,
                    onClose = {
                        selectedPoi = null
                        onPoiDetailClose()
                    },
                    onNavigate = {
                        selectedPoi?.let { onPoiNavigate(it) }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/* ==================== 小拖动栏（整体隐藏后保留） ==================== */

/**
 * 整体下拉隐藏后，悬浮在屏幕底部的小拖动栏。
 * 向上拖动超过阈值或点击均可恢复 [GeoKoriCenter]。
 */
@Composable
private fun HiddenDragHandle(
    modifier: Modifier = Modifier,
    onReveal: () -> Unit,
) {
    val density = LocalDensity.current
    val revealThresholdPx = with(density) { 48.dp.toPx() }
    var dragAcc by remember { mutableFloatStateOf(0f) }

    Box(modifier = modifier.padding(bottom = 12.dp)) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color.White.copy(alpha = 0.95f),
            shadowElevation = 4.dp,
        ) {
            Box(
                modifier = Modifier
                    .width(56.dp)
                    .height(28.dp)
                    .draggable(
                        state = rememberDraggableState { delta -> dragAcc += delta },
                        orientation = Orientation.Vertical,
                        onDragStopped = {
                            if (dragAcc < -revealThresholdPx) onReveal()
                            dragAcc = 0f
                        }
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onReveal
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowUp,
                    contentDescription = "展开",
                    tint = Color(0xFF5F6368),
                    modifier = Modifier.width(28.dp)
                )
            }
        }
    }
}

// ==================== 顶部 Sheet 实现（向下展开） ====================

/**
 * 从顶部向下展开的 Sheet，复用 [FlexibleSheetState] 但使用反向锚点。
 *
 * 锚点设计：
 * - Hidden: -fullyExpandedPx（完全位于容器上方隐藏）
 * - SlightlyExpanded: -(fullyExpandedPx - slightlyPx)
 * - IntermediatelyExpanded: -(fullyExpandedPx - intermediatelyPx)
 * - FullyExpanded: 0（完全显示，从容器顶部向下延伸）
 */
@Composable
private fun TopSheet(
    sheetState: FlexibleSheetState,
    onDismissRequest: () -> Unit = {},
    sheetWidth: Dp? = null,
    sheetHorizontalAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    content: @Composable ColumnScope.() -> Unit
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val screenHeight = screenHeight()

    val fullyExpandedPx = with(density) {
        (screenHeight * sheetState.flexibleSheetSize.fullyExpanded).toPx()
    }
    val intermediatelyPx = with(density) {
        (screenHeight * sheetState.flexibleSheetSize.intermediatelyExpanded).toPx()
    }
    val slightlyPx = with(density) {
        (screenHeight * sheetState.flexibleSheetSize.slightlyExpanded).toPx()
    }

    val anchors = remember(fullyExpandedPx, slightlyPx, intermediatelyPx) {
        mapOf(
            FlexibleSheetValue.Hidden to -fullyExpandedPx,
            FlexibleSheetValue.SlightlyExpanded to -(fullyExpandedPx - slightlyPx),
            FlexibleSheetValue.IntermediatelyExpanded to -(fullyExpandedPx - intermediatelyPx),
            FlexibleSheetValue.FullyExpanded to 0f
        )
    }

    LaunchedEffect(anchors) {
        val currentAnchors = sheetState.swipeableState.anchors
        if (currentAnchors != anchors) {
            sheetState.swipeableState.anchors = anchors
            if (sheetState.swipeableState.offsetOrNull == null) {
                sheetState.swipeableState.trySnapTo(sheetState.currentValue)
            }
        }
    }

    val boxAlignment = when (sheetHorizontalAlignment) {
        Alignment.Start -> Alignment.TopStart
        Alignment.End -> Alignment.TopEnd
        else -> Alignment.TopCenter
    }

    var isDragging by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(with(density) { fullyExpandedPx.toDp() })
    ) {
        Surface(
            modifier = Modifier
                .then(
                    if (sheetWidth != null) Modifier.width(sheetWidth)
                    else Modifier.fillMaxWidth()
                )
                .fillMaxHeight()
                .align(boxAlignment)
                .offset {
                    val offset = sheetState.offsetOrNull ?: (-fullyExpandedPx)
                    IntOffset(0, offset.toInt())
                }
                .draggable(
                    state = sheetState.swipeableState.swipeDraggableState,
                    orientation = Orientation.Vertical,
                    enabled = sheetState.isVisible,
                    startDragImmediately = sheetState.swipeableState.isAnimationRunning,
                    onDragStarted = { isDragging = true },
                    onDragStopped = { velocity ->
                        isDragging = false
                        scope.launch { sheetState.swipeableState.settle(velocity) }
                    }
                ),
            shape = BottomSheetDefaults.ExpandedShape,
            color = Color.White,
            tonalElevation = BottomSheetDefaults.Elevation
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                /* ---- 拖拽指示条（位于顶部，靠近 ToolBar） ---- */
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(40.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color(0xFFDADCE0))
                    )
                }
                content()
            }
        }
    }
}

// ==================== Sheet 内容宿主 ====================

@Composable
private fun SheetContentHost(
    sheetState: FlexibleSheetState,
    isDestinationSheetVisible: Boolean,
    destination: Any?,
    destinationContent: @Composable () -> Unit,
    quickActions: List<QuickActionSpec>,
    poiList: List<PoiItem>,
    onPoiClick: (PoiItem) -> Unit,
    onQuickActionClick: (QuickActionSpec) -> Unit,
) {
    if (isDestinationSheetVisible && destination != null) {
        destinationContent()
    } else {
        GeoKoriSheetContent(
            sheetState = sheetState,
            quickActions = quickActions,
            poiList = poiList,
            onPoiClick = onPoiClick,
            onQuickActionClick = onQuickActionClick,
        )
    }
}

// ==================== Sheet 内容 ====================

@Composable
private fun GeoKoriSheetContent(
    sheetState: FlexibleSheetState,
    quickActions: List<QuickActionSpec>,
    poiList: List<PoiItem>,
    onPoiClick: (PoiItem) -> Unit,
    onQuickActionClick: (QuickActionSpec) -> Unit,
) {
    val isExpanded by remember {
        derivedStateOf { sheetState.currentValue != FlexibleSheetValue.SlightlyExpanded }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
    ) {
        SearchHeaderV2()

        /* ---- 拖拽指示条 ---- */
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFFDADCE0))
            )
        }

        /* ---- 快捷操作（折叠/展开自动切换；接受任意 QuickActionSpec 实现） ---- */
        AnimatedContent(
            targetState = isExpanded,
            label = "quick_actions"
        ) { expanded ->
            if (expanded) {
                ExpandedQuickActions(
                    actions = quickActions,
                    onActionClick = onQuickActionClick
                )
            } else {
                CollapsedQuickActions(
                    actions = quickActions,
                    onActionClick = onQuickActionClick
                )
            }
        }

        /* ---- 分隔线（随 Sheet 展开进度渐变） ---- */
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .height(1.dp)
                .alpha(0.3f + sheetState.visibilityProgress * 0.7f)
                .background(Color(0xFFDADCE0))
        )

        /* ---- 列表头部 ---- */
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "附近推荐",
                fontSize = if (isExpanded) 18.sp else 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF202124)
            )
            TextButton(onClick = { }) {
                Text("查看更多", fontSize = 13.sp)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        /* ---- POI 列表 ---- */
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
        ) {
            items(poiList, key = { it.id }) { poi ->
                PoiListItemV2(
                    poi = poi,
                    onClick = { onPoiClick(poi) }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}