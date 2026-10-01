package org.cwcc.open.geokori.map

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import java.util.concurrent.ConcurrentHashMap

/**
* 地图运行时（全局单例）。
*
* 与 lib-geokori 同属宿主 ClassLoader，所有插件共享同一实例。
*
* 三种 Session 使用姿势：
* 1. 插件级：PluginEntryClass 通过 [openPluginSession] 打开，onUnload 通过 [closePluginSession] 关闭。
* 2. 从任意位置查询：插件内业务对象通过 [pluginSession] 查询。
* 3. 独立 Session：临时场景通过 [openDetachedSession]，由调用方自行管理生命周期。
*/
object MapRuntime {

    private val _map = MutableStateFlow<MapLibreMap?>(null)
    private val _style = MutableStateFlow<Style?>(null)

    internal val mapFlow: StateFlow<MapLibreMap?> = _map.asStateFlow()
    internal val styleFlow: StateFlow<Style?> = _style.asStateFlow()

    val isReady: Boolean get() = _map.value != null && _style.value != null
    val currentMap: MapLibreMap? get() = _map.value
    val currentStyle: Style? get() = _style.value

    // ---------- 插件级 Session 注册表 ----------

    private val pluginSessions = ConcurrentHashMap<String, MapSession>()

    /**
     * 打开插件级 Session。若该 pluginId 已有 Session，会先关闭旧的。
     * 通常在 PluginEntryClass.onLoad 中调用一次。
     */
    fun openPluginSession(pluginId: String): MapSession {
        val old = pluginSessions.remove(pluginId)
        old?.close()
        val session = MapSession(owner = pluginId, parent = null)
        pluginSessions[pluginId] = session
        return session
    }

    /** 查询插件级 Session */
    fun pluginSession(pluginId: String): MapSession? = pluginSessions[pluginId]

    /** 关闭并注销插件级 Session。通常在 PluginEntryClass.onUnload 中调用。 */
    fun closePluginSession(pluginId: String) {
        pluginSessions.remove(pluginId)?.close()
    }

    /** 独立 Session，不注册到表，由调用方自行管理生命周期 */
    fun openDetachedSession(owner: String): MapSession =
        MapSession(owner = owner, parent = null)

    // ---------- 地图状态（由地图插件调用） ----------

    /** 由地图插件在 style 就绪时调用 */
    fun attach(style: Style, map: MapLibreMap) {
        _style.value = style
        _map.value = map
    }

    /** 由地图插件在销毁时调用 */
    fun detach() {
        _map.value = null
        _style.value = null
    }
}