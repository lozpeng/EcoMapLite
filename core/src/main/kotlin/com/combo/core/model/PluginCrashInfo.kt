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

package com.combo.core.model

import java.io.Serializable


/**
 * 封装了插件崩溃的详细信息。
 *
 * @param throwable 捕获到的原始异常。
 * @param culpritPluginId 引发崩溃的插件ID。如果无法确定，则为 null。
 * @param defaultMessage 框架根据异常类型生成的默认提示信息。
 */
data class PluginCrashInfo(
    val throwable: Throwable,
    val culpritPluginId: String?,
    val defaultMessage: String
) : Serializable
