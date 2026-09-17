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

package com.combo.core.component.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Process
import com.combo.core.model.ProviderInfo
import com.combo.core.runtime.PluginManager
import java.net.URLDecoder

/**
 * 宿主端的 ContentProvider 统一代理。
 * ... (注释保持不变)
 */
open class BaseHostProvider : ContentProvider() {
    companion object {
        const val KEY_TARGET_URI = "com.combo.core.base.BaseHostProvider.TARGET_URI"
    }

    /**
     * 此方法在应用启动的早期被调用。
     * 框架的初始化工作已移至 BaseHostApplication 中，以确保统一入口。
     */
    override fun onCreate(): Boolean {
        return true
    }

    private fun getTargetProvider(className: String): ContentProvider? {
        return PluginManager.proxyManager.getOrInstantiateProvider(className)
    }

    /**
     * 将代理 URI "还原" 回插件的原生 URI
     */
    private fun rewriteUri(proxyUri: Uri, providerInfo: ProviderInfo): Uri {
        val pathSegments = proxyUri.pathSegments
        val originalAuthority = providerInfo.authorities.first()

        val originalPath = pathSegments.drop(1).joinToString("/")

        return proxyUri.buildUpon()
            .authority(originalAuthority)
            .path(originalPath)
            .clearQuery()
            .fragment(null)
            .build()
    }

    /**
     * 高阶函数，封装请求转发的重复逻辑
     */
    private inline fun <T> withForwardedRequest(
        uri: Uri,
        block: (provider: ContentProvider, rewrittenUri: Uri) -> T?,
    ): T? {
        val pluginAuthority = uri.pathSegments.getOrNull(0)?.let { URLDecoder.decode(it, "UTF-8") }
            ?: throw IllegalArgumentException("无法从 URI 中解析出插件 Authority: $uri")

        val providerInfo = PluginManager.proxyManager.findProviderInfoByAuthority(pluginAuthority)
            ?: throw SecurityException("拦截：目标 Provider Authority [$pluginAuthority] 未在 PluginManager 中注册。")

        val className = providerInfo.className

        if (!providerInfo.exported && Binder.getCallingUid() != Process.myUid()) {
            throw SecurityException("权限拒绝：Provider ${providerInfo.className} 未导出。")
        }

        val targetProvider = getTargetProvider(className)
            ?: throw IllegalStateException("无法创建或获取 Provider 实例: $className")

        val rewrittenUri = rewriteUri(uri, providerInfo)

        return block(targetProvider, rewrittenUri)
    }

    // --- CRUD 方法保持不变 ---

    override fun query(
        uri: Uri,
        p: Array<String>?,
        s: String?,
        sa: Array<String>?,
        so: String?
    ): Cursor? =
        withForwardedRequest(uri) { provider, rewritten -> provider.query(rewritten, p, s, sa, so) }

    override fun getType(uri: Uri): String? =
        withForwardedRequest(uri) { provider, rewritten -> provider.getType(rewritten) }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        var result: Uri? = null
        withForwardedRequest(uri) { provider, rewritten ->
            provider.insert(rewritten, values)?.also { originalResultUri ->
                context?.contentResolver?.notifyChange(uri, null)
                val pluginAuthority = originalResultUri.authority
                val hostAuthority = PluginManager.proxyManager.getHostProviderAuthority()

                result = originalResultUri.buildUpon()
                    .authority(hostAuthority)
                    .path("/$pluginAuthority${originalResultUri.path}")
                    .build()
            }
        }
        return result
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int =
        withForwardedRequest(uri) { provider, rewritten ->
            provider.delete(rewritten, selection, selectionArgs).also { deletedCount ->
                if (deletedCount > 0) {
                    context?.contentResolver?.notifyChange(uri, null)
                }
            }
        } ?: 0

    override fun update(uri: Uri, v: ContentValues?, s: String?, sa: Array<String>?): Int =
        withForwardedRequest(uri) { provider, rewritten ->
            provider.update(rewritten, v, s, sa).also { updatedCount ->
                if (updatedCount > 0) {
                    context?.contentResolver?.notifyChange(uri, null)
                }
            }
        } ?: 0

    @Suppress("DEPRECATION")
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val targetUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras?.getParcelable(KEY_TARGET_URI, Uri::class.java)
        } else {
            extras?.getParcelable(KEY_TARGET_URI)
        }
            ?: throw IllegalArgumentException("无法处理 call 请求：extras 中缺少目标 Uri (KEY_TARGET_URI)")

        extras?.remove(KEY_TARGET_URI)

        return withForwardedRequest(targetUri) { provider, _ ->
            provider.call(method, arg, extras)
        }
    }
}