package org.cwcc.open.geokori.permission

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import timber.log.Timber

private const val TAG = "PermissionGate"

/* ---------------- 基础扩展 ---------------- */

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

fun Context.isPermissionGranted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

fun Context.arePermissionsGranted(permissions: List<String>): Boolean =
    permissions.all { isPermissionGranted(it) }

fun Activity.shouldShowPermissionRationale(permission: String): Boolean =
    ActivityCompat.shouldShowRequestPermissionRationale(this, permission)

fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    )
}

fun String.permissionLabel(): String = when (this) {
    Manifest.permission.CAMERA -> "相机"
    Manifest.permission.RECORD_AUDIO -> "麦克风"
    Manifest.permission.ACCESS_FINE_LOCATION -> "精确位置"
    Manifest.permission.ACCESS_COARSE_LOCATION -> "粗略位置"
    Manifest.permission.ACCESS_BACKGROUND_LOCATION -> "后台位置"
    Manifest.permission.POST_NOTIFICATIONS -> "通知"
    Manifest.permission.BLUETOOTH_CONNECT -> "蓝牙连接"
    Manifest.permission.READ_MEDIA_IMAGES -> "读取图片"
    Manifest.permission.READ_MEDIA_VIDEO -> "读取视频"
    Manifest.permission.READ_MEDIA_AUDIO -> "读取音频"
    Manifest.permission.READ_EXTERNAL_STORAGE -> "读取存储"
    Manifest.permission.ACCESS_MEDIA_LOCATION -> "照片位置信息"
    Manifest.permission.ACCESS_NETWORK_STATE -> "网络状态"
    Manifest.permission.RECEIVE_BOOT_COMPLETED -> "开机自启"
    Manifest.permission.SCHEDULE_EXACT_ALARM -> "精确闹钟"
    Manifest.permission.FOREGROUND_SERVICE -> "前台服务"
    Manifest.permission.FOREGROUND_SERVICE_LOCATION -> "前台定位服务"
    Manifest.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE -> "前台设备服务"
    Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC -> "前台数据同步"
    Manifest.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK -> "前台媒体播放"
    else -> substringAfterLast('.')
}

/* ---------------- ★ 从 AndroidManifest 读取运行时权限 ---------------- */

/**
 * 读取**宿主 App**（不是插件）的 AndroidManifest 中声明的运行时权限。
 *
 * 关键点：
 *  - 使用 applicationContext，避免 Combolite 插件框架下 LocalContext 被改写为插件 Context
 *  - 通过 PermissionInfo.protectionLevel 过滤出 dangerous 权限
 *  - 剔除在新 SDK 上已失效的权限
 *  - 打印日志，方便定位
 */
fun Context.getDeclaredRuntimePermissions(): List<String> {
    // ★ 关键：使用 applicationContext 读取宿主包信息
    val app = applicationContext
    val pm = app.packageManager
    val pkgName = app.packageName

    val pkgInfo = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(
                pkgName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkgName, PackageManager.GET_PERMISSIONS)
        }
    } catch (e: PackageManager.NameNotFoundException) {
        Timber.tag(TAG).e(e, "getPackageInfo failed: $pkgName")
        return emptyList()
    }

    val declared = pkgInfo.requestedPermissions?.toList().orEmpty()
    Timber.tag(TAG).d("packageName=$pkgName")
    Timber.tag(TAG).d("declared permissions (%d): %s", declared.size, declared)

    val runtime = declared
        .filter { perm ->
            val ok = perm.isDangerousPermission(app)
            Timber.tag(TAG).d("  %s -> dangerous=%s", perm, ok)
            ok
        }
        .filterNot { it.isObsoleteOnCurrentSdk() }
        .distinct()

    Timber.tag(TAG).d("runtime permissions (%d): %s", runtime.size, runtime)
    return runtime
}

private fun String.isDangerousPermission(context: Context): Boolean = try {
    val info = context.packageManager.getPermissionInfo(this, 0)
    (info.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE) ==
            PermissionInfo.PROTECTION_DANGEROUS
} catch (e: PackageManager.NameNotFoundException) {
    Timber.tag(TAG).w("permission not found on this SDK: $this")
    false
}

private fun String.isObsoleteOnCurrentSdk(): Boolean = when (this) {
    Manifest.permission.READ_EXTERNAL_STORAGE,
    Manifest.permission.WRITE_EXTERNAL_STORAGE,
        -> Build.VERSION.SDK_INT >= 33

    else -> false
}

/**
 * ★ 兜底：如果动态读取失败（返回空），使用这份静态列表。
 * 这份列表必须与 AndroidManifest 里的 dangerous 权限保持一致。
 */
val FALLBACK_RUNTIME_PERMISSIONS: List<String> = buildList {
    add(Manifest.permission.CAMERA)
    add(Manifest.permission.RECORD_AUDIO)
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    add(Manifest.permission.ACCESS_MEDIA_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
        add(Manifest.permission.READ_MEDIA_IMAGES)
    } else {
        add(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_CONNECT)
    }
}