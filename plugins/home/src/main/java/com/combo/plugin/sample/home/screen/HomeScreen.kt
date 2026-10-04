package com.combo.plugin.sample.home.screen

import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Adb
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.combo.core.runtime.PluginManager
import com.combo.core.utils.sendInternalBroadcast
import com.combo.plugin.sample.common.component.EmptyPage
import com.combo.plugin.sample.home.bridge.TrackStateBridge
import com.combo.plugin.sample.home.state.PluginStatus
import com.combo.plugin.sample.home.viewmodel.HomeViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.cwcc.open.geokori.api.TrackIntents
import org.cwcc.open.geokori.ui.material3.GeoKoriPluginScreenContainer
import org.cwcc.open.geokori.ui.material3.bottomsheet.core.FlexibleSheetValue
import org.cwcc.open.geokori.ui.material3.center.BottomToolbarItem
import org.cwcc.open.geokori.ui.material3.center.GeoKoriCenter
import org.cwcc.open.geokori.ui.material3.pluginBottomSheetConfig
import org.cwcc.open.geokori.ui.material3.rememberPluginSheetState
import org.koin.androidx.compose.koinViewModel

/**
 * 主页屏幕
 *
 * 提供插件测试功能的主界面，包含导航、插件管理等功能
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    // 当前选中的目标（用于底部工具栏高亮）
    var currentDestination by rememberSaveable { mutableStateOf(AppDestinations.GeoKori) }
    // 控制弹窗显示
    var isSheetVisible by rememberSaveable { mutableStateOf(false) }

    // 当前弹窗显示的插件ID
    var currentSheetPluginId by rememberSaveable { mutableStateOf<String?>(null) }

    var isCenterVisible by rememberSaveable { mutableStateOf(true) }
    var toolbarHeight by remember { mutableStateOf(64.dp) }

    // ★ 订阅 geokori 的录制状态（geokori 未加载时默认 false）
    val trackRecordingFlow = remember { TrackStateBridge.isRecordingFlow() }
    val isRecording by (trackRecordingFlow ?: remember { MutableStateFlow(false) })
        .collectAsState()

    // ========== 弹窗配置（可定制宽度和位置） ==========
    val pluginScreenConfig = remember {
        pluginBottomSheetConfig(
            sheetWidth = null,  // null 自动适配
            sheetHorizontalAlignment = Alignment.End,  // null 自动适配
            isModal = false,
            isDraggable = true,
            showCloseButton = true,
            closeButtonAlignment = Alignment.End,
            fullyExpandedRatio = 0.9f,
            intermediatelyExpandedRatio = 0.5f,
            hasSlightlyExpanded = false,
            initialValue = FlexibleSheetValue.FullyExpanded,
        )
    }
    // Sheet 状态
    val pluginScreenState = rememberPluginSheetState(pluginScreenConfig)
    // 监听错误消息
    LaunchedEffect(state.isError, state.errorMessage) {
        if (state.isError && state.errorMessage != null) {
            Toast.makeText(context, state.errorMessage, Toast.LENGTH_LONG).show()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
            // 1. 地图作为常驻底层（始终保持）
            PluginScreenContent(
                pluginId = HomeViewModel.PLUGIN_GEOKORI,
                viewModel = viewModel
            )

        // 2. 底部工具栏（上层）
        val toolbarAlpha by animateFloatAsState(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 300),
            label = "toolbar_alpha"
        )

        // 构建工具栏数据
        val toolbarItems = buildToolbarItems(currentDestination,isRecording)
        // 底部工具栏
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .alpha(toolbarAlpha)
                .zIndex(1f)
        ) {
            GeoKoriCenter(
                toolbarItems = toolbarItems,
                selectedToolbarItemId = currentDestination.id,
                onToolbarItemSelected = { selectedItem ->
                    val destination = AppDestinations.fromId(selectedItem.id)
                    if (destination != null) {
                        currentDestination = destination
                        if (destination == AppDestinations.PUBLISH) {
                            context.sendInternalBroadcast(TrackIntents.ACTION_TOGGLE)
                            return@GeoKoriCenter
                        }
                        if (destination != AppDestinations.GeoKori) {
                            val pluginId = when (destination) {
                                AppDestinations.SETTING -> HomeViewModel.PLUGIN_SETTING
                                AppDestinations.ANIMAL  -> HomeViewModel.PLUGIN_ANIMAL
                                else -> null
                            }
                            if (pluginId != null) {
                                currentSheetPluginId = pluginId
                                isSheetVisible = true
                            } else {
                                currentDestination = AppDestinations.GeoKori
                                currentSheetPluginId = null
                                isSheetVisible = false
                            }
                        } else {
                            isSheetVisible = false
                            currentSheetPluginId = null
                        }
                    }
                },
                onVisibilityChanged = { visible -> isCenterVisible = visible},
                onToolbarHeightChanged={ h -> toolbarHeight = h },
                autoHideOnMapClick = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        // 3. 底部弹窗 - 使用封装好的 GeoKoriPluginScreenContainer
        // 核心修改：通过 bottomOffset 参数内部处理工具栏留白，不再使用外部 Modifier.offset hack
        if (isSheetVisible && currentSheetPluginId != null) {
            GeoKoriPluginScreenContainer(
                isVisible = isSheetVisible,
                onDismiss = {
                    isSheetVisible = false
                    currentSheetPluginId = null
                    currentDestination = AppDestinations.GeoKori
                },
                onBackPressed = {
                    isSheetVisible = false
                    currentSheetPluginId = null
                    currentDestination = AppDestinations.GeoKori
                },
                config = pluginScreenConfig,
                sheetState = pluginScreenState,
                bottomOffset = if (isCenterVisible) 30.dp else 0.dp,
            ) {
                PluginScreenContent(
                    pluginId = currentSheetPluginId!!,
                    viewModel = viewModel
                )
            }
        }
    }
}

/**
 * 构建工具栏项
 */
@Composable
private fun buildToolbarItems(
        currentDestination: AppDestinations,
        isRecording: Boolean,          // ★ 新增
    ): List<BottomToolbarItem> {
    return AppDestinations.entries.map { destination ->
        BottomToolbarItem(
            id = destination.id,
            icon = when {
                // ★ 录制中：悬浮按钮变成"结束"
                destination == AppDestinations.PUBLISH && isRecording -> Icons.Default.Stop
                else -> destination.icon
            },
            label = when {
                destination == AppDestinations.PUBLISH && isRecording -> "结束"
                else -> destination.label
            },
            isFloating = destination.isFloating,
            isSelected = currentDestination == destination,
            // ★ 录制中悬浮按钮变红
            customColor = if (destination == AppDestinations.PUBLISH && isRecording)
                androidx.compose.ui.graphics.Color(0xFFD81E06) else null,
            stopGuard = destination == AppDestinations.PUBLISH && isRecording,
        )
    }
}

/**
 * 插件页面的通用内容布局
 */
@Composable
private fun PluginScreenContent(pluginId: String, viewModel: HomeViewModel) {
    val state by viewModel.uiState.collectAsState()

    val entryClass = when (pluginId) {
        HomeViewModel.PLUGIN_GUIDE -> state.guideEntryClass
        HomeViewModel.PLUGIN_EXAMPLE -> state.exampleEntryClass
        HomeViewModel.PLUGIN_SETTING -> state.settingEntryClass
        HomeViewModel.PLUGIN_ANIMAL ->state.animalEntryClass
        HomeViewModel.PLUGIN_GEOKORI ->state.geokoriEntryClass
        else -> null
    }

    when {
        // 1. 检查是否下载失败
        state.failedDownloads.contains(pluginId) -> {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("插件[$pluginId]下载失败！")
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { viewModel.retryDownload(pluginId) }) {
                    Text("重试")
                }
            }
        }

        // 2. 检查是否正在下载
        state.downloadingPlugins.containsKey(pluginId) -> {
            val progress = state.downloadingPlugins[pluginId] ?: 0f
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = "下载中... ${(progress * 100).toInt()}%")
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.width(200.dp))
            }
        }

        // 3. 显示正常状态
        else -> {
            val pluginStatus = viewModel.getPluginStatus(pluginId)
            EmptyPage(
                entryClass = entryClass,
                message =
                    when (pluginStatus) {
                        PluginStatus.NOT_INSTALLED -> "插件[$pluginId]未安装"
                        PluginStatus.INSTALLED_NOT_STARTED -> "插件[$pluginId]未启动"
                        PluginStatus.INSTALLED_AND_STARTED -> "插件[$pluginId]已启动"
                    },
                buttonText =
                    when (pluginStatus) {
                        PluginStatus.NOT_INSTALLED -> "下载并安装最新插件"
                        PluginStatus.INSTALLED_NOT_STARTED -> "启动插件"
                        PluginStatus.INSTALLED_AND_STARTED -> "进入插件"
                    },
                onButtonClick = {
                    when (pluginStatus) {
                        PluginStatus.NOT_INSTALLED -> {
                            viewModel.installLatestPlugin(pluginId)
                        }

                        else -> {
                            CoroutineScope(Dispatchers.Main).launch {
                                PluginManager.launchPlugin(pluginId)
                            }
                        }
                    }
                },
            )
        }
    }
}

enum class AppDestinations(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val isFloating: Boolean = false,
) {
    GeoKori("org.kori.plugin.geo", "地图", Icons.Default.Map),
    ANIMAL("org.kori.plugin.wildlife","动物", Icons.Default.Adb),
    PUBLISH("publish", "记录", Icons.Default.Air, isFloating = true),
    SETTING("setting", "设置", Icons.Default.Settings),
    PROFILE("profile", "我的", Icons.Default.Person);

    companion object {
        fun fromId(id: String): AppDestinations? {
            return entries.find { it.id == id }
        }
    }
}