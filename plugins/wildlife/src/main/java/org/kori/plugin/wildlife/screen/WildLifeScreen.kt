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
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocalPolice
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
import androidx.compose.ui.draw.clip
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
import org.cwcc.open.geokori.map.LocalMapSession
import org.cwcc.open.geokori.map.MapSession
import org.cwcc.open.geokori.ui.material3.center.model.CollapsedQuickActions
import org.cwcc.open.geokori.ui.material3.center.model.ExpandedQuickActions
import org.cwcc.open.geokori.ui.material3.center.model.QuickAction
import org.cwcc.open.plugin.wildlife.viewmodel.WildLifeViewModel
import org.koin.androidx.compose.koinViewModel
import org.kori.plugin.wildlife.actions.defaultWildLifeActions
import org.kori.plugin.wildlife.layers.IllegalEventsLayerController

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)
@Composable
fun WildLifeScreen(
    quickActions: List<QuickAction> = defaultWildLifeActions(),
    bizActions: List<QuickAction> = defaultBizQuickActions(),
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

    // ★ 插件级 Session（PluginEntryClass 的 CompositionLocalProvider 注入）
    val mapSession = LocalMapSession.current

    // ★【关键修复】bizActions 提升为 Compose 可观察状态：
    //   直接改 QuickAction.checked（普通 var）不会触发重组，
    //   必须整项 copy 替换进 State<List>，按钮才会即时刷新。
    //   初始时同步一次控制器的真实开关状态（sheet 重开不高亮丢状态）。
    val bizActionsState = remember {
        mutableStateOf(
            bizActions.map {
                if (it.label == "盗猎活动") {
                    it.copy(checked = IllegalEventsLayerController.isActive)
                } else it
            }
        )
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
                                    handleQuickActionClick(action, context, true, mapSession, bizActionsState)
                                },
                            )
                        } else {
                            CollapsedQuickActions(
                                actions = quickActions.take(4),
                                onActionClick = { action ->
                                    handleQuickActionClick(action, context, true, mapSession, bizActionsState)
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
                                actions = bizActionsState.value,
                                onActionClick = { action ->
                                    handleQuickActionClick(action, context, true, mapSession, bizActionsState)
                                },
                            )
                        } else {
                            CollapsedQuickActions(
                                actions = bizActionsState.value.take(4),
                                onActionClick = { action ->
                                    handleQuickActionClick(action, context, true, mapSession, bizActionsState)
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
                    icon = Icons.Default.LocalPolice,
                    label = "同步",
                    color = Color(0xFF9C27B0),
                    onClick = {
                        onActionClick("sync_data")
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
 * ★【变更】盗猎活动的 Toast 全部由 IllegalEventsLayerController 负责
 *   （加载中/已显示/已关闭/失败）；本函数只负责开关 + 按钮 checked 状态刷新。
 * ★【关键】checked 状态通过替换 bizActionsState 里对应项来刷新
 *   （直接改 QuickAction.checked 这个普通 var 不会触发重组）。
 */
private fun handleQuickActionClick(
    action: QuickAction,
    context: android.content.Context,
    isDependenciesReady: Boolean,
    mapSession: MapSession?,
    bizActionsState: androidx.compose.runtime.MutableState<List<QuickAction>>,
) {
    if (!isDependenciesReady) {
        Toast.makeText(context, "依赖插件未加载，请检查插件配置", Toast.LENGTH_SHORT).show()
        return
    }

    when (action.label) {
        "虎人工繁育" -> Toast.makeText(context, "虎人工繁育", Toast.LENGTH_SHORT).show()

        "盗猎活动" -> {
            val session = mapSession
            if (session == null) {
                Toast.makeText(context, "地图插件未加载，请稍后再试", Toast.LENGTH_SHORT).show()
                return
            }
            val wasActive = IllegalEventsLayerController.isActive
            IllegalEventsLayerController.toggle(context, session)
            val nowActive = !wasActive

            // ★ 即时刷新按钮高亮：整项 copy 替换进 State<List> 触发重组
            bizActionsState.value = bizActionsState.value.map {
                if (it.label == "盗猎活动") it.copy(checked = nowActive) else it
            }
        }

        "象实时监测" -> {
            PluginModuleUtils.showBottomSheet(
                content = {},
                title = action.label,
                closeable = true,
                isNormalActivity = true
            )
            return
        }
        "栖息地分布" -> {
            PluginModuleUtils.showBottomSheet(
                content = { ElephantMonitoringContent() },
                title = action.label,
                isNormalActivity = false
            )
            return
        }
        else -> Toast.makeText(context, "点击: ${action.label}", Toast.LENGTH_SHORT).show()
    }
}



fun defaultBizQuickActions(): List<QuickAction> = listOf(
    QuickAction(null, "虎人工繁育", Color(0xFFE3F2FD), Color(0xFF1565C0)),
    QuickAction(
        null,
        "盗猎活动",
        Color(0xFFF3E5F5),
        Color(0xFF6A1B9A),
        checked = false
    ),
    QuickAction(null, "象实时监测", Color(0xFFFFF3E0), Color(0xFFEF6C00)),
    QuickAction(null, "栖息地分布", Color(0xFFFFFDE7), Color(0xFFF9A825)),
    QuickAction(null, "鸟类环志站", Color(0xFFE8F5E9), Color(0xFF2E7D32)),
    QuickAction(null, "繁育单位", Color(0xFFFFEBEE), Color(0xFFC62828)),
    QuickAction(Icons.Default.LocalPolice, "同步监测", Color(0xFFFFF3E0), Color(0xFFEF6C00)),
    QuickAction(Icons.Default.Flag, "水鸟分布", Color(0xFFE0F2F1), Color(0xFF00695C)),
    QuickAction(Icons.Default.BugReport, "越冬水鸟", Color(0xFFE0F2F1), Color(0xFF00695C)),
)

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