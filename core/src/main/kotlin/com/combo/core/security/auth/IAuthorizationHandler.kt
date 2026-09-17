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

import com.combo.core.model.AuthorizationRequest

/**
 * 插件框架统一授权处理器接口。
 *
 * 宿主 App 可以实现此接口，并通过 `PluginManager.setAuthorizationHandler` 进行注册，
 * 以自定义处理所有需要用户确认的敏感操作，如高权限API调用、未知签名插件安装等。
 */
interface IAuthorizationHandler {

    /**
     * 当需要处理一个授权请求时，框架会调用此方法。
     *
     * 这是一个 suspend 函数，允许您在实现中执行异步操作，例如显示一个对话框、
     * 发起一个网络请求，并等待用户的选择或服务器的响应。
     *
     * @param request 包含本次授权请求详细信息的 [AuthorizationRequest] 对象。
     * @return `true` 如果用户或业务逻辑同意授权，`false` 如果拒绝。
     */
    suspend fun onAuthorizationRequest(request: AuthorizationRequest): Boolean
}