package org.cwcc.open.geokori.map

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.core.content.ContextCompat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.cwcc.open.geokori.lib.utils.MapIconUtil
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * 系统照片标注图层（框架级 · 单实例）。
 *
 * 工作方式：
 *  1. 开启后（见 [PhotoMarkerLayers.setEnabled]）在 IO 线程扫描系统相册相机目录，
 *     读取每张图片的 Exif 经纬度（无定位信息的照片跳过），构建点图层；
 *  2. 点标记 = 圆角矩形照片（白边）+ 底部三角尾巴的气泡针（图2风格），近距聚集时
 *     自动聚合为红底白边计数徽章（MapLibre withCluster + 原生 circle/text 层）；
 *     生成结果按媒体 id 落盘缓存（filesDir/photo_marker_icons_v2/），重复加载零成本；
 *  3. 点击标记 → [PhotoViewer] 全屏浏览原图，支持捏合缩放（1x~5x）、
 *     拖动平移、双击放大/复位、双指/返回关闭。
 *
 * ★ 更新模式（不反复全量扫描相册）：
 *  · 首次开启：全量扫描相机目录（仅读 Exif，不复制文件）；
 *  · 运行期：ContentObserver 监听 MediaStore.Images 变化，防抖 1.5s 后
 *    【增量扫描】——只处理 DATE_ADDED 晚于上次扫描时间的新照片，与缓存合并；
 *  · 兜底：本地缓存超过 [cacheTtlMs]（默认 12h）后，下一次相册变化触发全量重扫
 *    （顺带清理已删除照片，修正 Exif 编辑）；
 *  · 手动：MapLayerManager.refresh(id) 立即全量重扫。
 *
 * 权限要求（宿主 AndroidManifest 声明 + 运行时申请）：
 * ```
 * <uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />            <!-- API 33+ -->
 * <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
 *                  android:maxSdkVersion="32" />
 * <!-- ★ Android 10+ 必须：否则系统脱敏媒体文件的 GPS Exif，latLong 全 null，
 *      扫描结果为空（图层开启但无任何标注的最常见根因） -->
 * <uses-permission android:name="android.permission.ACCESS_MEDIA_LOCATION" />
 * ```
 * 运行时：READ_MEDIA_IMAGES / READ_EXTERNAL_STORAGE 经常规 requestPermissions；
 * ACCESS_MEDIA_LOCATION 同样支持 requestPermissions（系统弹设置授权页），
 * 建议在照片标注开关开启前引导授予，未授予时扫描为空并弹 Toast 说明。
 */
class PhotoMarkerLayer : BaseBizeLibreLayer() {

    override val displayName: String = "照片标注"

    override val cacheDataFile: String = "photo_markers_cache.geojson"
    override val cacheMetaFile: String = "photo_markers_cache.meta"

    /** 增量扫描的兜底 TTL：超过后下一次相册变化触发全量重扫 */
    override val cacheTtlMs: Long = 12L * 60 * 60 * 1000

    // ---- 资源 id（全局单实例，固定即可） ----
    private val sourceId = "__photo_src"
    private val symbolLayerId = "__photo_sym"
    private val clusterLayerId = "__photo_cluster"
    private val clusterCountLayerId = "__photo_cluster_cnt"

    /**
     * 源是否已挂载：true 时数据更新只走 GeoJsonSource.setGeoJson()，
     * 不删源重建（规避 MapLibre GeoJSON 异步转换的销毁竞态 SIGSEGV）。
     */
    @Volatile
    private var sourceAttached = false

    /**
     * 交付代次：onCollectionLoaded 每次交付 +1，异步图标生成完成后仅
     * 最新一代允许挂载——避免重复交付（日志可见同毫秒两次）导致双 mount 竞态。
     */
    @Volatile
    private var mountGeneration = 0L

    companion object {
        /** internal：PhotoIndex 诊断日志共用 */
        internal const val TAG = "PhotoMarkerLayer"

        /**
         * 全量扫描的相册范围：false = 仅相机目录（默认，省 Exif 解析耗时）；
         * true = 全相册（照片分散在其他目录/桶名非 "Camera" 的机型排查时用）。
         */
        internal const val SCAN_ALL_BUCKETS = false

        const val LAYER_ID = "photo_markers"

        /** 特性属性键 */
        const val PROP_MEDIA_ID = "media_id"
        const val PROP_NAME = "name"
        const val PROP_URI = "uri"          // content://media/external/images/media/{id}
        const val PROP_DATE = "date"        // epoch seconds
        const val PROP_ICON = "icon"        // 符号图层 iconImage 引用的图名

        /** 图名前缀：{ICON_PREFIX}{media_id} */
        private const val ICON_PREFIX = "__photo_icon_"

        /**
         * 兜底图名：缩略图生成失败（云同步占位文件/解码失败/权限）时，
         * iconImage 经 coalesce 回落到此图——否则 MapLibre 会因引用
         * 不存在的图而【静默不渲染】该要素（"有提示无注记"的元凶之一）。
         */
        private const val DEFAULT_ICON = "__photo_default"

        /** 最多标注的照片数（按拍摄时间倒序截取） */
        private const val MAX_PHOTOS = 300

        /** 点标记底板颜色 */
        private const val MARKER_COLOR = 0xFF1E88E5

        /** 点标记输出像素（更大更清晰；符号层再按 zoom 插值放大） */
        private const val MARKER_SIZE_PX = 160

        /** 缩略图采样解码的目标边长 */
        private const val THUMB_EDGE_PX = 320

        /** 相册变化防抖窗口 */
        private const val DEBOUNCE_MS = 1500L

        /** 增量扫描回溯缓冲（秒），避免边界时间丢片 */
        private const val INCREMENTAL_BACKOFF_SEC = 60L
    }

    // =========================================================================================
    // 数据拉取：相册 → GeoJSON（IO 线程，launchLoad 保证）
    // =========================================================================================

    /** 全量扫描（基类管道：首启 / TTL 过期 / refresh 时调用） */
    override fun fetchRemoteGeoJson(): String =
        PhotoIndex.scan(context = layerContext!!, sinceAddedSec = 0L)

    // =========================================================================================
    // 图层组装：符号层 + 图标（后台生成，主线程挂载）
    // =========================================================================================

    override fun onCollectionLoaded(collection: FeatureCollection) {
        // ★ 注意：不再先 removeMapObjects() 再重建 —— 删源+建源会触发 MapLibre
        //   GeoJSON 异步转换的销毁竞态（SIGSEGV@GeoJSONData 消息）。源只建一次，
        //   数据更新走 setGeoJson()（见 mountLocked）。
        val features = collection.features().orEmpty()
            .sortedByDescending { it.getStringProperty(PROP_DATE)?.toLongOrNull() ?: 0L }
            .take(MAX_PHOTOS)

        val ctx = layerContext ?: return
        if (features.isEmpty()) {
            // ★ 不再静默：数据为空只有两类原因，明确提示（此前会弹"已显示"却无标注）。
            //  Android 10+ 未授予 ACCESS_MEDIA_LOCATION 时系统会脱敏 GPS Exif，
            //  latLong 全 null —— 这曾是"有提示无注记"的最常见根因。
            Log.w(TAG, "照片标注：扫描结果为空（相册权限/媒体位置权限未授予，或相机未开启定位）")
            Toast.makeText(
                ctx,
                "照片标注：未找到含定位信息的照片\n" +
                        "（请检查相册权限、媒体位置权限 ACCESS_MEDIA_LOCATION、相机定位开关）",
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        // 图标生成耗时：IO 线程生成/读磁盘缓存，主线程挂图层
        val generation = ++mountGeneration
        launchLoad {
            // 1) 预生成图标（磁盘缓存优先；失败的要素保留，走 DEFAULT_ICON 兜底）
            val icons = mutableListOf<Pair<String, Bitmap>>()
            var missed = 0
            for (f in features) {
                val mediaId = f.getStringProperty(PROP_MEDIA_ID) ?: continue
                val iconName = "$ICON_PREFIX$mediaId"
                f.addStringProperty(PROP_ICON, iconName)
                // 单张容错：解码/构图异常只跳过该张（走兜底图），不拖垮整批
                val bmp = runCatching {
                    markerFor(ctx, mediaId, f.getStringProperty(PROP_URI) ?: return@runCatching null)
                }.getOrNull()
                if (bmp != null) icons += iconName to bmp else missed++
            }
            if (missed > 0) {
                Log.w(TAG, "照片标注：$missed/${features.size} 张缩略图生成失败（云同步占位/解码失败），兜底图标显示")
            }

            onMain {
                if (!isActive) return@onMain
                if (generation != mountGeneration) {
                    Log.i(TAG, "挂载取消：已有更新的交付（代次 $generation → $mountGeneration）")
                    return@onMain
                }
                val session = currentSession() ?: run {
                    Log.w(TAG, "挂载跳过：Session 为空")
                    return@onMain
                }
                Log.i(
                    TAG,
                    "挂载开始：features=${features.size}, icons=${icons.size}, " +
                            "sourceAttached=$sourceAttached, " +
                            "styleReady=${MapRuntime.currentStyle != null}, map=${map != null}",
                )
                mountLocked(session, features, icons)
            }
        }
    }

    /**
     * 主线程挂载（含样式就绪守卫）：
     *  · 样式未就绪（极端时序：数据比地图先到）→ 经 session.onReady 等就绪再挂，
     *    源/层直接进新样式，不抛"地图尚未就绪"；
     *  · 先注册兜底图，再注册缩略图（同名覆盖保证新鲜）；
     *  · iconImage 用 coalesce 回落到兜底图，杜绝"引用不存在图标导致静默不渲染"。
     */
    @Synchronized
    private fun mountLocked(
        session: MapSession,
        features: List<Feature>,
        icons: List<Pair<String, Bitmap>>,
    ) {
        if (!isActive) return
        if (MapRuntime.currentStyle == null) {
            Log.i(TAG, "挂载等待：样式未就绪，经 session.onReady 延迟挂载")
            session.onReady { _, _ -> mountLocked(session, features, icons) }
            return
        }
        val ctx = layerContext ?: run {
            Log.w(TAG, "挂载失败：layerContext 为空")
            return
        }
        val style = map?.style ?: run {
            Log.w(TAG, "挂载失败：map.style 为空")
            return
        }

        // 1) 兜底图（无内嵌图标的水滴针 + 白圆盘）
        style.addImage(DEFAULT_ICON, defaultMarker(ctx))

        // 2) 缩略图（重载前同名覆盖，保证缩略图新鲜）
        icons.forEach { (name, bmp) -> style.addImage(name, bmp) }

        // 3) source + 聚合计数 + 单点符号层（图2风格）
        // ★ 源只建一次；数据更新走 setGeoJson() —— 避免 removeSource+addSource
        //   触发 MapLibre GeoJSON 异步转换的销毁竞态（native SIGSEGV）。
        //   GeoJsonSource 持有原生对象，重复 addSource 同 id 会抛异常，必须复用。
        val collection = FeatureCollection.fromFeatures(features)
        if (!sourceAttached) {
            session.addSource(
                GeoJsonSource(
                    sourceId,
                    collection,
                    GeoJsonOptions()
                        .withCluster(true)
                        .withClusterMaxZoom(14)
                        .withClusterRadius(56)
                        .withBuffer(512),
                ),
            )
            // 3a) 聚合计数圆（红底白边）+ 数字文本 —— 徽章用原生 layer，任意缩放清晰
            session.addLayer(
                CircleLayer(clusterLayerId, sourceId).apply {
                    setFilter(Expression.has("point_count"))
                    setProperties(
                        PropertyFactory.circleColor(0xFFE53935.toInt()),
                        PropertyFactory.circleRadius(14f),
                        PropertyFactory.circleStrokeColor(Color.WHITE),
                        PropertyFactory.circleStrokeWidth(2.5f),
                    )
                },
            )
            session.addLayer(
                SymbolLayer(clusterCountLayerId, sourceId).apply {
                    setFilter(Expression.has("point_count"))
                    setProperties(
                        PropertyFactory.textField(Expression.get("point_count_abbreviated")),
                        PropertyFactory.textSize(13f),
                        PropertyFactory.textColor(Color.WHITE),
                        PropertyFactory.textAllowOverlap(true),
                        PropertyFactory.textIgnorePlacement(true),
                    )
                },
            )
            // 3b) 单点符号层：iconImage 要素图名 → 兜底图；过滤掉聚合要素
            session.addLayer(
                symbolLayer(symbolLayerId, sourceId)
                    .apply {
                        setProperties(
                            PropertyFactory.iconImage(
                                Expression.coalesce(
                                    Expression.get(PROP_ICON),
                                    Expression.literal(DEFAULT_ICON),
                                ),
                            ),
                            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                            PropertyFactory.iconAllowOverlap(true),
                            PropertyFactory.iconIgnorePlacement(true),
                            PropertyFactory.iconSize(
                                Expression.interpolate(
                                    Expression.linear(), Expression.zoom(),
                                    Expression.stop(10, 0.55),
                                    Expression.stop(14, 0.9),
                                    Expression.stop(17, 1.35),
                                ),
                            ),
                        )
                        setFilter(
                            Expression.all(
                                Expression.has(PROP_URI),
                                Expression.not(Expression.has("point_count")),
                            ),
                        )
                    },
            )
            sourceAttached = true
        } else {
            (style.getSource(sourceId) as? GeoJsonSource)?.setGeoJson(collection)
        }

        onAlphaChanged(mAlpha)
        applyLayerVisibility()
        Log.i(
            TAG,
            "挂载完成：source=${style.getSource(sourceId) != null}, " +
                    "layer=${style.getLayer(symbolLayerId) != null}, " +
                    "cluster=${style.getLayer(clusterLayerId) != null}, " +
                    "visible=$mVisible, alpha=$mAlpha",
        )
    }

    override fun onClearMapObjects() = removeMapObjects()

    private fun removeMapObjects() {
        currentSession()?.removeLayer(symbolLayerId)
        currentSession()?.removeSource(sourceId)
        sourceAttached = false
    }

    // =========================================================================================
    // 标记位图：MapIconUtil 底板 + 圆形裁剪缩略图（磁盘缓存）
    // =========================================================================================

    /** 读缓存或生成标记位图；返回 null 表示该照片不可用（跳过） */
    private fun markerFor(context: Context, mediaId: String, uri: String): Bitmap? {
        // v2 目录：标记风格改版后不与旧版水滴针缓存混用
        val cache = File(context.filesDir, "photo_marker_icons_v2/$mediaId.png")
        if (cache.exists()) {
            BitmapFactory.decodeFile(cache.absolutePath)?.let { return it }
        }
        val thumb = PhotoIndex.decodeSampled(context, uri, THUMB_EDGE_PX) ?: return null
        val marker = buildMarker(thumb)
        thumb.recycle()
        runCatching {
            cache.parentFile?.mkdirs()
            FileOutputStream(cache).use { marker.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return marker
    }

    /**
     * 图2风格标记：圆角矩形照片（白色边框）+ 底部居中三角尾巴。
     * 尾巴底端即图标底端，符号层 iconAnchor=BOTTOM 时精确锚定坐标点。
     */
    private fun buildMarker(thumb: Bitmap): Bitmap =
        composeMarker(thumb)

    /** 兜底标记：无底片时的占位样式（同形状，灰色照片区） */
    private fun defaultMarker(context: Context): Bitmap =
        composeMarker(null)

    /**
     * 标记合成：委托 [MapIconUtil.photoPin]（圆角照片 + 白边 + 三角尾巴），
     * thumb=null 时照片区填浅灰占位。
     */
    private fun composeMarker(thumb: Bitmap?): Bitmap =
        MapIconUtil.photoPin(
            photo = thumb,
            sizePx = MARKER_SIZE_PX,
            frameColor = Color.WHITE,
            placeholderColor = 0xFFE0E0E0.toInt(),
        )

    // =========================================================================================
    // 透明度 / 显隐
    // =========================================================================================

    override fun onAlphaChanged(alpha: Float) {
        val s = map?.style ?: return
        s.getLayer(symbolLayerId)?.setProperties(PropertyFactory.iconOpacity(alpha))
        s.getLayer(clusterLayerId)?.setProperties(PropertyFactory.circleOpacity(alpha))
        s.getLayer(clusterCountLayerId)?.setProperties(PropertyFactory.textOpacity(alpha))
    }

    override fun applyLayerVisibility() {
        val s = map?.style ?: return
        val v = if (mVisible) Property.VISIBLE else Property.NONE
        listOf(symbolLayerId, clusterLayerId, clusterCountLayerId)
            .forEach { id -> s.getLayer(id)?.setProperties(PropertyFactory.visibility(v)) }
    }

    // =========================================================================================
    // 生命周期：注册相册监听器 → 加载；detach 时注销
    // =========================================================================================

    override fun onAttach(session: MapSession, context: Context) {
        super.onAttach(session, context)
        registerMediaObserver(context)
        loadCollection()   // 首次/缓存命中走基类管道（IO 线程扫描）
    }

    override fun onDetach() {
        unregisterMediaObserver()
        PhotoViewer.dismiss()
        unmountViewerHost()
        super.onDetach()
    }

    override fun onMapReadyInternal(map: MapLibreMap) {
        registerListeners(map)
        mountViewerHost()
        if (isLoaded) applyLayerVisibility()
    }

    private fun registerListeners(map: MapLibreMap) {
        trackMapListener(
            MapLibreMap.OnMapClickListener { latLng -> onMapClicked(latLng) },
            attach = { map.addOnMapClickListener(it) },
            detach = { map.removeOnMapClickListener(it) },
        )
    }

    private fun onMapClicked(latLng: LatLng): Boolean {
        if (!isActive || !isLoaded || !mVisible) return false
        val m = map ?: return false
        val screen = m.projection.toScreenLocation(latLng)
        val feature = m.queryRenderedFeatures(screen, symbolLayerId).firstOrNull()
            ?: return false
        val uri = feature.getStringProperty(PROP_URI) ?: return false
        PhotoViewer.show(
            PhotoViewer.Item(
                uri = uri,
                name = feature.getStringProperty(PROP_NAME).orEmpty(),
            ),
        )
        return true
    }

    // =========================================================================================
    // ★ 更新模式：ContentObserver + 防抖增量扫描（不反复全量读相册）
    // =========================================================================================

    private var mediaObserver: ContentObserver? = null
    private var observerHandler: Handler? = null
    private val debounceRunnable = Runnable {
        if (isActive) incrementalScan()
    }

    private fun registerMediaObserver(context: Context) {
        if (mediaObserver != null) return
        val handler = Handler(Looper.getMainLooper())
        observerHandler = handler
        mediaObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) = scheduleIncremental()
        }.also {
            context.contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,   // 监听整个相册树（含相机目录新文件）
                it,
            )
        }
    }

    private fun unregisterMediaObserver() {
        observerHandler?.removeCallbacks(debounceRunnable)
        observerHandler = null
        layerContext?.contentResolver?.unregisterContentObserver(mediaObserver ?: return)
        mediaObserver = null
    }

    /** 防抖：相册 1.5s 内多次变化（连拍/批量导入）合并为一次增量扫描 */
    private fun scheduleIncremental() {
        val h = observerHandler ?: return
        h.removeCallbacks(debounceRunnable)
        h.postDelayed(debounceRunnable, DEBOUNCE_MS)
    }

    /**
     * 增量扫描：只处理上次扫描之后新增的照片，合并进缓存后重建图层。
     * 缓存超过 TTL（照片可能被删除/编辑过）则自动升级为全量重扫。
     */
    private fun incrementalScan() {
        val ctx = layerContext ?: return
        launchLoad {
            val cache = readLocalCache(ctx)

            // 兜底：缓存不存在或已过期 → 全量重扫（顺带清理删除项）
            if (cache == null) {
                loadCollection(forceRefresh = true)
                return@launchLoad
            }

            val sinceSec = cache.timeMs / 1000L - INCREMENTAL_BACKOFF_SEC
            val fresh = FeatureCollection.fromJson(
                PhotoIndex.scan(ctx, sinceAddedSec = sinceSec),
            ).features().orEmpty()
            if (fresh.isEmpty()) return@launchLoad

            // 合并：新数据覆盖同 id，按时间倒序
            val merged = (fresh + FeatureCollection.fromJson(cache.geojson).features().orEmpty())
                .distinctBy { it.getStringProperty(PROP_MEDIA_ID) }
                .sortedByDescending { it.getStringProperty(PROP_DATE)?.toLongOrNull() ?: 0L }

            writeLocalCache(ctx, FeatureCollection.fromFeatures(merged).toJson(), merged.size)
            onMain { onCollectionLoaded(FeatureCollection.fromFeatures(merged)) }
        }
    }

    // =========================================================================================
    // 全屏查看器宿主（与 GpkgAttrSheet 同款挂载方式）
    // =========================================================================================

    private var viewerHostView: ComposeView? = null

    private fun mountViewerHost() {
        if (viewerHostView != null) return
        val parent = MapRuntime.currentMapView ?: return
        val view = ComposeView(parent.context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { PhotoViewer.Host() }
        }
        parent.addView(
            view,
            android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        viewerHostView = view
    }

    private fun unmountViewerHost() {
        viewerHostView?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        viewerHostView = null
    }
}

// =================================================================================================
// 相册扫描器：MediaStore 枚举 + Exif 经纬度提取（纯 IO，无文件复制）
// =================================================================================================

object PhotoIndex {

    /**
     * 扫描系统相册，返回含 Exif 定位信息的点要素 GeoJSON。
     *
     * @param sinceAddedSec 大于 0 时仅扫描 DATE_ADDED 晚于该值（秒）的照片 —— 增量模式；
     *                      0 = 全量（仅相机目录，见 [CAMERA_SELECTION]）
     */
    fun scan(context: Context, sinceAddedSec: Long): String {
        val features = mutableListOf<Feature>()
        val resolver = context.contentResolver

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_TAKEN,
        )

        val (selection, args) = if (sinceAddedSec > 0) {
            "${MediaStore.Images.Media.DATE_ADDED} > ?" to
                    arrayOf(sinceAddedSec.toString())
        } else if (PhotoMarkerLayer.SCAN_ALL_BUCKETS) {
            // 全相册模式（调试/弱过滤需求）：不加目录过滤
            null to null
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 全量只扫相机目录；如需全相册，把 PhotoMarkerLayer.SCAN_ALL_BUCKETS 置 true
            "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} = ? OR " +
                    "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?" to
                    arrayOf("Camera", "DCIM/%")
        } else {
            // API 29 以下无 RELATIVE_PATH 列，只能按桶名过滤
            "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} = ?" to arrayOf("Camera")
        }

        // ★ 诊断计数：区分"查询无行（权限/过滤）"与"有行但 Exif 无定位
        //   （ACCESS_MEDIA_LOCATION 脱敏 / 相机未开定位）"——空结果 Toast 的两类根因
        var rows = 0
        var noExif = 0
        var openFailed = 0

        resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, selection, args,
            "${MediaStore.Images.Media.DATE_ADDED} ASC",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val addedCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)

            while (cursor.moveToNext()) {
                rows++
                val id = cursor.getLong(idCol)
                val uri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id,
                )
                // ★ Exif 解析是唯一耗时点：流式读取头信息，不解码整图
                val latLng = when (val r = readExifLatLng(context, uri)) {
                    null -> {
                        if (exifOpenFailed(context, uri)) openFailed++ else noExif++
                        continue
                    }
                    else -> r
                }
                features += Feature.fromGeometry(
                    Point.fromLngLat(latLng[1].toDouble(), latLng[0].toDouble()),
                    com.google.gson.JsonObject().apply {
                        addProperty(PhotoMarkerLayer.PROP_MEDIA_ID, id.toString())
                        addProperty(PhotoMarkerLayer.PROP_NAME, cursor.getString(nameCol) ?: "IMG_$id")
                        addProperty(PhotoMarkerLayer.PROP_URI, uri.toString())
                        addProperty(PhotoMarkerLayer.PROP_DATE, cursor.getLong(addedCol))
                    },
                )
            }
        }
        Log.i(
            PhotoMarkerLayer.TAG, "扫描完成：rows=$rows, 含定位=${features.size}, " +
                    "无定位=$noExif, 打开失败=$openFailed, " +
                    "增量=${sinceAddedSec > 0}, 全相册=$PhotoMarkerLayer.SCAN_ALL_BUCKETS",
        )
        return FeatureCollection.fromFeatures(features).toJson()
    }

    /**
     * 区分"文件打不开"（云同步占位/权限）与"能打开但无 GPS ExIF"。
     * 打开失败单独计数 —— 这类照片即使授予 ACCESS_MEDIA_LOCATION 也读不到。
     */
    private fun exifOpenFailed(context: Context, uri: Uri): Boolean =
        context.contentResolver.openInputStream(uri)?.use { true } == null

    /**
     * Exif GPS 提取：纬度/经度任一缺失即返回 null（未定位照片不参与标注）。
     *
     * 注意：androidx ExifInterface 1.1.0+ 的无参 getLatLong() 返回 double[]?
     * （有参 float[] 版已 @Deprecated），Kotlin 属性 latLong 即 double[]?。
     */
    private fun readExifLatLng(context: Context, uri: Uri): DoubleArray? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            ExifInterface(input).latLong   // DoubleArray[2]：[lat, lng]，无定位返回 null
        }
    }.getOrNull()

    /**
     * 按目标边长采样解码（缩略图/大图浏览共用）。
     *
     * 两阶段：BitmapFactory 采样解码优先（省内存）；失败时（典型：华为等设备
     * 相机默认 HEIF/HEIC 格式，BitmapFactory 不支持但 ExifInterface 能读）
     * 回落 ImageDecoder（API 28+ 原生支持 HEIC/AVIF）。真实异常单次记录。
     */
    fun decodeSampled(context: Context, uri: String, maxEdge: Int): Bitmap? {
        val parsed = Uri.parse(uri)

        // 阶段 1：BitmapFactory 采样解码（省内存）。
        // ★ 注意：不得在这里用非局部 return —— 会直接跳出函数、跳过阶段 2 兜底。
        var factoryErr: Throwable? = null
        val viaFactory: Bitmap? = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val head = context.contentResolver.openInputStream(parsed)
                ?: return@runCatching null
            head.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2

            val input = context.contentResolver.openInputStream(parsed)
                ?: return@runCatching null
            input.use {
                BitmapFactory.decodeStream(
                    it, null, BitmapFactory.Options().apply { inSampleSize = sample },
                )
            }
        }.onFailure { factoryErr = it }.getOrNull()

        if (viaFactory != null) return viaFactory

        // 阶段 2：ImageDecoder 兜底（HEIC/AVIF 等）
        var decoderErr: Throwable? = null
        val viaDecoder = runCatching { decodeViaImageDecoder(context, parsed, maxEdge) }
            .onFailure { decoderErr = it }.getOrNull()
        if (viaDecoder == null) logDecodeFailureOnce(uri, factoryErr, decoderErr)
        return viaDecoder
    }

    /** ImageDecoder 兜底（API 28+）：支持 HEIC/AVIF 等 BitmapFactory 解不了的格式 */
    private fun decodeViaImageDecoder(context: Context, uri: Uri, maxEdge: Int): Bitmap? =
        runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
            val src = android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
            val bmp = android.graphics.ImageDecoder.decodeBitmap(src)
            val scale = maxEdge.toFloat() / maxOf(bmp.width, bmp.height)
            if (scale >= 1f) bmp
            else Bitmap.createScaledBitmap(
                bmp,
                (bmp.width * scale).roundToInt().coerceAtLeast(1),
                (bmp.height * scale).roundToInt().coerceAtLeast(1),
                true,
            )
        }.getOrNull()

    /** 解码失败：记录两阶段真实异常（同类只记一次，避免刷屏） */
    private var loggedFailureKinds = mutableSetOf<String>()
    private fun logDecodeFailureOnce(uri: String, factoryErr: Throwable?, decoderErr: Throwable?) {
        val key = listOf(factoryErr?.javaClass?.simpleName, decoderErr?.javaClass?.simpleName)
            .joinToString("/")
        if (!loggedFailureKinds.add(key)) return
        Log.w(
            PhotoMarkerLayer.TAG,
            "缩略图解码失败[$key] uri=$uri\n" +
                    "  BitmapFactory: ${factoryErr?.javaClass?.name}: ${factoryErr?.message}\n" +
                    "  ImageDecoder:  ${decoderErr?.javaClass?.name}: ${decoderErr?.message}",
        )
    }
}

// =================================================================================================
// 开关管理器：开关状态持久化 + 注册/启停（供 MapLayersControl 调用）
// =================================================================================================

object PhotoMarkerLayers {

    private const val PREFS = "photo_marker_prefs"
    private const val KEY_ENABLED = "enabled"

    /** 开关是否打开（跨启动持久化） */
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    /**
     * 开关切换：注册（幂等）→ 按目标状态精确启停 → 持久化。
     * MapLayersControl 的开关回调直接接这里。
     *
     * ★ 走框架图层通道（registerFramework）：attach 直接落地图主 Session，
     *   不依赖任何插件加载；地图未就绪时管理器挂起，就绪后自动补 attach。
     */
    fun setEnabled(context: Context, enabled: Boolean) {
        registerIfAbsent()
        if (enabled) {
            MapLayerManager.enable(PhotoMarkerLayer.LAYER_ID, context)
        } else {
            MapLayerManager.disable(PhotoMarkerLayer.LAYER_ID, context)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    // -------------------------------------------------------------------------
    // ★ 权限助手：READ_MEDIA_* 可读相册、ACCESS_MEDIA_LOCATION 决定 Exif GPS
    //   是否被系统脱敏（未授予 → latLong 全 null → 扫描为空）。
    //   开关 UI 建议先 hasAllPermissions() 检查，缺权限时先走运行时申请
    //   （ActivityResultContracts.RequestMultiplePermissions），
    //    grant 回调里再 setEnabled(context, true) 并 refresh() 重扫。
    // -------------------------------------------------------------------------

    /** 全部必要权限（按 API 级别区分读相册权限；ACCESS_MEDIA_LOCATION 29+） */
    val REQUIRED_PERMISSIONS: Array<String>
        get() = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(android.Manifest.permission.READ_MEDIA_IMAGES)
            } else {
                add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(android.Manifest.permission.ACCESS_MEDIA_LOCATION)
            }
        }.toTypedArray()

    /** 权限是否齐全 */
    fun hasAllPermissions(context: Context): Boolean =
        REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(context, it) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    /** 缺哪些权限（用于申请前提示） */
    fun missingPermissions(context: Context): List<String> =
        REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(context, it) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    /** 应用启动时恢复：开关曾打开则自动开启图层（数据走缓存秒开 + 增量更新） */
    fun restoreOnStartup(context: Context) {
        if (isEnabled(context)) {
            registerIfAbsent()
            MapLayerManager.enable(PhotoMarkerLayer.LAYER_ID, context)
        }
    }

    /** 注册（幂等）：框架级图层，不归属任何插件 */
    fun registerIfAbsent() {
        if (!MapLayerManager.isRegistered(PhotoMarkerLayer.LAYER_ID)) {
            MapLayerManager.registerFramework(PhotoMarkerLayer.LAYER_ID) {
                PhotoMarkerLayer()
            }
        }
    }
}

// =================================================================================================
// 全屏照片查看器：捏合缩放 / 拖动 / 双击 / 采样解码大图
// =================================================================================================

object PhotoViewer {

    data class Item(val uri: String, val name: String)

    private val _item = MutableStateFlow<Item?>(null)

    fun show(item: Item) {
        _item.value = item
    }

    /** 关闭（仅清空数据，Dialog 随状态自动卸载） */
    fun dismiss() {
        _item.value = null
    }

    @Composable
    fun Host() {
        val item by _item.collectAsState()
        val current = item ?: return

        Dialog(
            onDismissRequest = { dismiss() },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,   // 真·全屏
                decorFitsSystemWindows = false,
            ),
        ) {
            ZoomablePhoto(item = current)
        }
    }

    @Composable
    private fun ZoomablePhoto(item: Item) {
        val context = LocalContext.current

        var scale by remember(item.uri) { mutableFloatStateOf(1f) }
        var offset by remember(item.uri) { mutableStateOf(Offset.Zero) }
        var bitmap by remember(item.uri) { mutableStateOf<Bitmap?>(null) }

        // IO 线程采样解码原图（最长边 2048，兼顾清晰度与内存）
        LaunchedEffect(item.uri) {
            bitmap = withContext(Dispatchers.IO) {
                PhotoIndex.decodeSampled(context, item.uri, 2048)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color.Black)
                // 捏合缩放 + 单指拖动（缩放态）
                .pointerInput(item.uri) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        offset = if (scale > 1f) offset + pan else Offset.Zero
                    }
                }
                // 双击：放大 2x ↔ 复位
                .pointerInput(item.uri) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (scale > 1f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = 2f
                            }
                        },
                    )
                },
        ) {
            val bmp = bitmap
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = item.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y,
                        ),
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = androidx.compose.ui.graphics.Color.White,
                )
            }

            // 文件名 + 关闭
            Text(
                text = item.name,
                style = MaterialTheme.typography.labelMedium,
                color = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 16.dp, top = 40.dp),
            )
            IconButton(
                onClick = { dismiss() },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 32.dp, end = 8.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "关闭",
                    tint = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}