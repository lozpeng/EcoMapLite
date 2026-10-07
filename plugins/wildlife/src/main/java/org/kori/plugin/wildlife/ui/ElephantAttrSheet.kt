package org.kori.plugin.wildlife.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.MutableStateFlow
import org.cwcc.open.geokori.map.FeatureAttrSheet
import org.maplibre.geojson.Feature


// =================================================================================================
// 属性底部弹窗（图片点击全屏，全屏支持双指缩放/拖动，双击放大/还原）
// =================================================================================================
/**
 * 大象（红外相机）属性弹窗：字段少、单图，复用通用面板 UI。
 *
 * 对外 API 保持不变：show(feature) / dismiss() / Host()。
 */
object ElephantAttrSheet {

    private val _feature = MutableStateFlow<Feature?>(null)

    fun show(feature: Feature) {
        _feature.value = feature
    }

    fun dismiss() {
        _feature.value = null
    }

    private val CONFIG = FeatureAttrSheet.Config(
        fields = { f ->
            buildList {
                val name = f.getStringProperty("name")
                if (!name.isNullOrBlank()) add("名称" to name)
                val rowid = f.getStringProperty("rowid")
                if (!rowid.isNullOrBlank()) add("编号" to rowid)
                val desc = f.getStringProperty("desc")
                if (!desc.isNullOrBlank()) add("描述" to desc)
            }
        },
        attachments = { f ->
            // 红外相机的抓拍图（Coil；未引入 Coil 时换自己的图片加载器）
            val image = f.getStringProperty("image")
            if (image.isNullOrBlank()) {
                emptyList()
            } else {
                listOf(FeatureAttrSheet.Attachment(url = image))
            }
        },
    )

    @Composable
    fun Host() {
        val f by _feature.collectAsState()
        f?.let { feature ->
            FeatureAttrSheet.Content(
                feature = feature,
                config = CONFIG,
                onDismiss = { dismiss() },
            )
        }
    }
}