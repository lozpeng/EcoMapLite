package org.cwcc.open.geokori.permission

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

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

        is PermissionDialogState.BackgroundLocationRationale -> PermissionDialog(
            title = "后台定位权限说明",
            message = "为了在您退出应用或锁屏后仍能正常录制运动轨迹，下一步需要开启【后台定位】权限。\n\n在接下来的系统弹窗中，请务必选择【始终允许】，否则将无法记录完整的轨迹数据。",
            confirmText = "去开启",
            dismissText = "暂不开启",
            onConfirm = { controller.continueFromBackgroundRationale() },
            onDismiss = { controller.dismissDialog() }
        )
    }
}

/**
 * 美化后的权限总览对话框
 */
@Composable
fun PermissionIntroDialog(
    permissions: List<String>,
    title: String = "权限请求",
    hint: String = "获取以下权限，有助于「生态智图」为您提供更好的使用体验。您的个人信息仅用于以上用途，我们极度重视用户隐私，请放心使用。",
    confirmText: String = "同意并继续",
    dismissText: String = "稍后",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    // ★ 核心修改：在 UI 层内部再进行一次严格过滤，绝对不显示已授权的权限
    val pendingPermissions = remember(permissions) {
        permissions.filterNot { context.isPermissionGranted(it) }
    }

    // ★ 如果过滤后为空，说明权限在外部已经被授予完毕，直接不渲染此弹窗
    if (pendingPermissions.isEmpty()) {
        return
    }

    val uiModels = pendingPermissions.map { it.toPermissionUiModel() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .heightIn(max = 650.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 20.dp)
            ) {
                // 顶部标题与关闭按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 22.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "关闭",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 权限列表
                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(uiModels) { model ->
                        PermissionItemRow(model = model, onAllowClick = onConfirm)
                        HorizontalDivider(
                            modifier = Modifier.padding(top = 16.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(text = dismissText)
                    }
                    Button(
                        onClick = onConfirm,
                        modifier = Modifier.weight(2f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(text = confirmText)
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionItemRow(
    model: PermissionUiModel,
    onAllowClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = model.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = model.title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = model.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

//        TextButton(
//            onClick = onAllowClick,
//            colors = ButtonDefaults.textButtonColors(
//                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
//                contentColor = MaterialTheme.colorScheme.primary
//            ),
//            shape = RoundedCornerShape(16.dp),
//            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
//        ) {
//            Text(text = "允许", fontWeight = FontWeight.Bold)
//        }
    }
}