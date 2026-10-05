package org.kori.plugin.wildlife.layers

import android.graphics.Color
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.interpolate
import org.maplibre.android.style.expressions.Expression.linear
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.rgb
import org.maplibre.android.style.expressions.Expression.rgba
import org.maplibre.android.style.expressions.Expression.step
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.expressions.Expression.zoom
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
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

open class BaseLibreLayer {
    /**
     * 创建圆点图层
     * 修复：移除嵌套的 zoom-based interpolate，避免 JNI 表达式错误
     */
    protected fun circleLayer(lyrId: String, sourceId: String, labelField: String = ""): CircleLayer {
        val circleLayer = CircleLayer(lyrId, sourceId)
        circleLayer.setProperties(
            circleRadius(
                interpolate(
                    linear(),
                    zoom(),
                    stop(5, 1f),
                    stop(14, 50f)
                )
            ),
            circleColor(
                interpolate(
                    linear(), zoom(),
                    literal(1), rgba(33, 102, 172, 0),
                    literal(2), rgb(103, 169, 207),
                    literal(4), rgb(209, 229, 240),
                    literal(6), rgb(253, 219, 199),
                    literal(8), rgb(239, 138, 98),
                    literal(14), rgb(178, 24, 43)
                )
            ),
            circleOpacity(
                interpolate(
                    linear(),
                    zoom(),
                    stop(5, 0f),
                    stop(8, 1f)
                )
            ),
            circleStrokeColor("white"),
            circleStrokeWidth(1.5f)
        )
        return circleLayer
    }

    protected fun symbolLayer(
        lyrId: String, sourceId: String,
        icoField: String = "", labelField: String = ""
    ): SymbolLayer {
        val symbolLayer = SymbolLayer(lyrId, sourceId)
        symbolLayer.setProperties(
            iconAnchor(ICON_ANCHOR_BOTTOM),
            iconAllowOverlap(false),
            textSize(14f),
            textAllowOverlap(true),
            textColor(Color.parseColor("#000000")),
            textHaloBlur(.5f),
            textHaloColor(Color.parseColor("#FFFFFF")),
            textHaloWidth(2f),
            symbolZOrder(Property.SYMBOL_Z_ORDER_AUTO),
            textFont(arrayOf<String>("Open Sans Regular"))
        )
        if (icoField.isNotEmpty())
            symbolLayer.setProperties(iconImage("{$icoField}"))
        if (labelField.isNotEmpty()) {
            symbolLayer.setProperties(
                textField(
                    step(
                        zoom(),
                        get(""),
                        stop(6, get("$labelField"))
                    )
                ),
                textOffset(arrayOf(0.0f, 1.0f))
            )
        }
        return symbolLayer
    }

    /**
     * 新建一个多边形填充图层
     */
    protected fun fillLayer(lyrId: String, sourceId: String, labelField: String = ""): FillLayer {
        val layer = FillLayer(lyrId, sourceId).withProperties(
            fillColor(Color.TRANSPARENT),
            fillOutlineColor(Color.MAGENTA),
            lineWidth(2.0f),
            fillAntialias(true),
            textFont(arrayOf<String>("Open Sans Regular")),
            textColor(Color.parseColor("#000000")),
            textHaloBlur(.5f),
            textHaloWidth(2f)
        )
        if (labelField.isNotEmpty())
            layer.setProperties(textField("{$labelField}"))
        return layer
    }

    protected fun lineLayer(lyrId: String, sourceId: String, labelField: String = ""): LineLayer {
        val layer = LineLayer(lyrId, sourceId).withProperties(
            lineColor(Color.MAGENTA),
            lineWidth(3.0f)
        )
        if (labelField.isNotEmpty()) {
            layer.setProperties(textField("{$labelField}"))
            layer.setProperties(textFont(arrayOf<String>("Open Sans Regular")))
            layer.setProperties(textColor(Color.parseColor("#000000")))
            layer.setProperties(textHaloBlur(.5f))
            layer.setProperties(textHaloWidth(2f))
        }
        return layer
    }

}