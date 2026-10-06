package org.kori.plugin.wildlife.screen

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.cwcc.open.geokori.framework.PluginModuleUtils
import org.cwcc.open.geokori.map.MapLayerManager
import org.cwcc.open.geokori.ui.material3.center.model.CollapsedQuickActions
import org.cwcc.open.geokori.ui.material3.center.model.ExpandedQuickActions
import org.cwcc.open.geokori.ui.material3.center.model.QuickActionSpec
import org.cwcc.open.plugin.wildlife.viewmodel.WildLifeViewModel
import org.koin.androidx.compose.koinViewModel
import org.kori.plugin.wildlife.actions.WfActionType
import org.kori.plugin.wildlife.actions.WfBizAction
import org.kori.plugin.wildlife.actions.defaultBizQuickActions
import org.kori.plugin.wildlife.actions.defaultWildLifeActions

/**
 * 野生动植物监管屏（v3 · 类型化 Action）。
 *
 *  · 业务按钮全部使用 [WfBizAction]（QuickActionSpec 插件实现），
 *    分发按 action.type 类型路由，不再匹配中文字符串 label
 *  · 图层类（LAYER）→ MapLayerManager.toggle(action.id)，Session 自动注入
 *  · checked/loading → collect MapLayerManager.states 合并进列表（copy 替换重组）
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
    var showFloatingDialog by remember { mutableStateOf(false) }

    val pullRefreshState = rememberPullRefreshState(
        refreshing = state.isLoading,
        onRefresh = { viewModel.refreshWildLifeData() }
    )

    // ★ 框架级图层管理器状态 → 合并进 WfBizAction
    // 注意：states 的 key 是 fullId（pluginId:layerId），action.id 可能是裸 layerId，
    // 统一经 MapLayerManager.layerStateOf(id) 解析（支持两种 id，未绑定返回 null）
    val layerStates by MapLayerManager.states.collectAsState()

    val mergedBizActions = remember(bizActions, layerStates) {
        bizActions.map { action ->
            val st = MapLayerManager.layerStateOf(action.id)
            if (st == null) action
            else action.copy(checked = st.active, loading = st.loading)
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
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Info,
                            contentDescription = "信息",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                    }
                }
                item {
                    AnimatedContent(
                        targetState = isQuickActionsExpanded,
                        label = "quick_actions"
                    ) { expanded ->
                        if (expanded) {
                            ExpandedQuickActions(
                                actions = quickActions,
                                onActionClick = { action ->
                                    handleQuickActionClick(action, context)
                                },
                            )
                        } else {
                            CollapsedQuickActions(
                                actions = quickActions.take(4),
                                onActionClick = { action ->
                                    handleQuickActionClick(action, context)
                                },
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
                            fontSize = 18.sp ,
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
                            ExpandedQuickActions(
                                actions = mergedBizActions,
                                onActionClick = { action ->
                                    handleQuickActionClick(action, context)
                                },
                            )
                        } else {
                            CollapsedQuickActions(
                                actions = mergedBizActions.take(4),
                                onActionClick = { action ->
                                    handleQuickActionClick(action, context)
                                },
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
                        "open_folder" -> Toast.makeText(context, "打开文件夹", Toast.LENGTH_SHORT).show()
                        "add_record" -> Toast.makeText(context, "添加记录", Toast.LENGTH_SHORT).show()
                        "show_info" -> showFloatingDialog = true
                        "sync_data" -> Toast.makeText(context, "同步数据", Toast.LENGTH_SHORT).show()
                    }
                    isSpeedDialExpanded = false
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    if (showFloatingDialog) {
        AlertDialog(
            onDismissRequest = { showFloatingDialog = false },
            title = { Text("提示信息") },
            text = { Text("这是野生动物监管插件的浮动对话框。\n您可以在这里显示重要信息或操作提示。") },
            confirmButton = {
                TextButton(onClick = { showFloatingDialog = false }) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showFloatingDialog = false }) {
                    Text("取消")
                }
            }
        )
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
 * ★ 类型化分发（v3）：
 *  · WfBizAction 按 [WfBizAction.type] 路由 —— 不再匹配中文字符串
 *  · 非 WfBizAction 的 QuickActionSpec（如首页 quickActions）退回 label 分支兼容处理
 */
private fun handleQuickActionClick(
    action: QuickActionSpec,
    context: android.content.Context,
) {
    // 1. wildlife 业务动作：类型路由
    if (action is WfBizAction) {
        when (action.type) {
            WfActionType.LAYER -> {
                // 已注册 → 框架管理器接管（自动注入 Session、转圈/高亮/Toast）
                if (action.id.isNotEmpty() && MapLayerManager.isRegistered(action.id)) {
                    MapLayerManager.toggle(action.id, context)
                } else {
                    Toast.makeText(context, "图层未注册:${action.id}", Toast.LENGTH_SHORT).show()
                }
            }
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
        return
    }

    // 2. 非业务 QuickActionSpec 兼容路径
    Toast.makeText(context, "点击: ${action.label}", Toast.LENGTH_SHORT).show()
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
            Column(  modifier = Modifier.padding(16.dp)  )
            {
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