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