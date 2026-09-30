

package com.combo.core.security.crash

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import com.combo.core.component.activity.BasePluginActivity
import com.combo.core.model.PluginCrashInfo
import com.combo.core.runtime.PluginManager
import com.combo.core.ui.CrashScreen
import com.combo.core.ui.component.SystemAppearance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

class CrashActivity : BasePluginActivity() {

    private lateinit var crashInfo: PluginCrashInfo

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = proxyActivity?.intent

        crashInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getSerializableExtra(PluginCrashHandler.EXTRA_CRASH_INFO, PluginCrashInfo::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getSerializableExtra(PluginCrashHandler.EXTRA_CRASH_INFO) as? PluginCrashInfo
        } ?: createDefaultCrashInfo()

        proxyActivity?.setContent {
            SystemAppearance(!isSystemInDarkTheme())
            CrashScreen(
                crashInfo = crashInfo,
                onCloseApp = { handleCloseApp() },
                onRestartApp = { disablePlugin ->
                    handleRestartApp(disablePlugin)
                }
            )
        }
    }

    /**
     * 处理关闭应用的逻辑
     */
    private fun handleCloseApp() {
        proxyActivity?.let { activity ->
            activity.finishAndRemoveTask()
            killProcess()
        }
    }

    /**
     * 处理重启应用的逻辑
     * @param disablePlugin 用户是否选择禁用插件
     */
    private fun handleRestartApp(disablePlugin: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            if (disablePlugin && crashInfo.culpritPluginId != null && crashInfo.culpritPluginId != "未知") {
                PluginManager.setPluginEnabled(crashInfo.culpritPluginId!!, false)
            }

            proxyActivity?.let { activity ->
                val intent = activity.packageManager.getLaunchIntentForPackage(activity.packageName)
                val restartIntent = Intent.makeRestartActivityTask(intent!!.component)
                activity.startActivity(restartIntent)

                killProcess()
            }
        }
    }

    private fun killProcess() {
        Process.killProcess(Process.myPid())
        exitProcess(10)
    }

    private fun createDefaultCrashInfo(): PluginCrashInfo {
        return PluginCrashInfo(
            throwable = IllegalStateException("无法从Intent中获取原始崩溃信息。"),
            culpritPluginId = "未知",
            defaultMessage = "应用遇到未知问题，已被自动修复。"
        )
    }
}