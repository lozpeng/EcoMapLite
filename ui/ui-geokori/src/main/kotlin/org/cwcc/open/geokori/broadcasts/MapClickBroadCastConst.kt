package org.cwcc.open.geokori.broadcasts

/**
 * 通过系统组件的形式广播地图单击事件，用于其他业务模块响应进行事件处理
 */
object MapClickBroadCastConst {
  // 地图点击事件 Action
  const val ACTION_MAP_CLICK = "org.cwcc.open.plugin.MAP_CLICK"

  // 参数 Key
  const val EXTRA_LAT = "lat"
  const val EXTRA_LNG = "lng"
  const val EXTRA_SCREEN_X = "screen_x"
  const val EXTRA_SCREEN_Y = "screen_y"
}
