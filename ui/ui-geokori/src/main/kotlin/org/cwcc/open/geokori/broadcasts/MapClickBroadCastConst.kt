package org.cwcc.open.geokori.broadcasts

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.ui.unit.DpOffset
import org.maplibre.android.geometry.LatLng

/**
 * 通过系统组件的形式广播地图单击事件，用于其他业务模块响应进行事件处理
 */
object MapClickBroadCastConst {
  const val ACTION_MAP_CLICK = "org.kori.plugin.geo.action.MAP_CLICK"

  const val EXTRA_LAT = "extra_lat"
  const val EXTRA_LNG = "extra_lng"
  const val EXTRA_SCREEN_X = "extra_screen_x"
  const val EXTRA_SCREEN_Y = "extra_screen_y"
}

/**
 * 发送地图点击广播，供其它界面（Compose 屏、宿主页面等）接收处理。
 *
 * 与 [MapLibreMapView] 的 `onMapClick` 参数配合使用：
 *
 * ```kotlin
 * MapLibreMapView(
 *     ...,
 *     onMapClick = { pos, screenPos -> context.sendMapClickBroadcast(pos, screenPos) },
 * )
 * ```
 *
 * ## 为什么不消费事件
 *
 * 地图点击监听器返回 `true` 会吞掉事件（图层/标注点击失效）。
 * [MapLibreMapView] 内部注册的单击监听器**永远返回 false**，
 * 本函数只负责"通知"，不影响任何图层点击功能。
 *
 * ## 安全性
 *
 * 广播未声明 receiver permission，属于应用内广播（默认 exported=false 的
 * 动态 Receiver 才能收到）。如需跨应用，请改用显式 Intent 或加权限。
 */
fun Context.sendMapClickBroadcast(
  position: LatLng,
  screenPosition: DpOffset,
) {
  try {
    val clickIntent = Intent(MapClickBroadCastConst.ACTION_MAP_CLICK).apply {
      putExtra(MapClickBroadCastConst.EXTRA_LAT, position.latitude)
      putExtra(MapClickBroadCastConst.EXTRA_LNG, position.longitude)
      putExtra(MapClickBroadCastConst.EXTRA_SCREEN_X, screenPosition.x.value)
      putExtra(MapClickBroadCastConst.EXTRA_SCREEN_Y, screenPosition.y.value)
      // 显式 setPackage 限制在本应用内分发，避免泄漏
      `package` = this@sendMapClickBroadcast.packageName
    }
    sendBroadcast(clickIntent)
    Log.d("MapClick", "广播已发送: lat=${position.latitude}, lng=${position.longitude}")
  } catch (e: Exception) {
    Log.e("MapClick", "发送广播失败: ${e.message}")
  }
}