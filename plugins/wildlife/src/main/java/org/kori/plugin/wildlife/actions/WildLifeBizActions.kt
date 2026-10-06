package org.kori.plugin.wildlife.actions

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.ArtTrack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.LocalPolice
import androidx.compose.ui.graphics.Color
import org.cwcc.open.geokori.ui.material3.center.model.QuickActionSpec
import androidx.compose.ui.graphics.vector.ImageVector
import org.kori.plugin.wildlife.layers.IllegalEventsHeatLayer

/**
 * 野生动植物插件 · 业务动作类型（类型化分发，替代按 label 字符串路由）。
 */
enum class WfActionType {
    /** 地图图层开关（id 路由到 MapLayerManager，支持裸 layerId 或 fullId） */
    LAYER,
    /** 底部弹面板 */
    BOTTOM_SHEET,
    /** 简单 Toast 提示 */
    TOAST,
}

/**
 * wildlife 插件的快捷操作项（[QuickActionSpec] 的插件侧实现）。
 *
 * 相对框架通用 [org.cwcc.open.geokori.ui.material3.center.model.QuickAction]，
 * 额外携带业务语义：
 *  · [type]：分发类型 —— UI 侧 when(action.type) 取代 when(action.label) 中文字符串匹配
 *  · [payload]：附加参数（如 BOTTOM_SHEET 的内容 key），按类型解释
 *
 * checked/loading 仍由外部 Compose State 持有（copy 替换刷新）。
 *
 * 注意：LAYER 型 action 的 [id] 引用图层侧的 LAYER_ID 常量（单一事实源），
 * 不要手写字符串 —— 图层 id 改名时编译期即可发现。
 */
data class WfBizAction(
    override val label: String,
    val type: WfActionType = WfActionType.TOAST,
    override val icon: ImageVector? = null,
    override val containerColor: Color = Color(0xFFE3F2FD),
    override val contentColor: Color = Color(0xFF1565C0),
    override val checked: Boolean = false,
    override val loading: Boolean = false,
    override val checkedContainerColor: Color = Color(0xFFD81E06),
    override val checkedContentColor: Color = Color.White,
    override val id: String = "",
    val payload: String = "",
) : QuickActionSpec

/**
 * wildlife 业务快捷操作默认列表。
 */
fun defaultBizQuickActions(): List<WfBizAction> = listOf(
    WfBizAction("虎人工繁育"),
    WfBizAction(
        label = "盗猎活动",
        type = WfActionType.LAYER,
        containerColor = Color(0xFFF3E5F5),
        contentColor = Color(0xFF6A1B9A),
        id = IllegalEventsHeatLayer.LAYER_ID,
    ),
    WfBizAction(
        label = "象实时监测",
        type = WfActionType.BOTTOM_SHEET,
        containerColor = Color(0xFFFFF3E0),
        contentColor = Color(0xFFEF6C00),
        payload = "elephant-monitoring",
    ),
    WfBizAction(
        label = "栖息地分布",
        type = WfActionType.BOTTOM_SHEET,
        containerColor = Color(0xFFFFFDE7),
        contentColor = Color(0xFFF9A825),
        payload = "habitat",
    ),
    WfBizAction(
        label = "鸟类环志站",
        containerColor = Color(0xFFE8F5E9),
        contentColor = Color(0xFF2E7D32),
    ),
    WfBizAction(
        label = "繁育单位",
        containerColor = Color(0xFFFFEBEE),
        contentColor = Color(0xFFC62828),
    ),
    WfBizAction(
        label = "同步监测",
        type = WfActionType.TOAST,
        icon = Icons.Default.LocalPolice,
        containerColor = Color(0xFFFFF3E0),
        contentColor = Color(0xFFEF6C00),
    ),
    WfBizAction(
        label = "水鸟分布",
        icon = Icons.Default.Flag,
        containerColor = Color(0xFFE0F2F1),
        contentColor = Color(0xFF00695C),
    ),
    WfBizAction(
        label = "越冬水鸟",
        icon = Icons.Default.BugReport,
        containerColor = Color(0xFFE0F2F1),
        contentColor = Color(0xFF00695C),
    ),
)

fun defaultWildLifeActions(): List<WfBizAction> = listOf(
    WfBizAction("动物", containerColor = Color(0xFFE3F2FD), contentColor = Color(0xFF1565C0)),
    WfBizAction("植物", containerColor = Color(0xFFF3E5F5), contentColor = Color(0xFF6A1B9A)),
    WfBizAction("鸟类", containerColor = Color(0xFFFFF3E0), contentColor = Color(0xFFEF6C00)),
    WfBizAction("致害", containerColor = Color(0xFFFFFDE7), contentColor = Color(0xFFF9A825)),
    WfBizAction("收容", containerColor = Color(0xFFE8F5E9), contentColor = Color(0xFF2E7D32)),
    WfBizAction("谱系", containerColor = Color(0xFFFFEBEE), contentColor = Color(0xFFC62828)),
    WfBizAction(
        "执法",
        icon = Icons.Default.LocalPolice,
        containerColor = Color(0xFFFFF3E0),
        contentColor = Color(0xFFEF6C00),
    ),
    WfBizAction(
        "履约",
        icon = Icons.Default.Flag,
        containerColor = Color(0xFFE0F2F1),
        contentColor = Color(0xFF00695C),
    ),
    WfBizAction(
        "名录-动物",
        icon = Icons.Default.BugReport,
        containerColor = Color(0xFFE0F2F1),
        contentColor = Color(0xFF00695C),
    ),
    WfBizAction(
        "名录-植物",
        icon = Icons.Filled.AcUnit,
        containerColor = Color(0xFFFFFDE7),
        contentColor = Color(0xFF2E7D32),
    ),
    WfBizAction(
        "名录-三有",
        icon = Icons.Filled.ArtTrack,
        containerColor = Color(0xFFE0F2F1),
        contentColor = Color(0xFF00695C),
    ),
    WfBizAction(
        "CITES附录",
        containerColor = Color(0xFFE0F2F1),
        contentColor = Color(0xFF00695C),
    ),
)