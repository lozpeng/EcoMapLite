package org.cwcc.open.geokori.ui.material3.center

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.ArtTrack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.LocalPolice
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import org.cwcc.open.geokori.ui.material3.bottomsheet.FlexibleBottomSheet
import org.cwcc.open.geokori.ui.material3.bottomsheet.core.FlexibleSheetSize
import org.cwcc.open.geokori.ui.material3.bottomsheet.core.FlexibleSheetState
import org.cwcc.open.geokori.ui.material3.bottomsheet.core.FlexibleSheetValue
import org.cwcc.open.geokori.ui.material3.bottomsheet.core.rememberFlexibleBottomSheetState
import org.cwcc.open.geokori.ui.material3.center.model.CollapsedQuickActions
import org.cwcc.open.geokori.ui.material3.center.model.ExpandedQuickActions
import org.cwcc.open.geokori.ui.material3.center.model.PoiDetailCardV2
import org.cwcc.open.geokori.ui.material3.center.model.PoiItem
import org.cwcc.open.geokori.ui.material3.center.model.PoiListItemV2
import org.cwcc.open.geokori.ui.material3.center.model.QuickAction
import org.cwcc.open.geokori.ui.material3.center.model.SearchHeaderV2

/**
 * 为 GeoKori 地图场景预配置的 BottomSheet State。
 * 默认：非模态、三档展开、初始微展开、允许嵌套滚动。
 */
@Composable
fun rememberGeoKoriSheetState(
    initialValue: FlexibleSheetValue = FlexibleSheetValue.SlightlyExpanded,
    skipHiddenState: Boolean = false,
    isModal: Boolean = false,
): FlexibleSheetState = rememberFlexibleBottomSheetState(
    flexibleSheetSize = FlexibleSheetSize(
        fullyExpanded = 0.9f,
        intermediatelyExpanded = 0.5f,
        slightlyExpanded = 0.12f,
    ),
    isModal = isModal,
    skipSlightlyExpanded = false,
    skipHiddenState = skipHiddenState,
    allowNestedScroll = true,
    initialValue = initialValue,
)

/**
 * GeoKori 底部 Sheet + 详情弹窗一体化组件。
 *
 * @param modifier 外部修饰器
 * @param sheetState BottomSheet 状态，可由外部传入以精细控制
 * @param quickActions 快捷操作按钮列表
 * @param poiList POI 数据列表
 * @param destination 目的地数据（用于显示目的地选择 Sheet）
 * @param isDestinationSheetVisible 是否显示目的地选择内容
 * @param destinationContent 目的地选择的内容 Composable
 * @param shouldCollapseSheetOnPoiSelect 点击 POI 时是否自动收起 Sheet
 * @param sheetWidth Sheet 固定宽度，null 则根据屏幕尺寸自适应
 * @param sheetHorizontalAlignment Sheet 水平对齐，null 则根据屏幕尺寸自适应
 * @param onPoiSelected POI 被点击选中时回调
 * @param onPoiNavigate 详情卡片内点击"路线"时回调
 * @param onQuickActionClick 快捷操作被点击时回调
 * @param onPoiDetailClose 详情卡片关闭时回调
 * @param onSheetDismiss 点击遮罩/返回时回调
 */
@Composable
fun GeoKoriCenterSheet(
    modifier: Modifier = Modifier,
    sheetState: FlexibleSheetState = rememberGeoKoriSheetState(),
    quickActions: List<QuickAction> = defaultQuickActions(),
    poiList: List<PoiItem> = defaultPoiList(),
    destination: Any? = null,
    isDestinationSheetVisible: Boolean = false,
    destinationContent: @Composable () -> Unit = {},
    shouldCollapseSheetOnPoiSelect: Boolean = true,
    sheetWidth: Dp? = null,
    sheetHorizontalAlignment: Alignment.Horizontal? = null,
    onPoiSelected: (PoiItem) -> Unit = {},
    onPoiNavigate: (PoiItem) -> Unit = {},
    onQuickActionClick: (QuickAction) -> Unit = {},
    onPoiDetailClose: () -> Unit = {},
    onSheetDismiss: () -> Unit = {},
    bottomPadding: Dp = 0.dp,  // 新增底部 padding 参数
) {
  var selectedPoi by remember { mutableStateOf<PoiItem?>(null) }
  var previousSheetValue by remember { mutableStateOf<FlexibleSheetValue?>(null) }

  // ========== 使用 LocalWindowInfo 获取实时容器宽度 ==========
  val windowInfo = LocalWindowInfo.current
  val containerWidthPx = windowInfo.containerSize.width
  val density = LocalDensity.current
  val containerWidthDp = with(density) { containerWidthPx.toDp() }

  // 判断是否为宽屏（折叠屏展开/平板）
  val isWideScreen by remember(containerWidthDp) {
    derivedStateOf { containerWidthDp >= 600.dp }
  }

  // 计算 Sheet 宽度
  // 宽屏：占容器宽度一半；手机/折叠屏折叠：传 null 让 FlexibleBottomSheet 内部处理为全宽
  val adaptiveWidth = sheetWidth ?: when {
    isWideScreen -> containerWidthDp / 2
    else -> null
  }

  // 计算水平对齐方式
  // 宽屏默认靠左；手机默认居中
  val adaptiveAlignment = sheetHorizontalAlignment ?: when {
    isWideScreen -> Alignment.Start
    else -> Alignment.CenterHorizontally
  }

  // ========== Sheet 与弹窗状态联动 ==========
  LaunchedEffect(selectedPoi) {
    if (shouldCollapseSheetOnPoiSelect) {
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

  // ========== BottomSheet 主体 ==========
  FlexibleBottomSheet(
      sheetState = sheetState,
      containerColor = Color.White,
      onDismissRequest = onSheetDismiss,
      dragHandle = null,
      windowInsets = WindowInsets.systemBars,
      sheetWidth = adaptiveWidth,
      sheetHorizontalAlignment = adaptiveAlignment,
//      modifier = when(isWideScreen) {
//        true -> Modifier.padding(bottom = bottomPadding)
//        false -> Modifier.fillMaxSize().padding(bottom = bottomPadding)
//      }
      modifier = modifier.then(
          when(isWideScreen) {
            true -> Modifier
            false -> Modifier.fillMaxSize()
          }
      )
  ) {
    if (isDestinationSheetVisible && destination != null) {
      destinationContent()
    } else {
      BottomSheetContent(
          sheetState = sheetState,
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

  // ========== 非模态 POI 详情弹窗 ==========
  if (selectedPoi != null) {
    val cardWidth = (containerWidthDp * 0.85f).coerceAtMost(360.dp)

    Popup(
        alignment = Alignment.Center,
        properties = PopupProperties(
            focusable = false,
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

      val offsetY by androidx.compose.animation.core.animateIntOffsetAsState(
          targetValue = if (visible) {
            androidx.compose.ui.unit.IntOffset(0, 0)
          } else {
            androidx.compose.ui.unit.IntOffset(0, with(density) { 80.dp.toPx().toInt() })
          },
          animationSpec = androidx.compose.animation.core.spring(
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

// ==================== 内部实现 ====================

@Composable
private fun BottomSheetContent(
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

    // 拖拽指示条
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

    // 快捷操作（折叠/展开自动切换）
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

    // 分隔线（随 Sheet 展开进度渐变）
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .height(1.dp)
            .alpha(0.3f + sheetState.visibilityProgress * 0.7f)
            .background(Color(0xFFDADCE0))
    )

    // 列表头部
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

    // POI 列表
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

// ==================== 默认数据 ====================

fun defaultQuickActions(): List<QuickAction> = listOf(
    QuickAction(null, "动物", Color(0xFFE3F2FD), Color(0xFF1565C0)),
    QuickAction(null, "植物", Color(0xFFF3E5F5), Color(0xFF6A1B9A)),
    QuickAction(null, "鸟类", Color(0xFFFFF3E0), Color(0xFFEF6C00)),
    QuickAction(null, "致害", Color(0xFFFFFDE7), Color(0xFFF9A825)),
    QuickAction(null, "收容", Color(0xFFE8F5E9), Color(0xFF2E7D32)),
    QuickAction(null, "谱系", Color(0xFFFFEBEE), Color(0xFFC62828)),
    QuickAction(Icons.Default.LocalPolice, "执法", Color(0xFFFFF3E0), Color(0xFFEF6C00)),
    QuickAction(Icons.Default.Flag, "履约", Color(0xFFE0F2F1), Color(0xFF00695C)),
    QuickAction(Icons.Default.BugReport, "名录-动物", Color(0xFFE0F2F1), Color(0xFF00695C)),
    QuickAction(Icons.Filled.AcUnit, "名录-植物", Color(0xFFFFFDE7), Color(0xFF2E7D32)),
    QuickAction(Icons.Filled.ArtTrack, "名录-三有", Color(0xFFE0F2F1), Color(0xFF00695C)),
    QuickAction(null, "CITES附录", Color(0xFFE0F2F1), Color(0xFF00695C)),
)

fun defaultPoiList(): List<PoiItem> = listOf(
    PoiItem("1", "星巴克咖啡", "餐饮", 4.8f, "120m", "建国路88号SOHO现代城", listOf("咖啡", "WiFi", "安静")),
    PoiItem("2", "万达广场", "购物", 4.6f, "350m", "长安街1号", listOf("商场", "IMAX", "餐饮")),
    PoiItem("3", "建国门地铁站", "交通", 4.9f, "80m", "建国门站B口", listOf("1号线", "2号线", "换乘")),
    PoiItem("4", "海底捞火锅", "餐饮", 4.7f, "500m", "朝阳路66号", listOf("火锅", "24小时", "排队")),
    PoiItem("5", "北京协和医院", "医疗", 4.9f, "1.2km", "东单北大街53号", listOf("三甲", "急诊", "专家")),
    PoiItem("6", "全聚德烤鸭店", "餐饮", 4.5f, "800m", "前门大街30号", listOf("烤鸭", "老字号", "宴请")),
)
