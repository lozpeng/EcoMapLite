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

package com.combo.core.security.signature

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import timber.log.Timber
import java.security.MessageDigest


/**
 * 插件签名校验器
 *
 * 一个单例工具对象，提供获取和校验应用/插件APK签名的核心功能。
 * 内部处理了新旧Android版本的API兼容性，并对宿主签名信息进行缓存以提高性能。
 */
internal object SignatureValidator {

    private const val TAG = "SignatureValidator"

    @Volatile
    private var hostSignaturesCache: Set<Signature>? = null

    // 缓存宿主签名的SHA-256摘要集
    @Volatile
    private var hostSignatureDigestsCache: Set<String>? = null

    /**
     * 获取宿主 App 的所有签名摘要 (SHA-256)。
     * 结果会被缓存以提高后续调用的性能。
     *
     * @param context 上下文。
     * @return 宿主签名的 SHA-256 字符串集合，如果获取失败则返回空集合。
     */
    fun getHostSignatures(context: Context): Set<String> {
        hostSignatureDigestsCache?.let { return it }
        return synchronized(this) {
            hostSignatureDigestsCache ?: getSignaturesSha256(
                context,
                context.packageName,
                isApkFile = false
            ).also {
                hostSignatureDigestsCache = it
            }
        }
    }

    /**
     * 获取指定插件 APK 文件的所有签名摘要 (SHA-256)。
     *
     * @param context 上下文。
     * @param apkPath 插件 APK 的文件路径。
     * @return 插件签名的 SHA-256 字符串集合，如果获取失败则返回空集合。
     */
    fun getPluginSignatures(context: Context, apkPath: String): Set<String> {
        return getSignaturesSha256(context, apkPath, isApkFile = true)
    }

    /**
     * 将签名对象集合转换为SHA-256摘要字符串集合。
     */
    private fun Set<Signature>.toSha256Digests(): Set<String> {
        val messageDigest = MessageDigest.getInstance("SHA-256")
        return this.map { signature ->
            val digest = messageDigest.digest(signature.toByteArray())
            digest.joinToString(separator = "") { "%02x".format(it) }
        }.toSet()
    }

    /**
     * 获取签名摘要的核心方法，整合了签名获取与摘要计算。
     */
    private fun getSignaturesSha256(
        context: Context,
        source: String,
        isApkFile: Boolean
    ): Set<String> {
        return getSigningSignatures(context, source, isApkFile)
            ?.toSha256Digests()
            ?: emptySet()
    }

    /**
     * 获取签名信息的核心实现，适配新旧API。
     *
     * @param context 上下文
     * @param source  包名或APK文件路径
     * @param isApkFile 标记source是APK文件还是包名
     * @return 签名对象集合，获取失败则返回null
     */
    @Suppress("DEPRECATION")
    private fun getSigningSignatures(
        context: Context,
        source: String,
        isApkFile: Boolean = false,
    ): Set<Signature>? {
        if (!isApkFile && hostSignaturesCache != null) {
            return hostSignaturesCache
        }

        val packageManager = context.packageManager
        try {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
            } else {
                PackageManager.GET_SIGNATURES
            }

            val packageInfo: PackageInfo? = if (isApkFile) {
                packageManager.getPackageArchiveInfo(source, flags)
            } else {
                packageManager.getPackageInfo(source, flags)
            }

            if (packageInfo == null) {
                Timber.tag(TAG).w("无法获取 PackageInfo for: %s", source)
                return null
            }

            val signatures: Set<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val signingInfo = packageInfo.signingInfo ?: return null
                if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners.toSet()
                } else {
                    signingInfo.signingCertificateHistory?.toSet()
                }
            } else {
                packageInfo.signatures?.toSet()
            }

            if (!isApkFile && signatures != null) {
                hostSignaturesCache = signatures
            }

            return signatures

        } catch (_: PackageManager.NameNotFoundException) {
            Timber.tag(TAG).e("找不到包名: %s", source)
            return null
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "获取签名失败 for source: %s", source)
            return null
        }
    }
}