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

import com.combo.core.model.AuthorizationRequest
import com.combo.core.runtime.PluginManager
import java.lang.reflect.Method

/**
 * 扩展函数，用于在调用敏感API前检查调用者权限。
 *
 * @param targetPluginId (可选) 操作的目标插件ID。
 * @return `true` 如果授权通过，`false` 如果被拒绝。
 */
internal suspend fun Method.checkApiCaller(targetPluginId: String? = null): Boolean {
    val permissionAnnotation = this.getAnnotation(RequiresPermission::class.java)
        ?: return true

    val callingPluginId = getCallingPluginId()
        ?: return true

    val request = AuthorizationRequest.forApi(
        callingPluginId = callingPluginId,
        targetPluginId = targetPluginId,
        requiredPermission = permissionAnnotation.level,
        apiMethod = this
    )

    return PluginManager.authorizationManager.requestAuthorization(
        request = request,
        hardFail = permissionAnnotation.hardFail
    )
}

/**
 * 通过分析调用堆栈来识别调用方插件。
 */
private fun getCallingPluginId(): String? {
    val stackTrace = Thread.currentThread().stackTrace
    for (i in 4 until stackTrace.size) {
        val className = stackTrace[i].className
        if (className.startsWith("com.combo.core.")) {
            continue
        }
        PluginManager.getClassIndex()[className]?.let {
            return it
        }
    }
    return null
}