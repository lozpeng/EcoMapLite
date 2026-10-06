package org.cwcc.open.geokori.permission

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import timber.log.Timber

private const val TAG = "PermissionGate"

/**
 * 应用级权限门。
 *
 * 逻辑：
 *  1. 从宿主 AndroidManifest 读取运行时权限（带 fallback）
 *  2. 全部已授权 → 静默通过
 *  3. 有缺失 → 弹「权限总览」→ 用户点「去授权」→ 弹系统权限框
 *  4. 被拒 → 弹「说明 / 去设置」
 */
@Composable
fun AppPermissionGate(
    /** 传 null 表示自动从 Manifest 读取；也可显式指定权限列表 */
    permissions: List<String>? = null,
    title: String = "应用需要以下权限",
    hint: String = "为正常使用全部功能，请授予以下权限：",
    onResult: ((allGranted: Boolean) -> Unit)? = null,
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val controller = rememberPermissionController()

    // ★ 从 Manifest 读取权限；读取为空时回退到 FALLBACK
    val resolved = remember(permissions) {
        val fromManifest = permissions ?: appContext.getDeclaredRuntimePermissions()
        val finalList = if (fromManifest.isNullOrEmpty()) {
            Timber.tag(TAG).w("Manifest 读取为空，使用 FALLBACK 列表")
            FALLBACK_RUNTIME_PERMISSIONS
        } else {
            fromManifest
        }
        Timber.tag(TAG).i("最终申请权限列表: %s", finalList)
        finalList
    }

    // ★ 用 rememberSaveable 防止 Activity 重建时重复弹
    var showIntro by rememberSaveable { mutableStateOf(false) }
    var requestedOnce by rememberSaveable { mutableStateOf(false) }

    // ★ 用 LaunchedEffect(Unit) 只跑一次，避免 resolved 变化导致重启
    LaunchedEffect(Unit) {
        if (requestedOnce) {
            Timber.tag(TAG).d("已请求过，跳过")
            return@LaunchedEffect
        }
        requestedOnce = true

        val missing = resolved.filterNot { appContext.isPermissionGranted(it) }
        Timber.tag(TAG).d("缺失权限 (%d): %s", missing.size, missing)

        if (missing.isEmpty()) {
            Timber.tag(TAG).i("全部已授权，静默通过")
            onResult?.invoke(true)
        } else {
            Timber.tag(TAG).i("需要申请 %d 个权限，展示总览对话框", missing.size)
            showIntro = true
        }
    }

    if (showIntro) {
        // 每次都重新算一次 missing，避免权限在对话框打开期间被外部授予
        val missing = resolved.filterNot { appContext.isPermissionGranted(it) }

        if (missing.isEmpty()) {
            // 已经全授了，直接关掉
            showIntro = false
            onResult?.invoke(true)
        } else {
            PermissionIntroDialog(
                permissions = missing,
                title = title,
                hint = hint,
                onConfirm = {
                    Timber.tag(TAG).i("用户点击去授权，申请: %s", missing)
                    showIntro = false
                    controller.request(
                        PermissionRequest(
                            permissions = missing,
                            title = title,
                            description = hint,
                            confirmText = "继续授权",
                            dismissText = "暂不",
                            onResult = { ok, _ ->
                                Timber.tag(TAG).i("申请结果 allGranted=%s", ok)
                                onResult?.invoke(ok)
                            },
                        )
                    )
                },
                onDismiss = {
                    Timber.tag(TAG).i("用户取消授权")
                    showIntro = false
                    onResult?.invoke(false)
                },
            )
        }
    }

    PermissionDialogHost(controller)
}