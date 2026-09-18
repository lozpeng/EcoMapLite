package org.cwcc.open.geokori.ui.material3.center

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val TAG = "GeoKoriCenterToolBar"

/* ==================== 数据类 ==================== */

data class BottomToolbarItem(
    val id: String = "",
    val icon: ImageVector,
    val label: String,
    val isFloating: Boolean = false,
    val isSelected: Boolean = false,
    val onClick: (BottomToolbarItem) -> Unit = {},
    val customColor: Color? = null,
    val badgeCount: Int = 0,
    val enabled: Boolean = true
)

// 默认工具栏配置
fun defaultToolbarItems(): List<BottomToolbarItem> = listOf(
    BottomToolbarItem(
        id = "home",
        icon = Icons.Default.Home,
        label = "首页",
        isSelected = true
    ),
    BottomToolbarItem(
        id = "map",
        icon = Icons.Default.Map,
        label = "地图"
    ),
    BottomToolbarItem(
        id = "publish",
        icon = Icons.Default.Add,
        label = "发布",
        isFloating = true,
    ),
    BottomToolbarItem(
        id = "chat",
        icon = Icons.Default.ChatBubble,
        label = "聊天",
        badgeCount = 3
    ),
    BottomToolbarItem(
        id = "profile",
        icon = Icons.Default.Person,
        label = "我的"
    )
)

/* ==================== 主组件 ==================== */

@Composable
fun GeoKoriCenterToolBar(
    modifier: Modifier = Modifier,
    items: List<BottomToolbarItem> = defaultToolbarItems(),
    selectedItemId: String? = null,
    onItemSelected: (BottomToolbarItem) -> Unit = {},
    isExpanded: Boolean = true,
    toolbarWidth: Dp? = null,
    toolbarHorizontalAlignment: Alignment.Horizontal? = null,
    isVisible: Boolean = true,
    autoHideOnMapClick: Boolean = true,   //默认为自动关闭
    onVisibilityChanged: ((Boolean) -> Unit)? = null,
) {
  val context = LocalContext.current

  /* ---------- 状态管理 ---------- */
  var currentSelectedId by remember { mutableStateOf(selectedItemId ?: items.firstOrNull { it.isSelected }?.id ?: items.firstOrNull()?.id ?: "") }

  selectedItemId?.let { id ->
    if (id != currentSelectedId) {
      currentSelectedId = id
    }
  }

  // 内部可见性状态
  var internalVisible by remember { mutableStateOf(isVisible) }

  // 实际的可见性：外部控制优先，如果 autoHideOnMapClick 启用则内部状态控制
  val actualVisible = if (autoHideOnMapClick) internalVisible else isVisible

  // 当外部 isVisible 变化时同步内部状态
  LaunchedEffect(isVisible) {
    if (!autoHideOnMapClick) {
      internalVisible = isVisible
    }
  }

  // 可见性变化时通知外部
  LaunchedEffect(actualVisible) {
    onVisibilityChanged?.invoke(actualVisible)
  }
  /* ---------- 屏幕适配 ---------- */
  val windowInfo = LocalWindowInfo.current
  val density = LocalDensity.current
  val containerWidthDp = with(density) { windowInfo.containerSize.width.toDp() }
  val isWideScreen by remember(containerWidthDp) {
    derivedStateOf { containerWidthDp >= 600.dp }
  }

  val adaptiveWidth = toolbarWidth ?: when {
    isWideScreen -> containerWidthDp / 2
    else -> null
  }
  val adaptiveAlignment = toolbarHorizontalAlignment ?: when {
    isWideScreen -> Alignment.Start
    else -> Alignment.CenterHorizontally
  }

  /* ---------- 动画参数 ---------- */
  val toolbarHeight by animateDpAsState(
      targetValue = if (isExpanded) 72.dp else 56.dp,
      animationSpec = tween(300)
  )
  val shadowElevation by animateDpAsState(
      targetValue = if (isExpanded) 8.dp else 4.dp,
      animationSpec = tween(300)
  )

  val toolbarAlpha by animateFloatAsState(
      targetValue = if (actualVisible) 1f else 0f,
      animationSpec = tween(durationMillis = 300),
      label = "toolbar_alpha"
  )
  val toolbarOffsetY by animateDpAsState(
      targetValue = if (actualVisible) 0.dp else 100.dp,
      animationSpec = tween(durationMillis = 300),
      label = "toolbar_offset"
  )

  /* ---------- 凸起按钮参数 ---------- */
  val floatingSize by animateDpAsState(
      targetValue = if (isExpanded) 64.dp else 52.dp,
      animationSpec = tween(300)
  )
  val overlap = 40.dp
  val totalHeight = toolbarHeight + floatingSize - overlap

  val actualWidthModifier = if (adaptiveWidth != null) {
    Modifier.width(adaptiveWidth)
  } else {
    Modifier.fillMaxWidth()
  }
  val floatingCount = items.count { it.isFloating }
  require(floatingCount <= 1) {
    "只能有一个凸起按钮，当前有 $floatingCount 个。"
  }

  /* ---------- 浮动 Dock 参数 ---------- */
  val dockCornerRadius = 28.dp
  val dockPaddingH = 16.dp   // 左右离屏间距，让 Dock 悬浮
  val dockPaddingV = 12.dp   // 距屏幕底部间距，营造悬浮感
  val dockInnerPaddingH = 8.dp // Dock 内部图标与圆角边缘的间距

  /* ---------- 按钮点击处理 ---------- */
  fun handleItemClick(item: BottomToolbarItem) {
    if (!item.enabled || !actualVisible) return
    currentSelectedId = item.id
    onItemSelected(item)
    item.onClick(item)
  }

  /* ---------- 根容器 ---------- */
  Box(
      modifier = modifier
          .then(actualWidthModifier)
          //为 Dock 增加外边距，使其悬浮在下方内容之上
          .padding(horizontal = dockPaddingH, vertical = dockPaddingV)
          .height(totalHeight)
          .alpha(toolbarAlpha)
          .offset(y = toolbarOffsetY),
      contentAlignment = when (adaptiveAlignment) {
        Alignment.Start -> Alignment.BottomStart
        Alignment.End -> Alignment.BottomEnd
        else -> Alignment.BottomCenter
      }
  ) {
    /* ===== 1. 白色导航栏背景（底部对齐）===== */
//    Surface(
//        modifier = Modifier
//            .fillMaxWidth()
//            .height(toolbarHeight)
//            .align(Alignment.BottomCenter)
//            .clip(RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp))
//            .shadow(
//                elevation = shadowElevation,
//                shape = RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp)
//            ),
//        color = Color.White.copy(alpha = 0.95f),
//        shape = RoundedCornerShape(dockCornerRadius),
//        shadowElevation = shadowElevation
//    ) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(toolbarHeight)
            .align(Alignment.BottomCenter)
            // 用浅色阴影替代默认黑色阴影，避免圆角处出现黑色色块
            .shadow(
                elevation = shadowElevation,
                shape = RoundedCornerShape(dockCornerRadius),
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.06f),
                spotColor = Color.Black.copy(alpha = 0.10f)
            )
            .clip(RoundedCornerShape(dockCornerRadius))
            .background(Color.White.copy(alpha = 0.95f))
    ) {
      Row(
          modifier = Modifier.fillMaxSize()
          // 内部再留一点 padding，让图标不贴圆角
          .padding(horizontal = dockInnerPaddingH),
          horizontalArrangement = Arrangement.SpaceEvenly,
          verticalAlignment = Alignment.CenterVertically
      ) {
        items.forEachIndexed { index, item ->
          if (!item.isFloating) {
            val isSelected = item.id == currentSelectedId
            BottomToolbarButton(
                item = item,
                isSelected = isSelected,
                isExpanded = isExpanded,
                onClick = { handleItemClick(item) }
            )
          } else {
            Box(modifier = Modifier.width(floatingSize))
          }
        }
      }
    }

    /* ===== 2. 凸起按钮（顶部中央）===== */
    items.forEachIndexed { index, item ->
      if (item.isFloating) {
        val isSelected = item.id == currentSelectedId
        val defaultColor = item.customColor ?: if (isSelected) Color(0xFF1A73E8) else Color(0xFF4285F4)
        val floatingColor by animateColorAsState(
            targetValue = defaultColor,
            animationSpec = tween(300)
        )

        val floatingRipple = remember {
          ripple(
              color = Color.White.copy(alpha = 0.3f),
              radius = 32.dp
          )
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = 0.dp)
                .size(floatingSize)
                .shadow(elevation = 8.dp, shape = CircleShape)
                .clip(CircleShape)
                .background(floatingColor)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = floatingRipple,
                    onClick = { handleItemClick(item) }
                ),
            contentAlignment = Alignment.Center
        ) {
          Icon(
              imageVector = item.icon,
              contentDescription = item.label,
              tint = Color.White,
              modifier = Modifier.size(floatingSize * 0.45f)
          )

          if (item.badgeCount > 0) {
            Badge(
                count = item.badgeCount,
                modifier = Modifier.align(Alignment.TopEnd)
            )
          }
        }
      }
    }
  }
}

/* ==================== 普通按钮 ==================== */
@Composable
private fun BottomToolbarButton(
    item: BottomToolbarItem,
    isSelected: Boolean,
    isExpanded: Boolean,
    onClick: () -> Unit
) {
  val interactionSource = remember { MutableInteractionSource() }

  val textAlpha by animateFloatAsState(
      targetValue = if (isExpanded) 1f else 0f,
      animationSpec = tween(200)
  )
  val iconSize by animateDpAsState(
      targetValue = if (isExpanded) 28.dp else 24.dp,
      animationSpec = tween(200)
  )

  val selectedColor = item.customColor ?: Color(0xFF1A73E8)
  val unselectedColor = Color(0xFF5F6368)

  val iconColor by animateColorAsState(
      targetValue = if (isSelected) selectedColor else unselectedColor,
      animationSpec = tween(200)
  )
  val textColor by animateColorAsState(
      targetValue = if (isSelected) selectedColor else unselectedColor,
      animationSpec = tween(200)
  )

  val rippleIndication = remember {
    ripple(
        color = selectedColor.copy(alpha = 0.3f),
        radius = 32.dp
    )
  }

  val contentAlpha by animateFloatAsState(
      targetValue = if (item.enabled) 1f else 0.5f,
      animationSpec = tween(200)
  )

  Box(
      modifier = Modifier
          .size(if (isExpanded) 64.dp else 56.dp)
          .clip(CircleShape)
          .clickable(
              interactionSource = interactionSource,
              onClick = onClick,
              indication = rippleIndication,
              enabled = item.enabled
          ),
      contentAlignment = Alignment.Center
  ) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .alpha(contentAlpha)
    ) {
      Box {
        Icon(
            imageVector = item.icon,
            contentDescription = item.label,
            tint = iconColor,
            modifier = Modifier.size(iconSize)
        )

        if (item.badgeCount > 0) {
          Badge(
              count = item.badgeCount,
              modifier = Modifier.align(Alignment.TopEnd)
          )
        }
      }

      if (isExpanded) {
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = item.label,
            fontSize = 10.sp,
            color = textColor,
            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
            modifier = Modifier.alpha(textAlpha)
        )
      }
    }
  }
}

/* ==================== 角标组件 ==================== */
@Composable
private fun Badge(
    count: Int,
    modifier: Modifier = Modifier,
    color: Color = Color.Red
) {
  Box(
      modifier = modifier
          .size(18.dp)
          .background(color, CircleShape),
      contentAlignment = Alignment.Center
  ) {
    Text(
        text = if (count > 99) "99+" else count.toString(),
        color = Color.White,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold
    )
  }
}
