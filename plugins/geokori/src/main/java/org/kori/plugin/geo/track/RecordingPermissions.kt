package org.kori.plugin.geo.track

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * 轨迹记录的权限/系统设置检查工具（后台记录前置条件）。
 *
 * ## 后台记录需要的三个运行时权限
 *
 *  | 权限 | 不授权的后果 | 申请方式 |
 *  |---|---|---|
 *  | 精/粗定位 | 无法记录 | 普通运行时弹框 |
 *  | POST_NOTIFICATIONS（API 33+） | 通知不显示，切后台看不到"停止"按钮 | 普通运行时弹框 |
 *  | ACCESS_BACKGROUND_LOCATION | **切后台 30 秒后 GPS 停止**，轨迹中断 | 系统设置页手动选"始终允许" |
 *
 * 后台定位无法用普通弹框直接授予（系统策略），只能引导用户去设置页。
 * [openAppSettings] 打开应用详情页，用户点"权限 → 位置信息 → 始终允许"。
 *
 * ## 省电策略
 *
 * [isIgnoringBatteryOptimizations] 检查是否在电池优化白名单；不在的话
 * [openBatteryOptimizationSettings] 引导用户关闭（可选但强烈建议，
 * 否则 Doze 模式下记录会暂停）。
 *
 * 国产 ROM（小米/华为/OPPO 等）还需用户在系统设置里开启"自启动"和
 * "后台运行"——无法通过代码完成，只能引导。
 */
object RecordingPermissions {

    /** 是否有前台定位权限（精或粗任一）。 */
    fun hasLocation(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    /** 是否有后台定位权限（"始终允许"）。API < 29 只要有前台定位即可。 */
    fun hasBackgroundLocation(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                ContextCompat.checkSelfPermission(
                    ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                ) == PackageManager.PERMISSION_GRANTED

    /** 是否有通知权限（API < 33 默认有）。 */
    fun hasNotifications(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    ctx, Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED

    /**
     * 可用普通弹框一次性申请的权限列表（定位 + 通知）。
     * 返回空列表 = 无需弹框。
     */
    fun requestablePermissions(): Array<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    /**
     * 是否具备开始后台记录的全部条件。
     * false 时先弹框申请 [requestablePermissions]，再引导后台定位设置。
     */
    fun allGranted(ctx: Context): Boolean =
        hasLocation(ctx) && hasBackgroundLocation(ctx) && hasNotifications(ctx)

    /** 打开本应用的系统设置页（授予后台定位 / 通知用）。 */
    fun openAppSettings(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", ctx.packageName, null),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** 是否在电池优化白名单。 */
    fun isIgnoringBatteryOptimizations(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    /** 打开"忽略电池优化"的授权弹框（需宿主 manifest 声明 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS）。 */
    fun requestIgnoreBatteryOptimizations(activity: Activity) {
        runCatching {
            activity.startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.fromParts("package", activity.packageName,null),
                ),
            )
        }
    }

    /** 打开电池优化设置页（无 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 权限时用）。 */
    fun openBatteryOptimizationSettings(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}