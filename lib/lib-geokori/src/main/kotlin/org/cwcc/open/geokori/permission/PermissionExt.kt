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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import timber.log.Timber

private const val TAG = "PermissionGate"

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

fun Context.canDrawOverlays(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        Settings.canDrawOverlays(this)
    } else true
}

fun Context.openOverlaySettings() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        )
    }
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

data class PermissionUiModel(
    val permission: String,
    val title: String,
    val description: String,
    val icon: ImageVector
)

fun String.toPermissionUiModel(): PermissionUiModel {
    return when (this) {
        Manifest.permission.CAMERA -> PermissionUiModel(
            this, "相机", "用于拍摄照片、扫描二维码等", Icons.Default.CameraAlt
        )
        Manifest.permission.RECORD_AUDIO -> PermissionUiModel(
            this, "麦克风", "用于语音输入、发送语音消息", Icons.Default.Mic
        )
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION -> PermissionUiModel(
            this, "地理位置（前台）", "用于获取天气和基本定位服务", Icons.Default.LocationOn
        )
        Manifest.permission.ACCESS_BACKGROUND_LOCATION -> PermissionUiModel(
            this, "后台定位（关键）", "为了确保在后台能正常录制您的运动轨迹，请务必在系统弹窗中选择【始终允许】", Icons.Default.MyLocation
        )
        Manifest.permission.POST_NOTIFICATIONS -> PermissionUiModel(
            this, "通知", "用于推送消息，或在后台录制轨迹时显示实时状态", Icons.Default.Notifications
        )
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.READ_MEDIA_IMAGES -> PermissionUiModel(
            this, "读取图片", "用于选择头像或上传图片", Icons.Default.Image
        )
        Manifest.permission.ACCESS_MEDIA_LOCATION -> PermissionUiModel(
            this, "照片位置信息", "用于读取照片拍摄时的位置信息", Icons.Default.PhotoCamera
        )
        else -> PermissionUiModel(
            this, permissionLabel(), "需要使用此权限以提供完整功能", Icons.Default.Info
        )
    }
}

fun Context.getDeclaredRuntimePermissions(): List<String> {
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

    return declared
        .filter { perm -> perm.isDangerousPermission(app) }
        .filterNot { it.isObsoleteOnCurrentSdk() }
        .distinct()
}

private fun String.isDangerousPermission(context: Context): Boolean = try {
    val info = context.packageManager.getPermissionInfo(this, 0)
    (info.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE) ==
            PermissionInfo.PROTECTION_DANGEROUS
} catch (e: PackageManager.NameNotFoundException) {
    false
}

private fun String.isObsoleteOnCurrentSdk(): Boolean = when (this) {
    Manifest.permission.READ_EXTERNAL_STORAGE,
    Manifest.permission.WRITE_EXTERNAL_STORAGE,
        -> Build.VERSION.SDK_INT >= 33
    else -> false
}

val FALLBACK_RUNTIME_PERMISSIONS: List<String> = buildList {
    add(Manifest.permission.CAMERA)
    add(Manifest.permission.RECORD_AUDIO)
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }
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