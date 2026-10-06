package org.kori.plugin.wildlife.screen

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.cwcc.open.geokori.framework.PluginModuleUtils
import org.cwcc.open.geokori.map.GeoPackageLayers
import org.cwcc.open.geokori.map.GpkgImportState
import org.cwcc.open.geokori.map.MapLayerManager
import org.cwcc.open.geokori.map.rememberGpkgImport
import org.cwcc.open.geokori.ui.material3.center.model.CollapsedQuickActions
import org.cwcc.open.geokori.ui.material3.center.model.ExpandedQuickActions
import org.cwcc.open.geokori.ui.material3.center.model.QuickActionSpec
import org.cwcc.open.plugin.wildlife.viewmodel.WildLifeViewModel
import org.koin.androidx.compose.koinViewModel
import org.kori.plugin.wildlife.actions.WfActionType
import org.kori.plugin.wildlife.actions.WfBizAction
import org.kori.plugin.wildlife.actions.defaultBizQuickActions
import org.kori.plugin.wildlife.actions.defaultWildLifeActions
import java.io.File



/**
 * 野生动植物监管屏（v4 · GeoPackage 导入）。
 *
 *  · SpeedDial"打开" → 文件选择器 → 硬链接进 filesDir + 注册 + 立即加载
 *  · 打开信息持久化到 filesDir/gpkg_manifest.json（含属主插件）
 *  · bizActions 动态追加本插件导入的 gpkg 按钮（懒注册拦截，重启后可再加载）
 *  · 其余 v3 行为不变：类型化 Action 分发、checked/loading 状态合并
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)
@Composable
fun WildLifeScreen(
    quickActions: List<QuickActionSpec> = defaultWildLifeActions(),
    bizActions: List<WfBizAction> = defaultBizQuickActions(),
    viewModel: WildLifeViewModel = koinViewModel(),
    expand: Boolean = true,
) {
    val view = LocalView.current
    val activity = view.context.findActivity()
    val window = activity?.window
    val context = LocalContext.current

    val state by viewModel.uiState.collectAsState()

    var isQuickActionsExpanded by remember { mutableStateOf(expand) }
    var isSpeedDialExpanded by remember { mutableStateOf(false) }

    // ★ gpkg 按钮编辑模式（长按进入：抖动 + 红底减号角标）
    var gpkgEditMode by remember { mutableStateOf(false) }

    // ★ 业务动作分发器：gpkg 导入状态 + DIALOG 弹窗全部内化，调用方只管 onClick
    val dispatcher = rememberActionDispatcher()

    val pullRefreshState = rememberPullRefreshState(
        refreshing = state.isLoading,
        onRefresh = { viewModel.refreshWildLifeData() }
    )

    // ★ 框架级图层管理器状态 → 合并进 WfBizAction
    val layerStates by MapLayerManager.states.collectAsState()

    // ★ 静态业务按钮（gpkg 动态按钮改由 EditableQuickActions 渲染，支持长按编辑）
    val mergedBizActions = remember(bizActions, layerStates) {
        bizActions.map { action ->
            val st = MapLayerManager.layerStateOf(action.id)
            if (st == null) action
            else action.copy(checked = st.active, loading = st.loading)
        }
    }

    // gpkg 动态按钮（编辑模式可移除；checked/loading 与静态按钮同源）
    val gpkgActions = remember(dispatcher.gpkg.imported, layerStates) {
        dispatcher.gpkg.imported.map { imp ->
            val st = MapLayerManager.layerStateOf(imp.layerId)
            WfBizAction(
                label = imp.label,
                type = WfActionType.GPKG,
                payload = imp.fileName,
                id = imp.layerId,
                icon = Icons.Default.Layers,
                checked = st?.active == true,
                loading = st?.loading == true,
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("野生动植物监管", fontWeight = FontWeight.Bold) },
            )
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .pullRefresh(pullRefreshState)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Spacer(modifier = Modifier.width(6.dp))
                }
                item {
                    AnimatedContent(
                        targetState = isQuickActionsExpanded,
                        label = "quick_actions"
                    ) { expanded ->
                        if (expanded) {
                            ExpandedQuickActions(
                                actions = quickActions,
                                onActionClick = dispatcher.onClick,
                            )
                        } else {
                            CollapsedQuickActions(
                                actions = quickActions.take(4),
                                onActionClick = dispatcher.onClick,
                            )
                        }
                    }
                }
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "快捷操作",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF202124)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    AnimatedContent(
                        targetState = isQuickActionsExpanded,
                        label = "wildlife_biz_actions"
                    ) { expanded ->
                        if (expanded) {
                            Column {
                                ExpandedQuickActions(
                                    actions = mergedBizActions,
                                    onActionClick = dispatcher.onClick,
                                )
                                // ★ gpkg 动态按钮：可编辑（长按抖动 + 减号角标移除）
                                if (gpkgActions.isNotEmpty()) {
                                    EditableQuickActions(
                                        actions = gpkgActions,
                                        editMode = gpkgEditMode,
                                        onEditModeChange = { gpkgEditMode = it },
                                        onClick = dispatcher.onClick,
                                        onRemove = { dispatcher.gpkg.remove(it.payload) },
                                    )
                                }
                                if (gpkgEditMode) {
                                    TextButton(onClick = { gpkgEditMode = false }) {
                                        Text("完成")
                                    }
                                }
                            }
                        } else {
                            CollapsedQuickActions(
                                actions = mergedBizActions.take(4),
                                onActionClick = dispatcher.onClick,
                            )
                        }
                    }
                }
            }

            SpeedDialFAB(
                isExpanded = isSpeedDialExpanded,
                onExpandedChange = { isSpeedDialExpanded = it },
                onActionClick = { action ->
                    when (action) {
                        // ★ 打开：唤起 gpkg 文件选择器（原 Toast 替换）
                        "open_folder" -> dispatcher.gpkg.launchPicker()
                        "add_record" -> Toast.makeText(context, "添加记录", Toast.LENGTH_SHORT).show()
                        "show_info" -> dispatcher.showDialog("info")
                        "sync_data" -> Toast.makeText(context, "同步数据", Toast.LENGTH_SHORT).show()
                    }
                    isSpeedDialExpanded = false
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }

}

@Composable
fun SpeedDialFAB(
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onActionClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val fabSize = 56.dp
    val subButtonSize = 48.dp

    var isRightHandMode by remember { mutableStateOf(false) }

    val scale = remember { Animatable(if (isExpanded) 1f else 0f) }

    LaunchedEffect(isExpanded) {
        scale.animateTo(
            targetValue = if (isExpanded) 1f else 0f,
            animationSpec = tween(
                durationMillis = 300,
                easing = FastOutSlowInEasing
            )
        )
    }

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .align(if (isRightHandMode) Alignment.BottomStart else Alignment.BottomEnd)
                .padding(16.dp)
                .size(fabSize + 2.dp)
                .drawBehind {
                    val strokeWidth = 2f
                    val dashLength = 8f
                    val gapLength = 6f
                    val color = Color.Red

                    drawRoundRect(
                        color = color,
                        style = Stroke(width = strokeWidth),
                        cornerRadius = CornerRadius(8.0f)
                    )

                    val centerX = size.width / 2
                    val centerY = size.height / 2
                    drawLine(
                        color = color,
                        start = Offset(centerX, 0f),
                        end = Offset(centerX, size.height),
                        strokeWidth = strokeWidth,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashLength, gapLength))
                    )
                    drawLine(
                        color = color,
                        start = Offset(0f, centerY),
                        end = Offset(size.width, centerY),
                        strokeWidth = strokeWidth,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashLength, gapLength))
                    )
                }
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) {
                    isRightHandMode = !isRightHandMode
                    if (isExpanded) {
                        onExpandedChange(false)
                    }
                    Toast.makeText(
                        context,
                        if (isRightHandMode) "已切换到右手模式" else "已切换到左手模式",
                        Toast.LENGTH_SHORT
                    ).show()
                }
        ) {}

        Column(
            modifier = Modifier
                .align(if (isRightHandMode) Alignment.BottomEnd else Alignment.BottomStart)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (isExpanded) {
                SpeedDialActionButton(
                    icon = Icons.Default.FolderOpen,
                    label = "打开",
                    color = Color(0xFF2196F3),
                    onClick = {
                        onActionClick("open_folder")
                        onExpandedChange(false)
                    },
                    modifier = Modifier
                        .size(subButtonSize)
                        .scale(scale.value)
                )
            }

            if (isExpanded) {
                SpeedDialActionButton(
                    icon = Icons.Default.Info,
                    label = "信息",
                    color = Color(0xFFFF9800),
                    onClick = {
                        onActionClick("show_info")
                        onExpandedChange(false)
                    },
                    modifier = Modifier
                        .size(subButtonSize)
                        .scale(scale.value)
                )
            }

            if (isExpanded) {
                SpeedDialActionButton(
                    icon = Icons.Default.Add,
                    label = "添加",
                    color = Color(0xFF4CAF50),
                    onClick = {
                        onActionClick("add_record")
                        onExpandedChange(false)
                    },
                    modifier = Modifier
                        .size(subButtonSize)
                        .scale(scale.value)
                )
            }

            FloatingActionButton(
                onClick = { onExpandedChange(!isExpanded) },
                containerColor = if (isExpanded) Color(0xFFF44336) else Color(0xFF4CAF50),
                contentColor = Color.White,
                modifier = Modifier.size(fabSize)
            ) {
                Icon(
                    imageVector = if (isExpanded) Icons.Default.Close else Icons.Default.Add,
                    contentDescription = if (isExpanded) "关闭" else "展开",
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}

@Composable
fun SpeedDialActionButton(
    icon: ImageVector,
    label: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FloatingActionButton(
        onClick = onClick,
        containerColor = color,
        contentColor = Color.White,
        modifier = modifier
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(24.dp)
        )
    }
}

/**
 * 业务动作分发器：持有 gpkg 导入状态，DIALOG 弹窗 UI 由本 Composable 直接渲染；
 * 屏幕侧任何按钮统一 `dispatcher.onClick(action)`，类型路由全部内化。
 */
class ActionDispatcher(
    /** gpkg 导入状态（按钮列表 / 选择器 / 打开） */
    val gpkg: GpkgImportState,
    /** 统一动作入口 */
    val onClick: (QuickActionSpec) -> Unit,
    /** 直接弹 DIALOG 对话框（payload 决定内容；SpeedDial 等非 action 入口用） */
    val showDialog: (String) -> Unit,
)

/**
 * 记住分发器：一行接入，替代散落的 dialog 状态 + gpkg 状态 + 分发函数。
 *
 * 类型路由：
 *  · LAYER  → MapLayerManager.toggle（已注册前置校验）
 *  · GPKG   → 本插件导入的 gpkg 按钮：懒注册（补登记）后 toggle
 *  · DIALOG → AlertDialog（payload 决定内容，新内容加 dialogTitle/dialogContent 分支）
 *  · BOTTOM_SHEET / TOAST 照旧
 *  · 非 WfBizAction 的 QuickActionSpec 退回 label Toast 兼容
 */
@Composable
private fun rememberActionDispatcher(): ActionDispatcher {
    val context = LocalContext.current
    val gpkg = rememberGpkgImport()
    val dialogPayload = remember { mutableStateOf<String?>(null) }

    // DIALOG 的 UI 由分发器自己渲染（不污染屏幕层状态）
    dialogPayload.value?.let { payload ->
        AlertDialog(
            onDismissRequest = { dialogPayload.value = null },
            title = { Text(dialogTitle(payload)) },
            text = { Text(dialogContent(payload)) },
            confirmButton = {
                TextButton(onClick = { dialogPayload.value = null }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { dialogPayload.value = null }) { Text("取消") }
            },
        )
    }

    val onClick = remember(gpkg) {
        { action: QuickActionSpec ->
            if (action is WfBizAction) {
                when (action.type) {
                    WfActionType.LAYER -> {
                        if (action.id.isNotEmpty() && MapLayerManager.isRegistered(action.id)) {
                            MapLayerManager.toggle(action.id, context)
                        } else {
                            Toast.makeText(context, "图层未注册:${action.id}", Toast.LENGTH_SHORT).show()
                        }
                    }
                    WfActionType.GPKG -> {
                        val id = action.id.ifBlank {
                            action.payload.takeIf { it.isNotBlank() }
                                ?.let { GeoPackageLayers.idOf(File(context.filesDir, it)) }
                                .orEmpty()
                        }
                        when {
                            id.isEmpty() ->
                                Toast.makeText(context, "gpkg 按钮缺少 id/payload", Toast.LENGTH_SHORT).show()
                            !MapLayerManager.isRegistered(id) ->
                                gpkg.open(action.payload)   // 重启后首次点击：补注册再 toggle
                            else -> MapLayerManager.toggle(id, context)
                        }
                    }
                    WfActionType.DIALOG ->
                        dialogPayload.value = action.payload.ifBlank { "info" }
                    WfActionType.BOTTOM_SHEET -> when (action.payload) {
                        "habitat" -> PluginModuleUtils.showBottomSheet(
                            content = { ElephantMonitoringContent() },
                            title = action.label,
                            isNormalActivity = false,
                        )
                        else -> Toast.makeText(context, action.label, Toast.LENGTH_SHORT).show()
                    }
                    WfActionType.TOAST ->
                        Toast.makeText(context, action.label, Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(context, "点击: ${action.label}", Toast.LENGTH_SHORT).show()
            }
            Unit   // ← 各分支返回值不统一（toggle→Boolean、makeText→Toast），强制 lambda 为 Unit
        }
    }

    return ActionDispatcher(
        gpkg = gpkg,
        onClick = onClick,
        showDialog = { payload -> dialogPayload.value = payload },
    )
}

// =================================================================================================
// 可编辑快捷按钮（iOS 卸载式：长按抖动 + 红底减号角标）
// =================================================================================================

/**
 * 可编辑业务按钮网格。
 *
 * @param removable 扩展点：哪些 action 可移除（默认仅 GPKG；后续类型加进谓词即可参与编辑）
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun EditableQuickActions(
    actions: List<WfBizAction>,
    editMode: Boolean,
    onEditModeChange: (Boolean) -> Unit,
    onClick: (QuickActionSpec) -> Unit,
    onRemove: (WfBizAction) -> Unit,
    removable: (WfBizAction) -> Boolean = { it.type == WfActionType.GPKG },
) {
    // iOS 式抖动：±2° 往复 + 轻微横移
    val shake = rememberInfiniteTransition(label = "editShake")
    val angle by shake.animateFloat(
        initialValue = -2f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(120, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "angle",
    )

    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        actions.forEach { action ->
            val editable = removable(action)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box {
                    // 圆圈按钮（与框架 ExpandedQuickActions 同款结构）
                    // checked → 高亮环；loading → 转圈替代图标
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .graphicsLayer {
                                if (editMode && editable) {
                                    rotationZ = angle
                                    translationX = angle * 0.6f
                                }
                            }
                            .background(action.containerColor, CircleShape)
                            .then(
                                if (action.checked && !editMode) {
                                    Modifier.border(
                                        width = 2.dp,
                                        color = action.checkedContainerColor,
                                        shape = CircleShape,
                                    )
                                } else Modifier
                            )
                            .combinedClickable(
                                onClick = {
                                    if (editMode) onEditModeChange(false)  // 编辑态点空白处退出
                                    else onClick(action)
                                },
                                onLongClick = { if (editable) onEditModeChange(true) },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            action.loading ->
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.dp,
                                    color = action.contentColor,
                                )
                            else -> Icon(
                                imageVector = action.icon ?: Icons.Default.Layers,
                                contentDescription = null,
                                tint = action.contentColor,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }

                    // 红底减号角标（编辑态）
                    if (editMode && editable) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(4.dp, (-4).dp)
                                .size(20.dp)
                                .background(Color(0xFFF44336), CircleShape)
                                .clickable { onRemove(action) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "−",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
                // 圆圈下方文字
                Text(
                    text = action.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

private fun dialogTitle(payload: String): String = when (payload) {
    "info" -> "提示信息"
    "about" -> "关于"
    else -> "提示"
}

private fun dialogContent(payload: String): String = when (payload) {
    "info" -> "这是野生动物监管插件的浮动对话框。\n您可以在这里显示重要信息或操作提示。"
    "about" -> "野生动植物监管插件 v1.0"
    else -> payload
}

@Composable
fun ElephantMonitoringContent() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "当前状态",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "✅ 正常运行中",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.Green
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize()
        ) {
            items(20) { index ->
                ListItem(
                    headlineContent = { Text("监测点 ${index + 1}") },
                    supportingContent = {
                        Text("状态: 正常 • 更新时间: 2026-09-07")
                    },
                    leadingContent = {
                        Icon(
                            Icons.Default.RadioButtonChecked,
                            contentDescription = null,
                            tint = Color.Green
                        )
                    }
                )
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}