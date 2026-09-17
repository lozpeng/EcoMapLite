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

package com.combo.core.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import com.combo.core.model.AuthorizationRequest
import com.combo.core.model.AuthorizationRequest.Companion.KEY_API_METHOD_NAME
import com.combo.core.model.AuthorizationRequest.Companion.KEY_PERMISSION_LEVEL
import com.combo.core.model.AuthorizationRequest.Companion.KEY_PLUGIN_NAME
import com.combo.core.model.AuthorizationRequest.Companion.KEY_TARGET_PLUGIN_ID
import com.combo.core.runtime.PluginManager
import com.combo.core.runtime.installer.InstallerManager
import com.combo.core.security.auth.AuthorizationManager
import com.combo.core.security.crash.PluginCrashHandler
import com.combo.core.security.permission.PermissionLevel
import com.combo.core.ui.component.InfoRow
import com.combo.core.ui.component.PrimaryButton
import com.combo.core.ui.component.SecondaryButton
import com.combo.core.ui.theme.FrameworkTheme
import timber.log.Timber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiPermissionScreen(
    request: AuthorizationRequest,
    onResult: (Boolean) -> Unit,
    onExit: () -> Unit
) {
    val details = request.details
    val callingPluginInfo = remember(request.callingPluginId) {
        PluginManager.getAllInstallPlugins().find { it.id == request.callingPluginId }
    }
    val pluginName = callingPluginInfo?.name ?: request.callingPluginId

    val apiMethodName = details[KEY_API_METHOD_NAME] ?: "未知操作"
    val permissionLevel = details[KEY_PERMISSION_LEVEL]
    val targetPluginId = details[KEY_TARGET_PLUGIN_ID]
    val purpose = getPurposeFromApiName(apiMethodName)

    val context = LocalContext.current
    val pluginIcon = remember(callingPluginInfo) {
        if (callingPluginInfo == null || callingPluginInfo.iconResId == 0) {
            return@remember null
        }
        try {
            val pm = context.packageManager
            val appInfo = pm.getPackageArchiveInfo(callingPluginInfo.path, 0)?.applicationInfo
            appInfo?.let {
                it.sourceDir = callingPluginInfo.path
                it.publicSourceDir = callingPluginInfo.path
                val pluginRes = pm.getResourcesForApplication(it)
                ResourcesCompat.getDrawable(pluginRes, callingPluginInfo.iconResId, null)
            }
        } catch (e: Exception) {
            Timber.e(e, "从已安装插件加载图标失败: ${callingPluginInfo.id}")
            null
        }
    }

    FrameworkTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { },
                    navigationIcon = {
                        IconButton(onClick = onExit) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "返回"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            ) {
                // 1. 插件信息区域
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (pluginIcon != null) {
                        Image(
                            bitmap = pluginIcon.toBitmap().asImageBitmap(),
                            contentDescription = "Plugin Icon",
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(12.dp)),
                        )
                    } else {
                        Image(
                            painter = painterResource(id = android.R.mipmap.sym_def_app_icon),
                            contentDescription = "Default Plugin Icon",
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(12.dp)),
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(text = pluginName, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text(
                            text = request.callingPluginId,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
                
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.Start
                ) {
                    Text(
                        text = "插件请求执行以下操作:",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                                RoundedCornerShape(10.dp)
                            )
                            .padding(16.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = apiMethodName,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            if (permissionLevel == PermissionLevel.HOST.name) {
                                Row(
                                    modifier = Modifier
                                        .height(24.dp)
                                        .background(
                                            MaterialTheme.colorScheme.errorContainer,
                                            RoundedCornerShape(4.dp)
                                        )
                                        .padding(horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Lock,
                                        contentDescription = "宿主权限",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "宿主权限",
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Medium,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = purpose,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (!targetPluginId.isNullOrBlank() && targetPluginId != "宿主") {
                        Spacer(modifier = Modifier.height(24.dp))
                        InfoRow("操作目标", targetPluginId)
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SecondaryButton(
                        text = "拒绝",
                        onClick = { onResult(false) },
                        modifier = Modifier.weight(1f)
                    )
                    PrimaryButton(
                        text = "允许",
                        onClick = { onResult(true) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/**
 * 根据API方法名获取其人类可读的用途描述
 */
private fun getPurposeFromApiName(apiName: String): String {
    return when (apiName) {
        PluginManager::launchPlugin.name ->
            "请求加载或重启一个插件。此操作会立即执行插件代码，激活其功能。如果该插件正在运行，重启会中断其当前服务。"

        PluginManager::unloadPlugin.name ->
            "请求立即停止并卸载一个正在运行的插件。如果其他插件正依赖于它，此操作可能导致它们功能异常或崩溃。"

        PluginManager::loadEnabledPlugins.name ->
            "请求加载所有已安装且被启用的插件。这是一个全局批量操作，通常在应用启动时执行，用于恢复工作环境。"

        PluginManager::setPluginEnabled.name ->
            "修改目标插件的启用状态。被禁用的插件在应用下次启动时将不会自动加载，这可以用于临时隔离有问题的插件。"

        InstallerManager::installPlugin.name ->
            "请求安装或更新一个插件。此操作将在应用中引入新的可执行代码，是最高风险的操作之一，请仅在信任插件来源时授权。"

        InstallerManager::uninstallPlugin.name ->
            "请求永久卸载一个插件及其所有数据。这是一个不可恢复的操作，插件提供的所有功能都将消失。"

        PluginManager::setValidationStrategy.name ->
            "修改插件安装时的签名验证策略。降低安全等级（如允许任意签名）将使应用面临被恶意插件攻击的风险，直接影响应用的安全根基。"

        PluginCrashHandler::setGlobalClashCallback.name ->
            "修改全局插件崩溃处理逻辑。这是一个核心安全设置，错误的实现可能导致应用无法从插件崩溃中恢复，或被用于隐藏恶意行为。"

        PluginCrashHandler::setClashCallback.name ->
            "为插件自身注册一个专属的崩溃回调。这允许插件自定义其部分异常的处理方式，例如进行特定的数据清理或上报。"

        AuthorizationManager::setAuthorizationHandler.name ->
            "替换框架默认的授权管理器。这将改变未来所有敏感操作（如安装、API调用）的授权界面和逻辑，是一项最高级别的安全设置。"

        else ->
            "执行一项未在描述列表中指定的敏感操作，请谨慎处理。"
    }
}

@Preview(showBackground = true, name = "HOST Permission Light")
@Composable
fun ApiPermissionScreenHostLightPreview() {
    val request = AuthorizationRequest(
        type = AuthorizationRequest.RequestType.API_PERMISSION,
        callingPluginId = "com.plugin.app_store",
        details = mapOf(
            KEY_PLUGIN_NAME to "应用商店插件",
            KEY_API_METHOD_NAME to "installPlugin",
            KEY_PERMISSION_LEVEL to "HOST",
            KEY_TARGET_PLUGIN_ID to "com.plugin.new_app"
        )
    )
    ApiPermissionScreen(request = request, onResult = {}, onExit = {})
}