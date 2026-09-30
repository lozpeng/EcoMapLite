

package com.combo.plugin.sample.common.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade


/**
 * 支持加载网络 SVG 和其他格式图片的基础组件 (优化版)
 *
 * @param model 图片数据，可以是 R.drawable.xxx，或者 网络url地址
 * @param modifier 修饰符
 * @param contentDescription 图片描述
 * @param contentScale 图片缩放模式
 */
@Composable
fun NetworkImage(
    model: Any?,
    modifier: Modifier = Modifier,
    contentDescription: String?,
    contentScale: ContentScale = ContentScale.Fit,
) {
    AsyncImage(
        model =
            ImageRequest
                .Builder(LocalContext.current)
                .data(model)
                .crossfade(true)
                .build(),
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
    )
}
