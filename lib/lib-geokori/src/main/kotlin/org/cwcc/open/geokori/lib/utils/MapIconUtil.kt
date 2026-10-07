package org.cwcc.open.geokori.lib.utils

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.VectorDrawable
import android.util.Xml
import androidx.annotation.AnyRes
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import androidx.core.graphics.PathParser
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.FileOutputStream
import java.io.StringReader

/**
 * 地图标注图标生成器
 *
 * 以两个 SVG 底板图标（地图标注 / 标注）为基础：
 *  - 底板颜色、替换图标颜色均可设置
 *  - 中心区域可叠放任意数量的内嵌图标（资源 ID / Drawable / Bitmap 均可，
 *    多个图标自动居中叠层），支持圆形裁剪 + 描边（照片缩略图场景）
 *  - 可选"白色圆盘 + 彩色图标"双层效果
 *  - 支持直接生成 [Bitmap] / [VectorDrawable] / [Drawable] 对象，
 *    或导出 PNG 文件、VectorDrawable XML 文件（均可指定大小）
 *
 * 用法示例：
 * ```
 * // 1. 常规资源图标叠层
 * val marker = MapIconUtil(context)
 *     .base(MapIconUtil.BaseType.PIN_WITH_BASE)               // 底板类型
 *     .baseColor(0xFF1E88E5.toInt())                          // 底板颜色
 *     .innerDisc(Color.WHITE)                                 // 中心白色圆盘（可选）
 *     .inner(R.drawable.ic_gas_station, Color.RED, 0.6f)      // 叠放图标1（占圆盘60%）
 *     .inner(R.drawable.ic_bolt, Color.YELLOW, 0.35f)         // 叠放图标2（居中叠加）
 *
 * // 2. 照片缩略图圆贴（相机缩略图、头像等位图场景）
 * val photoMarker = MapIconUtil(context)
 *     .base(MapIconUtil.BaseType.MAP_PIN)
 *     .baseColor(0xFF1E88E5.toInt())
 *     .innerDisc(Color.WHITE)
 *     .innerCircle(thumbBitmap, scale = 1.0f, ringColor = Color.WHITE)
 *
 * imageView.setImageBitmap(marker.generateBitmap(96))         // Bitmap 对象
 * imageView.setImageDrawable(marker.generateVectorDrawable()) // VectorDrawable 对象
 * marker.savePng(File(dir, "marker.png"), 192)                // 保存 PNG
 * marker.saveVector(File(dir, "marker.xml"), 24)              // 保存矢量 XML
 * ```
 *
 * 注意：矢量输出要求替换图标是 VectorDrawable（res/drawable 或 res/raw 中的
 * <vector> XML 资源），只有矢量图才能合并 path；PNG/Bitmap 输出支持任意
 * Drawable / Bitmap（含圆形裁剪）。
 */
class MapIconUtil(private val context: Context) {

    /** 底板图标类型：viewBox 均为 1024 x 1024 */
    enum class BaseType(
        val pathDataList: List<String>,
        /** 中心可替换区域圆心 X（1024 坐标系） */
        val holeCx: Float,
        /** 中心可替换区域圆心 Y（1024 坐标系） */
        val holeCy: Float,
        /** 中心可替换区域直径（1024 坐标系），替换图标会完整落入该圆内 */
        val holeDiameter: Float,
    ) {
        /** 地图标注.svg —— 水滴定位针，中心圆（半径 124.37）为替换区 */
        MAP_PIN(
            pathDataList = listOf(
                // 外轮廓水滴 + 中圈 + 中心圆（原 SVG 三层同 pathData）
                "M512,34.3467c-206.08,0 -373.12,160.4267 -373.12,358.1867 " +
                        "c0,197.8667 373.12,597.0133 373.12,597.0133 " +
                        "S885.12,590.2933 885.12,392.5333 c0,-197.76 -167.04,-358.1867 -373.12,-358.1866z " +
                        "M512,631.36c-137.3867,0 -248.7467,-106.88 -248.7467,-238.8267 " +
                        "c0,-131.84 111.36,-238.8267 248.7467,-238.8266 " +
                        "c137.3867,0 248.7467,106.88 248.7467,238.8266 " +
                        "S649.3867,631.36 512,631.36z " +
                        "M512,273.1733c-68.6933,0 -124.3733,53.44 -124.3733,119.36 " +
                        "c0,65.92 55.68,119.36 124.3733,119.36 " +
                        "c68.6933,0 124.3733,-53.44 124.3733,-119.36 " +
                        "c0,-65.92 -55.68,-119.36 -124.3733,-119.36z",
            ),
            holeCx = 512f,
            holeCy = 392.5333f,
            holeDiameter = 250f,
        ),

        /** 标注.svg —— 带底座的立体地标针，中心白圈+红点（外半径 158.72）为替换区 */
        PIN_WITH_BASE(
            pathDataList = listOf(
                // 顶部高光层
                "M409.6,655.36s-189.44,20.48 -194.56,81.92c-5.12,61.44 112.64,117.76 296.96,117.76 " +
                        "c184.32,0 307.2,-46.08 302.08,-117.76c-5.12,-46.08 -199.68,-81.92 -199.68,-81.92 " +
                        "c81.92,15.36 143.36,46.08 143.36,76.8c0,46.08 -122.88,66.56 -245.76,66.56 " +
                        "s-245.76,-20.48 -245.76,-66.56c0,-30.72 66.56,-61.44 143.36,-76.8z",
                // 底座
                "M675.84,655.36c133.12,35.84 245.76,66.56 245.76,122.88 " +
                        "c0,76.8 -204.8,133.12 -404.48,133.12c-199.68,0 -409.6,-51.2 -409.6,-128 " +
                        "c0,-56.32 107.52,-87.04 235.52,-122.88c0,0 -312.32,25.6 -312.32,138.24 " +
                        "S204.8,988.16 512,988.16s486.4,-102.4 486.4,-204.8c0,-87.04 -322.56,-128 -322.56,-128z",
                // 针体
                "M500.0704,46.592c-148.48,0 -271.36,122.88 -271.36,276.48 " +
                        "c0,97.28 87.04,235.52 245.76,424.96c5.12,5.12 15.36,20.48 35.84,35.84 " +
                        "c20.48,-25.6 30.72,-35.84 35.84,-35.84 " +
                        "c158.72,-184.32 230.4,-327.68 230.4,-424.96c0,-153.6 -122.88,-276.48 -276.48,-276.48z",
                // 中心圆（原为白色圆环+红点，整体作为替换区，由替换图标覆盖）
                "M510.3104,328.192m-128,0a128,128 0 1 0 256,0a128,128 0 1 0 -256,0Z " +
                        "M510.3104,169.472c-87.04,0 -158.72,71.68 -158.72,158.72 " +
                        "s71.68,158.72 158.72,158.72c87.04,0 158.72,-71.68 158.72,-158.72 " +
                        "S597.3504,169.472 510.3104,169.472z " +
                        "M510.3104,425.472c-51.2,0 -97.28,-46.08 -97.28,-97.28 " +
                        "s46.08,-97.28 97.28,-97.28s97.28,46.08 97.28,97.28 " +
                        "s-46.08,97.28 -97.28,97.28z",
            ),
            holeCx = 510.3104f,
            holeCy = 328.192f,
            holeDiameter = 260f,
        ),
    }

    /**
     * 中心叠放的内嵌图标。
     *
     * [drawable] 与 [resId] 二选一：[resId] 用于资源引用（矢量输出也可用），
     * [drawable] 用于运行时对象（Drawable / Bitmap，仅 PNG/Bitmap 输出可用）。
     */
    class InnerIcon internal constructor(
        @AnyRes val resId: Int,
        /** 运行时图标对象（优先于 resId）；Bitmap 会被包装为 [BitmapDrawable] */
        val drawable: Drawable?,
        /** 应用到图标上的颜色，null 保留原色 */
        @ColorInt val tint: Int?,
        /** 相对中心空白区的缩放系数（0~1），多个图标各自独立 */
        val scale: Float,
        /** true：将图标圆形裁剪进中心圆（cover 填充），并绘制可选描边 */
        val circleClip: Boolean = false,
        /** 圆形裁剪后的描边颜色，null 不描边 */
        @ColorInt val ringColor: Int? = null,
        /** 描边宽度占图标边长的比例 */
        val ringRatio: Float = 0.03f,
    )

    // ============================== 可配置参数 ==============================

    private var baseType: BaseType = BaseType.MAP_PIN

    @ColorInt
    private var baseColor: Int = 0xFFFF3300.toInt()

    private val inners = mutableListOf<InnerIcon>()

    /** 中心圆盘颜色，null 表示不绘制圆盘 */
    @ColorInt
    private var discColor: Int? = null

    /** 替换图标整体缩放系数（在圆盘/空白区基础上的统一边距） */
    private var innerScale: Float = 0.88f

    // ============================== 链式配置 API ==============================

    fun base(type: BaseType) = apply { baseType = type }

    /** 设置底板图标颜色 */
    fun baseColor(@ColorInt color: Int) = apply { baseColor = color }

    /**
     * 在中心区域叠放一个资源图标，可多次调用形成多层叠放。
     * 按调用顺序依次绘制（后调用的在上层），均自动居中。
     *
     * @param resId      任意资源 ID（drawable 或 raw；PNG/JPEG/Vector 均可，
     *                   矢量输出时必须是 vector）
     * @param tint       图标颜色，null 保留图标原色
     * @param scale      相对中心空白区的缩放系数（0~1），如 1.0 占满、0.5 半大
     * @param circleClip true 时图标按中心圆裁剪（cover 填充），适合方形位图
     */
    fun inner(
        @AnyRes resId: Int,
        @ColorInt tint: Int? = Color.WHITE,
        scale: Float = 1.0f,
        circleClip: Boolean = false,
    ) = apply {
        inners += InnerIcon(resId, null, tint, scale.coerceIn(0.05f, 4.0f), circleClip)
    }

    /**
     * 在中心区域叠放一个 [Drawable]（运行时对象，无需资源 ID）。
     * 仅 PNG/Bitmap 输出支持；矢量输出会抛出异常。
     *
     * @param tint 图标颜色，null 保留原色（位图默认 null）
     */
    fun inner(
        drawable: Drawable,
        @ColorInt tint: Int? = null,
        scale: Float = 1.0f,
        circleClip: Boolean = false,
    ) = apply {
        inners += InnerIcon(0, drawable.mutate(), tint, scale.coerceIn(0.05f, 4.0f), circleClip)
    }

    /** 在中心区域叠放一个 [Bitmap]（包装为 BitmapDrawable），规则同 [inner] */
    fun inner(
        bitmap: Bitmap,
        @ColorInt tint: Int? = null,
        scale: Float = 1.0f,
        circleClip: Boolean = false,
    ) = inner(BitmapDrawable(context.resources, bitmap), tint, scale, circleClip)

    /**
     * 圆形裁剪内嵌（照片缩略图 / 头像场景便捷入口）：
     * 图标按中心圆 cover 裁剪 + 描边压边。等价于
     * `inner(drawable, tint = null, scale, circleClip = true)` + 描边。
     *
     * @param ringColor 描边颜色，null 不描边；默认白色（配白色圆盘最常用）
     * @param ringRatio 描边宽度占图标直径的比例（默认 3%）
     */
    fun innerCircle(
        drawable: Drawable,
        scale: Float = 1.0f,
        @ColorInt ringColor: Int? = Color.WHITE,
        ringRatio: Float = 0.03f,
    ) = apply {
        inners += InnerIcon(
            resId = 0,
            drawable = drawable.mutate(),
            tint = null,
            scale = scale.coerceIn(0.05f, 4.0f),
            circleClip = true,
            ringColor = ringColor,
            ringRatio = ringRatio.coerceIn(0.005f, 0.2f),
        )
    }

    /** [innerCircle] 的 Bitmap 版本 */
    fun innerCircle(
        bitmap: Bitmap,
        scale: Float = 1.0f,
        @ColorInt ringColor: Int? = Color.WHITE,
        ringRatio: Float = 0.03f,
    ) = innerCircle(BitmapDrawable(context.resources, bitmap), scale, ringColor, ringRatio)

    /** 清除所有中心替换图标（纯底板） */
    fun clearInners() = apply { inners.clear() }

    /**
     * 开启"圆盘 + 图标"双层效果：在底板与替换图标之间绘制一个填充圆盘
     * （颜色可任意指定，白色最常用）。
     *
     * @param color 圆盘颜色
     * @param diameterScale 圆盘直径占中心空白区的比例（默认 1.0 占满）
     */
    @ColorInt
    private var discColorValue: Int = Color.TRANSPARENT
    private var discScale: Float = 1.0f

    fun innerDisc(@ColorInt color: Int, diameterScale: Float = 1.0f) = apply {
        discColorValue = color
        discScale = diameterScale.coerceIn(0.3f, 3.0f)
    }

    /** 关闭中心圆盘 */
    fun noDisc() = apply { discColorValue = Color.TRANSPARENT }

    /** 替换图标整体缩放系数（相对中心空白区的统一边距，默认 0.88） */
    fun innerScale(scale: Float) = apply { innerScale = scale.coerceIn(0.3f, 1.2f) }

    // ============================== 几何计算（1024 坐标系） ==============================

    /** 圆盘外接矩形（含缩放），仅当开启圆盘时有效 */
    private data class DiscRect(val left: Float, val top: Float, val diameter: Float)

    private fun discRect(): DiscRect? {
        if (discColorValue == Color.TRANSPARENT) return null
        val d = baseType.holeDiameter * discScale
        return DiscRect(
            left = baseType.holeCx - d / 2f,
            top = baseType.holeCy - d / 2f,
            diameter = d,
        )
    }

    /** 替换图标可使用的最大区域：有圆盘时取圆盘内接的 86%，否则取空白区 */
    private fun iconAreaDiameter(): Float {
        val disc = discRect()
        return if (disc != null) disc.diameter * 0.86f else baseType.holeDiameter * innerScale
    }

    // ============================== Bitmap / Drawable 输出 ==============================

    /**
     * 生成合并后的 [Bitmap]（透明背景，方形）。
     * @param sizePx 输出边长像素，如 96、192、512
     */
    fun generateBitmap(sizePx: Int): Bitmap {
        require(sizePx > 0) { "sizePx 必须大于 0" }
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val unit = sizePx / 1024f   // 1024 坐标系 -> 像素

        // 1. 底板
        canvas.save()
        canvas.scale(unit, unit)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = baseColor
            style = Paint.Style.FILL
        }
        for (pathData in baseType.pathDataList) {
            canvas.drawPath(PathParser.createPathFromPathData(pathData), paint)
        }
        canvas.restore()

        // 2. 中心圆盘（双层效果）
        discRect()?.let { disc ->
            val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = discColorValue
                style = Paint.Style.FILL
            }
            canvas.drawCircle(
                disc.left * unit + disc.diameter * unit / 2f,
                disc.top * unit + disc.diameter * unit / 2f,
                disc.diameter * unit / 2f,
                discPaint,
            )
        }

        // 3. 中心叠放图标（按添加顺序，后添加在上层）
        val area = iconAreaDiameter()
        for (icon in inners) {
            val d = area * icon.scale
            val left = (baseType.holeCx - d / 2f) * unit
            val top = (baseType.holeCy - d / 2f) * unit
            drawInnerDrawable(canvas, icon, left, top, d * unit)
        }
        return bitmap
    }

    @SuppressLint("ResourceType") // resId 允许 drawable 或 raw，运行时按实际资源加载
    private fun drawInnerDrawable(
        canvas: Canvas,
        icon: InnerIcon,
        left: Float,
        top: Float,
        sizePx: Float,
    ) {
        val drawable = icon.drawable
            ?: ContextCompat.getDrawable(context, icon.resId)!!.mutate()
        drawable.setBounds(
            left.toInt(), top.toInt(),
            (left + sizePx).toInt(), (top + sizePx).toInt(),
        )
        icon.tint?.let {
            // SRC_IN：只给图标不透明部分着色，透明区保持透明
            drawable.colorFilter = PorterDuffColorFilter(it, PorterDuff.Mode.SRC_IN)
        }

        if (!icon.circleClip) {
            drawable.draw(canvas)
            return
        }

        // 圆形裁剪：cover 填充进中心圆 + 可选描边压边
        val cx = left + sizePx / 2f
        val cy = top + sizePx / 2f
        val r = sizePx / 2f
        val saveCount = canvas.save()
        canvas.clipPath(Path().apply { addCircle(cx, cy, r, Path.Direction.CCW) })
        drawable.draw(canvas)
        canvas.restoreToCount(saveCount)

        icon.ringColor?.let { ring ->
            canvas.drawCircle(
                cx, cy, r,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    color = ring
                    strokeWidth = sizePx * icon.ringRatio
                },
            )
        }
    }

    /** 生成可直接用于 ImageView / 地图 Marker 的 [BitmapDrawable] */
    fun generateDrawable(sizePx: Int): BitmapDrawable =
        BitmapDrawable(context.resources, generateBitmap(sizePx))

    /** 保存 PNG 到文件，[sizePx] 为边长像素 */
    fun savePng(file: File, sizePx: Int) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { out ->
            generateBitmap(sizePx).compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    // ============================== 矢量输出 ==============================

    private data class VectorPath(val pathData: String, val fillType: String?)

    /**
     * 解析资源中的 <vector> XML，取 viewport 与全部 path（忽略嵌套 group 变换，
     * iconfont 类扁平图标不受影响）。
     */
    private fun parseInnerVector(@AnyRes resId: Int): Triple<Float, Float, List<VectorPath>> {
        val paths = mutableListOf<VectorPath>()
        var vw = 24f
        var vh = 24f
        val ns = "http://schemas.android.com/apk/res/android"
        try {
            context.resources.openRawResource(resId).use { input ->
                val parser: XmlPullParser = Xml.newPullParser()
                parser.setInput(input, null)
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG) {
                        when (parser.name) {
                            "vector" -> {
                                parser.getAttributeValue(ns, "viewportWidth")?.toFloatOrNull()
                                    ?.let { vw = it }
                                parser.getAttributeValue(ns, "viewportHeight")?.toFloatOrNull()
                                    ?.let { vh = it }
                            }
                            "path" -> {
                                val d = parser.getAttributeValue(ns, "pathData") ?: ""
                                if (d.isNotEmpty()) {
                                    paths += VectorPath(d, parser.getAttributeValue(ns, "fillType"))
                                }
                            }
                        }
                    }
                    event = parser.next()
                }
            }
        } catch (e: Exception) {
            throw IllegalStateException(
                "资源 0x${Integer.toHexString(resId)} 不是可解析的 vector XML，" +
                        "矢量输出要求替换图标必须是 VectorDrawable（可放到 res/raw）", e
            )
        }
        check(paths.isNotEmpty()) { "vector 资源中没有 path 数据" }
        return Triple(vw, vh, paths)
    }

    /**
     * 生成合并后的 VectorDrawable XML 字符串。
     * 底板 path 使用 [baseColor]；圆盘为填充圆 path；替换图标 path 经 group
     * 缩放平移到中心空白区并使用各自颜色。
     *
     * 注意：内嵌图标必须是资源引用的 <vector>（[InnerIcon.resId]）；
     * Drawable / Bitmap / 圆形裁剪内嵌仅 PNG/Bitmap 输出支持，此处会抛异常。
     *
     * @param sizeDp 矢量图的声明尺寸（矢量可任意缩放，此值仅为默认显示大小）
     */
    fun generateVectorXml(sizeDp: Int = 24): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        sb.append("<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"\n")
        sb.append("    android:width=\"${sizeDp}dp\"\n")
        sb.append("    android:height=\"${sizeDp}dp\"\n")
        sb.append("    android:viewportWidth=\"1024\"\n")
        sb.append("    android:viewportHeight=\"1024\">\n")

        // 1. 底板 path（单一颜色）
        for (pathData in baseType.pathDataList) {
            sb.append("    <path\n")
            sb.append("        android:fillColor=\"${toHex(baseColor)}\"\n")
            sb.append("        android:pathData=\"$pathData\" />\n")
        }

        // 2. 中心圆盘
        discRect()?.let { disc ->
            sb.append("    <path\n")
            sb.append("        android:fillColor=\"${toHex(discColorValue)}\"\n")
            sb.append("        android:pathData=\"${circlePathData(disc)}\" />\n")
        }

        // 3. 中心叠放图标：每个图标一个 group，缩放到对应尺寸并居中
        val area = iconAreaDiameter()
        for ((index, icon) in inners.withIndex()) {
            check(icon.drawable == null) {
                "第 ${index + 1} 个内嵌图标是运行时 Drawable/Bitmap（或启用了圆形裁剪），" +
                        "矢量输出仅支持资源引用的 <vector> 图标；请改用 PNG/Bitmap 输出"
            }
            val (vw, vh, paths) = parseInnerVector(icon.resId)
            val d = area * icon.scale
            val s = d / maxOf(vw, vh)               // 等比缩放到目标区域
            val tx = baseType.holeCx - s * vw / 2f  // 居中平移量
            val ty = baseType.holeCy - s * vh / 2f
            sb.append("    <group\n")
            sb.append("        android:scaleX=\"${trimFloat(s)}\"\n")
            sb.append("        android:scaleY=\"${trimFloat(s)}\"\n")
            sb.append("        android:translateX=\"${trimFloat(tx)}\"\n")
            sb.append("        android:translateY=\"${trimFloat(ty)}\">\n")
            for (p in paths) {
                sb.append("        <path\n")
                sb.append("            android:fillColor=\"${toHex(icon.tint ?: Color.WHITE)}\"\n")
                if (p.fillType != null) {
                    sb.append("            android:fillType=\"${p.fillType}\"\n")
                }
                sb.append("            android:pathData=\"${p.pathData}\" />\n")
            }
            sb.append("    </group>\n")
        }

        sb.append("</vector>\n")
        return sb.toString()
    }

    /**
     * 直接生成 [VectorDrawable] 对象（API 21+）。
     * 比 Bitmap 更省内存、任意缩放不失真，推荐用于 Marker/图标场景。
     */
    fun generateVectorDrawable(): VectorDrawable {
        val xml = generateVectorXml()
        return try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))
            Drawable.createFromXml(context.resources, parser) as VectorDrawable
        } catch (e: Exception) {
            throw IllegalStateException("矢量图标生成失败", e)
        }
    }

    /** 把生成的矢量 XML 保存到文件（可作为 drawable 资源直接使用） */
    fun saveVector(file: File, sizeDp: Int = 24) {
        file.parentFile?.mkdirs()
        file.writeText(generateVectorXml(sizeDp))
    }

    // ============================== 工具 ==============================

    /** 圆盘外接矩形 -> 圆 pathData（两段圆弧闭合） */
    private fun circlePathData(disc: DiscRect): String {
        val r = disc.diameter / 2f
        val cx = disc.left + r
        val cy = disc.top + r
        return "M${trimFloat(cx - r)},${trimFloat(cy)} " +
                "a${trimFloat(r)},${trimFloat(r)} 0 1 0 ${trimFloat(disc.diameter)},0 " +
                "a${trimFloat(r)},${trimFloat(r)} 0 1 0 ${trimFloat(-disc.diameter)},0 Z"
    }

    private fun trimFloat(v: Float): String =
        if (v == v.toInt().toFloat()) v.toInt().toString()
        else String.format("%.4f", v).trimEnd('0').trimEnd('.')

    private fun toHex(@ColorInt color: Int): String = String.format("#%08X", color)

    companion object {
        /** 便捷入口：从 PNG 字节生成 Drawable（供后台/缓存场景） */
        fun drawableFromPng(context: Context, pngBytes: ByteArray): Drawable {
            val bmp = BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size)
            return BitmapDrawable(context.resources, bmp)
        }

        // =================================================================================
        // 照片气泡针（图2风格）：圆角矩形照片（白边）+ 底部三角尾巴
        // 独立于实例状态，任意处直接 MapIconUtil.photoPin(...) 调用。
        // =================================================================================

        /** 占位照片区默认底色（无底图时） */
        @ColorInt
        private val PHOTO_PIN_PLACEHOLDER: Int = 0xFFE0E0E0.toInt()

        /**
         * 合成"照片气泡针"位图：圆角矩形照片（白色边框）+ 底部居中三角尾巴。
         *
         * 布局（[sizePx] 方形画布）：
         *  · 照片区：居中偏上圆角矩形，cover 填充 [photo]（null 时填 [placeholderColor]）；
         *  · 尾巴：底边居中下三角，与边框同色一体成型。
         * 尾巴底端即图标底端——地图符号层 `iconAnchor = bottom` 时精确锚定坐标点。
         *
         * @param photo            缩略图（任意尺寸，自动 cover 缩放）；null = 占位样式
         * @param sizePx           输出边长像素（建议 144~192）
         * @param frameColor       边框/尾巴颜色（默认白）
         * @param placeholderColor 无底图时照片区填充色（默认浅灰）
         * @param photoRatio       照片区边长占画布比例（默认 0.68，余量留给尾巴）
         * @param cornerRatio      圆角半径占画布比例（默认 0.10）
         */
        fun photoPin(
            photo: Bitmap?,
            sizePx: Int,
            @ColorInt frameColor: Int = Color.WHITE,
            @ColorInt placeholderColor: Int = PHOTO_PIN_PLACEHOLDER,
            photoRatio: Float = 0.68f,
            cornerRatio: Float = 0.10f,
            tailHalfRatio: Float = 0.085f,
            tailLenRatio: Float = 0.18f,
            strokeRatio: Float = 0.035f,
        ): Bitmap {
            require(sizePx > 0) { "sizePx 必须大于 0" }
            val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)

            val photoSide = sizePx * photoRatio
            val left = (sizePx - photoSide) / 2f
            val top = sizePx * 0.05f
            val radius = sizePx * cornerRatio
            val tailHalf = sizePx * tailHalfRatio
            val tailLen = sizePx * tailLenRatio
            val stroke = sizePx * strokeRatio

            // 1) 边框 + 尾巴（一次 path，底即框）
            val frame = Path().apply {
                addRoundRect(
                    RectF(left, top, left + photoSide, top + photoSide),
                    radius, radius, Path.Direction.CW,
                )
                moveTo(sizePx / 2f - tailHalf, top + photoSide - stroke)
                lineTo(sizePx / 2f + tailHalf, top + photoSide - stroke)
                lineTo(sizePx / 2f, top + photoSide + tailLen)
                close()
            }
            canvas.drawPath(
                frame,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = frameColor
                    style = Paint.Style.FILL
                },
            )

            // 2) 照片区：内缩描边后圆角裁剪，cover 填充（null 时占位色）
            val inner = RectF(
                left + stroke, top + stroke,
                left + photoSide - stroke, top + photoSide - stroke,
            )
            canvas.save()
            canvas.clipPath(
                Path().apply {
                    addRoundRect(inner, radius * 0.75f, radius * 0.75f, Path.Direction.CCW)
                },
            )
            if (photo != null) {
                val scale = maxOf(inner.width() / photo.width, inner.height() / photo.height)
                canvas.drawBitmap(
                    photo,
                    Matrix().apply {
                        setScale(scale, scale)
                        postTranslate(
                            inner.centerX() - photo.width * scale / 2f,
                            inner.centerY() - photo.height * scale / 2f,
                        )
                    },
                    null,
                )
            } else {
                canvas.drawRect(
                    inner,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = placeholderColor },
                )
            }
            canvas.restore()
            return out
        }
    }
}