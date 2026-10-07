package org.cwcc.open.geokori.map

import android.content.Context
import android.graphics.Color
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.HeatmapLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.Property.ICON_ANCHOR_BOTTOM
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.fillAntialias
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOutlineColor
import org.maplibre.android.style.layers.PropertyFactory.heatmapColor
import org.maplibre.android.style.layers.PropertyFactory.heatmapIntensity
import org.maplibre.android.style.layers.PropertyFactory.heatmapOpacity
import org.maplibre.android.style.layers.PropertyFactory.heatmapRadius
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconAnchor
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.symbolZOrder
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textHaloBlur
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textOffset
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 业务图层抽象基类（抽象类，不参与框架扫描注册）。
 *
 * 已实现框架可选能力：
 *  · [RefreshableLayer] —— MapLayerManager.refresh(id) 强制重拉
 *  · [VisibilityControllableLayer] —— 控制面板"可见"开关（软显隐，复用 setVisible）
 *  · 透明度 —— 控制面板透明度滑杆；基类记账 [mAlpha]，子类覆盖
 *    [onAlphaChanged] 对具体 style layer 调 opacity 属性
 *
 * 已托管的通用能力：
 *  · 数据管道 [loadCollection]：本地缓存(1周默认) → summary 条数校验 → 全量拉取 →
 *    失败降级旧缓存；成功自动 notifyLoaded，失败自动 notifyFailed（launchLoad 模板）
 *  · 磁盘缓存读写/删除、[httpGet] 请求助手
 *  · 显隐状态（[setVisible]/[isVisible]/[applyLayerVisibility] 钩子）
 *  · [refresh]（RefreshableLayer）：清缓存强制重拉
 *  · 图层构建助手：heatmap/circle/symbol/fill/line
 *  · ★ 默认属性弹窗：点击要素 → [FeatureAttrSheet] 展示属性（零配置，见下）
 *
 * 默认属性弹窗（★★ 关键能力）：
 *  · 想让点击自动弹属性面板，只需覆盖 [clickableLayerIds] 返回参与查询的 layer id 列表；
 *  · 基类会在 [onMapReady] 里：
 *      1) 注册 OnMapClickListener → [onMapClick] → 命中要素写入 [_pickedFeature]；
 *      2) 在 mapView 上挂载一个 ComposeView，渲染 [FeatureAttrSheet.Content]；
 *      3) 点击要素 → 弹出表格式属性面板（UI 与 IllegalEventAttrSheet 一致）；
 *  · 子类可覆盖 [attrConfig] 定制 Config（字段映射 / 附件 / 名称 / 位置 / 是否显示操作栏）；
 *  · 子类可覆盖 [onMapClick] 完全自定义点击逻辑（返回 true = 消费）；
 *  · 子类可覆盖 [onMapReadyInternal] 做地图就绪后的初始化（原 onMapReady 里的逻辑搬这里）；
 *  · 子类若完全不想用默认能力（例如 IllegalEventsHeatLayer 用 IllegalEventAttrSheet），
 *    保持 [clickableLayerIds] 为空即可，基类不会注册监听、不会挂载 ComposeView。
 *
 * 生命周期默认实现：
 *  · onAttach 自动 [loadCollection]；
 *  · onMapReady（final）自动装监听 + 装宿主 + 转发 [onMapReadyInternal]；
 *  · onDetach 自动 [onClearMapObjects] + 清 pick 状态 + 卸载宿主。
 * 子类如需覆盖 onAttach/onDetach，务必调用 super。
 */
abstract class BaseBizeLibreLayer : LibreMapLayer(), RefreshableLayer, VisibilityControllableLayer {

    // =========================================================================================
    // 子类配置（抽象点）
    // =========================================================================================

    /** 本地缓存：GeoJSON 数据文件名（filesDir 下） */
    protected abstract val cacheDataFile: String

    /** 本地缓存：元信息文件名（条数 + 时间戳） */
    protected abstract val cacheMetaFile: String

    /**
     * 是否写本地缓存（默认 true）。
     * 本地静态数据源（gpkg 文件、离线包）可覆盖为 false，
     * 避免"源文件 + 缓存文件"双份存储占用。
     * 注意：此开关只控制写；读侧不受影响——
     * 适合"随包预置缓存、运行期不更新"的场景。
     */
    protected open val enableLocalCache: Boolean = true

    /** 本地缓存有效期，默认 1 周 */
    protected open val cacheTtlMs: Long = 7L * 24 * 60 * 60 * 1000

    /** 拉取全量 GeoJSON（IO 线程执行，抛异常即失败） */
    protected abstract fun fetchRemoteGeoJson(): String

    /**
     * 轻量统计接口返回当前总条数（IO 线程执行）。
     * 默认 null = 不校验，有缓存且未过期即直接用；
     * 返回具体值时与缓存条数比对，一致则免全量请求。
     */
    protected open fun fetchSummaryCount(): Int? = null

    /** 数据就绪（主线程、isActive 已守卫）：建 source/layer。成功后基类自动 notifyLoaded */
    protected abstract fun onCollectionLoaded(collection: FeatureCollection)

    /** 从地图精确移除本图层资源（onDetach / refresh 时调用） */
    protected abstract fun onClearMapObjects()

    /** 显隐策略（setVisible 时回调，子类实现） */
    protected open fun applyLayerVisibility() {}

    // =========================================================================================
    // 默认属性弹窗（子类可覆盖）
    // =========================================================================================

    /**
     * 参与点击查询的 style layer id 列表。
     *  · 非空 → 基类自动启用默认属性弹窗（注册点击 + 挂载 FeatureAttrSheet）；
     *  · 空（默认）→ 不启用默认弹窗，子类走自己的逻辑（如 IllegalEventsHeatLayer）。
     *
     * 查询顺序：按列表顺序 queryRenderedFeatures，第一个命中的 Feature 被采用。
     */
    protected open val clickableLayerIds: List<String> = emptyList()

    /** 是否启用默认属性弹窗（默认由 [clickableLayerIds] 是否非空决定） */
    protected open val defaultAttrSheetEnabled: Boolean
        get() = clickableLayerIds.isNotEmpty()

    /**
     * 默认属性弹窗的 Config（子类可覆盖自定义）。
     *
     * 默认行为：
     *  · fields：展开 Feature 全部属性；`__` 前缀（内部合成键）过滤掉；
     *  · displayName / position：使用 [FeatureAttrSheet.Config] 的默认实现
     *    （name 字段 / Point 几何）；
     *  · attachments：空；
     *  · showActions：true（有坐标时展示导航/分享）。
     */
    protected open fun attrConfig(feature: Feature): FeatureAttrSheet.Config =
        FeatureAttrSheet.Config(fields = ::defaultFields)

    /** 默认字段生成：全部属性，过滤 `__` 前缀 */
    protected open fun defaultFields(feature: Feature): List<Pair<String, String>> =
        feature.properties()?.asJsonObject?.entrySet()
            ?.filter { !it.key.startsWith("__") }
            ?.map { e ->
                val v = e.value?.let { if (it.isJsonNull) "" else it.asString } ?: ""
                e.key to v
            }
            ?: emptyList()

    /**
     * 点击处理入口（基类注册的 OnMapClickListener 会转发到这里）。
     * 返回 true = 事件被消费（地图不再触发其他默认行为）。
     *
     * 默认实现：
     *  · isActive / isLoaded / mVisible 全为 true 时；
     *  · 用 [queryClickableFeature] 查询命中要素；
     *  · 命中则写入 [_pickedFeature]，触发 [FeatureAttrSheet] 展示。
     *
     * 子类可覆盖完全自定义（例如调用自己的 AttrSheet 对象）。
     */
    protected open fun onMapClick(latLng: LatLng): Boolean {
        if (!isActive || !isLoaded || !mVisible) return false
        if (!defaultAttrSheetEnabled) return false
        val m = map ?: return false
        val feature = queryClickableFeature(m, latLng) ?: return false
        _pickedFeature.value = feature
        return true
    }

    /**
     * 默认查询：按 [clickableLayerIds] 顺序 queryRenderedFeatures，取第一个命中。
     * 子类可覆盖（例如跨多 source 反查、按 z 序合并等）。
     */
    protected open fun queryClickableFeature(map: MapLibreMap, latLng: LatLng): Feature? {
        val screen = map.projection.toScreenLocation(latLng)
        for (id in clickableLayerIds) {
            map.queryRenderedFeatures(screen, id).firstOrNull()?.let { return it }
        }
        return null
    }

    /** 当前被点击要素的可观察状态（供子类做辅助逻辑，例如上报） */
    private val _pickedFeature = MutableStateFlow<Feature?>(null)
    protected val pickedFeature: StateFlow<Feature?> = _pickedFeature.asStateFlow()

    // =========================================================================================
    // 状态
    // =========================================================================================

    @Volatile
    protected var mVisible = true

    @Volatile
    protected var isLoaded = false

    /** 当前透明度（0f~1f，控制面板滑杆驱动；基类记账，子类经 [onAlphaChanged] 应用） */
    @Volatile
    protected var mAlpha = 1f

    /** 默认点击监听是否已安装（onMapReady 幂等守卫；onDetach 时重置） */
    @Volatile
    private var defaultClickListenerInstalled = false

    /** 默认属性弹窗的 ComposeView 宿主 */
    private var attrSheetHostView: ComposeView? = null

    // =========================================================================================
    // 生命周期默认实现
    // =========================================================================================

    override fun onAttach(session: MapSession, context: Context) {
        // 数据加载与地图就绪并行，尽早出图
        loadCollection()
    }

    /**
     * ★ 模板方法（final）：基类在此安装默认能力，然后转发给 [onMapReadyInternal]。
     * 子类若需在地图就绪后初始化，请覆盖 [onMapReadyInternal]，不要覆盖本方法。
     */
    protected final override fun onMapReady(map: MapLibreMap) {
        if (defaultAttrSheetEnabled) {
            installDefaultClickListener(map)
            installAttrSheetHost()
        }
        onMapReadyInternal(map)
    }

    /**
     * 子类实现：地图就绪后的自定义初始化（原 onMapReady 里的逻辑搬到这里）。
     * 默认空。
     */
    protected open fun onMapReadyInternal(map: MapLibreMap) {}

    override fun onDetach() {
        onClearMapObjects()
        isLoaded = false
        // 默认属性弹窗清理
        defaultClickListenerInstalled = false
        _pickedFeature.value = null
        unmountAttrSheetHost()
    }

    // =========================================================================================
    // 默认属性弹窗的安装与卸载
    // =========================================================================================

    private fun installDefaultClickListener(map: MapLibreMap) {
        if (defaultClickListenerInstalled) return
        defaultClickListenerInstalled = true
        trackMapListener(
            MapLibreMap.OnMapClickListener { latLng -> onMapClick(latLng) },
            attach = { map.addOnMapClickListener(it) },
            detach = { map.removeOnMapClickListener(it) },
        )
    }

    private fun installAttrSheetHost() {
        if (attrSheetHostView != null) return
        val parent = MapRuntime.currentMapView ?: return
        val view = ComposeView(parent.context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { AttrSheetHost() }
        }
        parent.addView(
            view,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        attrSheetHostView = view
    }

    private fun unmountAttrSheetHost() {
        attrSheetHostView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        attrSheetHostView = null
    }

    /**
     * 默认属性弹窗的渲染宿主：Feature 为 null 时不渲染任何内容（透明空层）。
     * 想完全替换渲染内容的子类，可改为提供自己的 AttrSheet Host（见现有
     * IllegalEventsHeatLayer 的 mountSheetHost 模式）。
     */
    @Composable
    private fun AttrSheetHost() {
        val f by _pickedFeature.collectAsState()
        f?.let { feature ->
            val config = remember(feature) { attrConfig(feature) }
            FeatureAttrSheet.Content(
                feature = feature,
                config = config,
                onDismiss = { _pickedFeature.value = null },
            )
        }
    }

    // =========================================================================================
    // 显隐 / 刷新（公开 API）
    // =========================================================================================

    fun setVisible(visible: Boolean) {
        mVisible = visible
        if (isLoaded) applyLayerVisibility()
    }

    fun isVisible(): Boolean = mVisible

    /** 控制面板"可见"开关：软显隐（数据保留，仅隐藏显示） */
    override fun setSoftVisible(visible: Boolean) = setVisible(visible)

    /**
     * 透明度入口（MapLayerManager.setOpacity 触发）。
     * final：基类负责 [mAlpha] 记账，实际样式调整在 [onAlphaChanged]。
     */
    final override fun applyAlpha(alpha: Float) {
        mAlpha = alpha
        onAlphaChanged(alpha)
    }

    /** 子类实现：把 [alpha] 应用到自己的 style layers（heatmap/circle/symbol...） */
    protected open fun onAlphaChanged(alpha: Float) {}

    /** 强制重拉（删除本地缓存 + 重建）。经 MapLayerManager.refresh 触发。 */
    override fun refresh() {
        if (!isActive) return
        isLoaded = false
        onClearMapObjects()
        loadCollection(forceRefresh = true)
    }

    // =========================================================================================
    // 数据加载管道（本地缓存优先 + 可选 summary 条数校验）
    // =========================================================================================

    /**
     * @param forceRefresh true 时先删除本地缓存，强制走全量接口
     */
    protected fun loadCollection(forceRefresh: Boolean = false) {
        val ctx = layerContext ?: run {
            notifyFailed()
            return
        }

        launchLoad {
            if (forceRefresh) {
                deleteLocalCache(ctx)
            }

            // 1) 本地缓存（forceRefresh 时视为无）
            val local = if (forceRefresh) null else readLocalCache(ctx)

            // 2) summary 条数校验；失败返回 null（按"未知"处理）
            val summaryCount = fetchSummaryCount()

            // 3) 条数未变化（或 summary 失败但本地未过期）→ 直接用本地，秒开
            if (local != null && (summaryCount == null || summaryCount == local.count)) {
                deliver(ctx, FeatureCollection.fromJson(local.geojson))
                return@launchLoad
            }

            // 4) 无缓存 / 已过期 / 条数变化 → 拉全量
            try {
                val json = fetchRemoteGeoJson()
                val collection = FeatureCollection.fromJson(json)
                if (enableLocalCache) {                      // ← 新增开关
                    writeLocalCache(ctx, json, collection.features()?.size ?: 0)
                }
                deliver(ctx, collection)
            } catch (inner: Exception) {
                val stale = readLocalCache(ctx, ignoreTtl = true) ?: throw inner
                deliver(ctx, FeatureCollection.fromJson(stale.geojson))
            }
        }
    }

    /** 主线程交付：建图层 → 置状态 → 回报成功 */
    private suspend fun deliver(ctx: Context, collection: FeatureCollection) {
        onMain {
            onCollectionLoaded(collection)
            isLoaded = true
            notifyLoaded()
        }
    }

    // =========================================================================================
    // 磁盘缓存
    // =========================================================================================

    /** 本地缓存：数据本体 + 条数 + 写入时间 */
    protected class LocalCache(val geojson: String, val count: Int, val timeMs: Long)

    /** 读取本地缓存；ignoreTtl = true 时忽略有效期（降级兜底用） */
    protected fun readLocalCache(context: Context, ignoreTtl: Boolean = false): LocalCache? {
        val dataFile = File(context.filesDir, cacheDataFile)
        val metaFile = File(context.filesDir, cacheMetaFile)
        if (!dataFile.exists() || !metaFile.exists()) return null
        return try {
            val meta = JSONObject(metaFile.readText())
            val timeMs = meta.getLong("time")
            if (!ignoreTtl && System.currentTimeMillis() - timeMs >= cacheTtlMs) return null
            LocalCache(
                geojson = dataFile.readText(),
                count = meta.getInt("count"),
                timeMs = timeMs,
            )
        } catch (e: Exception) {
            null
        }
    }

    /** 写本地缓存：先写数据，再写元信息（读侧以元信息存在且未过期为准） */
    protected fun writeLocalCache(context: Context, geojson: String, count: Int) {
        try {
            File(context.filesDir, cacheDataFile).writeText(geojson)
            File(context.filesDir, cacheMetaFile).writeText(
                JSONObject()
                    .put("count", count)
                    .put("time", System.currentTimeMillis())
                    .toString(),
            )
        } catch (e: Exception) {
            // 写缓存失败不影响本次加载
        }
    }

    protected fun deleteLocalCache(context: Context) {
        File(context.filesDir, cacheDataFile).delete()
        File(context.filesDir, cacheMetaFile).delete()
    }

    // =========================================================================================
    // HTTP 助手
    // =========================================================================================

    /** 简单 GET 请求（IO 线程执行），返回响应体字符串 */
    protected fun httpGet(
        url: String,
        timeoutMs: Int = 60_000,
        accept: String = "application/json",
    ): String {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("Accept", accept)
            }
            connection.inputStream.use { it.bufferedReader().use { reader -> reader.readText() } }
        } finally {
            connection?.disconnect()
        }
    }

    // =========================================================================================
    // 图层构建助手
    // =========================================================================================

    // ---- 热力图 ----

    protected open val heatmapColorStops = arrayOf(
        Expression.stop(0.0, Expression.rgba(33, 102, 172, 0.0)),
        Expression.stop(0.2, Expression.rgba(103, 169, 207, 1.0)),
        Expression.stop(0.5, Expression.rgba(209, 229, 240, 1.0)),
        Expression.stop(0.8, Expression.rgba(253, 219, 199, 1.0)),
        Expression.stop(0.9, Expression.rgba(239, 138, 98, 1.0)),
        Expression.stop(1.0, Expression.rgba(178, 24, 43, 1.0)),
    )

    protected open val heatmapIntensityStops = arrayOf(
        Expression.stop(1.0, 0.0),
        Expression.stop(16.0, 3.0),
    )

    protected open val heatmapRadiusStops = arrayOf(
        Expression.stop(0.0, 10.0),
        Expression.stop(6.0, 20.0),
        Expression.stop(11.0, 30.0),
    )

    protected open val heatmapOpacityStops = arrayOf(
        Expression.stop(6.0, 1.0),
        Expression.stop(11.0, 0.7),
    )

    /** 创建热力图层（density 插值配色，zoom 插值强度/半径/透明度；样式经上方 val 覆盖） */
    protected fun heatmapLayer(
        lyrId: String,
        sourceId: String,
        maxZoom: Float,
    ): HeatmapLayer =
        HeatmapLayer(lyrId, sourceId).apply {
            setMaxZoom(maxZoom)
            setProperties(
                heatmapColor(
                    Expression.interpolate(
                        Expression.linear(), Expression.heatmapDensity(),
                        *heatmapColorStops,
                    ),
                ),
                heatmapIntensity(
                    Expression.interpolate(
                        Expression.linear(), Expression.zoom(), *heatmapIntensityStops,
                    ),
                ),
                heatmapRadius(
                    Expression.interpolate(
                        Expression.linear(), Expression.zoom(), *heatmapRadiusStops,
                    ),
                ),
                heatmapOpacity(
                    Expression.interpolate(
                        Expression.linear(), Expression.zoom(), *heatmapOpacityStops,
                    ),
                ),
            )
        }

    // ---- 圆点 ----

    protected fun circleLayer(
        lyrId: String,
        sourceId: String,
        minZoom: Float? = null,
    ): CircleLayer =
        CircleLayer(lyrId, sourceId).apply {
            minZoom?.let { setMinZoom(it) }
            setProperties(
                circleRadius(
                    Expression.interpolate(
                        Expression.linear(),
                        Expression.zoom(),
                        Expression.stop(5, 1f),
                        Expression.stop(14, 50f),
                    ),
                ),
                circleColor(
                    Expression.interpolate(
                        Expression.linear(), Expression.zoom(),
                        Expression.literal(1), Expression.rgba(33, 102, 172, 0),
                        Expression.literal(2), Expression.rgb(103, 169, 207),
                        Expression.literal(4), Expression.rgb(209, 229, 240),
                        Expression.literal(6), Expression.rgb(253, 219, 199),
                        Expression.literal(8), Expression.rgb(239, 138, 98),
                        Expression.literal(14), Expression.rgb(178, 24, 43),
                    ),
                ),
                circleOpacity(
                    Expression.interpolate(
                        Expression.linear(),
                        Expression.zoom(),
                        Expression.stop(5, 0f),
                        Expression.stop(8, 1f),
                    ),
                ),
                circleStrokeColor("white"),
                circleStrokeWidth(1.5f),
            )
        }

    // ---- 注记 ----

    protected fun symbolLayer(
        lyrId: String,
        sourceId: String,
        minZoom: Float? = null,
        icoField: String = "",
        labelField: String = "",
    ): SymbolLayer =
        SymbolLayer(lyrId, sourceId).apply {
            minZoom?.let { setMinZoom(it) }
            setProperties(
                iconAnchor(ICON_ANCHOR_BOTTOM),
                iconAllowOverlap(false),
                textSize(14f),
                textAllowOverlap(true),
                textColor(Color.parseColor("#000000")),
                textHaloBlur(.5f),
                textHaloColor(Color.parseColor("#FFFFFF")),
                textHaloWidth(2f),
                symbolZOrder(Property.SYMBOL_Z_ORDER_AUTO),
                textFont(arrayOf("Open Sans Regular")),
            )
            if (icoField.isNotEmpty()) {
                setProperties(iconImage("{$icoField}"))
            }
            if (labelField.isNotEmpty()) {
                setProperties(
                    textField(
                        Expression.step(
                            Expression.zoom(),
                            Expression.get(""),
                            Expression.stop(6, Expression.get("$labelField")),
                        ),
                    ),
                    textOffset(arrayOf(0.0f, 1.0f)),
                )
            }
        }

    // ---- 多边形 / 线 ----

    /** 新建一个多边形填充图层 */
    protected fun fillLayer(
        lyrId: String,
        sourceId: String,
        fillColor: Int = Color.TRANSPARENT,
        outlineColor: Int = Color.MAGENTA,
    ): FillLayer =
        FillLayer(lyrId, sourceId).withProperties(
            fillColor(fillColor),
            fillOutlineColor(outlineColor),
            fillAntialias(true),
        )

    protected fun lineLayer(
        lyrId: String,
        sourceId: String,
        color: Int = Color.MAGENTA,
        width: Float = 3.0f,
    ): LineLayer =
        LineLayer(lyrId, sourceId).withProperties(
            lineColor(color),
            lineWidth(width),
        )
}