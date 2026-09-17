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

package com.combo.core.security.auth

import android.app.Application
import com.combo.core.model.AuthorizationRequest
import com.combo.core.security.permission.PermissionLevel
import com.combo.core.security.permission.PermissionManager
import com.combo.core.security.permission.RequiresPermission
import com.combo.core.security.permission.checkApiCaller
import timber.log.Timber
import kotlin.reflect.jvm.javaMethod

/**
 * 统一授权协调器
 *
 * 作为所有需要用户介入操作的总入口，负责协调静态权限检查和动态用户授权流程。
 */
class AuthorizationManager(
    private val application: Application
) {
    // 持有静态权限检查器
    private val permissionManager = PermissionManager(application)

    // 可由外部配置的授权处理器
    internal var authorizationHandler: IAuthorizationHandler = DefaultAuthorizationHandler(application)

    /**
     * 请求授权的核心方法。
     *
     * @param request 封装了操作详情的授权请求对象。
     * @param hardFail 是否硬性失败。如果为 true，静态检查失败后将直接拒绝，不会请求用户授权。
     * @return `true` 如果操作被授权，否则 `false`。
     */
    internal suspend fun requestAuthorization(
        request: AuthorizationRequest,
        hardFail: Boolean
    ): Boolean {
        // 步骤 1: 静态权限检查
        val staticCheckPassed = when(request.type) {
            AuthorizationRequest.RequestType.API_PERMISSION -> {
                val requiredLevel = PermissionLevel.valueOf(request.details[AuthorizationRequest.KEY_PERMISSION_LEVEL]!!)
                val targetPluginId = request.details[AuthorizationRequest.KEY_TARGET_PLUGIN_ID]
                permissionManager.checkApiPermission(request.callingPluginId, requiredLevel, targetPluginId)
            }
            AuthorizationRequest.RequestType.INSTALL_PERMISSION -> {
                val signature = request.details[AuthorizationRequest.KEY_SIGNATURE_HASH]!!
                permissionManager.checkInstallPermission(signature)
            }
        }

        if (staticCheckPassed) {
            Timber.d("静态权限检查通过: ${request.type} from ${request.callingPluginId}")
            return true
        }

        if (hardFail) {
            Timber.w("授权拒绝 (硬性失败): ${request.type} from ${request.callingPluginId}")
            return false
        }

        Timber.i("静态权限检查未通过，正在向用户请求授权: ${request.type} from ${request.callingPluginId}")
        return authorizationHandler.onAuthorizationRequest(request)
    }

    /**
     * 设置自定义的授权处理器。
     * API权限要求：[PermissionLevel.HOST]
     *
     * @param handler 自定义的授权处理器。如果为 null，将恢复默认实现。
     */
    @RequiresPermission(PermissionLevel.HOST, hardFail = true)
    suspend fun setAuthorizationHandler(handler: IAuthorizationHandler?) {
        if (::setAuthorizationHandler.javaMethod?.checkApiCaller() == false) {
            return
        }
        this.authorizationHandler = handler ?: DefaultAuthorizationHandler(application)
        Timber.i("AuthorizationManager: IAuthorizationHandler 已更新。")
    }
}