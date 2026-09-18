package org.cwcc.open.geokori.framework

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.Composable
import com.combo.core.api.IPluginActivity
import com.combo.core.utils.startPluginActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.cwcc.open.geokori.ui.material3.PluginBottomSheetConfig
import timber.log.Timber

/**
 * 插件模块工具类
 */
object PluginModuleUtils {
  private const val TAG = "PluginModuleUtils"

  // ========== 响应式状态管理 ==========
  private val _appContext = MutableStateFlow<Application?>(null)
  val appContextFlow: StateFlow<Application?> = _appContext.asStateFlow()

  private val currentContext: Application?
    get() = _appContext.value

  /**
   * 初始化插件模块
   */
  fun init(context: Context) {
    val appContext = context.applicationContext as? Application
    if (appContext != null) {
      _appContext.value = appContext
      Timber.d("PluginModule initialized with Application: ${appContext.packageName}")
    } else {
      Timber.e("Failed to get Application context from: ${context.javaClass.simpleName}")
    }
  }

  /**
   * 检查插件模块是否就绪
   */
  fun isReady(): Boolean = _appContext.value != null

  /**
   * 安全获取 ApplicationContext
   */
  @Throws(IllegalStateException::class)
  fun requireContext(): Application {
    return _appContext.value ?: throw IllegalStateException(
        "PluginModuleUtils not initialized. Please call init(context) in your Application."
    )
  }

  /**
   * 安全获取 Context（可空版本）
   */
  fun getContextOrNull(): Application? = _appContext.value

  // ========== 插件 Activity 启动封装 ==========

  /**
   * 启动插件 Activity（使用 ComboLite 框架方法）
   */
  fun startPluginActivity(
      cls: Class<out IPluginActivity>,
      options: Bundle? = null,
      block: (Intent.() -> Unit)? = null
  ) {
    try {
      val context = requireContext()
      context.startPluginActivity(cls, options, block)
      Timber.d("Started plugin activity: ${cls.simpleName}")
    } catch (e: IllegalStateException) {
      Timber.e(e, "PluginModule not initialized")
    } catch (e: Exception) {
      Timber.e(e, "Failed to start plugin activity: ${cls.simpleName}")
    }
  }

  // ========== BottomSheet 启动封装（统一使用 PluginUIComposeActivity） ==========

  /**
   * 显示 BottomSheet（支持普通 Composable）
   * 使用 ComboLite 的 startPluginActivity 启动
   */
  fun showBottomSheet(
      content: @Composable () -> Unit,
      title: String? = null,
      closeable: Boolean = true,
      isNormalActivity: Boolean = false
  ) {
    try {
      val context = requireContext()

      // 设置内容到 PluginUIComposeActivity
      PluginUIComposeActivity.setContent(
          content = content,
          title = title ?: "",
          closeable = closeable,
          isNormalActivity = isNormalActivity
      )

      // ✅ 使用 ComboLite 的 startPluginActivity
      context.startPluginActivity(
          cls = PluginUIComposeActivity::class.java,
          options = null,
          block = {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          }
      )
      Timber.d("BottomSheet shown with title: $title")
    } catch (e: IllegalStateException) {
      Timber.e(e, "PluginModule not initialized")
    } catch (e: Exception) {
      Timber.e(e, "Failed to show BottomSheet")
    }
  }

  /**
   * 显示全屏 Activity（使用 PluginUIComposeActivity）
   */
  fun showFullScreenActivity(
      content: @Composable () -> Unit,
      title: String? = null,
      closeable: Boolean = true
  ) {
    try {
      val context = requireContext()
      PluginUIComposeActivity.setContent(
          content = content,
          title = title ?: "",
          closeable = closeable,
          isNormalActivity = true
      )
      // ✅ 使用 ComboLite 的 startPluginActivity
      context.startPluginActivity(
          cls = PluginUIComposeActivity::class.java,
          options = null,
          block = {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          }
      )
      Timber.d("Full screen activity shown with title: $title")
    } catch (e: Exception) {
      Timber.e(e, "Failed to show full screen activity")
    }
  }

  /**
   * 显示带标题和关闭按钮的 BottomSheet（便捷方法）
   */
  fun showBottomSheetWithTitle(
      content: @Composable () -> Unit,
      title: String
  ) {
    showBottomSheet(
        content = content,
        title = title,
        closeable = true,
        isNormalActivity = false
    )
  }

  /**
   * 显示 BottomSheet 并支持自定义配置
   */
  fun showBottomSheet(
      content: @Composable () -> Unit,
      title: String? = null,
      config: PluginBottomSheetConfig? = null,
      maxHeight: Float? = null
  ) {
    try {
      val context = requireContext()

      // 设置内容到 PluginUIComposeActivity
      PluginUIComposeActivity.setContent(
          content = content,
          title = title ?: "",
          closeable = true,
          isNormalActivity = false
      )

      context.startPluginActivity(
          cls = PluginUIComposeActivity::class.java,
          options = null,
          block = {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          }
      )
      Timber.d("BottomSheet shown with title: $title")
    } catch (e: Exception) {
      Timber.e(e, "Failed to show BottomSheet")
    }
  }

  // ========== 其他功能封装 ==========

  /**
   * 启动带有数据传递的 Activity
   */
  fun startActivityWithData(
      activityClass: Class<out IPluginActivity>,
      data: Bundle,
      flags: Int? = null
  ) {
    try {
      val context = requireContext()
      context.startPluginActivity(
          cls = activityClass,
          options = null,
          block = {
            putExtras(data)
            flags?.let { addFlags(it) }
          }
      )
      Timber.d("Started activity with data: ${activityClass.simpleName}")
    } catch (e: Exception) {
      Timber.e(e, "Failed to start activity with data")
    }
  }
}
