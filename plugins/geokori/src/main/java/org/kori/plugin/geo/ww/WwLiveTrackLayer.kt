package org.kori.plugin.geo.ww

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Position
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.render.Color
import earth.worldwind.render.image.ImageSource
import earth.worldwind.shape.Path
import earth.worldwind.shape.PathType
import earth.worldwind.shape.Placemark
import earth.worldwind.shape.PlacemarkAttributes
import earth.worldwind.shape.ShapeAttributes
import org.kori.plugin.geo.track.di.TrackMediaRecord
import org.kori.plugin.geo.track.di.TrackPoint

/**
 * WorldWind 版实时轨迹图层（API 已对 worldwind-android 2.1.1 源码逐一核对）。
 *
 *  · Path 是 Renderable，更新数据 = 重新赋值 positions（var positions 已确认）
 *  · 虚线：ImageSource.fromLineStipple(1, 0x00FF)（无需 dash.png 贴图）
 *  · 颜色：earth.worldwind.render.Color(packedInt)（ARGB 打包 int 直接可用）
 *  · 贴地：earth.worldwind.geom.AltitudeMode.CLAMP_TO_GROUND
 *  · 图层清理：RenderableLayer.clearRenderables() / removeRenderable()（均已确认）
 *
 * ## 生命周期（API 与 MapLibre 版一一对应）
 *
 *  [ensureLayers] → 图层挂载后调用一次（幂等）
 *  [updateTrack] / [updateMedia] / [updateHistory] / [updatePlayback]
 *  [clearTrack] / [clearHistory] / [clearMedia] / [clearAll]
 */
object WwLiveTrackLayer {

    const val LAYER = "ww-track-layer"

    // ---- 颜色（打包 ARGB Int，使用时包成 earth.worldwind.render.Color）----
    private const val COLOR_RAW = 0xFFDB4437.toInt()      // 原始：红
    private const val COLOR_SMOOTH = 0xFF1A73E8.toInt()   // 平滑：蓝
    private const val COLOR_HISTORY = 0xFFFF9100.toInt()  // 历史：橙
    private const val COLOR_PLAYBACK = 0xFF00E5FF.toInt() // 回放：亮青
    private const val COLOR_MEDIA_DEFAULT = 0xFF5F6368.toInt()
    private const val COLOR_MEDIA_PHOTO = 0xFFDB4437.toInt()
    private const val COLOR_MEDIA_VIDEO = 0xFF9334E6.toInt()
    private const val COLOR_MEDIA_AUDIO = 0xFF00897B.toInt()

    private var rawPath: Path? = null
    private var smoothPath: Path? = null
    private var historyPaths: MutableList<Path> = mutableListOf()
    private var mediaMarks: MutableList<Placemark> = mutableListOf()
    private var playbackMark: Placemark? = null

    private var renderableLayer: RenderableLayer? = null

    // =============================================================================================
    // 图层创建 / 移除
    // =============================================================================================

    /** 创建轨迹 RenderableLayer（幂等）。 */
    fun ensureLayers(): RenderableLayer {
        renderableLayer?.let { return it }
        val layer = RenderableLayer(LAYER)
        renderableLayer = layer
        return layer
    }

    /** 彻底卸载（clearRenderables 清空，引用全部置空）。 */
    fun removeLayers() {
        renderableLayer?.clearRenderables()
        rawPath = null
        smoothPath = null
        historyPaths.clear()
        mediaMarks.clear()
        playbackMark = null
        renderableLayer = null
    }

    // =============================================================================================
    // 轨迹更新
    // =============================================================================================

    /** 更新实时轨迹（原始 + 平滑），点数 <2 时画空线。 */
    fun updateTrack(
        rawPoints: List<TrackPoint>,
        smoothPoints: List<TrackPoint> = emptyList(),
    ) {
        val layer = renderableLayer ?: return

        // ---- 原始轨迹 ----
        if (rawPath == null && rawPoints.size >= 2) {
            rawPath = createPath(rawPoints, COLOR_RAW, 8f)
            layer.addRenderable(rawPath!!)
        } else {
            rawPath?.positions = rawPoints.toPositions().takeIf { it.size >= 2 } ?: emptyList()
        }

        // ---- 平滑轨迹 ----
        if (smoothPath == null && smoothPoints.size >= 2) {
            smoothPath = createPath(smoothPoints, COLOR_SMOOTH, 5f, dashed = true)
            layer.addRenderable(smoothPath!!)
        } else {
            smoothPath?.positions = smoothPoints.toPositions().takeIf { it.size >= 2 } ?: emptyList()
        }
    }

    /** 清空实时轨迹（Renderable 保留只清数据）。 */
    fun clearTrack() {
        rawPath?.positions = emptyList()
        smoothPath?.positions = emptyList()
    }

    // =============================================================================================
    // 历史轨迹
    // =============================================================================================

    /** 更新历史轨迹叠加层（每段 ≥2 点才绘制）。 */
    fun updateHistory(segments: List<List<TrackPoint>>) {
        val layer = renderableLayer ?: return
        historyPaths.forEach(layer::removeRenderable)
        historyPaths.clear()
        segments.filter { it.size >= 2 }.forEach { pts ->
            val path = createPath(pts, COLOR_HISTORY, 6f)
            layer.addRenderable(path)
            historyPaths.add(path)
        }
    }

    fun clearHistory() {
        val layer = renderableLayer ?: return
        historyPaths.forEach(layer::removeRenderable)
        historyPaths.clear()
    }

    // =============================================================================================
    // 媒体点位
    // =============================================================================================

    /** 更新媒体点位（按类型着色）。 */
    fun updateMedia(media: List<TrackMediaRecord>) {
        val layer = renderableLayer ?: return
        mediaMarks.forEach(layer::removeRenderable)
        mediaMarks.clear()
        media.forEach { m ->
            val mark = Placemark(
                Position.fromDegrees(m.lat, m.lng, 0.0),
                PlacemarkAttributes().apply {
                    imageSource = circleImageSource(colorFor(m.type))
                },
            ).apply { displayName = m.type.name }
            layer.addRenderable(mark)
            mediaMarks.add(mark)
        }
    }

    fun clearMedia() {
        val layer = renderableLayer ?: return
        mediaMarks.forEach(layer::removeRenderable)
        mediaMarks.clear()
    }

    // =============================================================================================
    // 回放标记
    // =============================================================================================

    /** 更新回放标记；[point] 为 null 时隐藏。 */
    fun updatePlayback(point: TrackPoint?) {
        val layer = renderableLayer ?: return
        if (point == null) {
            playbackMark?.isEnabled = false
            return
        }
        if (playbackMark == null) {
            playbackMark = Placemark(
                Position.fromDegrees(point.lat, point.lng, 0.0),
                PlacemarkAttributes().apply {
                    imageSource = circleImageSource(
                        COLOR_PLAYBACK, strokeColor = 0xFFFFFFFF.toInt(),
                    )
                },
            )
            layer.addRenderable(playbackMark!!)
        }
        playbackMark?.let {
            it.position = Position.fromDegrees(point.lat, point.lng, 0.0)
            it.isEnabled = true
        }
    }

    /** 清空所有（轨迹 + 媒体 + 历史 + 回放标记）。 */
    fun clearAll() {
        clearTrack()
        clearMedia()
        clearHistory()
        updatePlayback(null)
    }

    // =============================================================================================
    // 内部工具
    // =============================================================================================

    private fun createPath(
        points: List<TrackPoint>,
        colorInt: Int,
        width: Float,
        dashed: Boolean = false,
    ): Path {
        val attrs = ShapeAttributes().apply {
            isDrawOutline = true
            outlineColor = Color(colorInt)
            outlineWidth = width
            if (dashed) {
                // WW 内置线型图案：factor=线宽倍数，pattern=16 位点阵（0x00FF ≈ 短划）
                outlineImageSource = ImageSource.fromLineStipple(1, 0x00FF)
            }
        }
        return Path(points.toPositions(), attrs).apply {
            pathType = PathType.GREAT_CIRCLE
            altitudeMode = AltitudeMode.CLAMP_TO_GROUND
        }
    }

    private fun List<TrackPoint>.toPositions(): List<Position> =
        map { Position.fromDegrees(it.lat, it.lng, 0.0) }

    private fun colorFor(type: TrackMediaRecord.Type): Int = when (type) {
        TrackMediaRecord.Type.PHOTO -> COLOR_MEDIA_PHOTO
        TrackMediaRecord.Type.VIDEO -> COLOR_MEDIA_VIDEO
        TrackMediaRecord.Type.AUDIO -> COLOR_MEDIA_AUDIO
        else -> COLOR_MEDIA_DEFAULT
    }

    /** 程序化生成圆点 Bitmap（androidMain ImageSource.fromBitmap 已确认存在）。 */
    private fun circleImageSource(color: Int, strokeColor: Int? = null): ImageSource {
        val size = 40
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        if (strokeColor != null) {
            p.color = strokeColor
            c.drawCircle(size / 2f, size / 2f, size / 2f - 1f, p)
        }
        p.color = color
        val inset = if (strokeColor != null) 6f else 2f
        c.drawCircle(size / 2f, size / 2f, size / 2f - inset, p)
        return ImageSource.fromBitmap(bmp)
    }
}