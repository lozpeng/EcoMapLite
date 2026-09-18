package org.cwcc.open.geokori.ui.material3

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.cwcc.open.geokori.ui.material3.bottomsheet.BottomSheetDefaults
import org.cwcc.open.geokori.ui.material3.bottomsheet.FlexibleBottomSheet
import org.cwcc.open.geokori.ui.material3.bottomsheet.core.FlexibleSheetSize
import org.cwcc.open.geokori.ui.material3.bottomsheet.core.FlexibleSheetState
import org.cwcc.open.geokori.ui.material3.bottomsheet.core.FlexibleSheetValue
import org.cwcc.open.geokori.ui.material3.bottomsheet.core.rememberFlexibleBottomSheetState

/**
 * 插件底部弹窗配置
 * 参考 GeoKoriCenterSheet 的设计
 */
data class PluginBottomSheetConfig(
    val sheetWidth: Dp? = null,              // 固定宽度，null 时自动适配
    val sheetHorizontalAlignment: Alignment.Horizontal? = null,  // 水平对齐，null 时自动适配
    val isModal: Boolean = false,
    val isDraggable: Boolean = true,
    val showCloseButton: Boolean = true,     // 是否显示关闭按钮
    val closeButtonAlignment: Alignment.Horizontal = Alignment.End,  // 关闭按钮对齐方式
    val closeButtonFloating: Boolean = true, // 关闭按钮是否浮动在右上角（覆盖在内容上方）
    val skipHiddenState: Boolean = false,
    val fullyExpandedRatio: Float = 0.99f,   // 全展开高度比例
    val intermediatelyExpandedRatio: Float = 0.5f,  // 中等展开高度比例
    val slightlyExpandedRatio: Float = 0.15f,       // 微展开高度比例
    val hasSlightlyExpanded: Boolean = false,       // 是否有微展开状态
    val hasIntermediatelyExpanded: Boolean = true,  // 是否有中等展开状态
    val initialValue: FlexibleSheetValue = FlexibleSheetValue.Hidden,
    val bottomOffset: Dp = 0.dp,             // 底部偏移（非宽屏时给工具栏留出的空间）
)

/**
 * 创建插件 Sheet State
 * 参考 rememberGeoKoriSheetState
 */
@Composable
fun rememberPluginSheetState(
    config: PluginBottomSheetConfig = PluginBottomSheetConfig(),
): FlexibleSheetState {
  return rememberFlexibleBottomSheetState(
      flexibleSheetSize = FlexibleSheetSize(
          fullyExpanded = config.fullyExpandedRatio,
          intermediatelyExpanded = config.intermediatelyExpandedRatio,
          slightlyExpanded = config.slightlyExpandedRatio,
      ),
      isModal = config.isModal,
      skipSlightlyExpanded = !config.hasSlightlyExpanded,
      skipHiddenState = config.skipHiddenState,
      allowNestedScroll = true,
      initialValue = config.initialValue,
  )
}

/**
 * 插件底部弹窗容器
 * 参考 GeoKoriCenterSheet 的设计，支持宽度和位置定制
 *
 * @param isVisible 是否可见
 * @param onDismiss 关闭回调
 * @param onBackPressed 返回键回调
 * @param config 弹窗配置
 * @param bottomOffset 底部偏移量（动态传入，优先于 config 中的值），用于非宽屏时给底部工具栏留白
 * @param sheetState Sheet 状态
 * @param content 内容 Composable
 */
@Composable
fun GeoKoriPluginScreenContainer(
    modifier: Modifier = Modifier,
    isVisible: Boolean,
    onDismiss: () -> Unit,
    onBackPressed: () -> Unit = {},
    config: PluginBottomSheetConfig = PluginBottomSheetConfig(),
    bottomOffset: Dp = config.bottomOffset,
    sheetState: FlexibleSheetState = rememberPluginSheetState(config),
    content: @Composable () -> Unit,
) {
  val scope = rememberCoroutineScope()
  var isExpanding by remember { mutableStateOf(false) }

  // ========== 屏幕适配 ==========
  val windowInfo = LocalWindowInfo.current
  val containerWidthPx = windowInfo.containerSize.width
  val containerHeightPx = windowInfo.containerSize.height
  val density = LocalDensity.current
  val containerWidthDp = with(density) { containerWidthPx.toDp() }
  val containerHeightDp = with(density) { containerHeightPx.toDp() }

  val isWideScreen by remember(containerWidthDp) {
    derivedStateOf { containerWidthDp >= 600.dp }
  }

  val adaptiveWidth = config.sheetWidth ?: when {
    isWideScreen -> containerWidthDp / 2
    else -> null
  }

  val adaptiveAlignment = config.sheetHorizontalAlignment ?: when {
    isWideScreen -> Alignment.End  // 宽屏时靠右对齐
    else -> Alignment.CenterHorizontally
  }

  // ========== 动态计算 Sheet 高度和底部偏移 ==========
  // 宽屏：占满屏幕高度，不偏移
  // 非宽屏：高度减少 bottomOffset，并整体向上偏移 bottomOffset，底部留出工具栏空间
  val effectiveBottomOffset = if (isWideScreen) 0.dp else bottomOffset.coerceAtLeast(0.dp)

  val sheetHeight = if (isWideScreen) {
    containerHeightDp
  } else {
    (containerHeightDp - effectiveBottomOffset).coerceAtLeast(0.dp)
  }

  val sheetBottomOffset = if (isWideScreen) {
    0.dp
  } else {
    effectiveBottomOffset
  }
  // 非宽屏时整体向上偏移，宽屏时不偏移
  val containerModifier = if (!isWideScreen && effectiveBottomOffset > 0.dp) {
    modifier.offset(y = -effectiveBottomOffset)
  } else {
    modifier
  }
  // ========== 控制显示/隐藏 ==========
  LaunchedEffect(isVisible) {
    if (isVisible) {
      isExpanding = true
      scope.launch {
        try {
          sheetState.fullyExpand()
        } finally {
          isExpanding = false
        }
      }
    } else {
      scope.launch {
        sheetState.hide()
      }
    }
  }

  // ========== 监听状态变化 ==========
  LaunchedEffect(sheetState.currentValue) {
    if (sheetState.currentValue == FlexibleSheetValue.Hidden && isVisible && !isExpanding) {
      onDismiss()
    }
  }

  // ========== 渲染 BottomSheet ==========
  FlexibleBottomSheet(
      modifier = containerModifier,
      onDismissRequest = {
        if (!isExpanding) {
          onDismiss()
        }
      },
      onBackPressed = onBackPressed,
      sheetState = sheetState,
      onTargetChanges = { target ->
        if (!isExpanding && target == FlexibleSheetValue.Hidden && isVisible) {
          onDismiss()
        }
      },
      sheetWidth = adaptiveWidth,
      sheetHeight = sheetHeight,
      sheetBottomOffset = sheetBottomOffset,
      sheetHorizontalAlignment = adaptiveAlignment,
      containerColor = MaterialTheme.colorScheme.surface,
      contentColor = MaterialTheme.colorScheme.onSurface,
      dragHandle = if (config.isDraggable && !config.closeButtonFloating) {
        { BottomSheetDefaults.DragHandle() }
      } else {
        null
      },
  ) {
    if (config.closeButtonFloating) {
      // ========== 浮动关闭按钮模式（关闭按钮在右上角） ==========
      Box(
          modifier = Modifier
              .fillMaxWidth()
              .padding(top = 4.dp)
      ) {
        // 内容
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
          content()
        }

        // 关闭按钮 - 浮动在右上角
        if (config.showCloseButton) {
          Box(
              modifier = Modifier
                  .align(Alignment.TopEnd)
                  .padding(top = 44.dp, end = 8.dp)
          ) {
            IconButton(
                onClick = {
                  scope.launch {
                    sheetState.hide()
                  }.invokeOnCompletion {
                    onDismiss()
                  }
                },
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                        shape = CircleShape
                    )
            ) {
              Icon(
                  imageVector = Icons.Default.Close,
                  contentDescription = "关闭",
                  tint = MaterialTheme.colorScheme.onSurface,
                  modifier = Modifier.size(20.dp)
              )
            }
          }
        }
      }
    } else {
      // ========== 传统模式：关闭按钮在内容上方 ==========
      Column(
          modifier = Modifier.fillMaxWidth()
      ) {
        if (config.showCloseButton) {
          Box(
              modifier = Modifier.fillMaxWidth(),
              contentAlignment = when (config.closeButtonAlignment) {
                Alignment.Start -> Alignment.CenterStart
                Alignment.CenterHorizontally -> Alignment.Center
                Alignment.End -> Alignment.CenterEnd
                else -> Alignment.CenterEnd
              }
          ) {
            IconButton(
                onClick = {
                  scope.launch {
                    sheetState.hide()
                  }.invokeOnCompletion {
                    onDismiss()
                  }
                }
            ) {
              Icon(
                  imageVector = Icons.Default.Close,
                  contentDescription = "关闭",
                  tint = MaterialTheme.colorScheme.onSurface
              )
            }
          }
        }
        content()
      }
    }
  }
}

/**
 * 快捷创建 PluginBottomSheetConfig 的辅助函数
 */
fun pluginBottomSheetConfig(
    sheetWidth: Dp? = null,
    sheetHorizontalAlignment: Alignment.Horizontal? = null,
    isModal: Boolean = true,
    isDraggable: Boolean = true,
    showCloseButton: Boolean = true,
    closeButtonAlignment: Alignment.Horizontal = Alignment.End,
    closeButtonFloating: Boolean = true,
    skipHiddenState: Boolean = false,
    fullyExpandedRatio: Float = 1.0f,
    intermediatelyExpandedRatio: Float = 0.5f,
    slightlyExpandedRatio: Float = 0.15f,
    hasSlightlyExpanded: Boolean = false,
    hasIntermediatelyExpanded: Boolean = true,
    initialValue: FlexibleSheetValue = FlexibleSheetValue.Hidden,
    bottomOffset: Dp = 0.dp,
): PluginBottomSheetConfig {
  return PluginBottomSheetConfig(
      sheetWidth = sheetWidth,
      sheetHorizontalAlignment = sheetHorizontalAlignment,
      isModal = isModal,
      isDraggable = isDraggable,
      showCloseButton = showCloseButton,
      closeButtonAlignment = closeButtonAlignment,
      closeButtonFloating = closeButtonFloating,
      skipHiddenState = skipHiddenState,
      fullyExpandedRatio = fullyExpandedRatio,
      intermediatelyExpandedRatio = intermediatelyExpandedRatio,
      slightlyExpandedRatio = slightlyExpandedRatio,
      hasSlightlyExpanded = hasSlightlyExpanded,
      hasIntermediatelyExpanded = hasIntermediatelyExpanded,
      initialValue = initialValue,
      bottomOffset = bottomOffset,
  )
}
