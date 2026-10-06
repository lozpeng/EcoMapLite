package org.cwcc.open.geokori.map


import android.content.Context
import android.graphics.Color
import org.json.JSONObject
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
 * 已托管的通用能力，具体图层只需实现 4 个抽象点：
 *  · [cacheDataFile] / [cacheMetaFile]  本地缓存文件名
 *  · [fetchRemoteGeoJson]               全量 GeoJSON 拉取（IO 线程）
 *  · [onCollectionLoaded]               数据就绪后建图层（主线程）
 *  · 可选 [fetchSummaryCount]           轻量统计条数，用于"总次数未变则免全量"
 *
 * 通用能力清单：
 *  · 数据管道 [loadCollection]：本地缓存(1周默认) → summary 条数校验 → 全量拉取 →
 *    失败降级旧缓存；成功自动 notifyLoaded，失败自动 notifyFailed（launchLoad 模板）
 *  · 磁盘缓存读写/删除、[httpGet] 请求助手
 *  · 显隐状态（[setVisible]/[isVisible]/[applyLayerVisibility] 钩子）
 *  · [refresh]（RefreshableLayer）：清缓存强制重拉
 *  · 图层构建助手：heatmap/circle/symbol/fill/line（Fill/Line 上的
 *    非法 text 属性已移除，注记请用 symbolLayer）
 *
 * 生命周期默认实现：onAttach 自动 [loadCollection]，onDetach 自动 [onClearMapObjects]。
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
    // 状态
    // =========================================================================================

    @Volatile
    protected var mVisible = true

    @Volatile
    protected var isLoaded = false

    /** 当前透明度（0f~1f，控制面板滑杆驱动；基类记账，子类经 [onAlphaChanged] 应用） */
    @Volatile
    protected var mAlpha = 1f

    // =========================================================================================
    // 生命周期默认实现
    // =========================================================================================

    override fun onAttach(session: MapSession, context: Context) {
        // 数据加载与地图就绪并行，尽早出图
        loadCollection()
    }

    override fun onDetach() {
        onClearMapObjects()
        isLoaded = false
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

    // 注意：以下 stops 均为未显式类型的 arrayOf val（推断 Array<Expression.Stop>），
    // spread 进 interpolate vararg 协变安全；子类如需自定义，override 同名 val
    // 即可（同样不显式声明类型）。
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

    /**
     * 创建圆点图层
     * 修复：移除嵌套的 zoom-based interpolate，避免 JNI 表达式错误
     */
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

    // ---- 多边形 / 线（已移除 Fill/Line 上不合法的 text 属性；注记请用 symbolLayer） ----

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