

package com.combo.core.model

import kotlinx.serialization.Serializable

@Serializable
data class PluginInfo(
    val id: String,
    val name: String,
    val iconResId: Int,
    val versionCode: Long,
    val versionName: String,
    val path: String,
    val entryClass: String,
    var description: String,
    val enabled: Boolean,
    val installTime: Long,
    val staticReceivers: List<StaticReceiverInfo> = emptyList(),
    val providers: List<ProviderInfo> = emptyList(),
)

@Serializable
data class MetaDataInfo(
    val name: String,
    val value: String?,
    val resource: Int?,
)

@Serializable
data class IntentFilterInfo(
    val actions: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    val schemes: List<String> = emptyList(),
)

/**
 * 描述一个静态广播接收器的信息
 * @param className 接收器的完整类名
 * @param enabled 是否启用
 * @param exported 是否导出
 * @param intentFilters 它声明的意图过滤器列表
 */
@Serializable
data class StaticReceiverInfo(
    val className: String,
    val enabled: Boolean,
    val exported: Boolean,
    val intentFilters: List<IntentFilterInfo> = emptyList(),
)

/**
 * 描述一个插件 ContentProvider 的元数据信息
 * @param className Provider 的完整类名
 * @param authorities 它声明的授权列表 (例如 "com.plugin.notes")
 * @param enabled 是否启用
 * @param exported 是否导出
 * @param metaData 它声明的元数据列表
 */
@Serializable
data class ProviderInfo(
    val className: String,
    val authorities: List<String>,
    val enabled: Boolean,
    val exported: Boolean,
    val metaData: List<MetaDataInfo> = emptyList(),
)
