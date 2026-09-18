package org.cwcc.open.geokori.framework

import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import com.combo.core.component.activity.BasePluginActivity
import org.cwcc.open.geokori.ui.geo.TitleBar
import org.cwcc.open.geokori.ui.material3.bottomsheet.FlexibleBottomSheet
import org.cwcc.open.geokori.ui.material3.bottomsheet.core.FlexibleSheetValue
import org.cwcc.open.geokori.ui.material3.pluginBottomSheetConfig
import org.cwcc.open.geokori.ui.material3.rememberPluginSheetState
import timber.log.Timber

class PluginUIComposeActivity : BasePluginActivity() {

  companion object {
    private var staticContent: (@Composable () -> Unit)? = null
    private var staticTitle: String = ""
    private var staticCloseable: Boolean = true
    private var staticIsNormalActivity: Boolean = false

    /**
     * 设置 Activity 内容
     */
    fun setContent(
        content: @Composable () -> Unit,
        title: String = "",
        closeable: Boolean = true,
        isNormalActivity: Boolean = false
    ) {
      staticContent = content
      staticTitle = title
      staticCloseable = closeable
      staticIsNormalActivity = isNormalActivity
    }

    /**
     * 清除静态内容（防止内存泄漏）
     */
    fun clear() {
      staticContent = null
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // ✅ 设置非模态窗口 + 外部点击关闭
    setupNonModalWindow()

    proxyActivity?.setContent {
      val content = staticContent
      if (content != null) {
        if (!staticIsNormalActivity) {
          ShowByBottomSheet(content)
        } else {
          ShowByActivity(content)
        }
      } else {
        // 没有内容时自动关闭
        Timber.w("No content set for PluginUIComposeActivity")
        proxyActivity?.finish()
      }
    }
  }

  @Composable
  fun ShowByActivity(content: @Composable () -> Unit) {
    ComposeView(proxyActivity?.baseContext!!).apply {
      setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
      Column(
          modifier = Modifier
              .fillMaxSize()
              .background(Color.White) // 白色不透明背景
      ) {
        if (staticCloseable) {
          TitleBar(
              title = staticTitle,
              onClose = {
                proxyActivity?.finish()
              }
          )
        }
        content()
      }
    }
  }

  @Composable
  fun ShowByBottomSheet(content: @Composable () -> Unit) {
    var showSheet by remember { mutableStateOf(true) }
    val pluginScreenConfig = remember {
      pluginBottomSheetConfig(
          sheetWidth = null,  // null 自动适配
          sheetHorizontalAlignment = Alignment.End,
          isModal = true,
          isDraggable = true,
          fullyExpandedRatio = 1.0f,
          intermediatelyExpandedRatio = 0.7f,
          hasSlightlyExpanded = false,
          hasIntermediatelyExpanded = true,
          initialValue = FlexibleSheetValue.FullyExpanded,
      )
    }
    val pluginScreenState = rememberPluginSheetState(pluginScreenConfig)

    if (showSheet) {
      FlexibleBottomSheet(
          modifier = Modifier.fillMaxWidth(),
          sheetState = pluginScreenState,
          onDismissRequest = {
            showSheet = false
            proxyActivity?.finish()
          },
      ) {
        if (staticCloseable) {
          TitleBar(
              title = staticTitle,
              onClose = {
                showSheet = false
                proxyActivity?.finish()
              }
          )
        }
        val context = LocalContext.current
        Spacer(modifier = Modifier.height(8.dp))
        content()
      }
    }
  }

  private fun setupNonModalWindow() {
    proxyActivity?.window?.apply {
      setBackgroundDrawableResource(android.R.color.transparent)
      val params = attributes
      params.apply {
        width = WindowManager.LayoutParams.MATCH_PARENT
        height = WindowManager.LayoutParams.WRAP_CONTENT
        gravity = Gravity.BOTTOM

        flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

        dimAmount = 0f
      }
      attributes = params

      clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
      addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
      statusBarColor = android.graphics.Color.TRANSPARENT
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    // 清理静态引用，防止内存泄漏
    clear()
  }
}
