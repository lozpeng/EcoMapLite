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
 * ★ 新增第 4 种：框架级 —— [mainSession]，框架图层（MapLayerManager.registerFramework）
 *   的 attach 目标，不归属任何插件，owner = "framework"、
 *   pluginId = MapLayerManager.FRAMEWORK_OWNER，随地图生命周期由 [detach] 重建。
 *   懒创建：getter 首次访问时创建并通知 MapLayerManager 补挂待决框架图层；
 *   MapSession.onReady 是流式等待，主 Session 先于地图创建安全。
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

    // ---------- 框架主 Session（框架级图层 attach 目标） ----------

    @Volatile
    private var _mainSession: MapSession? = null

    /**
     * ★ 框架级图层的 Session（MapLayerManager.sessionForOwner 的 FRAMEWORK_OWNER
     *   分支取这里）。owner = "framework"，pluginId = MapLayerManager.FRAMEWORK_OWNER，
     *   不随任何插件生命周期；开关框架图层不要求任何插件加载。
     *
     * 懒创建 + 双检锁：首次访问时创建并回调 MapLayerManager.onMainSessionReady
     * 补挂"开关早于 Session 创建"的待决框架图层。由于 MapSession.onReady 基于
     * mapFlow/styleFlow 流式等待，主 Session 先于地图创建完全安全——
     * 地图就绪后框架图层的 onMapReady 自动触发。
     */
    val mainSession: MapSession
        get() {
            _mainSession?.let { return it }
            synchronized(this) {
                _mainSession?.let { return it }
                val session = MapSession(
                    owner = "framework",
                    pluginId = MapLayerManager.FRAMEWORK_OWNER,
                    parent = null,
                )
                _mainSession = session
                MapLayerManager.onMainSessionReady(session)
                return session
            }
        }

    /**
     * 可选：框架初始化早期显式预热（Application.onCreate）。
     * 非必需——mainSession 懒创建已覆盖全部时序；调用仅为让"待决补挂"
     * 在开关点击前完成，属于体验优化。幂等。
     */
    fun initMainSession() {
        mainSession   // 触发懒创建
    }

    /**
     * ★ 释放主 Session（一键清理其下框架图层经 Session 追踪的 source/layer/
     *   监听器），下次 [mainSession] 访问时重建并重新补挂。
     *   框架图层实例本身由 MapLayerManager.liveLayers 持有、detach 由开关驱动，
     *   这里只释放 Session 追踪的资源。
     */
    @Synchronized
    private fun releaseMainSession() {
        _mainSession?.let { runCatching { it.close() } }
        _mainSession = null
    }

    // ---------- 地图状态（由地图插件调用） ----------

    /** 由地图插件在 style 就绪时调用（mapView 供插件图层挂载渲染宿主） */
    fun attach(style: Style, map: MapLibreMap, mapView: org.maplibre.android.maps.MapView? = null) {
        // 重建场景（detach 已先调用）：主 Session 已在下方 mainSession 懒创建点
        // 或上次的 releaseMainSession 后重建 —— 先补挂/重挂框架图层再发布就绪流，
        // 保证 MapSession.onReady 的 combine 收到新值时图层已就位
        MapLayerManager.onMapReattached()
        _style.value = style
        _map.value = map
        mapViewRef = mapView?.let { WeakReference(it) }
    }

    /** 由地图插件在销毁时调用 */
    fun detach() {
        releaseMainSession()   // ★ 主 Session 随地图销毁重建（懒创建下次自动生效）
        _map.value = null
        _style.value = null
        mapViewRef = null
    }
}