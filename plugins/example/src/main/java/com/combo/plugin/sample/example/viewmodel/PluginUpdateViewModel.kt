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

package com.combo.plugin.sample.example.viewmodel

import android.app.Application
import android.content.Intent
import android.os.Process
import android.widget.Toast
import androidx.lifecycle.viewModelScope
import com.combo.core.runtime.PluginManager
import com.combo.core.runtime.installer.InstallerManager
import com.combo.plugin.sample.common.update.DownloadStatus
import com.combo.plugin.sample.common.update.UpdateManager
import com.combo.plugin.sample.common.update.model.PluginVersionInfo
import com.combo.plugin.sample.common.viewmodel.BaseViewModel
import com.combo.plugin.sample.example.state.PluginUpdateState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import kotlin.system.exitProcess

class PluginUpdateViewModel(
    private val application: Application,
    private val updateManager: UpdateManager
) : BaseViewModel<PluginUpdateState>(PluginUpdateState()) {

    init {
        fetchPlugins()
    }

    private fun fetchPlugins() {
        viewModelScope.launch {
            updateState { copy(isLoading = true) }
            val installed = PluginManager.getAllInstallPlugins().associate { it.id to it.versionName }
            val remote = updateManager.fetchRemotePlugins()
            updateState {
                copy(
                    isLoading = false,
                    remotePlugins = remote,
                    installedPlugins = installed
                )
            }
        }
    }

    fun downloadAndInstallPlugin(
        pluginId: String,
        pluginName: String,
        versionInfo: PluginVersionInfo
    ) {
        viewModelScope.launch {
            val downloadIdentifier = "$pluginId-${versionInfo.version}"
            updateState {
                copy(downloadingPlugins = downloadingPlugins + (downloadIdentifier to 0f))
            }

            updateManager.downloadPlugin(pluginName, versionInfo).collectLatest { status ->
                when (status) {
                    is DownloadStatus.InProgress -> {
                        updateState {
                            copy(downloadingPlugins = downloadingPlugins + (downloadIdentifier to status.progress))
                        }
                    }

                    is DownloadStatus.Success -> {
                        updateState {
                            copy(
                                downloadingPlugins = downloadingPlugins - downloadIdentifier,
                                installingPlugins = installingPlugins + downloadIdentifier
                            )
                        }
                        installPlugin(pluginId, status.file, downloadIdentifier)
                    }

                    is DownloadStatus.Failure -> {
                        Toast.makeText(application, "下载失败: ${status.error.message}", Toast.LENGTH_SHORT).show()
                        updateState {
                            copy(
                                downloadingPlugins = downloadingPlugins - downloadIdentifier,
                                isError = true,
                                errorMessage = "Download failed: ${status.error.message}"
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun installPlugin(pluginId: String, pluginFile: File, downloadIdentifier: String) {
        val wasPreviouslyInstalled = uiState.value.installedPlugins.containsKey(pluginId)

        val result = PluginManager.installerManager.installPlugin(pluginFile, true)
        updateState { copy(installingPlugins = installingPlugins - downloadIdentifier) }

        when (result) {
            is InstallerManager.InstallResult.Success -> {
                PluginManager.launchPlugin(result.pluginInfo.id)

                updateState {
                    copy(installedPlugins = installedPlugins + (result.pluginInfo.id to result.pluginInfo.versionName))
                }

                if (wasPreviouslyInstalled) {
                    updateState {
                        copy(
                            showInstallSuccessDialog = true,
                            recentlyInstalledPlugin = result.pluginInfo,
                            restartRequired = true
                        )
                    }
                }
            }

            is InstallerManager.InstallResult.Failure -> {
                Toast.makeText(application, "安装失败: ${result.reason}", Toast.LENGTH_SHORT).show()
                updateState {
                    copy(
                        isError = true,
                        errorMessage = "Install failed: ${result.reason}"
                    )
                }
            }
        }
    }

    /**
     * 重启整个应用程序
     */
    fun restartApp() {
        val intent = application.packageManager.getLaunchIntentForPackage(application.packageName)
        val restartIntent = Intent.makeRestartActivityTask(intent!!.component)
        application.startActivity(restartIntent)
        Process.killProcess(Process.myPid())
        exitProcess(0)
    }

    /**
     * 用户选择稍后重启（仅关闭对话框）
     */
    fun dismissRestartDialog() {
        updateState {
            copy(
                showInstallSuccessDialog = false,
                recentlyInstalledPlugin = null,
                restartRequired = false
            )
        }
    }
}