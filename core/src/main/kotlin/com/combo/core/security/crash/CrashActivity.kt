/*
 * Copyright (c) 2025, 贵州君城网络科技有限公司
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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