package org.kori.plugin.geo.track

import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * 实时轨迹图层。
 *
 * ## 三条图层（自下而上）
 *
 * ```
 * vela-track-live-raw       原始 GPS 轨迹（红色实线，宽 5px）
 * vela-track-live-smooth    平滑后轨迹（蓝色虚线，宽 3px）
 * vela-track-live-media     媒体点位（按类型着色：照片红 / 视频紫 / 录音青）
 * ```
 *
 * ## 生命周期
 *
 *  · [ensureLayers] —— 在地图 `setStyle` 回调里调用一次。幂等。
 *  · [updateTrack] —— 每次实时点变化时调用（由 `MapLibreMapView` 内部 effect 触发）
 *  · [updateMedia] —— 媒体列表变化时调用
 *  · [clearTrack] —— 记录停止时清空轨迹（保留图层，只清数据）
 *  · [removeLayers] —— 彻底卸载图层（用于地图销毁）
 *
 * ## 与其他模块的关系
 *
 * ```
 * SegmentedTrackRecorder
 *     └─ liveTrack: SharedFlow<Pair<List<TrackPoint>, List<TrackPoint>>>
 *         └─ TrackRecordingEngine.state.liveTrackPoints / liveSmoothPoints
 *             └─ MapLibreMapView(liveTrackPoints, liveSmoothPoints, liveTrackMedia)
 *                 └─ LiveTrackLayer.updateTrack / updateMedia
 * ```
 *
 * ## 数据来源
 *
 * `TrackPoint` 已由 `SegmentedTrackRecorder` 限制到 `LIVE_BUFFER_MAX = 2000` 个点，
 * 所以不需要在这里再做截断。
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
    private const val COLOR_HISTORY = "#FF9100"        // 历史轨迹：亮橙（醒目）
    private const val COLOR_PLAYBACK = "#00E5FF"       // 回放标记：亮青
    private const val COLOR_MEDIA_DEFAULT = "#5F6368"  // 媒体默认：灰
    private const val COLOR_MEDIA_PHOTO = "#FF5252"    // 照片：红
    private const val COLOR_MEDIA_VIDEO = "#9334E6"    // 视频：紫
    private const val COLOR_MEDIA_AUDIO = "#00897B"    // 录音：青

    // =============================================================================================
    // 图层创建
    // =============================================================================================

    /**
     * 创建所有实时轨迹图层（幂等）。
     *
     * 在地图 `setStyle` 回调里调用。若图层已存在则跳过。
     */
    fun ensureLayers(style: Style) {
        ensureRawTrackLayer(style)
        ensureSmoothTrackLayer(style)
        ensureMediaLayer(style)
        ensureHistoryLayer(style)
        ensurePlaybackLayer(style)
    }

    // ---- 回放标记层（亮青圆点 + 白色描边，置顶）----
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

    // ---- 历史轨迹层（多段灰线）----
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

    // ---- 媒体层 ----
    private fun ensureMediaLayer(style: Style) {
        if (style.getSource(SRC_MEDIA) == null) {
            style.addSource(GeoJsonSource(SRC_MEDIA))
        }
        if (style.getLayer(LAYER_MEDIA) == null) {
            style.addLayer(
                CircleLayer(LAYER_MEDIA, SRC_MEDIA).withProperties(
                    PropertyFactory.circleRadius(8f),
                    PropertyFactory.circleStrokeWidth(2f),
                    PropertyFactory.circleStrokeColor("#FFFFFF"),
                    // 按媒体类型着色
                    PropertyFactory.circleColor(
                        Expression.match(
                            Expression.get("type"),
                            Expression.literal(COLOR_MEDIA_DEFAULT),
                            Expression.stop("PHOTO", COLOR_MEDIA_PHOTO),
                            Expression.stop("VIDEO", COLOR_MEDIA_VIDEO),
                            Expression.stop("AUDIO", COLOR_MEDIA_AUDIO),
                        ),
                    ),
                ),
            )
        }
    }

    // =============================================================================================
    // 图层移除
    // =============================================================================================

    /**
     * 彻底移除所有实时轨迹图层和源。
     *
     * 用于地图销毁或完全切换样式时。通常不需要手动调用——
     * 地图销毁时 MapView 会自动清理。
     */
    fun removeLayers(style: Style) {
        runCatching { style.removeLayer(LAYER_PLAYBACK) }
        runCatching { style.removeLayer(LAYER_MEDIA) }
        runCatching { style.removeLayer(LAYER_SMOOTH) }
        runCatching { style.removeLayer(LAYER_RAW) }
        runCatching { style.removeSource(SRC_PLAYBACK) }
        runCatching { style.removeSource(SRC_MEDIA) }
        runCatching { style.removeSource(SRC_SMOOTH) }
        runCatching { style.removeSource(SRC_RAW) }
    }

    // =============================================================================================
    // 数据更新
    // =============================================================================================

    /**
     * 清空轨迹（记录停止时调用）。
     *
     * 图层保留，只清数据。这样下次开始记录时不需要重新创建图层。
     */
    fun clearTrack(style: Style) {
        style.getSourceAs<GeoJsonSource>(SRC_RAW)?.setGeoJson(
            FeatureCollection.fromFeatures(emptyList<Feature>()),
        )
        style.getSourceAs<GeoJsonSource>(SRC_SMOOTH)?.setGeoJson(
            FeatureCollection.fromFeatures(emptyList<Feature>()),
        )
    }

    /**
     * 更新历史轨迹叠加层（历史浏览时把已保存轨迹画在地图上）。
     *
     * @param segments 多个轨迹段（如：每个会话一条），每段 ≥2 点才绘制
     */
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

    /** 清空历史轨迹叠加层。 */
    fun clearHistory(style: Style) {
        style.getSourceAs<GeoJsonSource>(SRC_HISTORY)?.setGeoJson(
            FeatureCollection.fromFeatures(emptyList<Feature>()),
        )
    }

    /** 清空媒体点位。 */
    fun clearMedia(style: Style) {
        style.getSourceAs<GeoJsonSource>(SRC_MEDIA)?.setGeoJson(
            FeatureCollection.fromFeatures(emptyList<Feature>()),
        )
    }

    /** 清空所有（轨迹 + 媒体）。 */
    fun clearAll(style: Style) {
        clearTrack(style)
        clearMedia(style)
        clearHistory(style)
        updatePlayback(style, null)
    }

    /**
     * 更新轨迹。
     *
     * @param rawPoints   原始轨迹点（红色实线）
     * @param smoothPoints 平滑轨迹点（蓝色虚线），可为空
     */
    fun updateTrack(
        style: Style,
        rawPoints: List<TrackPoint>,
        smoothPoints: List<TrackPoint> = emptyList(),
    ) {
        // ---- 原始轨迹 ----
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

        // ---- 平滑轨迹 ----
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
     * 每个媒体是一个带 `type` 属性的 Point 特征，图层按 `type` 着色。
     */
    fun updateMedia(style: Style, media: List<TrackMediaRecord>) {
        val src = style.getSourceAs<GeoJsonSource>(SRC_MEDIA) ?: return
        src.setGeoJson(
            FeatureCollection.fromFeatures(
                media.map { m ->
                    Feature.fromGeometry(
                        Point.fromLngLat(m.lng, m.lat),
                    ).apply {
                        addStringProperty("type", m.type.name)
                        addStringProperty("filePath", m.filePath)
                        addNumberProperty("timestampMs", m.timestampMs)
                        m.durationSec?.let { addNumberProperty("durationSec", it) }
                        m.accuracyM?.let { addNumberProperty("accuracyM", it.toDouble()) }
                    }
                },
            ),
        )
    }
}