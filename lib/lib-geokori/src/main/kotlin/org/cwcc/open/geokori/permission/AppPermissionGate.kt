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

@Composable
fun AppPermissionGate(
    permissions: List<String>? = null,
    title: String = "应用需要以下权限",
    hint: String = "为正常使用全部功能，请授予以下权限：",
    onResult: ((allGranted: Boolean) -> Unit)? = null,
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val controller = rememberPermissionController()

    val resolved = remember(permissions) {
        val fromManifest = permissions ?: appContext.getDeclaredRuntimePermissions()
        val finalList = if (fromManifest.isNullOrEmpty()) {
            Timber.tag(TAG).w("Manifest 读取为空，使用 FALLBACK 列表")
            FALLBACK_RUNTIME_PERMISSIONS
        } else {
            fromManifest
        }
        finalList
    }

    var showIntro by rememberSaveable { mutableStateOf(false) }
    var requestedOnce by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (requestedOnce) return@LaunchedEffect
        requestedOnce = true

        val missing = resolved.filterNot { appContext.isPermissionGranted(it) }

        if (missing.isEmpty()) {
            onResult?.invoke(true)
        } else {
            showIntro = true
        }
    }

    if (showIntro) {
        // 每次重组时动态计算缺失权限
        val missing = resolved.filterNot { appContext.isPermissionGranted(it) }

        if (missing.isEmpty()) {
            showIntro = false
            onResult?.invoke(true)
        } else {
            PermissionIntroDialog(
                permissions = missing, // 传过去的是缺失的权限
                title = title,
                hint = hint,
                onConfirm = {
                    showIntro = false
                    controller.request(
                        PermissionRequest(
                            permissions = missing,
                            title = title,
                            description = hint,
                            confirmText = "继续授权",
                            dismissText = "暂不",
                            onResult = { ok, _ ->
                                onResult?.invoke(ok)
                            },
                        )
                    )
                },
                onDismiss = {
                    showIntro = false
                    onResult?.invoke(false)
                },
            )
        }
    }

    PermissionDialogHost(controller)
}