

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
