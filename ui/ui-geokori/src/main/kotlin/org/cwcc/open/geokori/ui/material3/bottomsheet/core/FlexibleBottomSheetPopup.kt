package org.cwcc.open.geokori.ui.material3.bottomsheet.core

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.BackHandler
import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionContext
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewRootForInspector
import androidx.compose.ui.semantics.popup
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import java.lang.reflect.Field
import java.util.UUID

/**
 * Popup specific for flexible bottom sheet.
 */
@Composable
public fun FlexibleBottomSheetPopup(
    onDismissRequest: () -> Unit,
    windowInsets: WindowInsets,
    sheetState: FlexibleSheetState,
    content: @Composable BoxScope.() -> Unit,
) {
  val view = LocalView.current
  val id = rememberSaveable { UUID.randomUUID() }
  val parentComposition = rememberCompositionContext()
  val currentContent by rememberUpdatedState(content)
  val isEdgeToEdge = isEdgeToEdgeEnabled(view)
  val onBackPressedDispatcherOwner = view.findViewTreeOnBackPressedDispatcherOwner()

  val flexibleBottomSheetWindow = remember {
    FlexibleBottomSheetWindow(
        onDismissRequest = onDismissRequest,
        composeView = view,
        sheetState = sheetState,
        isEdgeToEdge = isEdgeToEdge,
        onBackPressedDispatcherOwner = onBackPressedDispatcherOwner,
        saveId = id,
    ).apply {
      setCustomContent(
          parent = parentComposition,
          content = {
            if (!sheetState.skipHiddenState) {
              BackHandler { onDismissRequest() }
            }
            Box(
                Modifier
                    .semantics { this.popup() }
                    .then(
                        if (sheetState.containSystemBars || isEdgeToEdge) {
                          Modifier
                        } else {
                          Modifier.windowInsetsPadding(windowInsets)
                        },
                    )
                    .imePadding(),
            ) {
              currentContent()
            }
          },
      )
    }
  }

  SideEffect {
    flexibleBottomSheetWindow.updateParentComposition(parentComposition)
  }

  DisposableEffect(flexibleBottomSheetWindow) {
    flexibleBottomSheetWindow.show()
    onDispose {
      flexibleBottomSheetWindow.disposeComposition()
      flexibleBottomSheetWindow.dismiss()
    }
  }
}

/** Custom compose view for [FlexibleBottomSheet] */
@SuppressLint("ViewConstructor")
private class FlexibleBottomSheetWindow(
    private var onDismissRequest: () -> Unit,
    private val composeView: View,
    private val sheetState: FlexibleSheetState,
    private val isEdgeToEdge: Boolean,
    onBackPressedDispatcherOwner: OnBackPressedDispatcherOwner?,
    saveId: UUID,
) :
  AbstractComposeView(composeView.context),
  ViewTreeObserver.OnGlobalLayoutListener,
  ViewRootForInspector {

  init {
    id = android.R.id.content
    setViewTreeLifecycleOwner(composeView.findViewTreeLifecycleOwner())
    setViewTreeViewModelStoreOwner(composeView.findViewTreeViewModelStoreOwner())
    setViewTreeSavedStateRegistryOwner(composeView.findViewTreeSavedStateRegistryOwner())
    onBackPressedDispatcherOwner?.let { setViewTreeOnBackPressedDispatcherOwner(it) }
    setTag(androidx.compose.ui.R.id.compose_view_saveable_id_tag, "Popup:$saveId")
    clipChildren = false
    isFocusable = true
    isFocusableInTouchMode = true

    setOnKeyListener { _, keyCode, event ->
      if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
        onDismissRequest()
        true
      } else {
        false
      }
    }
  }

  private val windowManager =
      composeView.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

  private var content: @Composable () -> Unit by mutableStateOf({})
  private var onBackInvokedCallback: OnBackInvokedCallback? = null

  override var shouldCreateCompositionOnAttachedToWindow: Boolean = false
    private set

  @Composable
  override fun Content() {
    content()
  }

  fun setCustomContent(
      parent: CompositionContext? = null,
      content: @Composable () -> Unit,
  ) {
    parent?.let { setParentCompositionContext(it) }
    this.content = content
    shouldCreateCompositionOnAttachedToWindow = true
  }

  fun updateParentComposition(parent: CompositionContext) {
    setParentCompositionContext(parent)
  }

  fun show() {
    windowManager.addView(this, getWindowParams())
    requestFocus()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      onBackInvokedCallback = OnBackInvokedCallback { onDismissRequest() }
      findOnBackInvokedDispatcher()?.registerOnBackInvokedCallback(
          OnBackInvokedDispatcher.PRIORITY_DEFAULT,
          onBackInvokedCallback!!,
      )
    }
  }

  fun dismiss() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      onBackInvokedCallback?.let { callback ->
        findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(callback)
      }
      onBackInvokedCallback = null
    }

    setViewTreeLifecycleOwner(null)
    setViewTreeSavedStateRegistryOwner(null)
    composeView.viewTreeObserver.removeOnGlobalLayoutListener(this)
    windowManager.removeViewImmediate(this)
  }

  /**
   * 获取窗口参数
   *
   * 模态窗口 (isModal = true):
   *   - 全屏覆盖，拦截所有触摸事件
   *   - 用于需要阻止用户与下层 UI 交互的场景
   *
   * 非模态窗口 (isModal = false):
   *   - 仅覆盖底部区域，允许触摸穿透到下层窗口
   *   - 用于允许用户与地图等下层 UI 交互的场景
   */
  private fun getWindowParams(windowHeight: Int? = null): WindowManager.LayoutParams {
    return WindowManager.LayoutParams().apply {

      if (sheetState.isModal) {
        // ========== 模态窗口配置 ==========
        // 使用 APPLICATION_PANEL 类型，覆盖整个应用
        type = WindowManager.LayoutParams.TYPE_APPLICATION_PANEL

        width = WindowManager.LayoutParams.MATCH_PARENT

        // 根据 edge-to-edge 决定高度
        if (isEdgeToEdge) {
          height = WindowManager.LayoutParams.MATCH_PARENT
          gravity = Gravity.TOP or Gravity.CENTER
        } else {
          height = windowHeight ?: WindowManager.LayoutParams.MATCH_PARENT
          gravity = Gravity.BOTTOM or Gravity.CENTER
        }

        // 模态窗口：聚焦并拦截所有触摸
        flags = flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        flags = flags and (
            WindowManager.LayoutParams.FLAG_IGNORE_CHEEK_PRESSES or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
            ).inv()

        // 布局标志
        flags = if (isEdgeToEdge || sheetState.containSystemBars) {
          flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        } else {
          flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        }

      } else {
        // ========== 非模态窗口配置 ==========
        // 使用 APPLICATION_ATTACHED_DIALOG 类型，更轻量，不影响下层窗口的触摸事件分发
        type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG

        width = WindowManager.LayoutParams.MATCH_PARENT

        // 非模态：使用 WRAP_CONTENT 让窗口只覆盖底部区域
        // 这样窗口外的区域（如地图）可以正常交互
        height = WindowManager.LayoutParams.WRAP_CONTENT
        gravity = Gravity.BOTTOM or Gravity.CENTER

        // 关键：非模态窗口的核心标志组合
        // FLAG_NOT_FOCUSABLE - 窗口不接收焦点，让下层窗口可以获取焦点
        // FLAG_NOT_TOUCH_MODAL - 允许触摸事件穿透到窗口外的下层视图
        // FLAG_WATCH_OUTSIDE_TOUCH - 监听窗口外的触摸事件（用于点击外部关闭）
        flags = flags or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH

        // 非模态下移除 FLAG_ALT_FOCUSABLE_IM 以确保输入法可以正常工作
        flags = flags and WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM.inv()

        // 布局标志：允许延伸到系统栏区域（如果需要）
        flags = if (isEdgeToEdge || sheetState.containSystemBars) {
          flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        } else {
          flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        }
      }

      // ========== 通用配置 ==========
      format = PixelFormat.TRANSLUCENT
      title = if (sheetState.isModal) "Modal Bottom Sheet" else "Non-Modal Bottom Sheet"
      token = composeView.applicationWindowToken

      // 移除默认动画
      windowAnimations = 0x00000040

      // 设置私有标志，禁用移动动画
      try {
        val className = "android.view.WindowManager\$LayoutParams"
        val layoutParamsClass = Class.forName(className)

        val privateFlags: Field = layoutParamsClass.getField("privateFlags")
        val noAnim: Field = layoutParamsClass.getField("PRIVATE_FLAG_NO_MOVE_ANIMATION")

        var privateFlagsValue: Int = privateFlags.getInt(this)
        val noAnimFlag: Int = noAnim.getInt(this)
        privateFlagsValue = privateFlagsValue or noAnimFlag
        privateFlags.setInt(this, privateFlagsValue)
      } catch (e: Exception) {
        // 反射失败时忽略，不影响主要功能
      }
    }
  }

  /**
   * Taken from PopupWindow. Calls [onDismissRequest] when back button is pressed.
   */
  override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    if (event.keyCode == KeyEvent.KEYCODE_BACK) {
      if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
        onDismissRequest()
        return true
      }
      return true
    }
    return super.dispatchKeyEvent(event)
  }

  override fun onGlobalLayout() {
    // No-op
  }
}
