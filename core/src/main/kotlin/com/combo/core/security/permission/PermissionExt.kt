

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