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

package com.combo.core.security.permission

import android.app.Application
import com.combo.core.runtime.PluginManager.getPluginInfo
import com.combo.core.security.permission.PermissionLevel.HOST
import com.combo.core.security.permission.PermissionLevel.SELF
import com.combo.core.security.signature.SignatureValidator
import timber.log.Timber

/**
 * 定义了插件调用敏感 API 所需的权限等级。
 *
 * 权限等级：
 * - [HOST]：表示插件需要宿主签名才能调用的敏感 API。
 * - [SELF]：表示插件需要与目标插件 ID 相同才能调用的敏感 API。
 */
enum class PermissionLevel {
    HOST,
    SELF,
}

/**
 * 静态权限检查器
 *
 * 负责根据预设规则（如签名、调用者ID）进行无UI的权限检查。
 * 它不关心用户授权，只返回检查结果。
 */
internal class PermissionManager(private val context: Application) {
    // 懒加载并缓存宿主签名摘要
    private val hostSignatureDigests: Set<String> by lazy {
        SignatureValidator.getHostSignatures(context).also {
            if (it.isEmpty()) {
                Timber.e("无法获取宿主签名，权限系统可能无法正常工作。")
            }
        }
    }

    /**
     * 检查API调用的静态权限。
     *
     * @param callingPluginId 发起调用的插件ID。
     * @param requiredLevel 要求的权限等级。
     * @param targetPluginId （可选）操作的目标插件ID。
     * @return `true` 如果静态检查通过，否则 `false`。
     */
    fun checkApiPermission(
        callingPluginId: String,
        requiredLevel: PermissionLevel,
        targetPluginId: String? = null
    ): Boolean {
        val callingPluginInfo = getPluginInfo(callingPluginId)?.pluginInfo
            ?: return false

        return when (requiredLevel) {
            HOST -> hasHostSignature(callingPluginInfo.path)
            SELF -> callingPluginId == targetPluginId || hasHostSignature(callingPluginInfo.path)
        }
    }

    /**
     * 检查插件安装的静态权限。
     * 在严格模式下，只检查签名是否与宿主一致。
     *
     * @param pluginSignature 待安装插件的签名摘要。
     * @return `true` 如果签名匹配，否则 `false`。
     */
    fun checkInstallPermission(pluginSignature: String): Boolean {
        return hostSignatureDigests.contains(pluginSignature)
    }

    /**
     * 检查指定路径的APK签名是否与宿主完全一致。
     */
    private fun hasHostSignature(apkPath: String): Boolean {
        val pluginSignatureDigests = SignatureValidator.getPluginSignatures(context, apkPath)
        if (pluginSignatureDigests.isEmpty()) {
            return false
        }
        return hostSignatureDigests.containsAll(pluginSignatureDigests)
    }
}