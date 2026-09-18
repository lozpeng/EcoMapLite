package org.cwcc.open.geokori.ui.material3.center

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
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
import org.cwcc.open.geokori.ui.material3.center.model.QuickAction
import org.cwcc.open.geokori.ui.material3.center.model.SearchHeaderV2

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
 *
 * @param toolbarPosition ToolBar 位置（底部/顶部），决定 Sheet 展开方向
 * @param sheetState Sheet 状态，可由外部传入以精细控制
 * @param toolbarItems ToolBar 按钮列表
 * @param selectedToolbarItemId 当前选中的 ToolBar 项 ID
 * @param isVisible 组件整体是否可见（外部直接控制）
 * @param autoHideOnMapClick 是否监听地图点击广播来自动切换显隐
 * @param syncToolbarWithSheet 是否根据 Sheet 展开状态自动收起/展开 ToolBar
 * @param quickActions 快捷操作按钮列表
 * @param poiList POI 数据列表
 * @param shouldCollapseSheetOnPoiSelect 点击 POI 时是否自动收起 Sheet
 * @param toolbarWidth ToolBar 固定宽度，null 则根据屏幕尺寸自适应
 * @param toolbarHorizontalAlignment ToolBar 水平对齐，null 则根据屏幕尺寸自适应
 * @param sheetWidth Sheet 固定宽度，null 则根据屏幕尺寸自适应
 * @param sheetHorizontalAlignment Sheet 水平对齐，null 则根据屏幕尺寸自适应
 * @param destination 目的地数据（用于显示目的地选择 Sheet）
 * @param isDestinationSheetVisible 是否显示目的地选择内容
 * @param destinationContent 目的地选择的内容 Composable
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
    destination: Any? = null,
    isDestinationSheetVisible: Boolean = false,
    destinationContent: @Composable () -> Unit = {},
    onPoiSelected: (PoiItem) -> Unit = {},
    onPoiNavigate: (PoiItem) -> Unit = {},
    onQuickActionClick: (QuickAction) -> Unit = {},
    onPoiDetailClose: () -> Unit = {},
    onSheetDismiss: () -> Unit = {},
    onVisibilityChanged: ((Boolean) -> Unit)? = null,  // 新增回调
    onToolbarHeightChanged: ((Dp) -> Unit)? = null,
    toolbarItems: List<BottomToolbarItem> = defaultToolbarItems(),
    quickActions: List<QuickAction> = defaultQuickActions(),
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
          ContextCompat.RECEIVER_EXPORTED
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
      val target = sheetValueBeforeHide ?: FlexibleSheetValue.SlightlyExpanded
      if (sheetState.currentValue == FlexibleSheetValue.Hidden) {
        //sheetState.show(target)
        sheetState.swipeableState.trySnapTo(FlexibleSheetValue.SlightlyExpanded)
        sheetValueBeforeHide = null
      }
    } else {
      if (sheetState.currentValue != FlexibleSheetValue.Hidden) {
        sheetValueBeforeHide = sheetState.currentValue
        sheetState.hide()
      }
    }
  }

  /* ---------- ToolBar 状态 ---------- */
  var toolbarExpanded by remember { mutableStateOf(true) }
  val toolbarHeight = if (toolbarExpanded) 72.dp else 56.dp
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
          scope.launch { sheetState.hide() }.invokeOnCompletion { onSheetDismiss() }
        }
      }
    }
  }

  // 统一的 ToolBar 点击处理函数
  fun handleToolbarItemSelected(item: BottomToolbarItem) {
    currentSelectedToolbarId = item.id
    onToolbarItemSelected(item)
    // 点击 ToolBar 按钮时隐藏 Sheet
    if (sheetState.currentValue != FlexibleSheetValue.Hidden) {
      scope.launch {
        sheetState.hide()
      }
    }
  }
  // ⭐ 主内容：ToolBar + Sheet（仅在 internalVisible 为 true 时渲染）
  if (internalVisible) {
    Box(modifier = modifier.fillMaxSize()) {
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
    quickActions: List<QuickAction>,
    poiList: List<PoiItem>,
    onPoiClick: (PoiItem) -> Unit,
    onQuickActionClick: (QuickAction) -> Unit,
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

// ==================== Sheet 内容（复用原 BottomSheetContent 逻辑） ====================

@Composable
private fun GeoKoriSheetContent(
    sheetState: FlexibleSheetState,
    quickActions: List<QuickAction>,
    poiList: List<PoiItem>,
    onPoiClick: (PoiItem) -> Unit,
    onQuickActionClick: (QuickAction) -> Unit,
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

    /* ---- 快捷操作（折叠/展开自动切换） ---- */
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
