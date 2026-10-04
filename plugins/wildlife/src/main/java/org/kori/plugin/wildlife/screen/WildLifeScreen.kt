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
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import org.cwcc.open.geokori.map.MapSession
import org.cwcc.open.geokori.ui.material3.center.model.CollapsedQuickActions
import org.cwcc.open.geokori.ui.material3.center.model.ExpandedQuickActions
import org.cwcc.open.geokori.ui.material3.center.model.QuickAction
import org.cwcc.open.plugin.wildlife.viewmodel.WildLifeViewModel
import org.koin.androidx.compose.koinViewModel
import org.kori.plugin.wildlife.actions.defaultWildLifeActions

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)
@Composable
fun WildLifeScreen(
    quickActions: List<QuickAction> = defaultWildLifeActions(),
    bizActions:List<QuickAction> = defaultBizQuickActions(),
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
    val LocalMapSession: ProvidableCompositionLocal<MapSession?> =
        staticCompositionLocalOf { null }


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
//            Text(
//                text = "野生动植物分布情况",
//                fontWeight = FontWeight.Medium,
//                style = MaterialTheme.typography.bodyMedium,
//                color = MaterialTheme.colorScheme.onSurfaceVariant,
//            )
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
                                    handleQuickActionClick(action, context, true)
                                },
                            )
                        } else {
                            CollapsedQuickActions(
                                actions = quickActions.take(4),
                                onActionClick = { action ->
                                    handleQuickActionClick(action, context, true)
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
//            TextButton(onClick = { }) {
//              Text("查看更多", fontSize = 13.sp)
//            }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    AnimatedContent(
                        targetState = isQuickActionsExpanded,
                        label = "wildlife_biz_actions"
                    ) { expanded ->
                        if (expanded) {
                            ExpandedQuickActions(
                                actions = bizActions,
                                onActionClick = { action ->
                                    handleQuickActionClick(action, context, true)
                                },
                            )
                        } else {
                            CollapsedQuickActions(
                                actions = bizActions.take(4),
                                onActionClick = { action ->
                                    handleQuickActionClick(action, context, true)
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

/**
 * SpeedDial 风格浮动按钮组件
 *
 * 最终版修正：
 * 1. 摒弃了全屏透明侧边栏。
 * 2. 红框直接放在承载主按钮的 Box 中。
 * 3. 主按钮在哪边，红框就在另一边。
 * 4. 红框和主按钮都贴底对齐（平行）。
 * 5. 点击红框响应，主按钮直接切过去。
 */
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

    // 位置状态：true = 主按钮在右侧，false = 主按钮在左侧
    var isRightHandMode by remember { mutableStateOf(false) }

    // 展开动画
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

    // 承载主按钮和红框的顶层 Box
    Box(modifier = modifier) {

        // ===============================================
        // ✅ 红框（对侧显示）
        // 当主按钮在右时，红框在左 (BottomStart)；主按钮在左时，红框在右 (BottomEnd)
        // 确保和主按钮一样的 Bottom + Padding 距离
        // ===============================================
        Box(
            modifier = Modifier
                .align(if (isRightHandMode) Alignment.BottomStart else Alignment.BottomEnd)
                .padding(16.dp)
                .size(fabSize + 2.dp) // 比主按钮大 2dp
                .drawBehind {
                    val strokeWidth = 2f
                    val dashLength = 8f
                    val gapLength = 6f
                    val color = Color.Red // 红色

                    // 外边框（红实线）
                    drawRoundRect(
                        color = color,
                        style = Stroke(width = strokeWidth),
                        cornerRadius = CornerRadius(8.0f)
                    )

                    // 中间十字线（虚线）
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
                    // 点击红框：翻转模式，主按钮切换过来
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

        // ===============================================
        // ✅ 主按钮组
        // 跟随 isRightHandMode 在左右两侧切换
        // ===============================================
        Column(
            modifier = Modifier
                .align(if (isRightHandMode) Alignment.BottomEnd else Alignment.BottomStart)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 子按钮4：同步数据
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

            // 子按钮3：信息
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

            // 子按钮2：添加记录
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

            // 子按钮1：打开文件夹
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

            // 主按钮
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

/**
 * SpeedDial 子按钮组件
 */
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

private fun handleQuickActionClick(
    action: QuickAction,
    context: android.content.Context,
    isDependenciesReady: Boolean
) {
    if (!isDependenciesReady) {
        Toast.makeText(context, "依赖插件未加载，请检查插件配置", Toast.LENGTH_SHORT).show()
        return
    }

    when (action.label) {
        "虎人工繁育" -> Toast.makeText(context, "虎人工繁育", Toast.LENGTH_SHORT).show()
        "盗猎活动" -> {
            //WildlifeIllegalEventsProvider.loadFromServer()
        }
        "象实时监测" -> {
            PluginModuleUtils.showBottomSheet(
                content = {
//                    FerrostarTheme{
//                        ElephantMonitoringContent()
//                    }
                },
                title = action.label,
                closeable = true,
                isNormalActivity = true
            )
            return
        }
        "栖息地分布" -> {
            PluginModuleUtils.showBottomSheet(
                content = {
                        ElephantMonitoringContent()
                },
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
        // 监测数据卡片
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

        // 数据列表
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