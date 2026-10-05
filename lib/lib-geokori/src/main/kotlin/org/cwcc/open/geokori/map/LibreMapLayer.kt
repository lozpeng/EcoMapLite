package org.cwcc.open.geokori.map

import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow


/**
 * 地图图层开关 UI 状态（[MapLayerManager.states] 暴露，Compose 直接 collect）。
 */
data class MapLayerState(
    val active: Boolean = false,
    val loading: Boolean = false,
)

/**
 * 地图图层抽象（框架级，与 [MapSession] 平级）。
 *
 * 生命周期由 [MapLayerManager] 驱动：
 *  ```
 *  register(ownerPluginId, layerId, factory)   // 插件侧注册一次
 *       ↓ 用户点击
 *  toggle(fullId, context)                      // 管理器自动解析并注入 Session
 *       ↓
 *  onAttach(session, context)  ← 自动注入，内部做 session.onReady 等就绪等待
 *       ↓ 关闭 / 插件卸载
 *  onDetach() / close()
 *  ```
 *
 * 子类约定：
 *  · 加载状态经 [notifyLoading]/[notifyLoaded]/[notifyFailed] 回报，
 *    管理器自动同步到 [MapLayerManager.states]（按钮转圈/高亮由此驱动）
 *  · 所有地图操作（source/layer/监听器）经注入的 [MapSession] 进出
 *  · 构造器不做重活；挂载逻辑放 [onAttach]
 */
abstract class LibreMapLayer {

    /** 图层显示名（图层控制面板 / 提示用）。 */
    abstract val displayName: String

    @Volatile
    private var closed = false

    @Volatile
    private var attachedSession: MapSession? = null

    /** 当前是否已挂载（attach 且未 close）。 */
    val isActive: Boolean
        get() = !closed && attachedSession != null

    // -------------------------------------------------------------------------
    // 框架调用入口（管理器驱动，勿直接调用）
    // -------------------------------------------------------------------------

    internal fun attachInternal(session: MapSession, context: Context) {
        if (closed || attachedSession != null) return
        attachedSession = session
        runCatching { onAttach(session, context.applicationContext) }
    }

    internal fun detachInternal() {
        if (attachedSession == null) return
        runCatching { onDetach() }
        attachedSession = null
    }

    /** 彻底关闭（不可复用；热更新/卸载路径）。 */
    fun close() {
        if (closed) return
        closed = true
        detachInternal()
    }

    // -------------------------------------------------------------------------
    // 子类实现
    // -------------------------------------------------------------------------

    /** 挂载：Session 已就绪注入。内部自行 session.onReady 等待地图就绪。 */
    protected open fun onAttach(session: MapSession, context: Context) {}

    /** 卸载：从地图精确移除 source/layer（管理器可再次 toggle 重建）。 */
    protected open fun onDetach() {}

    // -------------------------------------------------------------------------
    // 子类工具
    // -------------------------------------------------------------------------

    /** 当前注入的 Session（onAttach 之后非空）。 */
    protected fun currentSession(): MapSession? = attachedSession

    /** 回报：加载中状态变化（true=开始/进行中，false=结束）。 */
    protected fun notifyLoading(loading: Boolean) {
        MapLayerManager.notifyLayerState(this, loading = loading)
    }

    /** 回报：加载成功、图层已显示。 */
    protected fun notifyLoaded() {
        MapLayerManager.notifyLayerState(this, loaded = true)
    }

    /** 回报：加载失败。 */
    protected fun notifyFailed() {
        MapLayerManager.notifyLayerState(this, failed = true)
    }
}

/**
 * 地图图层管理器（框架级单例）。
 *
 * · 图层按 "ownerPluginId:layerId" 全限定 id 注册，天然隔离多插件
 * · toggle 时自动注入 Session：从 [MapRuntime.pluginSession] 按注册时的
 *   ownerPluginId 解析 —— UI 层无需再传 Session
 * · 状态（active/loading）经 StateFlow 暴露；Toast 内置
 * · 插件 onUnload 调 [closeAll]（可按插件批量）
 */
object MapLayerManager {

    private data class Registration(
        val ownerPluginId: String,
        val layerId: String,
        val factory: () -> LibreMapLayer,
    )

    private val registrations = mutableMapOf<String, Registration>()
    private val liveLayers = mutableMapOf<String, LibreMapLayer>()

    private val _states = MutableStateFlow<Map<String, MapLayerState>>(emptyMap())
    val states: StateFlow<Map<String, MapLayerState>> = _states.asStateFlow()

    /** 全限定 id。 */
    fun fullId(ownerPluginId: String, layerId: String) = "$ownerPluginId:$layerId"

    /** 注册图层工厂（一般在插件伴生对象 init / PluginEntryClass.onLoad；重复注册覆盖）。 */
    fun register(ownerPluginId: String, layerId: String, factory: () -> LibreMapLayer) {
        registrations[fullId(ownerPluginId, layerId)] =
            Registration(ownerPluginId, layerId, factory)
    }

    fun isRegistered(fullId: String): Boolean = registrations.containsKey(fullId)

    fun isActive(fullId: String): Boolean = liveLayers.containsKey(fullId)

    /**
     * 开关切换（UI 点击唯一入口）。
     *
     * @param fullId   "ownerPluginId:layerId"
     * @param context  用于 Toast / 图层 Context（取 applicationContext）
     * @return true 处理成功（含"未注册返回 false"以外的所有情况）
     */
    fun toggle(fullId: String, context: Context): Boolean {
        val reg = registrations[fullId] ?: return false
        val appCtx = context.applicationContext
        appContextRef = appCtx   // 供 notifyLayerState 的 Toast 使用

        // ★ 关闭分支
        liveLayers[fullId]?.let { layer ->
            runCatching { layer.detachInternal() }
            liveLayers.remove(fullId)
            update(fullId, MapLayerState(active = false, loading = false))
            Toast.makeText(appCtx, "已关闭「${layer.displayName}」", Toast.LENGTH_SHORT).show()
            return true
        }

        // ★ 开启分支：自动注入 Session
        val session = MapRuntime.pluginSession(reg.ownerPluginId)
        if (session == null) {
            Toast.makeText(appCtx, "插件未加载，请稍后再试", Toast.LENGTH_SHORT).show()
            return true
        }
        val layer = reg.factory()
        liveLayers[fullId] = layer
        update(fullId, MapLayerState(active = false, loading = true))
        Toast.makeText(appCtx, "「${layer.displayName}」加载中…", Toast.LENGTH_SHORT).show()
        layer.attachInternal(session, appCtx)
        return true
    }

    /** 插件卸载：关闭该插件的全部图层（不传 owner 则全部）。 */
    fun closeAll(ownerPluginId: String? = null) {
        val targets = liveLayers.filterKeys {
            ownerPluginId == null || it.startsWith("$ownerPluginId:")
        }
        targets.forEach { (id, layer) ->
            runCatching { layer.close() }
            liveLayers.remove(id)
            update(id, MapLayerState(active = false, loading = false))
        }
    }

    // -------------------------------------------------------------------------
    // 内部：图层状态回报
    // -------------------------------------------------------------------------

    internal fun notifyLayerState(
        layer: LibreMapLayer,
        loading: Boolean? = null,
        loaded: Boolean = false,
        failed: Boolean = false,
    ) {
        val id = liveLayers.entries.firstOrNull { it.value === layer }?.key ?: return
        val current = _states.value[id] ?: MapLayerState()
        val next = when {
            loaded -> current.copy(active = true, loading = false)
            failed -> current.copy(active = false, loading = false)
            loading != null -> current.copy(loading = loading)
            else -> current
        }
        update(id, next)
        if (loaded || failed) {
            val name = layer.displayName
            // Toast 由管理器统一弹出（applicationContext 已在 toggle 时存入）
            appContextRef?.let { ctx ->
                Toast.makeText(
                    ctx,
                    if (loaded) "「$name」已显示" else "「$name」数据加载失败",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    @Volatile
    private var appContextRef: Context? = null

    private fun update(fullId: String, state: MapLayerState) {
        _states.value += (fullId to state)
    }
}