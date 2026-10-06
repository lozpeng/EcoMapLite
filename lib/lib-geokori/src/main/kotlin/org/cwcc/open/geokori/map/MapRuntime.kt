package org.cwcc.open.geokori.map

import com.combo.core.runtime.PluginManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.lang.ref.WeakReference
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
 *
 * 插件图层绑定：[openPluginSession] 创建 Session 后自动回调
 * [MapLayerManager.onPluginSessionBound]——pending 注册归队 + 扫描插件
 * ClassLoader 完成"继承即注册"。
 */
object MapRuntime {

    private val _map = MutableStateFlow<MapLibreMap?>(null)
    private val _style = MutableStateFlow<Style?>(null)

    internal val mapFlow: StateFlow<MapLibreMap?> = _map.asStateFlow()
    internal val styleFlow: StateFlow<Style?> = _style.asStateFlow()

    val isReady: Boolean get() = _map.value != null && _style.value != null
    val currentMap: MapLibreMap? get() = _map.value
    val currentStyle: Style? get() = _style.value

    /** 地图控件本体（由地图插件 attach 时传入）——插件图层往其上挂渲染宿主用。
     *  弱引用持有：避免静态单例强引用 MapView（renderView 链）导致内存泄漏。 */
    @Volatile
    private var mapViewRef: WeakReference<MapView>? = null

    val currentMapView: MapView?
        get() = mapViewRef?.get()

    // ---------- 插件级 Session 注册表 ----------

    private val pluginSessions = ConcurrentHashMap<String, MapSession>()

    /**
     * 打开插件级 Session。若该 pluginId 已有 Session，会先关闭旧的。
     * 通常在 PluginEntryClass.onLoad 中调用一次。
     *
     * 副作用：通知 [MapLayerManager] 绑定该插件的图层
     * （手动 pending 注册归队 + 插件类扫描自动注册）。
     */
    fun openPluginSession(pluginId: String): MapSession {
        val old = pluginSessions.remove(pluginId)
        old?.close()
        val session = MapSession(owner = pluginId, pluginId = pluginId, parent = null)
        pluginSessions[pluginId] = session
        MapLayerManager.onPluginSessionBound(pluginId, pluginClassLoader(pluginId))
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
        MapSession(owner = owner, pluginId = "", parent = null)

    /**
     * 解析插件 ClassLoader（供图层扫描 / pending 归队用）。
     * 取自 ComboLite 已加载插件信息；插件未加载时返回 null（扫描跳过，不崩）。
     */
    private fun pluginClassLoader(pluginId: String): ClassLoader? =
        runCatching { PluginManager.getPluginInfo(pluginId)?.classLoader }.getOrNull()

    // ---------- 地图状态（由地图插件调用） ----------

    /** 由地图插件在 style 就绪时调用（mapView 供插件图层挂载渲染宿主） */
    fun attach(style: Style, map: MapLibreMap, mapView: org.maplibre.android.maps.MapView? = null) {
        _style.value = style
        _map.value = map
        mapViewRef = mapView?.let { WeakReference(it) }
    }

    /** 由地图插件在销毁时调用 */
    fun detach() {
        _map.value = null
        _style.value = null
        mapViewRef = null
    }
}