package org.cwcc.open.geokori.map

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.toBitmap
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import org.cwcc.open.geokori.lib.utils.MapIconUtil
import org.maplibre.android.camera.CameraUpdateFactory
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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 系统照片标注图层（框架级 · 单实例）。
 *
 * 两级聚合：
 *  · 一级（物理）：KNN 30 米距离聚合，解决地图放大时点覆盖问题。
 *  · 二级（视觉）：MapLibre 原生 cluster，withClusterProperty 求和 photo_count。
 *
 * 图标形态（对齐 Android 系统地图相册）：
 *  · 所有 KNN 点统一使用 photoPin：圆角方形照片 + 白边 + 底部三角尾巴；
 *  · 聚合组（photo_count > 1）额外在照片右上角绘制红底白字数字角标。
 *
 * 缩放行为：iconSize 从低缩放的小尺寸逐渐长到中缩放，再略收缩。
 *
 * 查看器：
 *  · 从地图聚合点进入网格相册（铺满全屏），从网格进入全屏浏览；
 *  · 全屏浏览复用 [FeatureAttrSheet.FullscreenViewer]（黑底铺满 + 缩放 + 旋转 + 页码）；
 *  · 全屏顶部关闭返回网格，网格顶部关闭返回地图。
 */
class PhotoMarkerLayer : BaseBizeLibreLayer() {

    override val displayName: String = "照片标注"

    override val cacheDataFile: String = "photo_markers_cache.geojson"
    override val cacheMetaFile: String = "photo_markers_cache.meta"
    override val cacheTtlMs: Long = 12L * 60 * 60 * 1000

    private val sourceId = "__photo_src"
    private val symbolLayerId = "__photo_sym"
    private val clusterLayerId = "__photo_cluster"
    private val clusterCountLayerId = "__photo_cluster_cnt"

    @Volatile
    private var sourceAttached = false

    @Volatile
    private var mountGeneration = 0L

    companion object {
        internal const val TAG = "PhotoMarkerLayer"
        internal const val SCAN_ALL_BUCKETS = false
        const val LAYER_ID = "photo_markers"

        const val PROP_MEDIA_ID = "media_id"
        const val PROP_NAME = "name"
        const val PROP_URI = "uri"
        const val PROP_DATE = "date"
        const val PROP_ICON = "icon"

        const val PROP_PHOTO_COUNT = "photo_count"
        const val PROP_PHOTO_URIS = "photo_uris"
        const val PROP_SUM_PHOTO_COUNT = "sum_photo_count"

        private const val KNN_CLUSTER_RADIUS_METERS = 30.0
        private const val CLUSTER_MAX_ZOOM = 15
        private const val CLUSTER_RADIUS_PX = 56

        private const val ICON_PREFIX = "__photo_icon_"
        private const val DEFAULT_ICON = "__photo_default"
        private const val MAX_PHOTOS = 300

        /** ★ 从 160 降到 128：低缩放视野下图钉不再占据过多屏幕 */
        private const val MARKER_SIZE_PX = 128

        private const val THUMB_EDGE_PX = 320
        private const val DEBOUNCE_MS = 1500L
        private const val INCREMENTAL_BACKOFF_SEC = 60L

        /**
         * ★ pin 几何常量，必须与 [MapIconUtil.photoPin] 调用处保持一致。
         * MapIconUtil.photoPin 默认：photoRatio=0.68f, top=sizePx*0.05f。
         */
        private const val PIN_PHOTO_RATIO = 0.68f
        private const val PIN_TOP_RATIO = 0.05f

        /** 角标占照片区边长比例 */
        private const val BADGE_SIZE_RATIO = 0.24f
    }

    override fun fetchRemoteGeoJson(): String =
        PhotoIndex.scan(context = layerContext!!, sinceAddedSec = 0L)

    // =========================================================================================
    // 一级聚合 + 图标生成
    // =========================================================================================

    override fun onCollectionLoaded(collection: FeatureCollection) {
        val rawFeatures = collection.features().orEmpty()
            .sortedByDescending { it.getStringProperty(PROP_DATE)?.toLongOrNull() ?: 0L }
            .take(MAX_PHOTOS)

        val ctx = layerContext ?: return
        if (rawFeatures.isEmpty()) {
            Log.w(TAG, "照片标注：扫描结果为空")
            Toast.makeText(
                ctx,
                "照片标注：未找到含定位信息的照片\n" +
                        "（请检查相册权限、媒体位置权限 ACCESS_MEDIA_LOCATION、相机定位开关）",
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        val knnFeatures = clusterByDistance(rawFeatures)
        Log.i(TAG, "KNN 聚合完成：raw=${rawFeatures.size}, knn=${knnFeatures.size}")

        val generation = ++mountGeneration
        launchLoad {
            val icons = mutableListOf<Pair<String, Bitmap>>()
            var missed = 0
            val imageLoader = ctx.imageLoader
            val targetDensity = DisplayMetrics.DENSITY_DEFAULT

            for (f in knnFeatures) {
                val mediaId = f.getStringProperty(PROP_MEDIA_ID) ?: continue
                val uri = f.getStringProperty(PROP_URI) ?: continue
                val iconName = "$ICON_PREFIX$mediaId"
                f.addStringProperty(PROP_ICON, iconName)

                val bitmap = runCatching {
                    val request = ImageRequest.Builder(ctx)
                        .data(uri)
                        .size(MARKER_SIZE_PX)
                        // ★ 关键：禁用 Hardware Bitmap，否则 Canvas.drawBitmap 静默失败
                        .allowHardware(false)
                        .build()
                    (imageLoader.execute(request) as? SuccessResult)?.image?.toBitmap()
                }.getOrNull()

                if (bitmap != null) {
                    bitmap.density = targetDensity
                    val count = f.getNumberProperty(PROP_PHOTO_COUNT)?.toInt() ?: 1

                    // ★ 统一 pin 形态；聚合组叠角标
                    val pin = composePin(bitmap).also { it.density = targetDensity }
                    val marker = if (count > 1) {
                        drawKnnBadge(pin, count).also { it.density = targetDensity }
                    } else {
                        pin
                    }
                    icons += iconName to marker
                } else {
                    missed++
                    Log.w(TAG, "缩略图加载失败 mediaId=$mediaId uri=$uri")
                }
            }
            Log.i(TAG, "图标生成完成：成功=${icons.size}, 失败=$missed, 总=${knnFeatures.size}")

            onMain {
                if (!isActive) return@onMain
                if (generation != mountGeneration) {
                    Log.i(TAG, "挂载取消：已有更新的交付")
                    return@onMain
                }
                val session = currentSession() ?: return@onMain
                mountLocked(session, knnFeatures, icons)
            }
        }
    }

    private fun clusterByDistance(features: List<Feature>): List<Feature> {
        val result = mutableListOf<Feature>()
        val visited = BooleanArray(features.size) { false }

        for (i in features.indices) {
            if (visited[i]) continue
            val seed = features[i]
            val seedPoint = seed.geometry() as? Point ?: continue
            val seedLat = seedPoint.latitude()
            val seedLng = seedPoint.longitude()

            val group = mutableListOf(i)
            visited[i] = true

            for (j in i + 1 until features.size) {
                if (visited[j]) continue
                val p = features[j].geometry() as? Point ?: continue
                if (haversineMeters(seedLat, seedLng, p.latitude(), p.longitude())
                    <= KNN_CLUSTER_RADIUS_METERS
                ) {
                    group += j
                    visited[j] = true
                }
            }

            if (group.size == 1) {
                seed.addNumberProperty(PROP_PHOTO_COUNT, 1)
                result += seed
            } else {
                val photos = group.map { features[it] }
                val first = photos.first()
                val cluster = Feature.fromGeometry(seedPoint).apply {
                    addStringProperty(PROP_MEDIA_ID, first.getStringProperty(PROP_MEDIA_ID))
                    addStringProperty(PROP_URI, first.getStringProperty(PROP_URI))
                    addStringProperty(PROP_NAME, first.getStringProperty(PROP_NAME))
                    addStringProperty(PROP_DATE, first.getStringProperty(PROP_DATE))
                    addNumberProperty(PROP_PHOTO_COUNT, photos.size)
                    addStringProperty(
                        PROP_PHOTO_URIS,
                        photos.map { it.getStringProperty(PROP_URI) }
                            .joinToString(prefix = "[", postfix = "]", separator = ",") { "\"$it\"" },
                    )
                }
                result += cluster
            }
        }
        return result
    }

    private fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /**
     * KNN 数字角标：贴照片区右上角，红底白边白字。
     * 几何参数与 [composePin] 保持一致（photoRatio=0.68f, topRatio=0.05f）。
     */
    private fun drawKnnBadge(source: Bitmap, count: Int): Bitmap {
        val result = source.copy(Bitmap.Config.ARGB_8888, true)
        result.density = DisplayMetrics.DENSITY_DEFAULT

        val canvas = Canvas(result)
        val w = result.width.toFloat()

        val photoSide = w * PIN_PHOTO_RATIO
        val photoLeft = (w - photoSide) / 2f
        val photoTop = w * PIN_TOP_RATIO
        val photoRight = photoLeft + photoSide

        val badgeRadius = photoSide * BADGE_SIZE_RATIO
        // 圆心贴照片区右上角，向内缩 60% 半径
        val cx = photoRight - badgeRadius * 0.6f
        val cy = photoTop + badgeRadius * 0.6f

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFE53935.toInt()
            style = Paint.Style.FILL
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = badgeRadius * 0.18f
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = badgeRadius * 0.95f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }

        canvas.drawCircle(cx, cy, badgeRadius + strokePaint.strokeWidth / 2f, strokePaint)
        canvas.drawCircle(cx, cy, badgeRadius, bgPaint)

        val fm = textPaint.fontMetrics
        val baseline = cy - (fm.ascent + fm.descent) / 2f
        val label = if (count > 99) "99+" else count.toString()
        canvas.drawText(label, cx, baseline, textPaint)

        return result
    }

    // =========================================================================================
    // 图层组装
    // =========================================================================================

    @Synchronized
    private fun mountLocked(
        session: MapSession,
        knnFeatures: List<Feature>,
        icons: List<Pair<String, Bitmap>>,
    ) {
        if (!isActive) return
        if (MapRuntime.currentStyle == null) {
            session.onReady { _, _ -> mountLocked(session, knnFeatures, icons) }
            return
        }
        val ctx = layerContext ?: return
        val style = map?.style ?: return

        val targetDensity = DisplayMetrics.DENSITY_DEFAULT

        val fallback = defaultMarker().also { it.density = targetDensity }
        runCatching { style.addImage(DEFAULT_ICON, fallback) }
            .onFailure { Log.e(TAG, "兜底图注册失败", it) }

        var added = 0
        icons.forEach { (name, bmp) ->
            runCatching {
                if (bmp.density <= 0) bmp.density = targetDensity
                style.addImage(name, bmp)
            }.onSuccess { added++ }
                .onFailure { Log.e(TAG, "缩略图注册失败 name=$name", it) }
        }
        Log.i(TAG, "缩略图注册完成：成功=$added, 期望=${icons.size}")

        val collection = FeatureCollection.fromFeatures(knnFeatures)
        if (!sourceAttached) {
            session.addSource(
                GeoJsonSource(
                    sourceId,
                    collection,
                    GeoJsonOptions()
                        .withCluster(true)
                        .withClusterMaxZoom(CLUSTER_MAX_ZOOM)
                        .withClusterRadius(CLUSTER_RADIUS_PX)
                        .withBuffer(512)
                        .withClusterProperty(
                            PROP_SUM_PHOTO_COUNT,
                            Expression.sum(
                                Expression.accumulated(),
                                Expression.get(PROP_PHOTO_COUNT),
                            ),
                            Expression.get(PROP_PHOTO_COUNT),
                        ),
                ),
            )

            // 层 1a：MapLibre cluster 红底圆
            session.addLayer(
                CircleLayer(clusterLayerId, sourceId).apply {
                    setFilter(Expression.has("point_count"))
                    setProperties(
                        PropertyFactory.circleColor(0xFFE53935.toInt()),
                        PropertyFactory.circleRadius(
                            Expression.interpolate(
                                Expression.linear(), Expression.zoom(),
                                Expression.stop(6f, 12f),
                                Expression.stop(10f, 16f),
                                Expression.stop(14f, 14f),
                                Expression.stop(18f, 11f),
                            ),
                        ),
                        PropertyFactory.circleStrokeColor(Color.WHITE),
                        PropertyFactory.circleStrokeWidth(2.5f),
                    )
                },
            )

            // 层 1b：MapLibre cluster 数字
            session.addLayer(
                SymbolLayer(clusterCountLayerId, sourceId).apply {
                    setFilter(Expression.has("point_count"))
                    setProperties(
                        PropertyFactory.textField(
                            Expression.concat(
                                Expression.coalesce(
                                    Expression.get(PROP_SUM_PHOTO_COUNT),
                                    Expression.get("point_count"),
                                    Expression.literal(1),
                                ),
                                Expression.literal(""),
                            ),
                        ),
                        PropertyFactory.textSize(
                            Expression.interpolate(
                                Expression.linear(), Expression.zoom(),
                                Expression.stop(6f, 11f),
                                Expression.stop(10f, 14f),
                                Expression.stop(14f, 13f),
                                Expression.stop(18f, 11f),
                            ),
                        ),
                        PropertyFactory.textColor(Color.WHITE),
                        PropertyFactory.textAllowOverlap(true),
                        PropertyFactory.textIgnorePlacement(true),
                    )
                },
            )

            // 层 2：KNN 缩略图（pin 形态，含 Bitmap 内嵌角标）
            // ★ iconSize 在低缩放（6~10）时压到 0.30~0.55，避免远视野图钉巨大。
            session.addLayer(
                symbolLayer(symbolLayerId, sourceId).apply {
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
                                Expression.stop(6f, 0.30f),
                                Expression.stop(8f, 0.42f),
                                Expression.stop(10f, 0.55f),
                                Expression.stop(12f, 0.68f),
                                Expression.stop(14f, 0.72f),
                                Expression.stop(16f, 0.65f),
                                Expression.stop(18f, 0.55f),
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
                    "sym=${style.getLayer(symbolLayerId) != null}, " +
                    "cluster=${style.getLayer(clusterLayerId) != null}, " +
                    "cnt=${style.getLayer(clusterCountLayerId) != null}, " +
                    "features=${knnFeatures.size}, visible=$mVisible, alpha=$mAlpha",
        )
    }

    override fun onClearMapObjects() = removeMapObjects()

    private fun removeMapObjects() {
        currentSession()?.removeLayer(symbolLayerId)
        currentSession()?.removeLayer(clusterLayerId)
        currentSession()?.removeLayer(clusterCountLayerId)
        currentSession()?.removeSource(sourceId)
        sourceAttached = false
    }

    // =========================================================================================
    // 图标合成：统一 pin 形态
    // =========================================================================================

    /** pin：圆角方形照片 + 白边 + 底部三角尾巴（对齐系统地图相册） */
    private fun composePin(thumb: Bitmap?): Bitmap =
        MapIconUtil.photoPin(
            photo = thumb,
            sizePx = MARKER_SIZE_PX,
            frameColor = Color.WHITE,
            placeholderColor = 0xFFE0E0E0.toInt(),
            photoRatio = PIN_PHOTO_RATIO,
            tailLenRatio = 0.18f,
            cornerRatio = 0.10f,
        )

    /** 兜底 pin（无照片） */
    private fun defaultMarker(): Bitmap =
        composePin(null).also { it.density = DisplayMetrics.DENSITY_DEFAULT }

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
    // 生命周期
    // =========================================================================================

    override fun onAttach(session: MapSession, context: Context) {
        super.onAttach(session, context)
        registerMediaObserver(context)
        loadCollection()
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

    // =========================================================================================
    // 点击分流
    // =========================================================================================

    private fun onMapClicked(latLng: LatLng): Boolean {
        if (!isActive || !isLoaded || !mVisible) return false
        val m = map ?: return false
        val screen: PointF = m.projection.toScreenLocation(latLng)

        val clusterFeature =
            m.queryRenderedFeatures(screen, clusterLayerId).firstOrNull()
                ?: m.queryRenderedFeatures(screen, clusterCountLayerId).firstOrNull()

        if (clusterFeature != null) {
            val source = m.style?.getSource(sourceId) as? GeoJsonSource ?: return true
            val expansionZoom = source.getClusterExpansionZoom(clusterFeature)
            m.animateCamera(
                CameraUpdateFactory.newLatLngZoom(latLng, expansionZoom.toDouble()),
                400,
            )
            return true
        }

        val feature = m.queryRenderedFeatures(screen, symbolLayerId).firstOrNull() ?: return false
        val photoCount = feature.getNumberProperty(PROP_PHOTO_COUNT)?.toInt() ?: 1

        if (photoCount > 1) {
            val urisJson = feature.getStringProperty(PROP_PHOTO_URIS) ?: return false
            val uris = runCatching {
                Gson().fromJson(urisJson, Array<String>::class.java).toList()
            }.getOrDefault(emptyList())
            if (uris.isEmpty()) return false
            PhotoViewer.showGrid(uris)
        } else {
            val uri = feature.getStringProperty(PROP_URI) ?: return false
            PhotoViewer.showList(listOf(uri), 0)
        }
        return true
    }

    // =========================================================================================
    // ContentObserver + 增量扫描
    // =========================================================================================

    private var mediaObserver: ContentObserver? = null
    private var observerHandler: Handler? = null
    private val debounceRunnable = Runnable { if (isActive) incrementalScan() }

    private fun registerMediaObserver(context: Context) {
        if (mediaObserver != null) return
        val handler = Handler(Looper.getMainLooper())
        observerHandler = handler
        mediaObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) = scheduleIncremental()
        }.also {
            context.contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
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

    private fun scheduleIncremental() {
        val h = observerHandler ?: return
        h.removeCallbacks(debounceRunnable)
        h.postDelayed(debounceRunnable, DEBOUNCE_MS)
    }

    private fun incrementalScan() {
        val ctx = layerContext ?: return
        launchLoad {
            val cache = readLocalCache(ctx)
            if (cache == null) {
                loadCollection(forceRefresh = true)
                return@launchLoad
            }

            val sinceSec = cache.timeMs / 1000L - INCREMENTAL_BACKOFF_SEC
            val fresh = FeatureCollection.fromJson(
                PhotoIndex.scan(ctx, sinceAddedSec = sinceSec),
            ).features().orEmpty()
            if (fresh.isEmpty()) return@launchLoad

            val merged = (fresh + FeatureCollection.fromJson(cache.geojson).features().orEmpty())
                .distinctBy { it.getStringProperty(PROP_MEDIA_ID) }
                .sortedByDescending { it.getStringProperty(PROP_DATE)?.toLongOrNull() ?: 0L }

            writeLocalCache(ctx, FeatureCollection.fromFeatures(merged).toJson(), merged.size)
            onMain { onCollectionLoaded(FeatureCollection.fromFeatures(merged)) }
        }
    }

    // =========================================================================================
    // 查看器宿主
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
// 相册扫描器
// =================================================================================================

object PhotoIndex {

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
            null to null
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} = ? OR " +
                    "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?" to
                    arrayOf("Camera", "DCIM/%")
        } else {
            "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} = ?" to arrayOf("Camera")
        }

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
                val latLng = when (val r = readExifLatLng(context, uri)) {
                    null -> {
                        if (exifOpenFailed(context, uri)) openFailed++ else noExif++
                        continue
                    }
                    else -> r
                }
                features += Feature.fromGeometry(
                    Point.fromLngLat(latLng[1], latLng[0]),
                    com.google.gson.JsonObject().apply {
                        addProperty(PhotoMarkerLayer.PROP_MEDIA_ID, id.toString())
                        addProperty(
                            PhotoMarkerLayer.PROP_NAME,
                            cursor.getString(nameCol) ?: "IMG_$id",
                        )
                        addProperty(PhotoMarkerLayer.PROP_URI, uri.toString())
                        addProperty(PhotoMarkerLayer.PROP_DATE, cursor.getLong(addedCol))
                    },
                )
            }
        }
        Log.i(
            PhotoMarkerLayer.TAG,
            "扫描完成：rows=$rows, 含定位=${features.size}, " +
                    "无定位=$noExif, 打开失败=$openFailed, 增量=${sinceAddedSec > 0}",
        )
        return FeatureCollection.fromFeatures(features).toJson()
    }

    private fun exifOpenFailed(context: Context, uri: Uri): Boolean =
        context.contentResolver.openInputStream(uri)?.use { true } == null

    private fun readExifLatLng(context: Context, uri: Uri): DoubleArray? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            ExifInterface(input).latLong
        }
    }.getOrNull()

    fun decodeSampled(context: Context, uri: String, maxEdge: Int): Bitmap? {
        val parsed = uri.toUri()

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

        if (viaFactory != null) {
            if (viaFactory.density <= 0) {
                viaFactory.density = DisplayMetrics.DENSITY_DEFAULT
            }
            return viaFactory
        }

        var decoderErr: Throwable? = null
        val viaDecoder = runCatching { decodeViaImageDecoder(context, parsed, maxEdge) }
            .onFailure { decoderErr = it }.getOrNull()
        if (viaDecoder == null) logDecodeFailureOnce(uri, factoryErr, decoderErr)
        if (viaDecoder != null && viaDecoder.density <= 0) {
            viaDecoder.density = DisplayMetrics.DENSITY_DEFAULT
        }
        return viaDecoder
    }

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

    private val loggedFailureKinds = mutableSetOf<String>()
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
// 开关管理器
// =================================================================================================

object PhotoMarkerLayers {

    private const val PREFS = "photo_marker_prefs"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

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

    fun hasAllPermissions(context: Context): Boolean =
        REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(context, it) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    fun missingPermissions(context: Context): List<String> =
        REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(context, it) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    fun restoreOnStartup(context: Context) {
        if (isEnabled(context)) {
            registerIfAbsent()
            MapLayerManager.enable(PhotoMarkerLayer.LAYER_ID, context)
        }
    }

    fun registerIfAbsent() {
        if (!MapLayerManager.isRegistered(PhotoMarkerLayer.LAYER_ID)) {
            MapLayerManager.registerFramework(PhotoMarkerLayer.LAYER_ID) {
                PhotoMarkerLayer()
            }
        }
    }
}

// =================================================================================================
// 全屏照片查看器：网格相册（全屏铺满）+ 复用 FeatureAttrSheet.FullscreenViewer
// =================================================================================================

object PhotoViewer {

    data class Item(val uri: String, val name: String = "")

    private val _photos = MutableStateFlow<List<String>>(emptyList())
    private val _currentIndex = MutableStateFlow(0)
    private val _isGridMode = MutableStateFlow(false)

    /** 记录进入全屏前的来源，用于"返回上一层" */
    private val _cameFromGrid = MutableStateFlow(false)
    private val _gridPhotosBackup = MutableStateFlow<List<String>>(emptyList())

    /** 兼容旧调用：单张全屏 */
    fun show(item: Item) = showList(listOf(item.uri), 0)

    /** 显示网格相册（从地图聚合点进入） */
    fun showGrid(uris: List<String>) {
        if (uris.isEmpty()) return
        _photos.value = uris
        _isGridMode.value = true
        _cameFromGrid.value = false
        _gridPhotosBackup.value = emptyList()
    }

    /** 显示全屏查看器（从单点或网格内进入） */
    fun showList(uris: List<String>, startIndex: Int) {
        if (uris.isEmpty()) return
        // ★ 若当前是网格，备份以便返回
        if (_isGridMode.value) {
            _cameFromGrid.value = true
            _gridPhotosBackup.value = _photos.value
        } else {
            _cameFromGrid.value = false
        }
        _photos.value = uris
        _currentIndex.value = startIndex.coerceIn(0, uris.lastIndex)
        _isGridMode.value = false
    }

    /** 全屏关闭：优先返回网格，否则完全关闭 */
    fun backFromFullScreen() {
        if (_cameFromGrid.value) {
            _photos.value = _gridPhotosBackup.value
            _isGridMode.value = true
            _cameFromGrid.value = false
            _gridPhotosBackup.value = emptyList()
        } else {
            dismiss()
        }
    }

    /** 完全关闭（网格顶部关闭、图层 detach） */
    fun dismiss() {
        _photos.value = emptyList()
        _isGridMode.value = false
        _cameFromGrid.value = false
        _gridPhotosBackup.value = emptyList()
    }

    @Composable
    fun Host() {
        val photos by _photos.collectAsState()
        val isGrid by _isGridMode.collectAsState()
        if (photos.isEmpty()) return

        if (isGrid) {
            PhotoGridDialog(
                photos = photos,
                onPhotoClick = { index -> showList(photos, index) },
                onDismiss = { dismiss() },
            )
        } else {
            val startIndex by _currentIndex.collectAsState()
            // ★ 全屏浏览复用 FeatureAttrSheet.FullscreenViewer：
            //   黑底铺满 / 图片缩放双击 / 旋转 / 页码 / 关闭按钮 全部由它提供。
            //   Coil 直接加载 content:// URI 没有问题，无需先转本地路径。
            val attachments = remember(photos) {
                photos.map { FeatureAttrSheet.Attachment(url = it, isVideo = false) }
            }
            FeatureAttrSheet.FullscreenViewer(
                attachments = attachments,
                initialPage = startIndex,
                onClose = { backFromFullScreen() },
            )
        }
    }
}

/**
 * Google Photos 风格：4 列网格相册（真正铺满全屏）。
 *
 * 仅靠 [DialogProperties] 的 `usePlatformDefaultWidth=false` +
 * `decorFitsSystemWindows=false` 在部分设备上仍会被系统栏或默认 window 尺寸撑开，
 * 因此通过 [DialogWindowProvider] 拿到 Dialog 的 Window，强制设为 MATCH_PARENT，
 * 并替换默认 Window 背景、去掉背后变暗遮罩。
 */
@Composable
private fun PhotoGridDialog(
    photos: List<String>,
    onPhotoClick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        // ★ 拿到 Dialog Window，强制铺满屏幕
        val view = LocalView.current
        LaunchedEffect(Unit) {
            val window = generateSequence(view.parent) { it.parent }
                .filterIsInstance<DialogWindowProvider>()
                .firstOrNull()?.window ?: return@LaunchedEffect
            window.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
            )
            window.setBackgroundDrawable(ColorDrawable(Color.WHITE))
            window.setDimAmount(0f)
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = androidx.compose.ui.graphics.Color(0xFFF5F5F5),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(vertical = 12.dp, horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "关闭",
                            tint = androidx.compose.ui.graphics.Color.Black,
                        )
                    }
                    Text(
                        text = "所有照片 (${photos.size})",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.size(48.dp))
                }

                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    itemsIndexed(photos) { index, uri ->
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(uri)
                                .crossfade(false)
                                .build(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(2.dp))
                                .clickable { onPhotoClick(index) },
                        )
                    }
                }
            }
        }
    }
}