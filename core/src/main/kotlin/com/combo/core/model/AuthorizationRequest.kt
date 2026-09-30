

package com.combo.core.model

import com.combo.core.security.permission.PermissionLevel
import java.io.Serializable
import java.lang.reflect.Method

/**
 * 封装了授权请求的详细信息。
 *
 * @property type 请求的类型（API调用 或 插件安装）。
 * @property callingPluginId 发起请求的插件ID。
 * @property details 关于请求的详细描述信息，可以直接呈现给用户。
 */
data class AuthorizationRequest(
    val type: RequestType,
    val callingPluginId: String,
    val details: Map<String, String>
) : Serializable {
    enum class RequestType {
        API_PERMISSION,
        INSTALL_PERMISSION
    }

    companion object {
        const val KEY_TARGET_PLUGIN_ID = "targetPluginId"
        const val KEY_PERMISSION_LEVEL = "permissionLevel"
        const val KEY_API_METHOD_NAME = "apiMethodName"

        const val KEY_PLUGIN_NAME = "pluginName"
        const val KEY_PLUGIN_ICON_RES_ID = "pluginIconResId"
        const val KEY_PLUGIN_VERSION_NAME = "pluginVersionName"
        const val KEY_PLUGIN_DESCRIPTION = "pluginDescription"
        const val KEY_SIGNATURE_HASH = "signatureHash"
        const val KEY_PLUGIN_APK_PATH = "pluginApkPath"

        fun forApi(
            callingPluginId: String,
            targetPluginId: String?,
            requiredPermission: PermissionLevel,
            apiMethod: Method
        ) = AuthorizationRequest(
            type = RequestType.API_PERMISSION,
            callingPluginId = callingPluginId,
            details = mapOf(
                KEY_TARGET_PLUGIN_ID to (targetPluginId ?: "宿主"),
                KEY_PERMISSION_LEVEL to requiredPermission.name,
                KEY_API_METHOD_NAME to apiMethod.name
            )
        )

        fun forInstall(
            pluginId: String,
            signature: String,
            versionName: String,
            description: String,
            name: String,
            iconResId: Int,
            apkPath: String
        ) = AuthorizationRequest(
            type = RequestType.INSTALL_PERMISSION,
            callingPluginId = pluginId,
            details = mapOf(
                KEY_PLUGIN_NAME to name,
                KEY_PLUGIN_ICON_RES_ID to iconResId.toString(),
                KEY_PLUGIN_VERSION_NAME to versionName,
                KEY_PLUGIN_DESCRIPTION to description,
                KEY_SIGNATURE_HASH to signature,
                KEY_PLUGIN_APK_PATH to apkPath
            )
        )
    }
}