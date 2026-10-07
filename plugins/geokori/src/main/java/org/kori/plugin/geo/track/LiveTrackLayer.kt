package org.kori.plugin.geo.track

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.util.Log
import android.util.LruCache
import androidx.core.graphics.PathParser
import org.cwcc.open.geokori.lib.utils.MapIconUtil
import org.kori.plugin.geo.track.di.TrackMediaRecord
import org.kori.plugin.geo.track.di.TrackPoint
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.io.File

/**
 * 实时轨迹图层。
 *
 * ## 图层（自下而上）
 *
 * ```
 * vela-track-live-raw       原始 GPS 轨迹（红色实线，宽 5px）
 * vela-track-live-smooth    平滑后轨迹（蓝色虚线，宽 3px）
 * vela-track-live-media     媒体点位（SymbolLayer；AUDIO/VIDEO 为图标针，PHOTO 为照片气泡针）
 * vela-track-history        历史轨迹叠加（橙线）
 * vela-track-playback       回放标记（亮青圆点）
 * ```
 *
 * ## 媒体图标规则
 *
 * | 类型  | 外观                                                              |
 * | ----- | ----------------------------------------------------------------- |
 * | AUDIO | MAP_PIN（青绿底板）+ 白色圆盘 + 话筒图标                          |
 * | VIDEO | MAP_PIN（紫底板）+ 白色圆盘 + 录像图标                            |
 * | PHOTO | `MapIconUtil.photoPin` 照片气泡针（白边圆角缩略图 + 底部白尾）    |
 * | 其它  | 走 photoPin 占位风格（无底图时浅灰圆角）                          |
 *
 * ## 数据驱动图标大小
 *
 * [updateMedia] 的 `scales: filePath -> scale` 会写进 GeoJSON feature 的 `scale` 属性；
 * SymbolLayer 的 `iconSize` 表达式读取该属性，回放循环每帧更新 map 即可实现
 * "到点弹出"的缩放动画。
 */
object LiveTrackLayer {

    // =============================================================================================
    // 常量
    // =============================================================================================

    // ---- 原始轨迹 ----
    const val SRC_RAW = "vela-track-live-raw"
    const val LAYER_RAW = "vela-track-live-raw-layer"

    // ---- 平滑轨迹 ----
    const val SRC_SMOOTH = "vela-track-live-smooth"
    const val LAYER_SMOOTH = "vela-track-live-smooth-layer"

    // ---- 媒体点位 ----
    const val SRC_MEDIA = "vela-track-live-media"
    const val LAYER_MEDIA = "vela-track-live-media-layer"

    // ---- 历史轨迹叠加层 ----
    const val SRC_HISTORY = "vela-track-history"
    const val LAYER_HISTORY = "vela-track-history-layer"

    // ---- 轨迹回放标记 ----
    const val SRC_PLAYBACK = "vela-track-playback"
    const val LAYER_PLAYBACK = "vela-track-playback-layer"

    // ---- 颜色 ----
    private const val COLOR_RAW = "#FF5252"        // 原始：红
    private const val COLOR_SMOOTH = "#1A73E8"     // 平滑：蓝
    private const val COLOR_HISTORY = "#FF9100"    // 历史轨迹：亮橙
    private const val COLOR_PLAYBACK = "#00E5FF"   // 回放标记：亮青
    private const val COLOR_MEDIA_VIDEO = "#9334E6"    // 视频底板：紫
    private const val COLOR_MEDIA_AUDIO = "#00897B"    // 录音底板：青绿

    // ---- 媒体图标生成参数 ----
    /** 输出图标边长（像素）。太小会糊，太大费内存；128 是一个平衡点。 */
    private const val MEDIA_ICON_SIZE_PX = 128

    private const val TAG = "LiveTrackLayer"

    // Material Design 24×24 viewport 图标 pathData
    private const val PATH_MIC =
        "M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 " +
                "1.34,3 3,3zM17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11H5c0,3.41 2.72,6.23 " +
                "6,6.72V21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z"

    private const val PATH_VIDEO =
        "M17,10.5V7c0,-0.55 -0.45,-1 -1,-1H4c-0.55,0 -1,0.45 -1,1v10c0,0.55 0.45,1 1,1h12c0.55,0 " +
                "1,-0.45 1,-1v-3.5l4,4v-11l-4,4z"

    /** 图标位图缓存（key = iconId），跨地图实例共享，避免重复生成 / 重复读照片。 */
    private val mediaIconCache = object : LruCache<String, Bitmap>(64) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    // =============================================================================================
    // 图层创建
    // =============================================================================================

    /**
     * 创建所有实时轨迹图层（幂等）。在地图 `setStyle` 回调里调用。
     */
    fun ensureLayers(style: Style) {
        ensureRawTrackLayer(style)
        ensureSmoothTrackLayer(style)
        ensureHistoryLayer(style)
        ensurePlaybackLayer(style)
        // ★ 媒体图标永远创建在最上层，不被轨迹线/回放点盖住
        ensureMediaLayer(style)
    }

    // ---- 回放标记层 ----
    private fun ensurePlaybackLayer(style: Style) {
        if (style.getSource(SRC_PLAYBACK) == null) {
            style.addSource(GeoJsonSource(SRC_PLAYBACK))
        }
        if (style.getLayer(LAYER_PLAYBACK) == null) {
            style.addLayer(
                CircleLayer(LAYER_PLAYBACK, SRC_PLAYBACK).withProperties(
                    PropertyFactory.circleRadius(9f),
                    PropertyFactory.circleColor(COLOR_PLAYBACK),
                    PropertyFactory.circleStrokeWidth(2f),
                    PropertyFactory.circleStrokeColor("#FFFFFF"),
                ),
            )
        }
    }

    /** 更新回放标记位置；[point] 为 null 时隐藏。 */
    fun updatePlayback(style: Style, point: TrackPoint?) {
        val src = style.getSourceAs<GeoJsonSource>(SRC_PLAYBACK) ?: return
        src.setGeoJson(
            if (point == null) {
                FeatureCollection.fromFeatures(emptyList<Feature>())
            } else {
                FeatureCollection.fromFeature(
                    Feature.fromGeometry(Point.fromLngLat(point.lng, point.lat)),
                )
            },
        )
    }

    // ---- 历史轨迹层 ----
    private fun ensureHistoryLayer(style: Style) {
        if (style.getSource(SRC_HISTORY) == null) {
            style.addSource(GeoJsonSource(SRC_HISTORY))
        }
        if (style.getLayer(LAYER_HISTORY) == null) {
            style.addLayer(
                LineLayer(LAYER_HISTORY, SRC_HISTORY).withProperties(
                    PropertyFactory.lineColor(COLOR_HISTORY),
                    PropertyFactory.lineWidth(5f),
                    PropertyFactory.lineCap("round"),
                    PropertyFactory.lineJoin("round"),
                    PropertyFactory.lineOpacity(0.9f),
                ),
            )
        }
    }

    // ---- 原始轨迹层 ----
    private fun ensureRawTrackLayer(style: Style) {
        if (style.getSource(SRC_RAW) == null) {
            style.addSource(GeoJsonSource(SRC_RAW))
        }
        if (style.getLayer(LAYER_RAW) == null) {
            style.addLayer(
                LineLayer(LAYER_RAW, SRC_RAW).withProperties(
                    PropertyFactory.lineColor(COLOR_RAW),
                    PropertyFactory.lineWidth(5f),
                    PropertyFactory.lineCap("round"),
                    PropertyFactory.lineJoin("round"),
                    PropertyFactory.lineOpacity(0.85f),
                ),
            )
        }
    }

    // ---- 平滑轨迹层 ----
    private fun ensureSmoothTrackLayer(style: Style) {
        if (style.getSource(SRC_SMOOTH) == null) {
            style.addSource(GeoJsonSource(SRC_SMOOTH))
        }
        if (style.getLayer(LAYER_SMOOTH) == null) {
            style.addLayer(
                LineLayer(LAYER_SMOOTH, SRC_SMOOTH).withProperties(
                    PropertyFactory.lineColor(COLOR_SMOOTH),
                    PropertyFactory.lineWidth(3f),
                    PropertyFactory.lineCap("round"),
                    PropertyFactory.lineJoin("round"),
                    PropertyFactory.lineDasharray(arrayOf(2f, 2f)),
                    PropertyFactory.lineOpacity(0.95f),
                ),
            )
        }
    }

    // ---- 媒体层（SymbolLayer） ----
    private fun ensureMediaLayer(style: Style) {
        if (style.getSource(SRC_MEDIA) == null) {
            style.addSource(GeoJsonSource(SRC_MEDIA))
        }
        // 兼容旧版本：若遗留的是 CircleLayer，先移除
        style.getLayer(LAYER_MEDIA)?.let { existing ->
            if (existing !is SymbolLayer) style.removeLayer(existing)
        }
        if (style.getLayer(LAYER_MEDIA) == null) {
            style.addLayer(
                SymbolLayer(LAYER_MEDIA, SRC_MEDIA).withProperties(
                    // iconId 由 feature 携带 → 不同媒体点位显示各自图标
                    PropertyFactory.iconImage(Expression.get("iconId")),
                    // ★ scale 由 feature 提供；未设置时回退 1
                    PropertyFactory.iconSize(
                        Expression.coalesce(
                            Expression.get("scale"),
                            Expression.literal(1f),
                        ),
                    ),
                    // 底部锚点：MAP_PIN 针尖 / photoPin 尾巴末端贴近坐标
                    PropertyFactory.iconAnchor("bottom"),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                    PropertyFactory.iconPitchAlignment("map"),
                ),
            )
        }
    }

    // =============================================================================================
    // 图层移除
    // =============================================================================================

    /** 彻底移除所有图层和源（用于地图销毁或完全切换样式）。 */
    fun removeLayers(style: Style) {
        runCatching { style.removeLayer(LAYER_PLAYBACK) }
        runCatching { style.removeLayer(LAYER_HISTORY) }
        runCatching { style.removeLayer(LAYER_MEDIA) }
        runCatching { style.removeLayer(LAYER_SMOOTH) }
        runCatching { style.removeLayer(LAYER_RAW) }
        runCatching { style.removeSource(SRC_PLAYBACK) }
        runCatching { style.removeSource(SRC_HISTORY) }
        runCatching { style.removeSource(SRC_MEDIA) }
        runCatching { style.removeSource(SRC_SMOOTH) }
        runCatching { style.removeSource(SRC_RAW) }
    }

    // =============================================================================================
    // 数据更新
    // =============================================================================================

    /** 清空轨迹（记录停止时）。图层保留，只清数据。 */
    fun clearTrack(style: Style) {
        style.getSourceAs<GeoJsonSource>(SRC_RAW)?.setGeoJson(
            FeatureCollection.fromFeatures(emptyList<Feature>()),
        )
        style.getSourceAs<GeoJsonSource>(SRC_SMOOTH)?.setGeoJson(
            FeatureCollection.fromFeatures(emptyList<Feature>()),
        )
    }

    fun updateHistory(style: Style, segments: List<List<TrackPoint>>) {
        val src = style.getSourceAs<GeoJsonSource>(SRC_HISTORY) ?: return
        val features = segments
            .filter { it.size >= 2 }
            .map { pts ->
                Feature.fromGeometry(
                    LineString.fromLngLats(
                        pts.map { Point.fromLngLat(it.lng, it.lat) },
                    ),
                )
            }
        src.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    fun clearHistory(style: Style) {
        style.getSourceAs<GeoJsonSource>(SRC_HISTORY)?.setGeoJson(
            FeatureCollection.fromFeatures(emptyList<Feature>()),
        )
    }

    fun clearMedia(style: Style) {
        style.getSourceAs<GeoJsonSource>(SRC_MEDIA)?.setGeoJson(
            FeatureCollection.fromFeatures(emptyList<Feature>()),
        )
    }

    fun clearAll(style: Style) {
        clearTrack(style)
        clearMedia(style)
        clearHistory(style)
        updatePlayback(style, null)
    }

    fun updateTrack(
        style: Style,
        rawPoints: List<TrackPoint>,
        smoothPoints: List<TrackPoint> = emptyList(),
    ) {
        val rawSrc = style.getSourceAs<GeoJsonSource>(SRC_RAW)
        rawSrc?.setGeoJson(
            if (rawPoints.size < 2) {
                FeatureCollection.fromFeatures(emptyList<Feature>())
            } else {
                FeatureCollection.fromFeature(
                    Feature.fromGeometry(
                        LineString.fromLngLats(
                            rawPoints.map { Point.fromLngLat(it.lng, it.lat) },
                        ),
                    ),
                )
            },
        )

        val smoothSrc = style.getSourceAs<GeoJsonSource>(SRC_SMOOTH)
        smoothSrc?.setGeoJson(
            if (smoothPoints.size < 2) {
                FeatureCollection.fromFeatures(emptyList<Feature>())
            } else {
                FeatureCollection.fromFeature(
                    Feature.fromGeometry(
                        LineString.fromLngLats(
                            smoothPoints.map { Point.fromLngLat(it.lng, it.lat) },
                        ),
                    ),
                )
            },
        )
    }

    /**
     * 更新媒体点位。
     *
     * @param context 用于 [MapIconUtil] 生成图标与读取照片文件
     * @param scales  `filePath -> scale`（0 隐藏，1 完全显示，中间值动画）。
     *                空 map = 全部按 scale=1 显示；非空时缺失项按 0 处理。
     */
    fun updateMedia(
        style: Style,
        context: Context,
        media: List<TrackMediaRecord>,
        scales: Map<String, Float> = emptyMap(),
    ) {
        val src = style.getSourceAs<GeoJsonSource>(SRC_MEDIA)
        if (src == null) {
            // ★ 静默失败防护：图层未建好时打日志，便于排查
            Log.w(TAG, "updateMedia: 媒体源 $SRC_MEDIA 不存在（ensureLayers 未调用？）")
            return
        }
        Log.i(TAG, "updateMedia: media=${media.size}, scales=${scales.size}")

        val features = media.map { m ->
            val iconId = mediaIconId(m)
            if (style.getImage(iconId) == null) {
                val bmp = mediaIconCache[iconId] ?: generateMediaBitmap(context, m).also {
                    mediaIconCache.put(iconId, it)
                }
                style.addImage(iconId, bmp)
            }
            val scale = if (scales.isEmpty()) 1f else (scales[m.filePath] ?: 0f)
            Feature.fromGeometry(Point.fromLngLat(m.lng, m.lat)).apply {
                addStringProperty("iconId", iconId)
                addNumberProperty("scale", scale)
                addStringProperty("type", m.type.name)
                addStringProperty("filePath", m.filePath)
                addNumberProperty("timestampMs", m.timestampMs)
                m.durationSec?.let { addNumberProperty("durationSec", it) }
                m.accuracyM?.let { addNumberProperty("accuracyM", it.toDouble()) }
            }
        }
        src.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    // =============================================================================================
    // 媒体图标生成
    // =============================================================================================

    /**
     * 每个媒体对应一个稳定的 iconId。key 必须包含类型和版本号——
     * 图标样式参数（尺寸/颜色/底盘）变化时 bump 版本，否则 LruCache
     * 会一直返回旧样式的位图（静默不生效）。
     */
    private fun mediaIconId(m: TrackMediaRecord): String =
        "vela-media-v2-" + m.type.name + "-" + m.filePath.hashCode().toUInt().toString(16)

    private fun generateMediaBitmap(context: Context, m: TrackMediaRecord): Bitmap =
        when (m.type.name.uppercase()) {
            "AUDIO" -> MapIconUtil(context)
                .base(MapIconUtil.BaseType.MAP_PIN)
                .baseColor(Color.parseColor(COLOR_MEDIA_AUDIO))
                .innerDisc(Color.WHITE, diameterScale = 2.56f)   // 白色圆盘放大
                .inner(buildVectorIcon(PATH_MIC), tint = Color.RED, scale = 1.13f)
                .generateBitmap(MEDIA_ICON_SIZE_PX)

            "VIDEO" -> MapIconUtil(context)
                .base(MapIconUtil.BaseType.MAP_PIN)
                .baseColor(Color.parseColor(COLOR_MEDIA_VIDEO))
                .innerDisc(Color.WHITE, diameterScale = 2.56f)   // 白色圆盘放大
                .inner(buildVectorIcon(PATH_VIDEO), tint = Color.RED, scale = 1.13f)
                .generateBitmap(MEDIA_ICON_SIZE_PX)

            else -> {
                // PHOTO 及未知类型 → 照片气泡针
                val thumb = loadThumbnail(m.filePath, MEDIA_ICON_SIZE_PX)
                MapIconUtil.photoPin(
                    photo = thumb,
                    sizePx = MEDIA_ICON_SIZE_PX,
                    frameColor = Color.WHITE,
                    placeholderColor = 0xFFE0E0E0.toInt(),
                )
            }
        }

    /**
     * 用 24×24 viewport 的 pathData 现场构造一个轻量 [Drawable]，
     * 交给 [MapIconUtil.inner] 后会自动按 bounds 缩放平移到中心圆盘。
     */
    private fun buildVectorIcon(pathData: String, viewport: Float = 24f): Drawable =
        object : Drawable() {
            private val path = PathParser.createPathFromPathData(pathData)
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE // 会被 MapIconUtil 的 tint 覆盖
                style = Paint.Style.FILL
            }

            override fun draw(canvas: Canvas) {
                val b = bounds
                if (b.isEmpty) return
                val save = canvas.save()
                canvas.translate(b.left.toFloat(), b.top.toFloat())
                canvas.scale(b.width() / viewport, b.height() / viewport)
                canvas.drawPath(path, paint)
                canvas.restoreToCount(save)
            }

            override fun setAlpha(alpha: Int) { paint.alpha = alpha }
            override fun setColorFilter(colorFilter: ColorFilter?) {
                paint.colorFilter = colorFilter
            }

            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }

    /**
     * 读取本地缩略图并按需采样（避免 OOM）。文件不存在 / 不可读返回 null（走占位样式）。
     */
    private fun loadThumbnail(filePath: String, targetPx: Int): Bitmap? = runCatching {
        val f = File(filePath)
        if (!f.exists() || !f.canRead()) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(filePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        val minSide = minOf(bounds.outWidth, bounds.outHeight)
        while (minSide / (sample * 2) >= targetPx) sample *= 2

        BitmapFactory.decodeFile(
            filePath,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        )
    }.getOrNull()
}