

package com.combo.core.security.permission

/**
 * 标记一个方法需要特定的权限才能被插件调用。
 * @param level 所需的最低权限等级。
 * @param hardFail 是否为硬性需求，默认为 `false`。
 * 如果为 `true`，则表示该方法调用时必须具有指定的权限。
 * 如果为 `false`，则表示该方法调用时如果没有权限，会走申请权限流程。
 * @see[PermissionLevel]
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RequiresPermission(
    val level: PermissionLevel,
    val hardFail: Boolean = false
)