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
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import com.combo.core.model.AuthorizationRequest
import com.combo.core.model.AuthorizationRequest.Companion.KEY_PLUGIN_APK_PATH
import com.combo.core.model.AuthorizationRequest.Companion.KEY_PLUGIN_DESCRIPTION
import com.combo.core.model.AuthorizationRequest.Companion.KEY_PLUGIN_ICON_RES_ID
import com.combo.core.model.AuthorizationRequest.Companion.KEY_PLUGIN_NAME
import com.combo.core.model.AuthorizationRequest.Companion.KEY_PLUGIN_VERSION_NAME
import com.combo.core.model.AuthorizationRequest.Companion.KEY_SIGNATURE_HASH
import com.combo.core.ui.component.InfoRowStyled
import com.combo.core.ui.component.PrimaryButton
import com.combo.core.ui.theme.FrameworkTheme
import timber.log.Timber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstallPermissionScreen(
    request: AuthorizationRequest,
    onResult: (Boolean) -> Unit,
    onExit: () -> Unit
) {
    val details = request.details
    val pluginName = details[KEY_PLUGIN_NAME] ?: request.callingPluginId
    val pluginVersion = details[KEY_PLUGIN_VERSION_NAME] ?: "未知版本"
    val pluginDescription = (details[KEY_PLUGIN_DESCRIPTION] ?: "无").ifBlank { "无" }
    val signature = details[KEY_SIGNATURE_HASH] ?: "未知"

    val context = LocalContext.current
    val pluginIcon = remember(request) {
        val apkPath = details[KEY_PLUGIN_APK_PATH]
        val iconResId = details[KEY_PLUGIN_ICON_RES_ID]?.toIntOrNull() ?: 0
        if (apkPath.isNullOrBlank() || iconResId == 0) {
            return@remember null
        }
        try {
            val pm = context.packageManager
            val packageInfo = pm.getPackageArchiveInfo(apkPath, 0)

            packageInfo?.applicationInfo?.let { appInfo ->
                appInfo.sourceDir = apkPath
                appInfo.publicSourceDir = apkPath

                val pluginRes = pm.getResourcesForApplication(appInfo)
                ResourcesCompat.getDrawable(pluginRes, iconResId, null)
            }
        } catch (e: Exception) {
            Timber.e(e, "从 APK 路径加载图标失败: $apkPath")
            null
        }
    }



    FrameworkTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {  },
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
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
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
                            text = "版本 $pluginVersion",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.tertiaryContainer,
                            RoundedCornerShape(10.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(
                        modifier = Modifier.size(20.dp),
                        imageVector = Icons.Rounded.Info,
                        contentDescription = "Warning",
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "该插件的数字签名与本应用不一致，可能存在未知风险，是否继续安装？",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    InfoRowStyled("插件 ID", request.callingPluginId)
                    InfoRowStyled("描述", pluginDescription)
                    InfoRowStyled("数字签名 (SHA-256)", signature)
                }

                Spacer(modifier = Modifier.weight(1f))

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    PrimaryButton(
                        text = "仍然安装",
                        onClick = { onResult(true) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(onClick = { onResult(false) }) {
                        Text("取消")
                    }
                }
            }
        }
    }
}