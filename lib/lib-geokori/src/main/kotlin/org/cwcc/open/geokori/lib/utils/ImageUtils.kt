package org.cwcc.open.geokori.lib.utils

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.graphics.Bitmap.CompressFormat
import android.graphics.PorterDuff.Mode
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.renderscript.*
import android.view.View
import android.widget.ImageView
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.annotation.FloatRange
import androidx.annotation.IntRange
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.io.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.createBitmap
import androidx.exifinterface.media.ExifInterface

object ImageUtils {
  private const val TAG = "ImageUtils"

  /**
   * Bitmap to bytes.
   *
   * @param bitmap The bitmap.
   * @param format The format of bitmap.
   * @return bytes
   */
  @JvmStatic
  fun bitmap2Bytes(bitmap: Bitmap?, format: CompressFormat): ByteArray? {
    if (bitmap == null) {
      return null
    }
    val baos = ByteArrayOutputStream()
    bitmap.compress(format, 100, baos)
    return baos.toByteArray()
  }

  /**
   * Bytes to bitmap.
   *
   * @param bytes The bytes.
   * @return bitmap
   */
  @JvmStatic
  fun bytes2Bitmap(bytes: ByteArray?): Bitmap? {
    return if (bytes == null || bytes.isEmpty()) null
    else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
  }

  /**
   * Drawable to bitmap.
   *
   * @param drawable The drawable.
   * @return bitmap
   */
  @JvmStatic
  fun drawable2Bitmap(drawable: Drawable?): Bitmap? {
    if (drawable == null) {
      return null
    }
    if (drawable is BitmapDrawable) {
      val bitmapDrawable = drawable
      if (bitmapDrawable.bitmap != null) {
        return bitmapDrawable.bitmap
      }
    }
    val bitmap: Bitmap = if (drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) {
      Bitmap.createBitmap(
          1, 1,
          if (drawable.opacity != PixelFormat.OPAQUE) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565,
      )
    } else {
      Bitmap.createBitmap(
          drawable.intrinsicWidth,
          drawable.intrinsicHeight,
          if (drawable.opacity != PixelFormat.OPAQUE) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565,
      )
    }
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bitmap
  }

  /**
   * Bitmap to drawable.
   *
   * @param bitmap The bitmap.
   * @return drawable
   */
  @JvmStatic
  fun bitmap2Drawable(context: Context, bitmap: Bitmap?): Drawable? {
    return bitmap?.toDrawable(context.resources)
  }

  /**
   * Drawable to bytes.
   *
   * @param drawable The drawable.
   * @param format   The format of bitmap.
   * @return bytes
   */
  @JvmStatic
  fun drawable2Bytes(drawable: Drawable?, format: CompressFormat): ByteArray? {
    return if (drawable == null) null
    else bitmap2Bytes(drawable2Bitmap(drawable), format)
  }

  /**
   * Bytes to drawable.
   *
   * @param bytes The bytes.
   * @return drawable
   */
  @JvmStatic
  fun bytes2Drawable(context:Context,bytes: ByteArray?): Drawable? {
    return bitmap2Drawable(context,bytes2Bitmap(bytes))
  }

  /**
   * View to bitmap.
   *
   * @param view The view.
   * @return bitmap
   */
  @JvmStatic
  fun view2Bitmap(view: View?): Bitmap? {
    if (view == null) {
      return null
    }
    val ret = Bitmap.createBitmap(
        view.width,
        view.height,
        Bitmap.Config.ARGB_8888,
    )
    val canvas = Canvas(ret)
    val bgDrawable = view.background
    if (bgDrawable != null) {
      bgDrawable.draw(canvas)
    } else {
      canvas.drawColor(Color.WHITE)
    }
    view.draw(canvas)
    return ret
  }

  /**
   * Return bitmap.
   *
   * @param file The file.
   * @return bitmap
   */
  @JvmStatic
  fun getBitmap(file: File?): Bitmap? {
    if (file == null) {
      return null
    }
    return BitmapFactory.decodeFile(file.absolutePath)
  }

  /**
   * Return bitmap.
   *
   * @param file      The file.
   * @param maxWidth  The maximum width.
   * @param maxHeight The maximum height.
   * @return bitmap
   */
  @JvmStatic
  fun getBitmap(file: File?, maxWidth: Int, maxHeight: Int): Bitmap? {
    if (file == null) {
      return null
    }
    val options = BitmapFactory.Options()
    options.inJustDecodeBounds = true
    BitmapFactory.decodeFile(file.absolutePath, options)
    options.inSampleSize = calculateInSampleSize(options, maxWidth, maxHeight)
    options.inJustDecodeBounds = false
    return BitmapFactory.decodeFile(file.absolutePath, options)
  }

  /**
   * Return bitmap.
   *
   * @param filePath The path of file.
   * @return bitmap
   */
  @JvmStatic
  fun getBitmap(filePath: String?): Bitmap? {
    if (isSpace(filePath)) {
      return null
    }
    return BitmapFactory.decodeFile(filePath)
  }

  /**
   * Return bitmap.
   *
   * @param filePath  The path of file.
   * @param maxWidth  The maximum width.
   * @param maxHeight The maximum height.
   * @return bitmap
   */
  @JvmStatic
  fun getBitmap(filePath: String?, maxWidth: Int, maxHeight: Int): Bitmap? {
    if (isSpace(filePath)) {
      return null
    }
    val options = BitmapFactory.Options()
    options.inJustDecodeBounds = true
    BitmapFactory.decodeFile(filePath, options)
    options.inSampleSize = calculateInSampleSize(options, maxWidth, maxHeight)
    options.inJustDecodeBounds = false
    return BitmapFactory.decodeFile(filePath, options)
  }

  /**
   * Return bitmap.
   *
   * @param is The input stream.
   * @return bitmap
   */
  @JvmStatic
  fun getBitmap(`is`: InputStream?): Bitmap? {
    if (`is` == null) {
      return null
    }
    return BitmapFactory.decodeStream(`is`)
  }

  /**
   * Return bitmap.
   *
   * @param is        The input stream.
   * @param maxWidth  The maximum width.
   * @param maxHeight The maximum height.
   * @return bitmap
   */
  @JvmStatic
  fun getBitmap(`is`: InputStream?, maxWidth: Int, maxHeight: Int): Bitmap? {
    if (`is` == null) {
      return null
    }
    val bytes = input2Byte(`is`)
    return getBitmap(bytes, 0, maxWidth, maxHeight)
  }

  /**
   * Return bitmap.
   *
   * @param data   The data.
   * @param offset The offset.
   * @return bitmap
   */
  @JvmStatic
  fun getBitmap(data: ByteArray, offset: Int): Bitmap? {
    if (data.isEmpty()) {
      return null
    }
    return BitmapFactory.decodeByteArray(data, offset, data.size)
  }

  /**
   * Return bitmap.
   *
   * @param data      The data.
   * @param offset    The offset.
   * @param maxWidth  The maximum width.
   * @param maxHeight The maximum height.
   * @return bitmap
   */
  @JvmStatic
  fun getBitmap(data: ByteArray?, offset: Int, maxWidth: Int, maxHeight: Int): Bitmap? {
    data?.isEmpty() ?: return null

    val options = BitmapFactory.Options()
    options.inJustDecodeBounds = true
    BitmapFactory.decodeByteArray(data, offset, data.size, options)
    options.inSampleSize = calculateInSampleSize(options, maxWidth, maxHeight)
    options.inJustDecodeBounds = false
    return BitmapFactory.decodeByteArray(data, offset, data.size, options)
  }

  /**
   * Return bitmap.
   *
   * @param resId The resource id.
   * @return bitmap
   */
  @JvmStatic
  fun getBitmap(@DrawableRes resId: Int,context:Context): Bitmap? {
    val drawable = ContextCompat.getDrawable(context, resId) ?: return null
    val canvas = Canvas()
    val bitmap = createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight)
    canvas.setBitmap(bitmap)
    drawable.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
    drawable.draw(canvas)
    return bitmap
  }

  /**
   * Return bitmap.
   *
   * @param resId     The resource id.
   * @param maxWidth  The maximum width.
   * @param maxHeight The maximum height.
   * @return bitmap
   */
  @JvmStatic
  fun getBitmap(@DrawableRes resId: Int, maxWidth: Int, maxHeight: Int,context:Context): Bitmap? {
    val options = BitmapFactory.Options()
    val resources =context.resources
    options.inJustDecodeBounds = true
    BitmapFactory.decodeResource(resources, resId, options)
    options.inSampleSize = calculateInSampleSize(options, maxWidth, maxHeight)
    options.inJustDecodeBounds = false
    return BitmapFactory.decodeResource(resources, resId, options)
  }

  /**
   * Return bitmap.
   *
   * @param fd The file descriptor.
   * @return bitmap
   */
  @JvmStatic
  fun getBitmap(fd: FileDescriptor?): Bitmap? {
    if (fd == null) {
      return null
    }
    return BitmapFactory.decodeFileDescriptor(fd)
  }

  /**
   * Return bitmap.
   *
   * @param fd        The file descriptor
   * @param maxWidth  The maximum width.
   * @param maxHeight The maximum height.
   * @return bitmap
   */
  @JvmStatic
  fun getBitmap(fd: FileDescriptor?, maxWidth: Int, maxHeight: Int): Bitmap? {
    if (fd == null) {
      return null
    }
    val options = BitmapFactory.Options()
    options.inJustDecodeBounds = true
    BitmapFactory.decodeFileDescriptor(fd, null, options)
    options.inSampleSize = calculateInSampleSize(options, maxWidth, maxHeight)
    options.inJustDecodeBounds = false
    return BitmapFactory.decodeFileDescriptor(fd, null, options)
  }

  /**
   * 清空图片的内存
   */
  @JvmStatic
  fun clearImgMemory(imageView: ImageView?) {
    if (imageView == null) {
      return
    }

    val d = imageView.drawable
    if (d is BitmapDrawable) {
      val bmp = d.bitmap
      if (bmp != null && !bmp.isRecycled) {
        bmp.recycle()
      }
    }
    imageView.setImageBitmap(null)
    d?.callback = null
  }

  /**
   * Return the bitmap with the specified color.
   *
   * @param src   The source of bitmap.
   * @param color The color.
   * @return the bitmap with the specified color
   */
  @JvmStatic
  fun drawColor(src: Bitmap, @ColorInt color: Int): Bitmap? {
    return drawColor(src, color, false)
  }

  /**
   * Return the bitmap with the specified color.
   *
   * @param src     The source of bitmap.
   * @param color   The color.
   * @param recycle True to recycle the source of bitmap, false otherwise.
   * @return the bitmap with the specified color
   */
  @JvmStatic
  fun drawColor(src: Bitmap?, @ColorInt color: Int, recycle: Boolean): Bitmap? {
    if (isEmptyBitmap(src)) {
      return null
    }
    val ret = if (recycle) src else src?.config?.let { src.copy(it, true) }
    val canvas = Canvas(ret!!)
    canvas.drawColor(color, Mode.DARKEN)
    return ret
  }

  /**
   * Return the scaled bitmap.
   *
   * @param src       The source of bitmap.
   * @param newWidth  The new width.
   * @param newHeight The new height.
   * @return the scaled bitmap
   */
  @JvmStatic
  fun scale(src: Bitmap?, newWidth: Int, newHeight: Int): Bitmap? {
    return scale(src, newWidth, newHeight, false)
  }

  /**
   * Return the scaled bitmap.
   *
   * @param src       The source of bitmap.
   * @param newWidth  The new width.
   * @param newHeight The new height.
   * @param recycle   True to recycle the source of bitmap, false otherwise.
   * @return the scaled bitmap
   */
  @JvmStatic
  fun scale(src: Bitmap?, newWidth: Int, newHeight: Int, recycle: Boolean): Bitmap? {
    if (isEmptyBitmap(src)) {
      return null
    }
    val ret = Bitmap.createScaledBitmap(src!!, newWidth, newHeight, true)
    if (recycle && !src.isRecycled) {
      src.recycle()
    }
    return ret
  }

  /**
   * Return the scaled bitmap
   *
   * @param src         The source of bitmap.
   * @param scaleWidth  The scale of width.
   * @param scaleHeight The scale of height.
   * @return the scaled bitmap
   */
  @JvmStatic
  fun scale(src: Bitmap?, scaleWidth: Float, scaleHeight: Float): Bitmap? {
    return scale(src, scaleWidth, scaleHeight, false)
  }

  /**
   * Return the scaled bitmap
   *
   * @param src         The source of bitmap.
   * @param scaleWidth  The scale of width.
   * @param scaleHeight The scale of height.
   * @param recycle     True to recycle the source of bitmap, false otherwise.
   * @return the scaled bitmap
   */
  @JvmStatic
  fun scale(src: Bitmap?, scaleWidth: Float, scaleHeight: Float, recycle: Boolean): Bitmap? {
    if (isEmptyBitmap(src)) {
      return null
    }
    val matrix = Matrix()
    matrix.setScale(scaleWidth, scaleHeight)
    val ret = Bitmap.createBitmap(src!!, 0, 0, src.width, src.height, matrix, true)
    if (recycle && !src.isRecycled) {
      src.recycle()
    }
    return ret
  }

  /**
   * Return the clipped bitmap.
   *
   * @param src    The source of bitmap.
   * @param x      The x coordinate of the first pixel.
   * @param y      The y coordinate of the first pixel.
   * @param width  The width.
   * @param height The height.
   * @return the clipped bitmap
   */
  @JvmStatic
  fun clip(src: Bitmap?, x: Int, y: Int, width: Int, height: Int): Bitmap? {
    return clip(src, x, y, width, height, false)
  }

  /**
   * Return the clipped bitmap.
   *
   * @param src     The source of bitmap.
   * @param x       The x coordinate of the first pixel.
   * @param y       The y coordinate of the first pixel.
   * @param width   The width.
   * @param height  The height.
   * @param recycle True to recycle the source of bitmap, false otherwise.
   * @return the clipped bitmap
   */
  @JvmStatic
  fun clip(src: Bitmap?, x: Int, y: Int, width: Int, height: Int, recycle: Boolean): Bitmap? {
    if (isEmptyBitmap(src)) {
      return null
    }
    val ret = Bitmap.createBitmap(src!!, x, y, width, height)
    if (recycle && !src.isRecycled) {
      src.recycle()
    }
    return ret
  }

  /**
   * Return the skewed bitmap.
   *
   * @param src The source of bitmap.
   * @param kx  The skew factor of x.
   * @param ky  The skew factor of y.
   * @return the skewed bitmap
   */
  @JvmStatic
  fun skew(src: Bitmap?, kx: Float, ky: Float): Bitmap? {
    return skew(src, kx, ky, 0f, 0f, false)
  }

  /**
   * Return the skewed bitmap.
   *
   * @param src     The source of bitmap.
   * @param kx      The skew factor of x.
   * @param ky      The skew factor of y.
   * @param recycle True to recycle the source of bitmap, false otherwise.
   * @return the skewed bitmap
   */
  @JvmStatic
  fun skew(src: Bitmap?, kx: Float, ky: Float, recycle: Boolean): Bitmap? {
    return skew(src, kx, ky, 0f, 0f, recycle)
  }

  /**
   * Return the skewed bitmap.
   *
   * @param src The source of bitmap.
   * @param kx  The skew factor of x.
   * @param ky  The skew factor of y.
   * @param px  The x coordinate of the pivot point.
   * @param py  The y coordinate of the pivot point.
   * @return the skewed bitmap
   */
  @JvmStatic
  fun skew(src: Bitmap?, kx: Float, ky: Float, px: Float, py: Float): Bitmap? {
    return skew(src, kx, ky, px, py, false)
  }

  /**
   * Return the skewed bitmap.
   *
   * @param src     The source of bitmap.
   * @param kx      The skew factor of x.
   * @param ky      The skew factor of y.
   * @param px      The x coordinate of the pivot point.
   * @param py      The y coordinate of the pivot point.
   * @param recycle True to recycle the source of bitmap, false otherwise.
   * @return the skewed bitmap
   */
  @JvmStatic
  fun skew(src: Bitmap?, kx: Float, ky: Float, px: Float, py: Float, recycle: Boolean): Bitmap? {
    if (isEmptyBitmap(src)) {
      return null
    }
    val matrix = Matrix()
    matrix.setSkew(kx, ky, px, py)
    val ret = Bitmap.createBitmap(src!!, 0, 0, src.width, src.height, matrix, true)
    if (recycle && !src.isRecycled) {
      src.recycle()
    }
    return ret
  }

  /**
   * Return the rotated bitmap.
   *
   * @param src     The source of bitmap.
   * @param degrees The number of degrees.
   * @param px      The x coordinate of the pivot point.
   * @param py      The y coordinate of the pivot point.
   * @return the rotated bitmap
   */
  @JvmStatic
  fun rotate(src: Bitmap?, degrees: Int, px: Float, py: Float): Bitmap? {
    return rotate(src, degrees, px, py, false)
  }

  /**
   * Return the rotated bitmap.
   *
   * @param src     The source of bitmap.
   * @param degrees The number of degrees.
   * @param px      The x coordinate of the pivot point.
   * @param py      The y coordinate of the pivot point.
   * @param recycle True to recycle the source of bitmap, false otherwise.
   * @return the rotated bitmap
   */
  @JvmStatic
  fun rotate(src: Bitmap?, degrees: Int, px: Float, py: Float, recycle: Boolean): Bitmap? {
    if (isEmptyBitmap(src)) {
      return null
    }
    if (degrees == 0) {
      return src
    }
    val matrix = Matrix()
    matrix.setRotate(degrees.toFloat(), px, py)
    val ret = Bitmap.createBitmap(src!!, 0, 0, src.width, src.height, matrix, true)
    if (recycle && !src.isRecycled) {
      src.recycle()
    }
    return ret
  }

  /**
   * Return the rotated degree.
   *
   * @param filePath The path of file.
   * @return the rotated degree
   */
  @JvmStatic
  fun getRotateDegree(filePath: String?): Int {
    if (filePath == null) {
      return -1
    }
    try {
      val exifInterface = ExifInterface(filePath)
      val orientation = exifInterface.getAttributeInt(
          ExifInterface.TAG_ORIENTATION,
          ExifInterface.ORIENTATION_NORMAL,
      )
      return when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
      }
    } catch (e: IOException) {
      e.printStackTrace()
      return -1
    }
  }

  /**
   * Return the round bitmap.
   *
   * @param src The source of bitmap.
   * @return the round bitmap
   */
  @JvmStatic
  fun toRound(src: Bitmap?): Bitmap? {
    return toRound(src, 0, 0, false)
  }

  /**
   * Return the round bitmap.
   *
   * @param src     The source of bitmap.
   * @param recycle True to recycle the source of bitmap, false otherwise.
   * @return the round bitmap
   */
  @JvmStatic
  fun toRound(src: Bitmap?, recycle: Boolean): Bitmap? {
    return toRound(src, 0, 0, recycle)
  }

  /**
   * Return the round bitmap.
   *
   * @param src         The source of bitmap.
   * @param borderSize  The size of border.
   * @param borderColor The color of border.
   * @return the round bitmap
   */
  @JvmStatic
  fun toRound(src: Bitmap?, @IntRange(from = 0) borderSize: Int, @ColorInt borderColor: Int): Bitmap? {
    return toRound(src, borderSize, borderColor, false)
  }

  /**
   * Return the round bitmap.
   *
   * @param src         The source of bitmap.
   * @param recycle     True to recycle the source of bitmap, false otherwise.
   * @param borderSize  The size of border.
   * @param borderColor The color of border.
   * @return the round bitmap
   */
  @JvmStatic
  fun toRound(src: Bitmap?, @IntRange(from = 0) borderSize: Int, @ColorInt borderColor: Int, recycle: Boolean): Bitmap? {
    if (isEmptyBitmap(src)) {
      return null
    }
    val width = src!!.width
    val height = src.height
    val size = min(width, height)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val ret = src.config?.let { createBitmap(width, height, it) }
    val center = size / 2f
    val rectF = RectF(0f, 0f, width.toFloat(), height.toFloat())
    rectF.inset((width - size) / 2f, (height - size) / 2f)
    val matrix = Matrix()
    matrix.setTranslate(rectF.left, rectF.top)
    matrix.preScale(size.toFloat() / width, size.toFloat() / height)
    val shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    shader.setLocalMatrix(matrix)
    paint.shader = shader
    val canvas = Canvas(ret!!)
    canvas.drawRoundRect(rectF, center, center, paint)
    if (borderSize > 0) {
      paint.shader = null
      paint.color = borderColor
      paint.style = Paint.Style.STROKE
      paint.strokeWidth = borderSize.toFloat()
      val radius = center - borderSize / 2f
      canvas.drawCircle(width / 2f, height / 2f, radius, paint)
    }
    if (recycle && !src.isRecycled) {
      src.recycle()
    }
    return ret
  }

  /**
   * Return the round corner bitmap.
   *
   * @param src    The source of bitmap.
   * @param radius The radius of corner.
   * @return the round corner bitmap
   */
  @JvmStatic
  fun toRoundCorner(src: Bitmap?, radius: Float): Bitmap? {
    return toRoundCorner(src, radius, 0, 0, false)
  }

  /**
   * Return the round corner bitmap.
   *
   * @param src     The source of bitmap.
   * @param radius  The radius of corner.
   * @param recycle True to recycle the source of bitmap, false otherwise.
   * @return the round corner bitmap
   */
  @JvmStatic
  fun toRoundCorner(src: Bitmap?, radius: Float, recycle: Boolean): Bitmap? {
    return toRoundCorner(src, radius, 0, 0, recycle)
  }

  /**
   * Return the round corner bitmap.
   *
   * @param src         The source of bitmap.
   * @param radius      The radius of corner.
   * @param borderSize  The size of border.
   * @param borderColor The color of border.
   * @return the round corner bitmap
   */
  @JvmStatic
  fun toRoundCorner(src: Bitmap?, radius: Float, @IntRange(from = 0) borderSize: Int, @ColorInt borderColor: Int): Bitmap? {
    return toRoundCorner(src, radius, borderSize, borderColor, false)
  }

  /**
   * Return the round corner bitmap.
   *
   * @param src         The source of bitmap.
   * @param radius      The radius of corner.
   * @param borderSize  The size of border.
   * @param borderColor The color of border.
   * @param recycle     True to recycle the source of bitmap, false otherwise.
   * @return the round corner bitmap
   */
  @JvmStatic
  fun toRoundCorner(
      src: Bitmap?,
      radius: Float,
      @IntRange(from = 0) borderSize: Int,
      @ColorInt borderColor: Int,
      recycle: Boolean
  ): Bitmap? {
    if (isEmptyBitmap(src)) {
      return null
    }
    val width = src!!.width
    val height = src.height
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val ret: Bitmap? = src.config?.let { createBitmap(width, height, it) }
    val shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    paint.shader = shader
    val canvas = Canvas(ret!!)
    val rectF = RectF(0f, 0f, width.toFloat(), height.toFloat())
    val halfBorderSize = borderSize / 2f
    rectF.inset(halfBorderSize, halfBorderSize)
    canvas.drawRoundRect(rectF, radius, radius, paint)
    if (borderSize > 0) {
      paint.shader = null
      paint.color = borderColor
      paint.style = Paint.Style.STROKE
      paint.strokeWidth = borderSize.toFloat()
      paint.strokeCap = Paint.Cap.ROUND
      canvas.drawRoundRect(rectF, radius, radius, paint)
    }
    if (recycle && !src.isRecycled) {
      src.recycle()
    }
    return ret
  }

  /**
   * Return the round corner bitmap with border.
   *
   * @param src          The source of bitmap.
   * @param borderSize   The size of border.
   * @param color        The color of border.
   * @param cornerRadius The radius of corner.
   * @return the round corner bitmap with border
   */
  @JvmStatic
  fun addCornerBorder(
      src: Bitmap?,
      @IntRange(from = 1) borderSize: Int,
      @ColorInt color: Int,
      @FloatRange(from = 0.0) cornerRadius: Float
  ): Bitmap? {
    return addBorder(src, borderSize, color, false, cornerRadius, false)
  }

  /**
   * Return the round corner bitmap with border.
   *
   * @param src          The source of bitmap.
   * @param borderSize   The size of border.
   * @param color        The color of border.
   * @param cornerRadius The radius of corner.
   * @param recycle      True to recycle the source of bitmap, false otherwise.
   * @return the round corner bitmap with border
   */
  @JvmStatic
  fun addCornerBorder(
      src: Bitmap?,
      @IntRange(from = 1) borderSize: Int,
      @ColorInt color: Int,
      @FloatRange(from = 0.0) cornerRadius: Float,
      recycle: Boolean
  ): Bitmap? {
    return addBorder(src, borderSize, color, false, cornerRadius, recycle)
  }

  /**
   * Return the round bitmap with border.
   *
   * @param src        The source of bitmap.
   * @param borderSize The size of border.
   * @param color      The color of border.
   * @return the round bitmap with border
   */
  @JvmStatic
  fun addCircleBorder(
      src: Bitmap?,
      @IntRange(from = 1) borderSize: Int,
      @ColorInt color: Int
  ): Bitmap? {
    return addBorder(src, borderSize, color, true, 0f, false)
  }

  /**
   * Return the round bitmap with border.
   *
   * @param src        The source of bitmap.
   * @param borderSize The size of border.
   * @param color      The color of border.
   * @param recycle    True to recycle the source of bitmap, false otherwise.
   * @return the round bitmap with border
   */
  @JvmStatic
  fun addCircleBorder(
      src: Bitmap?,
      @IntRange(from = 1) borderSize: Int,
      @ColorInt color: Int,
      recycle: Boolean
  ): Bitmap? {
    return addBorder(src, borderSize, color, true, 0f, recycle)
  }

  /**
   * Return the bitmap with border.
   *
   * @param src          The source of bitmap.
   * @param borderSize   The size of border.
   * @param color        The color of border.
   * @param isCircle     True to draw circle, false to draw corner.
   * @param cornerRadius The radius of corner.
   * @param recycle      True to recycle the source of bitmap, false otherwise.
   * @return the bitmap with border
   */
  private fun addBorder(
      src: Bitmap?,
      @IntRange(from = 1) borderSize: Int,
      @ColorInt color: Int,
      isCircle: Boolean,
      cornerRadius: Float,
      recycle: Boolean
  ): Bitmap? {
    if (isEmptyBitmap(src)) {
      return null
    }
    val ret = if (recycle) src else src?.config?.let { src.copy(it, true) }
    ret ?: return null
    val width = ret.width
    val height = ret.height
    val canvas = Canvas(ret)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = color
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = borderSize.toFloat()
    if (isCircle) {
      val radius = min(width, height) / 2f - borderSize / 2f
      canvas.drawCircle(width / 2f, height / 2f, radius, paint)
    } else {
      val halfBorderSize = borderSize / 2
      val rectF = RectF(
          halfBorderSize.toFloat(), halfBorderSize.toFloat(),
          (width - halfBorderSize).toFloat(), (height - halfBorderSize).toFloat(),
      )
      canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, paint)
    }
    if (recycle && src != null && !src.isRecycled) {
      src.recycle()
    }
    return ret
  }

  /**
   * Return the bitmap with reflection.
   *
   * @param src              The source of bitmap.
   * @param reflectionHeight The height of reflection.
   * @return the bitmap with reflection
   */
  @JvmStatic
  fun addReflection(src: Bitmap?, reflectionHeight: Int): Bitmap? {
    return addReflection(src, reflectionHeight, false)
  }

  /**
   * Return the bitmap with reflection（倒影）.
   *
   * @param src              The source of bitmap.
   * @param reflectionHeight The height of reflection.
   * @param recycle          True to recycle the source of bitmap, false otherwise.
   * @return the bitmap with reflection
   */
  @JvmStatic
  fun addReflection(src: Bitmap?, reflectionHeight: Int, recycle: Boolean): Bitmap? {
    if (isEmptyBitmap(src)) {
      return null
    }
    val REFLECTION_GAP = 0
    val srcWidth = src!!.width
    val srcHeight = src.height
    val matrix = Matrix()
    matrix.preScale(1f, -1f)
    val reflectionBitmap = Bitmap.createBitmap(
        src, 0, srcHeight - reflectionHeight,
        srcWidth, reflectionHeight, matrix, false,
    )
    val ret = src.config?.let { createBitmap(srcWidth, srcHeight + reflectionHeight, it) }
    val canvas = Canvas(ret!!)
    canvas.drawBitmap(src, 0f, 0f, null)
    canvas.drawBitmap(reflectionBitmap, 0f, (srcHeight + REFLECTION_GAP).toFloat(), null)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val shader = LinearGradient(
        0f, srcHeight.toFloat(),
        0f, (ret.height + REFLECTION_GAP).toFloat(),
        0x70FFFFFF,
        0x00FFFFFF,
        Shader.TileMode.MIRROR,
    )
    paint.shader = shader
    paint.xfermode = PorterDuffXfermode(Mode.DST_IN)
    canvas.drawRect(
        0f, (srcHeight + REFLECTION_GAP).toFloat(),
        srcWidth.toFloat(), ret.height.toFloat(), paint,
    )
    if (!reflectionBitmap.isRecycled) {
      reflectionBitmap.recycle()
    }
    if (recycle && !src.isRecycled) {
      src.recycle()
    }
    return ret
  }

  /**
   * 横向拼接两张Bitmap
   *
   * @param src              拼接的源图片
   * @param mergeBitmap      拼接的图片
   * @param spaceWidth       间距宽度【px】
   * @param dividerLineColor 分割线的颜色
   * @return
   */
  @JvmStatic
  fun addBitmapHorizontal(src: Bitmap?, mergeBitmap: Bitmap?, spaceWidth: Int, dividerLineColor: Int): Bitmap? {
    if (src == null || mergeBitmap == null) {
      return null
    }
    val width = src.width + mergeBitmap.width + spaceWidth
    val height = max(src.height, mergeBitmap.height)
    //val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val result = createBitmap(width, height)
    val canvas = Canvas(result)
    canvas.drawBitmap(src, 0f, 0f, null)
    //画分隔线
    canvas.drawLine(
        (src.width + spaceWidth / 2f), 0f,
        (src.width + spaceWidth / 2f), height.toFloat(),
        getDividerLinePaint(spaceWidth, dividerLineColor),
    )

    canvas.drawBitmap(mergeBitmap, (src.width + spaceWidth).toFloat(), 0f, null)
    return result
  }

  /**
   * 纵向拼接两张Bitmap
   *
   * @param src              拼接的源图片
   * @param mergeBitmap      拼接的图片
   * @param spaceWidth       间距宽度【px】
   * @param dividerLineColor 分割线的颜色
   * @return
   */
  @JvmStatic
  fun addBitmapVertical(src: Bitmap?, mergeBitmap: Bitmap?, spaceWidth: Int, dividerLineColor: Int): Bitmap? {
    if (src == null || mergeBitmap == null) {
      return null
    }
    val width = max(src.width, mergeBitmap.width)
    val height = src.height + mergeBitmap.height + spaceWidth
    val result = createBitmap(width, height)
    val canvas = Canvas(result)
    canvas.drawBitmap(src, 0f, 0f, null)
    //画分隔线
    canvas.drawLine(
        0f, (src.height + spaceWidth / 2f),
        width.toFloat(), (src.height + spaceWidth / 2f),
        getDividerLinePaint(spaceWidth, dividerLineColor),
    )

    canvas.drawBitmap(mergeBitmap, 0f, (src.height + spaceWidth).toFloat(), null)
    return result
  }

  /**
   * 获取分割线的画笔
   *
   * @param spaceWidth       分割线的宽度
   * @param dividerLineColor 分割线的颜色
   * @return
   */
  private fun getDividerLinePaint(spaceWidth: Int, dividerLineColor: Int): Paint {
    return Paint().apply {
      color = dividerLineColor
      strokeWidth = spaceWidth.toFloat()
    }
  }

  /**
   * 添加文字水印
   *
   * @param src      The source of bitmap.
   * @param content  The content of text.
   * @param textSize The size of text.
   * @param color    The color of text.
   * @param x        The x coordinate of the first pixel.
   * @param y        The y coordinate of the first pixel.
   * @return the bitmap with text watermarking
   */
  @JvmStatic
  fun addTextWatermark(
      src: Bitmap?,
      content: String?,
      textSize: Float,
      @ColorInt color: Int,
      x: Float,
      y: Float
  ): Bitmap? {
    return addTextWatermark(src, content, textSize, color, x, y, false)
  }

  /**
   * 添加文字水印
   *
   * @param src      The source of bitmap.
   * @param content  The content of text.
   * @param textSize The size of text.
   * @param color    The color of text.
   * @param x        The x coordinate of the first pixel.
   * @param y        The y coordinate of the first pixel.
   * @param recycle  True to recycle the source of bitmap, false otherwise.
   * @return the bitmap with text watermarking
   */
  @JvmStatic
  fun addTextWatermark(
      src: Bitmap?,
      content: String?,
      textSize: Float,
      @ColorInt color: Int,
      x: Float,
      y: Float,
      recycle: Boolean
  ): Bitmap? {
    if (isEmptyBitmap(src) || content == null) {
      return null
    }
    val ret = src?.config?.let { src.copy(it, true) }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val canvas = Canvas(ret!!)
    paint.color = color
    paint.textSize = textSize
    val bounds = Rect()
    paint.getTextBounds(content, 0, content.length, bounds)
    canvas.drawText(content, x, y + textSize, paint)
    if (recycle && !src.isRecycled) {
      src.recycle()
    }
    return ret
  }

  /**
   * 添加图片水印
   *
   * @param src       The source of bitmap.
   * @param watermark The image watermarking.
   * @param x         The x coordinate of the first pixel.
   * @param y         The y coordinate of the first pixel.
   * @param alpha     The alpha of watermark.
   * @return the bitmap with image watermarking
   */
  @JvmStatic
  fun addImageWatermark(
      src: Bitmap?,
      watermark: Bitmap?,
      x: Int,
      y: Int,
      alpha: Int
  ): Bitmap? {
    return addImageWatermark(src, watermark, x, y, alpha, false)
  }

  /**
   * 添加图片水印
   *
   * @param src       The source of bitmap.
   * @param watermark The image watermarking.
   * @param x         The x coordinate of the first pixel.
   * @param y         The y coordinate of the first pixel.
   * @param alpha     The alpha of watermark.
   * @param recycle   True to recycle the source of bitmap, false otherwise.
   * @return the bitmap with image watermarking
   */
  @JvmStatic
  fun addImageWatermark(
      src: Bitmap?,
      watermark: Bitmap?,
      x: Int,
      y: Int,
      alpha: Int,
      recycle: Boolean
  ): Bitmap? {
    if (isEmptyBitmap(src)) {
      return null
    }
    val ret = src?.config?.let { src.copy(it, true) }
    if (!isEmptyBitmap(watermark)) {
      val paint = Paint(Paint.ANTI_ALIAS_FLAG)
      val canvas = Canvas(ret!!)
      paint.alpha = alpha
      canvas.drawBitmap(watermark!!, x.toFloat(), y.toFloat(), paint)
    }
    if (recycle && !src?.isRecycled!!) {
      src.recycle()
    }
    return ret
  }

  /**
   * Return the alpha bitmap.
   *
   * @param src The source of bitmap.
   * @return the alpha bitmap
   */
  @JvmStatic
  fun toAlpha(src: Bitmap?): Bitmap? {
    return toAlpha(src, false)
  }

  /**
   * Return the alpha bitmap.
   *
   * @param src     The source of bitmap.
   * @param recycle True to recycle the source of bitmap, false otherwise.
   * @return the alpha bitmap
   */
  @JvmStatic
  fun toAlpha(src: Bitmap?, recycle: Boolean): Bitmap? {
    if (isEmptyBitmap(src)) {
      return null
    }
    val ret = src!!.extractAlpha()
    if (recycle && !src.isRecycled) {
      src.recycle()
    }
    return ret
  }

  /**
   * Return the gray bitmap.
   *
   * @param src The source of bitmap.
   * @return the gray bitmap
   */
  @JvmStatic
  fun toGray(src: Bitmap?): Bitmap? {
    return toGray(src, false)
  }

  /**
   * Return the gray bitmap.
   *
   * @param src     The source of bitmap.
   * @param recycle True to recycle the source of bitmap, false otherwise.
   * @return the gray bitmap
   */
  @JvmStatic
  fun toGray(src: Bitmap?, recycle: Boolean): Bitmap? {
    if (isEmptyBitmap(src)) {
      return null
    }
    val ret = src?.config?.let { createBitmap(src.width, src.height, it) }
    val canvas = Canvas(ret!!)
    val paint = Paint()
    val colorMatrix = ColorMatrix()
    colorMatrix.setSaturation(0f)
    val colorMatrixColorFilter = ColorMatrixColorFilter(colorMatrix)
    paint.colorFilter = colorMatrixColorFilter
    canvas.drawBitmap(src, 0f, 0f, paint)
    if (recycle && !src.isRecycled) {
      src.recycle()
    }
    return ret
  }

  /**
   * Return the blur bitmap fast.
   * <p>zoom out, blur, zoom in</p>
   *
   * @param src    The source of bitmap.
   * @param scale  The scale(0...1).
   * @param radius The radius(0...25).
   * @return the blur bitmap
   */
  @JvmStatic
  fun fastBlur(
      src: Bitmap?,
      @FloatRange(from = 0.0, to = 1.0, fromInclusive = false) scale: Float,
      @FloatRange(from = 0.0, to = 25.0, fromInclusive = false) radius: Float,
      context:Context
  ): Bitmap? {
    return fastBlur(src, scale, radius, false,context)
  }

  /**
   * Return the blur bitmap fast.
   * <p>zoom out, blur, zoom in</p>
   *
   * @param src     The source of bitmap.
   * @param scale   The scale(0...1).
   * @param radius  The radius(0...25).
   * @param recycle True to recycle the source of bitmap, false otherwise.
   * @return the blur bitmap
   */
  @JvmStatic
  fun fastBlur(
      src: Bitmap?,
      @FloatRange(from = 0.0, to = 1.0, fromInclusive = false) scale: Float,
      @FloatRange(from = 0.0, to = 25.0, fromInclusive = false) radius: Float,
      recycle: Boolean,
      context:Context
  ): Bitmap? {
    if (isEmptyBitmap(src)) {
      return null
    }
    val width = src!!.width
    val height = src.height
    val matrix = Matrix()
    matrix.setScale(scale, scale)
    var scaleBitmap =
        Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    val canvas = Canvas()
    val filter = PorterDuffColorFilter(
        Color.TRANSPARENT, Mode.SRC_ATOP,
    )
    paint.colorFilter = filter
    canvas.scale(scale, scale)
    canvas.drawBitmap(scaleBitmap, 0f, 0f, paint)
    scaleBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
      renderScriptBlur(scaleBitmap, radius, recycle,context) ?: scaleBitmap
    } else {
      stackBlur(scaleBitmap, radius.toInt(), recycle) ?: scaleBitmap
    }
    if (scale == 1f) {
      if (recycle && !src.isRecycled) {
        src.recycle()
      }
      return scaleBitmap
    }
    val ret = Bitmap.createScaledBitmap(scaleBitmap, width, height, true)
    if (!scaleBitmap.isRecycled) {
      scaleBitmap.recycle()
    }
    if (recycle && !src.isRecycled) {
      src.recycle()
    }
    return ret
  }

  /**
   * Return the blur bitmap using render script.
   *
   * @param src    The source of bitmap.
   * @param radius The radius(0...25).
   * @return the blur bitmap
   */
  @RequiresApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
  @JvmStatic
  fun renderScriptBlur(
      src: Bitmap?,
      @FloatRange(from = 0.0, to = 25.0, fromInclusive = false) radius: Float,
      context:Context
  ): Bitmap? {
    return renderScriptBlur(src, radius, false,context)
  }

  /**
   * Return the blur bitmap using render script.
   *
   * @param src     The source of bitmap.
   * @param radius  The radius(0...25).
   * @param recycle True to recycle the source of bitmap, false otherwise.
   * @return the blur bitmap
   */
  @RequiresApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
  @JvmStatic
  fun renderScriptBlur(
      src: Bitmap?,
      @FloatRange(from = 0.0, to = 25.0, fromInclusive = false) radius: Float,
      recycle: Boolean,
      context:Context

  ): Bitmap? {
    if (isEmptyBitmap(src)) {
      return null
    }
    var rs: RenderScript? = null
    val ret = if (recycle) src else src?.config?.let { src.copy(it, true) }
    ret ?: return null
    try {
      rs = RenderScript.create(context)
      rs.messageHandler = RenderScript.RSMessageHandler()
      val input = Allocation.createFromBitmap(
          rs,
          ret,
          Allocation.MipmapControl.MIPMAP_NONE,
          Allocation.USAGE_SCRIPT,
      )
      val output = Allocation.createTyped(rs, input.type)
      val blurScript = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))
      blurScript.setInput(input)
      blurScript.setRadius(radius)
      blurScript.forEach(output)
      output.copyTo(ret)
    } finally {
      rs?.destroy()
    }
    return ret
  }

  /**
   * Return the blur bitmap using stack.
   *
   * @param src    The source of bitmap.
   * @param radius The radius(0...25).
   * @return the blur bitmap
   */
  @JvmStatic
  fun stackBlur(src: Bitmap?, radius: Int): Bitmap? {
    return stackBlur(src, radius, false)
  }

  /**
   * Return the blur bitmap using stack.
   *
   * @param src     The source of bitmap.
   * @param radius  The radius(0...25).
   * @param recycle True to recycle the source of bitmap, false otherwise.
   * @return the blur bitmap
   */
  @SuppressLint("NewApi")
  @JvmStatic
  fun stackBlur(src: Bitmap?, radius: Int, recycle: Boolean): Bitmap? {
    if (isEmptyBitmap(src)) {
      return null
    }
    val ret = if (recycle) src else src?.config?.let { src.copy(it, true) }
    ret ?: return null
    var blurRadius = radius
    if (blurRadius < 1) {
      blurRadius = 1
    }
    val w = ret.width
    val h = ret.height

    val pix = IntArray(w * h)
    ret.getPixels(pix, 0, w, 0, 0, w, h)

    val wm = w - 1
    val hm = h - 1
    val wh = w * h
    val div = blurRadius + blurRadius + 1

    val r = IntArray(wh)
    val g = IntArray(wh)
    val b = IntArray(wh)
    var rsum: Int
    var gsum: Int
    var bsum: Int
    var x: Int
    var y: Int
    var i: Int
    var p: Int
    var yp: Int
    var yi: Int
    var yw: Int
    val vmin = IntArray(max(w, h))

    val divsum = (div + 1) shr 1
    var divsumFinal = divsum * divsum
    val dv = IntArray(256 * divsumFinal)
    for (i2 in 0 until 256 * divsumFinal) {
      dv[i2] = (i2 / divsumFinal)
    }

    yw = 0
    yi = 0

    val stack = Array(div) { IntArray(3) }
    var stackpointer: Int
    var stackstart: Int
    var sir: IntArray
    var rbs: Int
    val r1 = blurRadius + 1
    var routsum: Int
    var goutsum: Int
    var boutsum: Int
    var rinsum: Int
    var ginsum: Int
    var binsum: Int

    for (y2 in 0 until h) {
      rinsum = 0
      ginsum = 0
      binsum = 0
      routsum = 0
      goutsum = 0
      boutsum = 0
      rsum = 0
      gsum = 0
      bsum = 0
      i = -blurRadius
      while (i <= blurRadius) {
        p = pix[yi + min(wm, max(i, 0))]
        sir = stack[i + blurRadius]
        sir[0] = (p and 0xff0000) shr 16
        sir[1] = (p and 0x00ff00) shr 8
        sir[2] = (p and 0x0000ff)
        rbs = r1 - abs(i)
        rsum += sir[0] * rbs
        gsum += sir[1] * rbs
        bsum += sir[2] * rbs
        if (i > 0) {
          rinsum += sir[0]
          ginsum += sir[1]
          binsum += sir[2]
        } else {
          routsum += sir[0]
          goutsum += sir[1]
          boutsum += sir[2]
        }
        i++
      }
      stackpointer = blurRadius

      for (x2 in 0 until w) {

        r[yi] = dv[rsum]
        g[yi] = dv[gsum]
        b[yi] = dv[bsum]

        rsum -= routsum
        gsum -= goutsum
        bsum -= boutsum

        stackstart = stackpointer - blurRadius + div
        sir = stack[stackstart % div]

        routsum -= sir[0]
        goutsum -= sir[1]
        boutsum -= sir[2]

        if (y2 == 0) {
          vmin[x2] = min(x2 + blurRadius + 1, wm)
        }
        p = pix[yw + vmin[x2]]

        sir[0] = (p and 0xff0000) shr 16
        sir[1] = (p and 0x00ff00) shr 8
        sir[2] = (p and 0x0000ff)

        rinsum += sir[0]
        ginsum += sir[1]
        binsum += sir[2]

        rsum += rinsum
        gsum += ginsum
        bsum += binsum

        stackpointer = (stackpointer + 1) % div
        sir = stack[stackpointer % div]

        routsum += sir[0]
        goutsum += sir[1]
        boutsum += sir[2]

        rinsum -= sir[0]
        ginsum -= sir[1]
        binsum -= sir[2]

        yi++
      }
      yw += w
    }
    for (x3 in 0 until w) {
      rinsum = 0
      ginsum = 0
      binsum = 0
      routsum = 0
      goutsum = 0
      boutsum = 0
      rsum = 0
      gsum = 0
      bsum = 0
      yp = -blurRadius * w
      i = -blurRadius
      while (i <= blurRadius) {
        yi = max(0, yp) + x3

        sir = stack[i + blurRadius]

        sir[0] = r[yi]
        sir[1] = g[yi]
        sir[2] = b[yi]

        rbs = r1 - abs(i)

        rsum += r[yi] * rbs
        gsum += g[yi] * rbs
        bsum += b[yi] * rbs

        if (i > 0) {
          rinsum += sir[0]
          ginsum += sir[1]
          binsum += sir[2]
        } else {
          routsum += sir[0]
          goutsum += sir[1]
          boutsum += sir[2]
        }

        if (i < hm) {
          yp += w
        }
        i++
      }
      yi = x3
      stackpointer = blurRadius
      for (y3 in 0 until h) {
        // Preserve alpha channel: ( 0xff000000 & pix[yi] )
        pix[yi] = ((0xff000000 and pix[yi].toLong()) or ((dv[rsum] shl 16).toLong()) or ((dv[gsum] shl 8).toLong()) or dv[bsum].toLong()).toInt()

        rsum -= routsum
        gsum -= goutsum
        bsum -= boutsum

        stackstart = stackpointer - blurRadius + div
        sir = stack[stackstart % div]

        routsum -= sir[0]
        goutsum -= sir[1]
        boutsum -= sir[2]

        if (x3 == 0) {
          vmin[y3] = min(y3 + r1, hm) * w
        }
        p = x3 + vmin[y3]

        sir[0] = r[p]
        sir[1] = g[p]
        sir[2] = b[p]

        rinsum += sir[0]
        ginsum += sir[1]
        binsum += sir[2]

        rsum += rinsum
        gsum += ginsum
        bsum += binsum

        stackpointer = (stackpointer + 1) % div
        sir = stack[stackpointer]

        routsum += sir[0]
        goutsum += sir[1]
        boutsum += sir[2]

        rinsum -= sir[0]
        ginsum -= sir[1]
        binsum -= sir[2]

        yi += w
      }
    }
    ret.setPixels(pix, 0, w, 0, 0, w, h)
    return ret
  }

  /**
   * Save the bitmap.
   *
   * @param view     The view.
   * @param filePath The path of file.
   * @param format   The format of the image.
   * @return {@code true}: success<br>{@code false}: fail
   */
  @JvmStatic
  fun save(view: View?, filePath: String?, format: CompressFormat): Boolean {
    return save(view2Bitmap(view), getFileByPath(filePath), format, false)
  }

  /**
   * Save the bitmap.
   *
   * @param src      The source of bitmap.
   * @param filePath The path of file.
   * @param format   The format of the image.
   * @return {@code true}: success<br>{@code false}: fail
   */
  @JvmStatic
  fun save(src: Bitmap?, filePath: String?, format: CompressFormat): Boolean {
    return save(src, getFileByPath(filePath), format, false)
  }

  /**
   * Save the bitmap.
   *
   * @param src    The source of bitmap.
   * @param file   The file.
   * @param format The format of the image.
   * @return {@code true}: success<br>{@code false}: fail
   */
  @JvmStatic
  fun save(src: Bitmap?, file: File?, format: CompressFormat): Boolean {
    return save(src, file, format, false)
  }

  /**
   * Save the bitmap.
   *
   * @param src      The source of bitmap.
   * @param filePath The path of file.
   * @param format   The format of the image.
   * @param recycle  True to recycle the source of bitmap, false otherwise.
   * @return {@code true}: success<br>{@code false}: fail
   */
  @JvmStatic
  fun save(src: Bitmap?, filePath: String?, format: CompressFormat, recycle: Boolean): Boolean {
    return save(src, getFileByPath(filePath), format, recycle)
  }

  /**
   * Save the bitmap.
   *
   * @param src     The source of bitmap.
   * @param file    The file.
   * @param format  The format of the image.
   * @param recycle True to recycle the source of bitmap, false otherwise.
   * @return {@code true}: success<br>{@code false}: fail
   */
  @JvmStatic
  fun save(src: Bitmap?, file: File?, format: CompressFormat, recycle: Boolean): Boolean {
    if (isEmptyBitmap(src) || file == null || !createFileByDeleteOldFile(file)) {
      return false
    }
    var os: OutputStream? = null
    var ret = false
    try {
      os = BufferedOutputStream(FileOutputStream(file))
      ret = src!!.compress(format, 100, os)
      if (recycle && !src.isRecycled) {
        src.recycle()
      }
    } catch (e: IOException) {
      e.printStackTrace()
    } finally {
      os?.close()
    }
    return ret
  }

  /**
   * Save the bitmap.
   *
   * @param src      The source of bitmap.
   * @param targetOs The file of outputStream.
   * @param format   The format of the image.
   * @return {@code true}: success<br>{@code false}: fail
   */
  @JvmStatic
  fun save(src: Bitmap?, targetOs: OutputStream?, format: CompressFormat): Boolean {
    return save(src, targetOs, format, false)
  }

  /**
   * Save the bitmap.
   *
   * @param src      The source of bitmap.
   * @param targetOs The file of outputStream.
   * @param format   The format of the image.
   * @param recycle  True to recycle the source of bitmap, false otherwise.
   * @return {@code true}: success<br>{@code false}: fail
   */
  @JvmStatic
  fun save(src: Bitmap?, targetOs: OutputStream?, format: CompressFormat, recycle: Boolean): Boolean {
    if (isEmptyBitmap(src) || targetOs == null) {
      return false
    }
    var os: OutputStream? = null
    val ret: Boolean
    try {
      os = BufferedOutputStream(targetOs)
      ret = src!!.compress(format, 100, os)
      if (recycle && !src.isRecycled) {
        src.recycle()
      }
    } finally {
      os?.close()
    }
    return ret
  }

  /**
   * Return whether it is a image according to the file name.
   *
   * @param file The file.
   * @return {@code true}: yes<br>{@code false}: no
   */
  @JvmStatic
  fun isImage(file: File?): Boolean {
    return file != null && isImage(file.path)
  }

  /**
   * Return whether it is a image according to the file name.
   *
   * @param filePath The path of file.
   * @return {@code true}: yes<br>{@code false}: no
   */
  @JvmStatic
  fun isImage(filePath: String?): Boolean {
    if (filePath == null) return false
    val path = filePath.uppercase()
    return path.endsWith(".PNG") || path.endsWith(".JPG") ||
        path.endsWith(".JPEG") || path.endsWith(".BMP") ||
        path.endsWith(".GIF") || path.endsWith(".WEBP")
  }

  /**
   * Return the type of image.
   *
   * @param filePath The path of file.
   * @return the type of image
   */
  @JvmStatic
  fun getImageType(filePath: String?): String {
    return getImageType(getFileByPath(filePath))
  }

  /**
   * Return the type of image.
   *
   * @param file The file.
   * @return the type of image
   */
  @JvmStatic
  fun getImageType(file: File?): String {
    if (file == null) {
      return ""
    }
    var `is`: InputStream? = null
    try {
      `is` = FileInputStream(file)
      val type = getImageType(`is`)
      if (type != null) {
        return type
      }
    } catch (e: IOException) {
      e.printStackTrace()
    } finally {
      try {
        `is`?.close()
      } catch (e: IOException) {
        e.printStackTrace()
      }
    }
    return getFileExtension(file.absolutePath).uppercase()
  }

  private fun getFileExtension(filePath: String?): String {
    if (isSpace(filePath)) {
      return filePath ?: ""
    }
    val lastPoi = filePath!!.lastIndexOf('.')
    val lastSep = filePath.lastIndexOf(File.separator)
    if (lastPoi == -1 || lastSep >= lastPoi) {
      return ""
    }
    return filePath.substring(lastPoi + 1)
  }

  private fun getImageType(`is`: InputStream?): String? {
    if (`is` == null) {
      return null
    }
    try {
      val bytes = ByteArray(8)
      return if (`is`.read(bytes, 0, 8) != -1) getImageType(bytes) else null
    } catch (e: IOException) {
      e.printStackTrace()
      return null
    }
  }

  private fun getImageType(bytes: ByteArray): String? {
    when {
      isJPEG(bytes) -> return "JPEG"
      isGIF(bytes) -> return "GIF"
      isPNG(bytes) -> return "PNG"
      isBMP(bytes) -> return "BMP"
    }
    return null
  }

  private fun isJPEG(b: ByteArray): Boolean {
    return b.size >= 2 &&
        (b[0].toInt() and 0xFF) == 0xFF && (b[1].toInt() and 0xFF) == 0xD8
  }

  private fun isGIF(b: ByteArray): Boolean {
    return b.size >= 6 &&
        b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() &&
        b[2] == 'F'.code.toByte() && b[3] == '8'.code.toByte() &&
        (b[4] == '7'.code.toByte() || b[4] == '9'.code.toByte()) && b[5] == 'a'.code.toByte()
  }

  private fun isPNG(b: ByteArray): Boolean {
    return b.size >= 8 &&
        (b[0].toInt() and 0xFF) == 137 && (b[1].toInt() and 0xFF) == 80 &&
        (b[2].toInt() and 0xFF) == 78 && (b[3].toInt() and 0xFF) == 71 &&
        (b[4].toInt() and 0xFF) == 13 && (b[5].toInt() and 0xFF) == 10 &&
        (b[6].toInt() and 0xFF) == 26 && (b[7].toInt() and 0xFF) == 10
  }

  private fun isBMP(b: ByteArray): Boolean {
    return b.size >= 2 &&
        (b[0].toInt() and 0xFF) == 0x42 && (b[1].toInt() and 0xFF) == 0x4D
  }

  private fun isEmptyBitmap(src: Bitmap?): Boolean {
    return src == null || src.width == 0 || src.height == 0
  }

  ///////////////////////////////////////////////////////////////////////////
  // about compress
  ///////////////////////////////////////////////////////////////////////////

  /**
   * Return the compressed bitmap using scale.
   *
   * @param src       The source of bitmap.
   * @param newWidth  The new width.
   * @param newHeight The new height.
   * @return the compressed bitmap
   */
  @JvmStatic
  fun compressByScale(src: Bitmap?, newWidth: Int, newHeight: Int): Bitmap? {
    return scale(src, newWidth, newHeight, false)
  }

  /**
   * Return the compressed bitmap using scale.
   *
   * @param src       The source of bitmap.
   * @param newWidth  The new width.
   * @param newHeight The new height.
   * @param recycle   True to recycle the source of bitmap, false otherwise.
   * @return the compressed bitmap
   */
  @JvmStatic
  fun compressByScale(src: Bitmap?, newWidth: Int, newHeight: Int, recycle: Boolean): Bitmap? {
    return scale(src, newWidth, newHeight, recycle)
  }

  /**
   * Return the compressed bitmap using scale.
   *
   * @param src         The source of bitmap.
   * @param scaleWidth  The scale of width.
   * @param scaleHeight The scale of height.
   * @return the compressed bitmap
   */
  @JvmStatic
  fun compressByScale(src: Bitmap?, scaleWidth: Float, scaleHeight: Float): Bitmap? {
    return scale(src, scaleWidth, scaleHeight, false)
  }

  /**
   * Return the compressed bitmap using scale.
   *
   * @param src         The source of bitmap.
   * @param scaleWidth  The scale of width.
   * @param scaleHeight The scale of height.
   * @param recycle     True to recycle the source of bitmap, false otherwise.
   * @return he compressed bitmap
   */
  @JvmStatic
  fun compressByScale(src: Bitmap?, scaleWidth: Float, scaleHeight: Float, recycle: Boolean): Bitmap? {
    return scale(src, scaleWidth, scaleHeight, recycle)
  }

  /**
   * Return the compressed bitmap using quality.
   *
   * @param src     The source of bitmap.
   * @param quality The quality.
   * @return the compressed bitmap
   */
  @JvmStatic
  fun compressByQuality(src: Bitmap?, @IntRange(from = 0, to = 100) quality: Int): Bitmap? {
    return compressByQuality(src, quality, false)
  }

  /**
   * Return the compressed bitmap using quality.
   *
   * @param src     The source of bitmap.
   * @param quality The quality.
   * @param recycle True to recycle the source of bitmap, false otherwise.
   * @return the compressed bitmap
   */
  @JvmStatic
  fun compressByQuality(src: Bitmap?, @IntRange(from = 0, to = 100) quality: Int, recycle: Boolean): Bitmap? {
    if (isEmptyBitmap(src)) {
      return null
    }
    val baos = ByteArrayOutputStream()
    src!!.compress(CompressFormat.JPEG, quality, baos)
    val bytes = baos.toByteArray()
    if (recycle && !src.isRecycled) {
      src.recycle()
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
  }

  /**
   * Return the compressed bitmap using quality.
   *
   * @param src         The source of bitmap.
   * @param maxByteSize The maximum size of byte.
   * @return the compressed bitmap
   */
  @JvmStatic
  fun compressByQuality(src: Bitmap?, maxByteSize: Long): Bitmap? {
    return compressByQuality(src, maxByteSize, false)
  }

  /**
   * Return the compressed bitmap using quality.
   *
   * @param src         The source of bitmap.
   * @param maxByteSize The maximum size of byte.
   * @param recycle     True to recycle the source of bitmap, false otherwise.
   * @return the compressed bitmap
   */
  @JvmStatic
  fun compressByQuality(src: Bitmap?, maxByteSize: Long, recycle: Boolean): Bitmap? {
    if (isEmptyBitmap(src) || maxByteSize <= 0) {
      return null
    }
    val baos = ByteArrayOutputStream()
    src!!.compress(CompressFormat.JPEG, 100, baos)
    val bytes: ByteArray
    if (baos.size() <= maxByteSize) {
      bytes = baos.toByteArray()
    } else {
      baos.reset()
      src.compress(CompressFormat.JPEG, 0, baos)
      if (baos.size() >= maxByteSize) {
        bytes = baos.toByteArray()
      } else {
        // find the best quality using binary search
        var st = 0
        var end = 100
        var mid = 0
        while (st < end) {
          mid = (st + end) / 2
          baos.reset()
          src.compress(CompressFormat.JPEG, mid, baos)
          val len = baos.size().toLong()
          when {
            len == maxByteSize -> break
            len > maxByteSize -> end = mid - 1
            else -> st = mid + 1
          }
        }
        if (end == mid - 1) {
          baos.reset()
          src.compress(CompressFormat.JPEG, st, baos)
        }
        bytes = baos.toByteArray()
      }
    }
    if (recycle && !src.isRecycled) {
      src.recycle()
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
  }

  /**
   * Return the compressed bitmap using sample size.
   *
   * @param src        The source of bitmap.
   * @param sampleSize The sample size.
   * @return the compressed bitmap
   */
  @JvmStatic
  fun compressBySampleSize(src: Bitmap?, sampleSize: Int): Bitmap? {
    return compressBySampleSize(src, sampleSize, false)
  }

  /**
   * Return the compressed bitmap using sample size.
   *
   * @param src        The source of bitmap.
   * @param sampleSize The sample size.
   * @param recycle    True to recycle the source of bitmap, false otherwise.
   * @return the compressed bitmap
   */
  @JvmStatic
  fun compressBySampleSize(src: Bitmap?, sampleSize: Int, recycle: Boolean): Bitmap? {
    if (isEmptyBitmap(src)) {
      return null
    }
    val options = BitmapFactory.Options()
    options.inSampleSize = sampleSize
    val baos = ByteArrayOutputStream()
    src!!.compress(CompressFormat.JPEG, 100, baos)
    val bytes = baos.toByteArray()
    if (recycle && !src.isRecycled) {
      src.recycle()
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
  }

  /**
   * Return the compressed bitmap using sample size.
   *
   * @param src       The source of bitmap.
   * @param maxWidth  The maximum width.
   * @param maxHeight The maximum height.
   * @return the compressed bitmap
   */
  @JvmStatic
  fun compressBySampleSize(src: Bitmap?, maxWidth: Int, maxHeight: Int): Bitmap? {
    return compressBySampleSize(src, maxWidth, maxHeight, false)
  }

  /**
   * Return the compressed bitmap using sample size.
   *
   * @param src       The source of bitmap.
   * @param maxWidth  The maximum width.
   * @param maxHeight The maximum height.
   * @param recycle   True to recycle the source of bitmap, false otherwise.
   * @return the compressed bitmap
   */
  @JvmStatic
  fun compressBySampleSize(src: Bitmap?, maxWidth: Int, maxHeight: Int, recycle: Boolean): Bitmap? {
    if (isEmptyBitmap(src)) {
      return null
    }
    val options = BitmapFactory.Options()
    options.inJustDecodeBounds = true
    val baos = ByteArrayOutputStream()
    src!!.compress(CompressFormat.JPEG, 100, baos)
    val bytes = baos.toByteArray()
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    options.inSampleSize = calculateInSampleSize(options, maxWidth, maxHeight)
    options.inJustDecodeBounds = false
    if (recycle && !src.isRecycled) {
      src.recycle()
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
  }

  private fun getFileByPath(filePath: String?): File? {
    return if (isSpace(filePath)) null else File(filePath)
  }

  private fun createFileByDeleteOldFile(file: File?): Boolean {
    if (file == null) {
      return false
    }
    if (file.exists() && !file.delete()) {
      return false
    }
    if (!createOrExistsDir(file.parentFile)) {
      return false
    }
    return try {
      file.createNewFile()
    } catch (e: IOException) {
      e.printStackTrace()
      false
    }
  }

  private fun createOrExistsDir(file: File?): Boolean {
    return file != null && (file.exists() && file.isDirectory || file.mkdirs())
  }

  private fun isSpace(s: String?): Boolean {
    if (s == null) {
      return true
    }
    for (i in 0 until s.length) {
      if (!Character.isWhitespace(s[i])) {
        return false
      }
    }
    return true
  }

  /**
   * Return the sample size.
   *
   * @param options   The options.
   * @param maxWidth  The maximum width.
   * @param maxHeight The maximum height.
   * @return the sample size
   */
  private fun calculateInSampleSize(options: BitmapFactory.Options, maxWidth: Int, maxHeight: Int): Int {
    var height = options.outHeight
    var width = options.outWidth
    var inSampleSize = 1
    while ((width shr 1) >= maxWidth && (height shr 1) >= maxHeight) {
      inSampleSize = inSampleSize shl 1
      width = width shr 1
      height = height shr 1
    }
    return inSampleSize
  }

  private fun input2Byte(`is`: InputStream?): ByteArray? {
    if (`is` == null) {
      return null
    }
    try {
      val os = ByteArrayOutputStream()
      val b = ByteArray(1024) // MemoryConstants.KB = 1024
      var len: Int
      while (`is`.read(b, 0, 1024).also { len = it } != -1) {
        os.write(b, 0, len)
      }
      return os.toByteArray()
    } catch (e: IOException) {
      e.printStackTrace()
      return null
    } finally {
      try {
        `is`.close()
      } catch (e: IOException) {
        e.printStackTrace()
      }
    }
  }
}
