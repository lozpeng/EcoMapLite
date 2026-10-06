package org.cwcc.open.geokori.permission

import android.app.Activity
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

data class PermissionRequest(
    val permissions: List<String>,
    val title: String,
    val description: String,
    val confirmText: String = "继续授权",
    val dismissText: String = "暂不",
    val onResult: ((allGranted: Boolean, denied: List<String>) -> Unit)? = null,
)

sealed interface PermissionDialogState {
    data object None : PermissionDialogState

    data class Rationale(
        val request: PermissionRequest,
        val denied: List<String>,
    ) : PermissionDialogState

    data class PermanentlyDenied(
        val request: PermissionRequest,
        val denied: List<String>,
    ) : PermissionDialogState
}

@Stable
class PermissionController internal constructor(
    private val context: Context,
    private val activity: Activity?,
) {
    internal var dialogState by mutableStateOf<PermissionDialogState>(PermissionDialogState.None)
        private set

    internal var launch: ((Array<String>) -> Unit)? = null

    private var currentRequest: PermissionRequest? = null

    fun request(request: PermissionRequest) {
        currentRequest = request

        val missing = request.permissions.filterNot { context.isPermissionGranted(it) }
        if (missing.isEmpty()) {
            request.onResult?.invoke(true, emptyList())
            return
        }

        val needExplain = missing.filter {
            activity?.shouldShowPermissionRationale(it) == true
        }
        if (needExplain.isNotEmpty()) {
            dialogState = PermissionDialogState.Rationale(request, missing)
        } else {
            launch?.invoke(missing.toTypedArray())
        }
    }

    fun isGranted(permissions: List<String>): Boolean =
        context.arePermissionsGranted(permissions)

    internal fun continueFromRationale() {
        val s = dialogState as? PermissionDialogState.Rationale ?: return
        dialogState = PermissionDialogState.None
        launch?.invoke(s.denied.toTypedArray())
    }

    internal fun goToSettings() {
        dialogState = PermissionDialogState.None
        context.openAppSettings()
    }

    internal fun dismissDialog() {
        val s = dialogState
        dialogState = PermissionDialogState.None
        val req = when (s) {
            is PermissionDialogState.Rationale -> s.request
            is PermissionDialogState.PermanentlyDenied -> s.request
            else -> currentRequest
        }
        val denied = when (s) {
            is PermissionDialogState.Rationale -> s.denied
            is PermissionDialogState.PermanentlyDenied -> s.denied
            else -> emptyList()
        }
        req?.onResult?.invoke(false, denied)
    }

    internal fun onSystemResult(result: Map<String, Boolean>) {
        val req = currentRequest ?: return
        val denied = result.filterValues { !it }.keys.toList()
        if (denied.isEmpty()) {
            req.onResult?.invoke(true, emptyList())
            return
        }
        val canAskAgain = denied.any {
            activity?.shouldShowPermissionRationale(it) == true
        }
        dialogState = if (canAskAgain) {
            PermissionDialogState.Rationale(req, denied)
        } else {
            PermissionDialogState.PermanentlyDenied(req, denied)
        }
    }
}

@Composable
fun rememberPermissionController(): PermissionController {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val controller = remember(context) { PermissionController(context, activity) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        controller.onSystemResult(result)
    }

    SideEffect {
        controller.launch = { perms -> launcher.launch(perms) }
    }

    return controller
}