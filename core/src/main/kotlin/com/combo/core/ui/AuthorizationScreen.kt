

package com.combo.core.ui

import androidx.compose.runtime.Composable
import com.combo.core.model.AuthorizationRequest

/**
 * 授权页面的路由 Composable
 * 根据请求类型，决定显示具体的授权UI
 */
@Composable
fun AuthorizationScreen(
    request: AuthorizationRequest,
    onResult: (Boolean) -> Unit,
    onExit: () -> Unit,
) {
    when (request.type) {
        AuthorizationRequest.RequestType.API_PERMISSION -> {
            ApiPermissionScreen(
                request = request,
                onResult = onResult,
                onExit = onExit
            )
        }
        AuthorizationRequest.RequestType.INSTALL_PERMISSION -> {
            InstallPermissionScreen(
                request = request,
                onResult = onResult,
                onExit = onExit
            )
        }
    }
}