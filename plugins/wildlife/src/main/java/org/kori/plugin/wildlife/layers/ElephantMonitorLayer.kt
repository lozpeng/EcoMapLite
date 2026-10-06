package org.kori.plugin.wildlife.layers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import coil3.compose.AsyncImage
import com.google.gson.JsonObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.cwcc.open.geokori.map.BaseBizeLibreLayer
import org.cwcc.open.geokori.map.GeoKoriLayer
import org.cwcc.open.geokori.map.MapRuntime
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** 亚洲象监测图层：人工上报点(dian) + 红外相机(hongwai) + 迁徙轨迹(puer) */
@GeoKoriLayer(ElephantMonitorLayer.LAYER_ID)
class ElephantMonitorLayer : BaseBizeLibreLayer() {

    companion object {
        const val LAYER_ID = "elephant-monitor"

        private const val TAG = "ElephantMonitorLayer"

        private const val SOURCE_ID = "__sys__ele-source"
        private const val TRACK_SOURCE_ID = "__sys__ele-track-source"
        private const val HEATMAP_LAYER_ID = "__sys__ele-heatmap"
        private const val ICON_LAYER_ID = "__sys__ele-icon"
        private const val LABEL_LAYER_ID = "__sys__ele-label"
        private const val TRACK_LAYER_ID = "__sys__ele-track"

        private const val API_URL = "https://all.zgyzx.cn/api/index/get_daping3_map"

        /** zoom 分界：低倍热力、高倍图片标注 */
        private const val DETAIL_MAX_ZOOM = 11.0

        // 图片标注用的图标（在 style 中注册的 icon-image 名）
        private const val ICON_DIAN = "ele-icon-dian"       // 人工上报
        private const val ICON_HONGWAI = "ele-icon-hongwai" // 红外相机
    }

    // 图标 drawable 资源；没有对应资源时可传 null，自动回退为程序生成的圆点
    @DrawableRes
    private var iconDianRes: Int? = org.kori.plugin.wildlife.R.drawable.ic_elephant_pin

    @DrawableRes
    private var iconHongwaiRes: Int? = org.kori.plugin.wildlife.R.drawable.ic_camera_pin

    override val displayName: String = "亚洲象监测"

    override val cacheDataFile: String = "elephant_monitor_cache.geojson"
    override val cacheMetaFile: String = "elephant_monitor_cache.meta"

    // 接口无 summary 统计端点：依赖默认实现（null = 有缓存未过期即直接用）

    /** 全量拉取后直接把业务 JSON 转成 GeoJSON 字符串（缓存/管道仍是基类那套） */
    override fun fetchRemoteGeoJson(): String = unifyToGeoJson(httpGet(API_URL))

    // =========================================================================================
    // 数据统一：dian / hongwai / puer → GeoJSON
    // =========================================================================================
    private fun props(vararg pairs: Pair<String, Any?>): JsonObject {
        val o = JsonObject()
        for ((k, v) in pairs) {
            when (v) {
                null -> Unit // 空属性直接不写，读取端 getStringProperty 返回 ""
                is Number -> o.addProperty(k, v)
                is Boolean -> o.addProperty(k, v)
                else -> o.addProperty(k, v.toString())
            }
        }
        return o
    }
    /**
     * dian:  [lat, lon, "时间<br>地点<br>数量", id]      —— 注意纬度在前
     * hongwai: { id, lat, lon, name, info, address, images, county }  —— 字符串经纬度
     * puer:  [[lat, lon], ...] 轨迹
     */
    private fun unifyToGeoJson(apiBody: String): String {
        val data = JSONObject(apiBody).optJSONObject("data")
            ?: return FeatureCollection.fromFeatures(emptyList()).toJson()

        val features = mutableListOf<Feature>()

        // ---- dian：人工上报点 ----
        data.optJSONArray("dian")?.let { arr ->
            for (i in 0 until arr.length()) {
                val row = arr.optJSONArray(i) ?: continue
                val lat = row.optDouble(0, Double.NaN)
                val lon = row.optDouble(1, Double.NaN)
                if (lat.isNaN() || lon.isNaN()) continue
                val desc = row.optString(2, "")
                    .replace("<br>", "\n")
                    .replace("<br/>", "\n")
                    .replace("<br />", "\n")
                    .trim()
                features.add(Feature.fromGeometry(
                    Point.fromLngLat(lon, lat),  // ★ 交换为 经度,纬度
                    props(
                        "kind" to "dian",
                        "name" to "亚洲象出没上报",
                        "desc" to desc,
                        "image" to "",
                        "rowid" to (row.opt(3)?.toString() ?: ""),
                    ),
                ))
            }
        }

        // ---- hongwai：红外相机 ----
        data.optJSONArray("hongwai")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val lat = o.optString("lat").toDoubleOrNull() ?: continue
                val lon = o.optString("lon").toDoubleOrNull() ?: continue
                val desc = listOfNotNull(
                    o.optString("info").takeIf { it.isNotBlank() },
                    o.optString("address").takeIf { it.isNotBlank() },
                    o.optString("county").takeIf { it.isNotBlank() },
                ).joinToString("\n")
                features.add(Feature.fromGeometry(
                    Point.fromLngLat(lon, lat),
                    props(
                        "kind" to "hongwai",
                        "name" to o.optString("name").ifBlank { "红外相机" },
                        "desc" to desc,
                        "image" to o.optString("images"),
                        "rowid" to (o.opt("id")?.toString() ?: ""),
                    ),
                ))
            }
        }

        // ---- puer：轨迹线（单独 source） ----
        data.optJSONArray("puer")?.let { arr ->
            val path = mutableListOf<Point>()
            for (i in 0 until arr.length()) {
                val p = arr.optJSONArray(i) ?: continue
                val lat = p.optString(0).toDoubleOrNull() ?: continue
                val lon = p.optString(1).toDoubleOrNull() ?: continue
                path.add(Point.fromLngLat(lon, lat))
            }
            if (path.size >= 2) {
                trackFeature = Feature.fromGeometry(LineString.fromLngLats(path))
            }
        }

        return FeatureCollection.fromFeatures(features).toJson()
    }

    // 轨迹在 onCollectionLoaded 之前解析完成，暂存于此
    private var trackFeature: Feature? = null

    // =========================================================================================
    // 图层组装（低倍热力 / 高倍图片标注+文字 / 轨迹线）
    // =========================================================================================

    override fun onCollectionLoaded(collection: FeatureCollection) {
        removeMapObjects()
        if (collection.features().isNullOrEmpty() && trackFeature == null) return

        val session = currentSession() ?: return

        if (!collection.features().isNullOrEmpty()) {
            session.addSource(
                GeoJsonSource(
                    SOURCE_ID, collection,
                    GeoJsonOptions().withCluster(false).withTolerance(0f).withBuffer(512),
                ),
            )
            session.addLayer(buildHeatmapLayer())
            session.addLayer(buildIconLayer())
            session.addLayer(buildLabelLayer())
        }

        trackFeature?.let { tf ->
            session.addSource(GeoJsonSource(TRACK_SOURCE_ID, FeatureCollection.fromFeature(tf)))
            session.addLayer(
                lineLayer(TRACK_LAYER_ID, TRACK_SOURCE_ID, "#7B1FA2".toColorInt(), 3f)
                    .apply {
                        // 轨迹任何层级都可见（不受热力/标注分界影响）
                        setMinZoom(0f)
                        setProperties(PropertyFactory.lineOpacity(0.8f))
                    },
            )
        }

        // 数据重建后重放透明度与显隐
        onAlphaChanged(mAlpha)
        applyVisibilityByZoom(map?.cameraPosition?.zoom ?: 0.0, force = true)
    }

    override fun onClearMapObjects() = removeMapObjects()

    private fun buildHeatmapLayer() =
        heatmapLayer(HEATMAP_LAYER_ID, SOURCE_ID, maxZoom = DETAIL_MAX_ZOOM.toFloat())

    /** ★ 图片标注替代 CircleLayer：按 kind 字段切换图标 */
    private fun buildIconLayer() =
        symbolLayer(ICON_LAYER_ID, SOURCE_ID, minZoom = DETAIL_MAX_ZOOM.toFloat()).apply {
            setProperties(
                PropertyFactory.iconImage(
                    Expression.switchCase(
                        Expression.eq(Expression.get("kind"), Expression.literal("hongwai")),
                        Expression.literal(ICON_HONGWAI),
                        Expression.literal(ICON_DIAN),
                    ),
                ),
                // 图标放大：z11 即接近全尺寸，高倍继续放大
                PropertyFactory.iconSize(
                    Expression.interpolate(
                        Expression.linear(), Expression.zoom(),
                        Expression.stop(11f, 0.85f),
                        Expression.stop(14f, 1.1f),
                        Expression.stop(16f, 1.4f),
                    ),
                ),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                PropertyFactory.symbolZOrder(Property.SYMBOL_Z_ORDER_AUTO),
            )
        }

    private fun buildLabelLayer() =
        symbolLayer(LABEL_LAYER_ID, SOURCE_ID, minZoom = DETAIL_MAX_ZOOM.toFloat()).apply {
            setProperties(
                PropertyFactory.textField(Expression.get("name")),
                // 注记字号同步放大
                PropertyFactory.textSize(
                    Expression.interpolate(
                        Expression.linear(), Expression.zoom(),
                        Expression.stop(11f, 13f),
                        Expression.stop(14f, 16f),
                    ),
                ),
                PropertyFactory.textColor(Expression.color("#4E342E".toColorInt())),
                PropertyFactory.textHaloColor(Expression.color("#FFFFFF".toColorInt())),
                PropertyFactory.textHaloWidth(1.2f),
                PropertyFactory.textOffset(arrayOf(0f, 1.4f)),
                PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                PropertyFactory.textAllowOverlap(false),
                PropertyFactory.textIgnorePlacement(false),
            )
        }

    // =========================================================================================
    // 透明度（对齐 IllegalEventsHeatLayer 的修法：zoom 保持顶层，alpha 折进 stops）
    // =========================================================================================

    override fun onAlphaChanged(alpha: Float) {
        val s = map?.style ?: return
        val a = alpha.toDouble()
        s.getLayer(HEATMAP_LAYER_ID)?.setProperties(
            PropertyFactory.heatmapOpacity(
                Expression.interpolate(
                    Expression.linear(), Expression.zoom(),
                    Expression.stop(6.0, 1.0 * a),
                    Expression.stop(11.0, 0.7 * a),
                ),
            ),
        )
        s.getLayer(ICON_LAYER_ID)?.setProperties(
            PropertyFactory.iconOpacity(0.9f * alpha),
        )
        s.getLayer(LABEL_LAYER_ID)?.setProperties(
            PropertyFactory.textOpacity(alpha),
        )
        s.getLayer(TRACK_LAYER_ID)?.setProperties(
            PropertyFactory.lineOpacity(0.8f * alpha),
        )
    }

    // =========================================================================================
    // 显隐（zoom 分界，轨迹线除外）
    // =========================================================================================

    private var appliedHeatMode: Boolean? = null

    override fun applyLayerVisibility() {
        applyVisibilityByZoom(map?.cameraPosition?.zoom ?: 0.0, force = true)
    }

    private fun applyVisibilityByZoom(zoom: Double, force: Boolean = false) {
        val s = map?.style ?: return
        val heat = s.getLayer(HEATMAP_LAYER_ID) ?: return
        val icon = s.getLayer(ICON_LAYER_ID) ?: return
        val label = s.getLayer(LABEL_LAYER_ID) ?: return

        if (!mVisible) {
            listOf(heat, icon, label).forEach {
                it.setProperties(PropertyFactory.visibility(Property.NONE))
            }
            s.getLayer(TRACK_LAYER_ID)
                ?.setProperties(PropertyFactory.visibility(Property.NONE))
            appliedHeatMode = null
            return
        }

        val heatMode = zoom < DETAIL_MAX_ZOOM
        if (!force && appliedHeatMode == heatMode) return
        appliedHeatMode = heatMode

        if (heatMode) {
            icon.setProperties(PropertyFactory.visibility(Property.NONE))
            label.setProperties(PropertyFactory.visibility(Property.NONE))
            heat.setProperties(PropertyFactory.visibility(Property.VISIBLE))
        } else {
            heat.setProperties(PropertyFactory.visibility(Property.NONE))
            icon.setProperties(PropertyFactory.visibility(Property.VISIBLE))
            label.setProperties(PropertyFactory.visibility(Property.VISIBLE))
        }
        // 轨迹线常显
        s.getLayer(TRACK_LAYER_ID)?.setProperties(PropertyFactory.visibility(Property.VISIBLE))
    }

    // =========================================================================================
    // 生命周期 / 监听器 / 点击
    // =========================================================================================

    override fun onMapReady(map: MapLibreMap) {
        registerIcons(map)
        registerListeners(map)
        mountSheetHost()
        if (isLoaded) applyVisibilityByZoom(map.cameraPosition.zoom, force = true)
    }

    override fun onDetach() {
        super.onDetach()
        appliedHeatMode = null
        trackFeature = null
        ElephantAttrSheet.dismiss()
        unmountSheetHost()
    }

    /** 把图片注册进 style；资源缺失时回退为程序生成的圆点，保证图标永不为空 */
    private fun registerIcons(map: MapLibreMap) {
        val ctx = layerContext ?: return
        val style = map.style ?: return
        style.addImage(
            ICON_DIAN,
            iconDianRes?.let { runCatching { drawableToBitmap(ctx, it) }.getOrNull() }
                ?: fallbackDot("#FF7043".toColorInt()),
        )
        style.addImage(
            ICON_HONGWAI,
            iconHongwaiRes?.let { runCatching { drawableToBitmap(ctx, it) }.getOrNull() }
                ?: fallbackDot("#8E24AA".toColorInt()),
        )
    }

    private fun drawableToBitmap(context: Context, @DrawableRes resId: Int): Bitmap {
        val d: Drawable = ContextCompat.getDrawable(context, resId)!!
        val size = (36 * context.resources.displayMetrics.density).toInt()
        val bmp = createBitmap(size, size)
        val canvas = Canvas(bmp)
        d.setBounds(0, 0, size, size)
        d.draw(canvas)
        return bmp
    }

    /** 兜底图标：带白边的实心圆（无任何资源也能出图） */
    private fun fallbackDot(color: Int): Bitmap {
        val size = 96
        val bmp = createBitmap(size, size)
        val c = Canvas(bmp)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.color = android.graphics.Color.WHITE
        c.drawCircle(size / 2f, size / 2f, size / 2f - 4f, p)
        p.color = color
        c.drawCircle(size / 2f, size / 2f, size / 2f - 12f, p)
        return bmp
    }

    private fun registerListeners(map: MapLibreMap) {
        trackMapListener(
            MapLibreMap.OnCameraMoveListener {
                if (!isActive || !isLoaded || !mVisible) return@OnCameraMoveListener
                applyVisibilityByZoom(map.cameraPosition.zoom)
            },
            attach = { map.addOnCameraMoveListener(it) },
            detach = { map.removeOnCameraMoveListener(it) },
        )
        trackMapListener(
            MapLibreMap.OnMapClickListener { latLng -> onMapClicked(latLng) },
            attach = { map.addOnMapClickListener(it) },
            detach = { map.removeOnMapClickListener(it) },
        )
    }

    private fun onMapClicked(latLng: LatLng): Boolean {
        if (!isActive || !isLoaded || !mVisible) return false
        val m = map ?: return false
        if (m.cameraPosition.zoom < DETAIL_MAX_ZOOM) return false
        val feature = m.queryRenderedFeatures(
            m.projection.toScreenLocation(latLng), ICON_LAYER_ID,
        ).firstOrNull() ?: return false
        ElephantAttrSheet.show(feature)
        return true
    }

    private fun removeMapObjects() {
        currentSession()?.removeLayer(HEATMAP_LAYER_ID)
        currentSession()?.removeLayer(ICON_LAYER_ID)
        currentSession()?.removeLayer(LABEL_LAYER_ID)
        currentSession()?.removeLayer(TRACK_LAYER_ID)
        currentSession()?.removeSource(SOURCE_ID)
        currentSession()?.removeSource(TRACK_SOURCE_ID)
    }

    // =============================================================================================
    // 属性弹窗（与 IllegalEventAttrSheet 同模式：图层自持 ComposeView 宿主）
    // =============================================================================================

    private var sheetHostView: ComposeView? = null

    private fun mountSheetHost() {
        if (sheetHostView != null) return
        val parent = MapRuntime.currentMapView ?: return
        val view = ComposeView(parent.context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { ElephantAttrSheet.Host() }
        }
        parent.addView(
            view,
            android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        sheetHostView = view
    }

    private fun unmountSheetHost() {
        sheetHostView?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        sheetHostView = null
    }
}

// =================================================================================================
// 属性底部弹窗（图片点击全屏，全屏支持双指缩放/拖动，双击放大/还原）
// =================================================================================================

@OptIn(ExperimentalMaterial3Api::class)
object ElephantAttrSheet {

    private val _feature = MutableStateFlow<Feature?>(null)

    fun show(feature: Feature) {
        _feature.value = feature
    }

    fun dismiss() {
        _feature.value = null
    }

    @Composable
    fun Host() {
        val feature by _feature.collectAsState()
        val f = feature ?: return
        val sheetState = rememberModalBottomSheetState()

        var fullImageUrl by remember { mutableStateOf<String?>(null) }

        ModalBottomSheet(
            onDismissRequest = { dismiss() },
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = f.getStringProperty("name"),
                    style = MaterialTheme.typography.titleMedium,
                )
                val rowid = f.getStringProperty("rowid")
                if (rowid.isNotBlank()) {
                    Text(
                        text = "编号：$rowid",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                val desc = f.getStringProperty("desc")
                if (desc.isNotBlank()) {
                    Text(
                        text = desc,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }

                // 红外相机的抓拍图（Coil；未引入 Coil 时换自己的图片加载器）
                val image = f.getStringProperty("image")
                if (image.isNotBlank()) {
                    AsyncImage(
                        model = image,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .heightIn(max = 260.dp)
                            .clickable { fullImageUrl = image },   // ★ 点击全屏浏览
                    )
                }
            }
        }

        // ★ 全屏浏览：黑底 Dialog，双指缩放 + 拖动，双击放大/还原，右上角关闭
        fullImageUrl?.let { url ->
            FullscreenImageViewer(
                url = url,
                onClose = { fullImageUrl = null },
            )
        }
    }
}

/**
 * 全屏图片查看器
 *  · 双指捏合缩放（1x~6x）+ 单指拖动
 *  · 双击：放大到 2.5x / 还原 1x（带缓动动画）
 *  · 右上角关闭按钮
 */
@Composable
private fun FullscreenImageViewer(
    url: String,
    onClose: () -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            var scale by remember { mutableFloatStateOf(1f) }
            var offset by remember { mutableStateOf(Offset.Zero) }

            val animScale = remember { Animatable(1f) }
            val scope = rememberCoroutineScope()
            var zoomed by remember { mutableStateOf(false) }

            AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.Center)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
                    // ★ 顺序1：变换手势优先 —— 只消费产生位移/缩放的事件，
                    //    轻点/双击事件不被它消费，自然穿透到后面的 tap 检测
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            if (animScale.isRunning) return@detectTransformGestures
                            scale = (scale * zoom).coerceIn(1f, 6f)
                            offset = if (scale > 1f) offset + pan else Offset.Zero
                        }
                    }
                    // ★ 顺序2：双击检测（轻点事件穿透到这里）
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (animScale.isRunning) return@detectTapGestures
                                zoomed = !zoomed
                                scope.launch {
                                    val target = if (zoomed) 2.5f else 1f
                                    animScale.snapTo(scale)
                                    animScale.animateTo(target) { scale = value }  // 每帧回写渲染源
                                    if (!zoomed) offset = Offset.Zero
                                }
                            },
                        )
                    },
            )

            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "关闭",
                    tint = Color.White,
                )
            }
        }
    }
}