package org.kori.plugin.geo.ww

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import earth.worldwind.geom.Position
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.render.Color
import earth.worldwind.render.image.ImageSource
import earth.worldwind.shape.Ellipse
import earth.worldwind.shape.Placemark
import earth.worldwind.shape.PlacemarkAttributes
import earth.worldwind.shape.ShapeAttributes
import org.kori.plugin.geo.location.LocationTracker

/**
 * WorldWind 版定位蓝点（API 已对 worldwind-android 2.1.1 源码核对）。
 *
 *  · 蓝点：Placemark + Bitmap（ImageSource.fromBitmap，androidMain 已确认）
 *  · 精度圈：Ellipse —— 本版本 WW 无 SurfaceCircle，等轴 Ellipse（major==minor）即圆，
 *    半径单位米，var center / majorRadius / minorRadius 均可写
 */
object WwLocationIndicator {

    const val LAYER = "ww-user-location"

    private const val COLOR_DOT = 0xFF1A73E8.toInt()        // 蓝点填充
    private const val COLOR_DOT_STROKE = 0xFFFFFFFF.toInt() // 白描边
    private const val COLOR_ACCURACY = 0x331A73E8.toInt()   // 精度圈（20% 透明度蓝）

    private var layer: RenderableLayer? = null
    private var dot: Placemark? = null
    private var accuracyCircle: Ellipse? = null

    /** 创建/复用定位图层（幂等）。 */
    fun ensureLayers(): RenderableLayer {
        layer?.let { return it }
        val l = RenderableLayer(LAYER)

        // ---- 精度圈（垫在蓝点下面）----
        accuracyCircle = Ellipse(
            Position.fromDegrees(0.0, 0.0, 0.0),
            0.0, 0.0,
            ShapeAttributes().apply {
                isDrawInterior = true
                interiorColor = Color(COLOR_ACCURACY)
                isDrawOutline = false
            },
        ).apply { isEnabled = false }   // 等第一个带精度的 fix 再显示
        l.addRenderable(accuracyCircle!!)

        // ---- 蓝点 ----
        dot = Placemark(
            Position.fromDegrees(0.0, 0.0, 0.0),
            PlacemarkAttributes().apply { imageSource = dotImageSource() },
        ).apply { isEnabled = false }
        l.addRenderable(dot!!)

        layer = l
        return l
    }

    /** 更新定位显示（由 TrackGlobeView 收集 fix 后调用，主线程）。 */
    fun updateFix(fix: LocationTracker.Fix) {
        val pos = Position.fromDegrees(fix.lat, fix.lng, 0.0)

        dot?.let {
            it.position = pos
            it.isEnabled = true
        }

        val acc = fix.accuracyM
        accuracyCircle?.let { circle ->
            if (acc != null && acc > 0f) {
                circle.center = pos
                circle.majorRadius = acc.toDouble()
                circle.minorRadius = acc.toDouble()
                circle.isEnabled = true
            } else {
                circle.isEnabled = false
            }
        }
    }

    /** 开关定位显示。 */
    fun setEnabled(enabled: Boolean) {
        layer?.isEnabled = enabled
        if (!enabled) {
            dot?.isEnabled = false
            accuracyCircle?.isEnabled = false
        }
    }

    /** 彻底卸载（地图销毁时）。 */
    fun removeLayers() {
        layer?.clearRenderables()
        layer = null
        dot = null
        accuracyCircle = null
    }

    /** 蓝点 Bitmap：白描边 + 蓝填充。 */
    private fun dotImageSource(): ImageSource {
        val size = 56
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = COLOR_DOT_STROKE
        c.drawCircle(size / 2f, size / 2f, size / 2f - 1f, p)
        p.color = COLOR_DOT
        c.drawCircle(size / 2f, size / 2f, size / 2f - 7f, p)
        return ImageSource.fromBitmap(bmp)
    }
}