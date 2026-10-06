package org.cwcc.open.geokori.permission

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun PermissionDialog(
    title: String,
    message: String,
    confirmText: String = "确定",
    dismissText: String = "取消",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissText) } },
    )
}

@Composable
fun PermissionDialogHost(controller: PermissionController) {
    when (val state = controller.dialogState) {
        PermissionDialogState.None -> Unit

        is PermissionDialogState.Rationale -> PermissionDialog(
            title = state.request.title,
            message = buildString {
                append(state.request.description)
                append("\n\n待授权权限：")
                append(state.denied.joinToString("、") { it.permissionLabel() })
            },
            confirmText = state.request.confirmText,
            dismissText = state.request.dismissText,
            onConfirm = { controller.continueFromRationale() },
            onDismiss = { controller.dismissDialog() },
        )

        is PermissionDialogState.PermanentlyDenied -> PermissionDialog(
            title = "${state.request.title} 已被拒绝",
            message = buildString {
                append("以下权限已被系统永久拒绝，请前往「设置 → 权限」手动开启：\n\n")
                append(state.denied.joinToString("、") { it.permissionLabel() })
            },
            confirmText = "去设置",
            dismissText = "取消",
            onConfirm = { controller.goToSettings() },
            onDismiss = { controller.dismissDialog() },
        )
    }
}

@Composable
fun PermissionIntroDialog(
    permissions: List<String>,
    title: String = "应用需要以下权限",
    hint: String = "为正常使用全部功能，请授予以下权限：",
    confirmText: String = "去授权",
    dismissText: String = "稍后",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(hint, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                permissions.forEach { perm ->
                    Text(
                        text = "• ${perm.permissionLabel()}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                    Text(
                        text = perm,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissText) } },
    )
}