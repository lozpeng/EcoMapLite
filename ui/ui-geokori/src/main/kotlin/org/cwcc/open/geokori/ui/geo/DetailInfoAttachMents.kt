package org.cwcc.open.geokori.ui.geo

import android.app.Dialog
import android.content.Context
import android.view.Window
import android.view.WindowManager
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestOptions
import com.github.chrisbanes.photoview.PhotoView
import org.cwcc.open.geokori.ui.VideoViewPlayerWithFullscreen

@Composable
fun GeoDetailInfoAttachments(detailInfo: GeoDetailInfo,apiUrl:String,context: Context){
  val imgAttIds = detailInfo.getImgAttIds()
  val attachmentId = detailInfo.getAttachmentId()
  val attachmentIsVideo = detailInfo.attachmentIsVideo() ?: return
  Box(
      modifier = Modifier
          .fillMaxWidth()
          .height(200.dp)  // 固定高度 250dp
          .padding(horizontal = 16.dp)  // 添加左右边距
          .clip(RoundedCornerShape(12.dp))  // 统一圆角
          .background(MaterialTheme.colorScheme.surfaceVariant),  // 背景色占位
  ) {
    when {
      attachmentIsVideo -> {
        VideoViewPlayerWithFullscreen(
            url = "$apiUrl$attachmentId",
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp)),
            autoPlay = true,
            title = "视频"
        )
      }
      imgAttIds.size == 1 -> {
        val attachmentUrl = "$apiUrl$attachmentId"
        AndroidView(
            factory = { ctx ->
              PhotoView(ctx).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setOnClickListener {
                  // ✅ 点击全屏浏览，直接复用 Glide 缓存
                  showFullscreenImage(context, attachmentUrl)
                }
                maximumScale = 3.0f
                minimumScale = 0.5f
              }
            },
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp)),
            update = { photoView ->
              // ✅ 使用 Glide 加载图片并缓存
              Glide.with(context)
                  .load(attachmentUrl)
                  .apply(
                      RequestOptions()
                          .placeholder(android.R.drawable.ic_menu_gallery)  // 占位图
                          .error(android.R.drawable.ic_menu_report_image)   // 错误图
                          .diskCacheStrategy(DiskCacheStrategy.ALL)
                          .skipMemoryCache(false)
                  )
                  .transition(DrawableTransitionOptions.withCrossFade())  // 淡入动画
                  .into(photoView)
            }
        )
      }
      imgAttIds.size > 1 -> {
        //这里搞个轮播器
//            val imageUrls = imgAttIds.map { "$apiUrl/$it" } // 根据实际URL格式调整
//            if (imageUrls.isNotEmpty())
//            ImageBanner(
//                imageSize = imageUrls.size,
//                modifier = Modifier
//                    .fillMaxWidth()
//                    .height(200.dp),
//                autoScroll = true, // 开启自动轮播
//                autoScrollTime = 3000L, // 轮播间隔，单位毫秒
//                imageContent = { index -> // index 是当前页面的索引
//                  val url = imageUrls[index]
//                  // 使用 AsyncImage (Coil) 加载网络图片
//                  AsyncImage(
//                      model = url,
//                      contentDescription = "附件图片 $index",
//                      modifier = Modifier
//                          .fillMaxWidth()
//                          .height(200.dp)
//                          // 👇 点击事件：显示全屏图片
//                          .clickable {
//                            showFullscreenImage(context,url) // 调用全屏显示函数
//                          }
//                  )
//                }
//            )
      }
      else -> {

      }
    }
  }

}

private fun showFullscreenImage(context: android.content.Context, url: String) {
  // ✅ 使用 Android 原生 Dialog，不包裹 ComposeView
  val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
  dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
  dialog.window?.apply {
    setFlags(
        WindowManager.LayoutParams.FLAG_FULLSCREEN,
        WindowManager.LayoutParams.FLAG_FULLSCREEN,
    )
    //setBackgroundDrawableResource(android.R.color.black)
    setLayout(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT
    )
  }
  // ✅ 直接使用 PhotoView
  val photoView = PhotoView(context).apply {
    scaleType = ImageView.ScaleType.FIT_CENTER
    setOnClickListener { dialog.dismiss() }
    maximumScale = 3.0f
    minimumScale = 0.5f
  }
  dialog.setContentView(photoView)
  dialog.show()

  // ✅ 使用 Glide 加载图片
  Glide.with(context)
      .load(url)
      .apply(
          RequestOptions()
              .placeholder(android.R.drawable.ic_menu_gallery)  // 占位图
              .error(android.R.drawable.ic_menu_report_image)   // 错误图
              .diskCacheStrategy(DiskCacheStrategy.ALL)
              .skipMemoryCache(false)
      )
      .transition(DrawableTransitionOptions.withCrossFade())  // 淡入动画
      .into(photoView)
}
