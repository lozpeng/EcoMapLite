package org.kori.plugin.wildlife.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.cwcc.open.geokori.map.FeatureAttrSheet
import org.json.JSONObject
import org.maplibre.geojson.Feature

/**
 * 盗猎事件属性弹窗：把 Feature properties 解析成通用面板的 Config，
 * 渲染统一交给 [FeatureAttrSheet]。
 *
 * 对外 API 保持不变：show(feature) / dismiss() / Host()。
 */
object IllegalEventAttrSheet {

    private const val IMG_BASE =
        "http://8.152.157.180/api/illegal/getimg?cmd=oop&rowid="

    // =========================================================================================
    // 展示总线（Compose 范式：状态驱动，谁组合谁渲染）
    // =========================================================================================

    private val _currentFeature = MutableStateFlow<Feature?>(null)

    /** 当前待展示的要素（null = 无弹窗） */
    val currentFeature: StateFlow<Feature?> = _currentFeature.asStateFlow()

    /** 图层侧：请求展示属性弹窗 */
    fun show(feature: Feature) {
        _currentFeature.value = feature
    }

    /** 关闭弹窗（UI onDismiss / 图层 onDetach 调用） */
    fun dismiss() {
        _currentFeature.value = null
    }

    // =========================================================================================
    // 通用面板配置
    // =========================================================================================

    /** 属性字段展示配置（label ← properties key），附件元数据不参与展示 */
    private val ATTR_FIELD_MAP = linkedMapOf(
        "name" to "名称",
        "illegal" to "违法行为",
        "ani_type" to "动物类型",
        "prov" to "省份",
        "city" to "城市",
        "county" to "区县",
        "time" to "时间",
        "source" to "来源",
        "rowid" to "编号",
    )

    private val CONFIG = FeatureAttrSheet.Config(
        fields = ::buildFields,
        attachments = ::parseAttachments,
    )

    // =========================================================================================
    // 属性 / 附件解析
    // =========================================================================================

    /** 构建属性行（label → value，按配置顺序；空值跳过） */
    fun buildFields(feature: Feature): List<Pair<String, String>> {
        val props = feature.properties() ?: return emptyList()
        val json = JSONObject(props.toString())
        return ATTR_FIELD_MAP.mapNotNull { (key, label) ->
            val value = json.opt(key)?.toString()?.takeIf { it.isNotBlank() }
            if (value == null) null else label to value
        }
    }

    /** 从 Feature properties 解析附件列表（无 att_ids/img_types 返回空） */
    fun parseAttachments(feature: Feature): List<FeatureAttrSheet.Attachment> {
        val props = feature.properties() ?: return emptyList()
        val json = JSONObject(props.toString())
        val idsRaw = json.optString("att_ids", "").trim()
        val typesRaw = json.optString("img_types", "").trim()
        if (idsRaw.isEmpty() || typesRaw.isEmpty()) return emptyList()
        val ids = idsRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val types = typesRaw.split(",").map { it.trim() }
        // 按下标配对；img_types 缺失/少于 att_ids 的项默认按图片处理，不丢附件
        return ids.mapIndexed { index, id ->
            val type = types.getOrNull(index)?.takeIf { it.isNotEmpty() } ?: "jpg"
            FeatureAttrSheet.Attachment(
                url = IMG_BASE + id,
                isVideo = type.lowercase() == "mp4",
            )
        }
    }

    // =========================================================================================
    // 渲染宿主：任何包含它的组合都会响应 show 请求
    // =========================================================================================

    @Composable
    fun Host() {
        val f by currentFeature.collectAsState()
        f?.let { feature ->
            FeatureAttrSheet.Content(
                feature = feature,
                config = CONFIG,
                onDismiss = { dismiss() },
            )
        }
    }
}