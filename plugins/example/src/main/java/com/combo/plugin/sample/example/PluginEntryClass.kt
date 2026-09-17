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

package com.combo.plugin.sample.example

import android.widget.Toast
import androidx.compose.runtime.Composable
import com.combo.core.api.IPluginEntryClass
import com.combo.core.model.PluginContext
import com.combo.core.model.PluginCrashInfo
import com.combo.core.security.crash.IPluginCrashCallback
import com.combo.core.security.crash.PluginCrashHandler
import com.combo.plugin.sample.example.di.diModule
import com.combo.plugin.sample.example.receiver.NotificationUtil
import com.combo.plugin.sample.example.screen.ExampleMainScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.module.Module

class PluginEntryClass : IPluginEntryClass {
    override val pluginModule: List<Module>
        get() = listOf(
            diModule
        )

    @Composable
    override fun Content() {
        ExampleMainScreen()
    }

    override fun onLoad(context: PluginContext) {
        NotificationUtil.createChannels(context.application)
        CoroutineScope(Dispatchers.IO).launch {
            // 设置插件崩溃处理回调
            PluginCrashHandler.setClashCallback(
                context.pluginInfo.id,
                object : IPluginCrashCallback {
                    /**
                     * 当插件因 ClassCastException 崩溃时调用。
                     * 通常发生在插件热更新后，新旧类的实例冲突。
                     *
                     * @param info 崩溃详情。
                     * @return `true` 表示已处理该异常，框架不再执行默认逻辑；`false` 则继续执行默认逻辑。
                     */
                    override fun onClassCastException(info: PluginCrashInfo): Boolean {
                        Toast.makeText(context.application, "插件ClassCastException ${info.culpritPluginId}", Toast.LENGTH_SHORT).show()
                        return false
                    }

                    /**
                     * 当插件因 PluginDependencyException (ClassNotFound) 崩溃时调用。
                     *
                     * @param info 崩溃详情。
                     * @return `true` 表示已处理该异常，框架不再执行默认逻辑；`false` 则继续执行默认逻辑。
                     */
                    override fun onDependencyException(info: PluginCrashInfo): Boolean {
                        Toast.makeText(context.application, "插件PluginDependencyException ${info.culpritPluginId}", Toast.LENGTH_SHORT).show()
                        return false
                    }

                    /**
                     * 当插件因 Resources.NotFoundException 崩溃时调用。
                     * 通常发生在插件更新后，代码尝试访问一个不再存在的资源ID。
                     *
                     * @param info 崩溃详情。
                     * @return `true` 表示已处理该异常，框架不再执行默认逻辑；`false` 则继续执行默认逻辑。
                     */
                    override fun onResourceNotFoundException(info: PluginCrashInfo): Boolean {
                        Toast.makeText(context.application, "插件Resources.NotFoundException ${info.culpritPluginId}", Toast.LENGTH_SHORT).show()
                        return false
                    }

                    /**
                     * 当插件因 API 不兼容 (如 NoSuchMethodError, NoSuchFieldError) 崩溃时调用。
                     *
                     * @param info 崩溃详情。
                     * @return `true` 表示已处理该异常，框架不再执行默认逻辑；`false` 则继续执行默认逻辑。
                     */
                    override fun onApiIncompatibleException(info: PluginCrashInfo): Boolean {
                        Toast.makeText(context.application, "插件onApiIncompatibleException ${info.culpritPluginId}", Toast.LENGTH_SHORT).show()
                        return false
                    }

                    /**
                     * 当发生其他与插件相关的未知异常时调用。
                     *
                     * @param info 崩溃详情。
                     * @return `true` 表示已处理该异常，框架不再执行默认逻辑；`false` 则继续执行默认逻辑。
                     */
                    override fun onOtherPluginException(info: PluginCrashInfo): Boolean {
                        Toast.makeText(context.application, "插件onOtherPluginException ${info.culpritPluginId}", Toast.LENGTH_SHORT).show()
                        return false
                    }
                }
            )
        }
    }

    override fun onUnload() {
    }
}
