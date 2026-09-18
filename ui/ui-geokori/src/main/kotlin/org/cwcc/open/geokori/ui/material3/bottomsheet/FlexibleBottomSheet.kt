package org.cwcc.open.geokori.ui.material3.bottomsheet

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.cwcc.open.geokori.ui.material3.bottomsheet.core.FlexibleSheetState
import org.cwcc.open.geokori.ui.material3.bottomsheet.core.FlexibleSheetValue
import org.cwcc.open.geokori.ui.material3.bottomsheet.core.Scrim
import org.cwcc.open.geokori.ui.material3.bottomsheet.core.consumeSwipeWithinBottomSheetBoundsNestedScrollConnection
import org.cwcc.open.geokori.ui.material3.bottomsheet.core.emptySwipeWithinBottomSheetBoundsNestedScrollConnection
import org.cwcc.open.geokori.ui.material3.bottomsheet.core.flexibleBottomSheetAnchorChangeHandler
import org.cwcc.open.geokori.ui.material3.bottomsheet.core.flexibleBottomSheetSwipeable
import org.cwcc.open.geokori.ui.material3.bottomsheet.core.rememberFlexibleBottomSheetState
import org.cwcc.open.geokori.ui.material3.bottomsheet.core.removeMinHeightConstraint
import org.cwcc.open.geokori.ui.material3.bottomsheet.core.resolveSheetSize
import org.cwcc.open.geokori.ui.material3.bottomsheet.core.screenHeight
import org.cwcc.open.geokori.ui.material3.bottomsheet.core.sheetPaddings
import org.cwcc.open.geokori.ui.material3.bottomsheet.core.toPx
import org.cwcc.open.geokori.ui.material3.bottomsheet.core.wrapContentMeasureConstraint

/**
 * https://github.com/skydoves/FlexibleBottomSheet
 * Flexible bottom sheets are used as an alternative to inline menus or simple dialogs on mobile,
 * especially when offering a long list of action items, or when items require longer descriptions
 * and icons. Like dialogs, flexible bottom sheets appear in front of app content, disabling all other
 * app functionality when they appear, and remaining on screen until confirmed, dismissed, or a
 * required action has been taken.
 *
 * @param onDismissRequest Executes when the user clicks outside of the bottom sheet, after sheet
 * animates to [FlexibleSheetValue.Hidden].
 * @param modifier Optional [Modifier] for the bottom sheet.
 * @param sheetState The state of the bottom sheet.
 * @param onTargetChanges Callback to listen for changes in [FlexibleSheetValue] targets.
 * @param shape The shape of the bottom sheet.
 * @param containerColor The color used for the background of this bottom sheet
 * @param contentColor The preferred color for content inside this bottom sheet. Defaults to either
 * the matching content color for [containerColor], or to the current [LocalContentColor] if
 * [containerColor] is not a color from the theme.
 * @param tonalElevation The tonal elevation of this bottom sheet.
 * @param scrimColor Color of the scrim that obscures content when the bottom sheet is open.
 * @param dragHandle Optional visual marker to swipe the bottom sheet.
 * @param windowInsets window insets to be passed to the bottom sheet window via [PaddingValues]
 * params.
 * @param sheetWidth Fixed width for the sheet. If null, defaults to fillMaxWidth with max 640dp,
 *                   and auto-adapts to half width on wide screens (>600dp).
 * @param sheetHeight Explicit height for the sheet surface. If null, uses constraint height.
 * @param sheetBottomOffset Bottom offset for the sheet. In non-modal mode, this is handled by
 *                          parent Box padding to avoid conflict with swipeable anchors.
 * @param sheetHorizontalAlignment Horizontal alignment of the sheet within its container.
 * @param content The content to be displayed inside the bottom sheet.
 */
@Composable
public fun FlexibleBottomSheet(
    modifier: Modifier = Modifier,
    onDismissRequest: () -> Unit = {},
    onBackPressed: () -> Unit = {},
    sheetState: FlexibleSheetState = rememberFlexibleBottomSheetState(),
    onTargetChanges: (FlexibleSheetValue) -> Unit = {},
    shape: Shape = BottomSheetDefaults.ExpandedShape,
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    contentColor: Color = contentColorFor(containerColor),
    tonalElevation: Dp = BottomSheetDefaults.Elevation,
    scrimColor: Color = BottomSheetDefaults.ScrimColor,
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
    windowInsets: WindowInsets = BottomSheetDefaults.windowInsets,
    sheetWidth: Dp? = null,
    sheetHeight: Dp? = null,
    sheetBottomOffset: Dp = 0.dp,
    sheetHorizontalAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    content: @Composable ColumnScope.() -> Unit,
) {
  val scope = rememberCoroutineScope()

  val animateToDismiss: () -> Unit = {
    if (sheetState.swipeableState.confirmValueChange(FlexibleSheetValue.Hidden)) {
      scope.launch { sheetState.hide() }.invokeOnCompletion {
        if (!sheetState.isVisible) {
          onDismissRequest()
        }
      }
    }
  }
  val settleToDismiss: (velocity: Float) -> Unit = {
    scope.launch { sheetState.settle(it) }.invokeOnCompletion {
      if (!sheetState.isVisible) onDismissRequest()
    }
  }

  val anchorChangeHandler = remember(sheetState, scope) {
    flexibleBottomSheetAnchorChangeHandler(
        state = sheetState,
        animateTo = { target, velocity ->
          scope.launch { sheetState.animateTo(target, velocity = velocity) }
        },
        snapTo = { target ->
          val didSnapImmediately = sheetState.trySnapTo(target)
          if (!didSnapImmediately) {
            scope.launch { sheetState.snapTo(target) }
          }
        },
    )
  }

  LaunchedEffect(sheetState.targetValue) {
    onTargetChanges.invoke(sheetState.targetValue)
  }

  LaunchedEffect(sheetState.swipeableState.anchors) {
    sheetState.swipeableState.isInitialized = sheetState.swipeableState.anchors.size ==
        listOf(
            FlexibleSheetValue.Hidden,
            FlexibleSheetValue.SlightlyExpanded,
            FlexibleSheetValue.IntermediatelyExpanded,
            FlexibleSheetValue.FullyExpanded,
        ).size
  }

  // Map Alignment.Horizontal to BoxScope Alignment
  val boxAlignment = when (sheetHorizontalAlignment) {
    Alignment.Start -> Alignment.BottomStart
    Alignment.End -> Alignment.BottomEnd
    else -> Alignment.BottomCenter
  }

  // ==================== 核心修改 1 ====================
  // 非模态时，底部偏移通过父 Box 的 padding 处理，不再在 .offset 中重复减去
  // 避免与 flexibleBottomSheetSwipeable 的锚点计算冲突
  val effectiveBottomOffset = if (!sheetState.isModal) 0.dp else sheetBottomOffset
  // ==================== 核心修改 1 结束 ====================

  // Extract content body as BoxScope extension for reuse by both modal and non-modal modes
  val sheetContent: @Composable BoxScope.() -> Unit = {
    var isDragging by remember { mutableStateOf(false) }
    val isAnimationRunning = sheetState.swipeableState.isAnimationRunning
    val screenHeightSize = screenHeight()
    val screenHeightPxSize = screenHeightSize.toPx()
    val density = LocalDensity.current

    // Track measured content height for wrap content mode
    var contentHeightPx by remember { mutableStateOf(0f) }
    val contentHeightDp = with(density) { contentHeightPx.toDp() }

    val flexibleSheetSize = sheetState.flexibleSheetSize

    // Resolve sizes considering wrap content mode
    val resolvedFullyExpanded = flexibleSheetSize.fullyExpanded
        .resolveSheetSize(screenHeightPxSize, contentHeightPx)
    val resolvedIntermediatelyExpanded = flexibleSheetSize.intermediatelyExpanded
        .resolveSheetSize(screenHeightPxSize, contentHeightPx)
    val resolvedSlightlyExpanded = flexibleSheetSize.slightlyExpanded
        .resolveSheetSize(screenHeightPxSize, contentHeightPx)

    val fullyExpandedHeight: Dp = screenHeightSize * resolvedFullyExpanded

    val expectedSheetSize: Dp = when (sheetState.targetValue) {
      FlexibleSheetValue.Hidden -> 1.dp
      FlexibleSheetValue.FullyExpanded -> screenHeightSize * resolvedFullyExpanded
      FlexibleSheetValue.IntermediatelyExpanded ->
        screenHeightSize * resolvedIntermediatelyExpanded
      FlexibleSheetValue.SlightlyExpanded -> screenHeightSize * resolvedSlightlyExpanded
    }

    val sheetModifier = if (sheetState.isModal) {
      if (flexibleSheetSize.hasWrapContent) {
        Modifier.fillMaxWidth().height(screenHeightSize + (contentHeightPx * 0.0001f).dp)
      } else {
        Modifier.fillMaxSize()
      }
    } else {
      // 非模态：容器始终使用全高，通过父 Box 的 padding 控制底部留白
      Modifier.fillMaxWidth().height(fullyExpandedHeight)
    }

    val isContentMeasured = contentHeightPx > 0f
    val needsContentMeasurement = flexibleSheetSize.hasWrapContent && !isContentMeasured

    // 模态遮罩只在模态时显示
    if (sheetState.isModal) {
      Scrim(
          color = scrimColor,
          onDismissRequest = animateToDismiss,
          visible = sheetState.targetValue != FlexibleSheetValue.Hidden,
      )
    }

    BoxWithConstraints(
        modifier = sheetModifier
            .align(boxAlignment)
            .graphicsLayer {
              alpha = when {
                needsContentMeasurement -> 0f
                sheetState.targetValue == FlexibleSheetValue.Hidden &&
                    !isDragging && !isAnimationRunning -> 0f
                else -> 1f
              }
            },
    ) {
      val constraintHeight = constraints.maxHeight.toFloat()
      val constraintMaxWidth = maxWidth

      val actualSheetWidth = sheetWidth ?: when {
        constraintMaxWidth > 600.dp -> (constraintMaxWidth / 2).coerceAtMost(BottomSheetMaxWidth)
        else -> constraintMaxWidth  // 非宽屏：全宽，不限制最大宽度
      }

      val bottomSheetPaneTitle = "Bottom Sheet"
      Surface(
          modifier = modifier
              .width(actualSheetWidth)
              .height(sheetHeight?:constraintHeight.dp)
              .align(boxAlignment)
              .semantics { paneTitle = bottomSheetPaneTitle }
              // ==================== 核心修改 2 ====================
              // 非模态时 effectiveBottomOffset = 0，不再重复减去 bottomOffset
              // 底部留白已交由父 Box 的 padding 处理
              .offset {
                val sheetOffset = sheetState.offsetOrNull?.toInt() ?: 0
                val bottomOffsetPx = with(density) { effectiveBottomOffset.toPx() }.toInt()
                IntOffset(
                    x = 0,
                    y = sheetOffset - bottomOffsetPx
                )
              }
              // ==================== 核心修改 2 结束 ====================
              .nestedScroll(
                  remember(sheetState) {
                    if (sheetState.allowNestedScroll) {
                      consumeSwipeWithinBottomSheetBoundsNestedScrollConnection(
                          sheetState = sheetState,
                          orientation = Orientation.Vertical,
                          screenHeight = screenHeightSize.value,
                          onFling = settleToDismiss,
                          onDragging = {
                            isDragging = it
                          },
                      )
                    } else {
                      emptySwipeWithinBottomSheetBoundsNestedScrollConnection()
                    }
                  },
              )
              .flexibleBottomSheetSwipeable(
                  sheetState = sheetState,
                  anchorChangeHandler = anchorChangeHandler,
                  sheetFullHeight = fullyExpandedHeight.toPx(),
                  sheetConstraintHeight = constraintHeight,
                  screenMaxHeight = screenHeightSize.toPx(),
                  flexibleSheetSize = sheetState.flexibleSheetSize,
                  isModal = sheetState.isModal,
                  contentHeight = contentHeightPx,
                  onDragStarted = {
                    isDragging = true
                  },
                  onDragStopped = {
                    isDragging = false
                    settleToDismiss(it)
                  },
              ),
          shape = shape,
          color = containerColor,
          contentColor = contentColor,
          tonalElevation = tonalElevation,
      ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
          Column(
              modifier = Modifier
                  .fillMaxWidth()
                  .then(
                      if (flexibleSheetSize.hasWrapContent) {
                        Modifier.wrapContentMeasureConstraint(screenHeightPxSize.toInt())
                      } else {
                        Modifier.removeMinHeightConstraint()
                      },
                  )
                  .onSizeChanged { size ->
                    contentHeightPx = size.height.toFloat()
                  }
                  .then(
                      if (sheetState.isModal) {
                        Modifier.sheetPaddings(sheetState)
                      } else {
                        Modifier
                      },
                  ),
          ) {
            if (dragHandle != null) {
              val collapseActionLabel = "Collapse bottom sheet"
              val dismissActionLabel = "Dismiss bottom sheet"
              val expandActionLabel = "expand bottom sheet"
              Box(
                  modifier = Modifier
                      .align(Alignment.CenterHorizontally)
                      .semantics(mergeDescendants = true) {
                        with(sheetState) {
                          dismiss(dismissActionLabel) {
                            animateToDismiss()
                            true
                          }
                          if (currentValue == FlexibleSheetValue.IntermediatelyExpanded) {
                            expand(expandActionLabel) {
                              if (swipeableState.confirmValueChange(
                                    FlexibleSheetValue.FullyExpanded,
                                )
                              ) {
                                scope.launch { fullyExpand() }
                              }
                              true
                            }
                          } else if (currentValue == FlexibleSheetValue.SlightlyExpanded) {
                            expand(expandActionLabel) {
                              if (swipeableState.confirmValueChange(
                                    FlexibleSheetValue.IntermediatelyExpanded,
                                )
                              ) {
                                scope.launch { intermediatelyExpand() }
                              }
                              true
                            }
                          } else if (hasIntermediatelyExpandedState) {
                            collapse(collapseActionLabel) {
                              if (
                                swipeableState.confirmValueChange(
                                    FlexibleSheetValue.IntermediatelyExpanded,
                                )
                              ) {
                                scope.launch { intermediatelyExpand() }
                              }
                              true
                            }
                          } else if (hasSlightlyExpandedState) {
                            collapse(collapseActionLabel) {
                              if (
                                swipeableState.confirmValueChange(
                                    FlexibleSheetValue.SlightlyExpanded,
                                )
                              ) {
                                scope.launch { slightlyExpand() }
                              }
                              true
                            }
                          }
                        }
                      },
              ) {
                dragHandle()
              }
            }
            content()
          }
        }
      }
    }
  }

  // 统一嵌入当前 Compose 树，无论模态/非模态
  // 模态时 Scrim 已在 sheetContent 内部处理，无需额外 PopupWindow
  // 彻底避免 PopupWindow 隐藏后仍拦截下层触摸的问题
  // ==================== 核心修改 3 ====================
  // 非模态且需要底部偏移时，通过父 Box 的 padding 挤压出工具栏空间
  // 这样 BoxWithConstraints 的 Bottom 对齐点自然停在工具栏上方
  Box(
      modifier = Modifier
          .fillMaxSize()
          .then(
              if (!sheetState.isModal && sheetBottomOffset > 0.dp) {
                Modifier.padding(bottom = sheetBottomOffset)
              } else {
                Modifier
              }
          )
  ) {
    // ==================== 核心修改 3 结束 ====================
    sheetContent()
    // 返回键处理（模态和非模态共用）
    if (!sheetState.skipHiddenState) {
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
            scope.launch { sheetState.hide() }.invokeOnCompletion { onDismissRequest() }
          }
        }
        onBackPressed.invoke()
      }
    } else {
      // skipHiddenState = true 时：返回键只逐级收起，不隐藏
      BackHandler {
        val current = sheetState.currentValue
        when {
          current == FlexibleSheetValue.FullyExpanded && sheetState.hasIntermediatelyExpandedState -> {
            scope.launch { sheetState.intermediatelyExpand() }
          }
          current == FlexibleSheetValue.IntermediatelyExpanded && sheetState.hasSlightlyExpandedState -> {
            scope.launch { sheetState.slightlyExpand() }
          }
          current == FlexibleSheetValue.SlightlyExpanded -> {
            // 已是最低可见状态，返回键无操作或触发 onBackPressed
            onBackPressed.invoke()
          }
          else -> {
            onBackPressed.invoke()
          }
        }
      }
    }
  }

  if (sheetState.hasFullyExpandedState) {
    LaunchedEffect(sheetState) {
      sheetState.show()
    }
  }
}
