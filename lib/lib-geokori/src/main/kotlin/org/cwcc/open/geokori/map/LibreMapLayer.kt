package org.cwcc.open.geokori.map

import android.content.Context
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.maps.MapLibreMap
import java.util.concurrent.ConcurrentHashMap


/**
 * 地图图层开关 UI 状态（[MapLayerManager.states] 暴露，Compose 直接 collect，key = fullId）。
 */
data class MapLayerState(
    val active: Boolean = false,
    val loading: Boolean = false,
)

/**
 * 地图图层抽象（框架级，与 [MapSession] 平级）。
 *
 * 注册方式（均无需 pluginId）：
 *  · 推荐：直接继承本类 —— 插件 Session 打开时框架扫描插件类，
 *    非抽象子类自动注册（可用 [@GeoKoriLayer] 指定 id，默认类名首字母小写）；
 *  · 手动：任意处调 MapLayerManager.register(layerId) { XxxLayer() }，
 *    在 Session 绑定时按 ClassLoader 归队。
 *
 * 生命周期：
 *  ```
 *  toggle(id, context)              // id 支持 fullId 或裸 layerId
 *       ↓
 *  onAttach(session, context)  ← Session 自动注入
 *       ↓ 框架自动 session.onReady
 *  onMapReady(map)             ← 地图就绪钩子（Context/Map 已由基类持有）
 *       ↓ 关闭 / 插件卸载
 *  onDetach() / close()
 *  ```
 *
 * 基类已托管（子类不再需要自己声明这些样板）：
 *  · [layerContext] / [map] / [pluginId]：attach / ready 后自动可用
 *  · [layerScope]：SupervisorJob 生命周期作用域，close() 不取消在途任务
 *  · [launchLoad] + [onMain]：异步加载模板（IO 切换、异常回报、isActive 守卫）
 *  · [trackMapListener]：监听器经 session.track 托管，插件卸载自动注销
 *
 * 子类约定：
 *  · 成功处调 [notifyLoaded]（失败由 [launchLoad] 自动回报）
 *  · 所有地图操作（source/layer/监听器）经注入的 [MapSession] 进出
 *  · 构造器不做重活；挂载逻辑放 [onAttach]，与地图相关的放 [onMapReady]
 *  · 可选实现 [RefreshableLayer] 以支持 MapLayerManager.refresh(id) 触发刷新
 */
abstract class LibreMapLayer {

    /** 图层显示名（图层控制面板 / 提示用）。 */
    abstract val displayName: String

    @Volatile
    private var closed = false

    @Volatile
    private var attachedSession: MapSession? = null

    private var appContext: Context? = null

    @Volatile
    private var mapRef: MapLibreMap? = null

    /** 当前是否已挂载（attach 且未 close）。 */
    val isActive: Boolean
        get() = !closed && attachedSession != null

    /** attach 后非空（applicationContext）。 */
    protected val layerContext: Context?
        get() = appContext

    /** onMapReady 后非空；detach 时自动清空。 */
    protected val map: MapLibreMap?
        get() = mapRef

    /** 所属插件 id（Session 注入后非空）。 */
    protected val pluginId: String?
        get() = attachedSession?.pluginId

    /**
     * 生命周期协程作用域（SupervisorJob + Main）。
     * 故意不在 close() 时取消：在途请求继续完成（如写本地缓存供下次秒开）。
     */
    protected val layerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // -------------------------------------------------------------------------
    // 框架调用入口（管理器驱动，勿直接调用）
    // -------------------------------------------------------------------------

    internal fun attachInternal(session: MapSession, context: Context) {
        if (closed || attachedSession != null) return
        attachedSession = session
        val ctx = context.applicationContext
        appContext = ctx
        runCatching {
            onAttach(session, ctx)
            // 框架统一等待地图就绪；子类改用 onMapReady 钩子
            session.onReady { _, m ->
                if (!isActive) return@onReady
                mapRef = m
                runCatching { onMapReady(m) }
            }
        }
    }

    internal fun detachInternal() {
        if (attachedSession == null) return
        runCatching { onDetach() }
        mapRef = null
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

    /** 挂载：Session 已就绪注入。轻量初始化放这里（起加载等）。 */
    protected open fun onAttach(session: MapSession, context: Context) {}

    /** 地图就绪：基类完成 session.onReady 等待后回调，[map] 已可安全使用。 */
    protected open fun onMapReady(map: MapLibreMap) {}

    /** 卸载：从地图精确移除 source/layer（管理器可再次 toggle 重建）。 */
    protected open fun onDetach() {}

    // -------------------------------------------------------------------------
    // 子类工具
    // -------------------------------------------------------------------------

    /** 当前注入的 Session（onAttach 之后非空）。 */
    protected fun currentSession(): MapSession? = attachedSession

    /** 监听器的托管注册：attach 挂到地图，detach/插件卸载时自动注销。 */
    protected fun <T : Any> trackMapListener(
        listener: T,
        attach: (T) -> Unit,
        detach: (T) -> Unit,
    ) {
        currentSession()?.track(listener, attach, detach)
    }

    /**
     * 异步加载模板（IO 调度器执行 [block]）：
     *  · 自动回报 loading 起点
     *  · 异常自动回报 notifyFailed()（CancellationException 原样上抛）
     *  · 成功后子类自行调 notifyLoaded()（通常配合 [onMain]）
     */
    protected fun launchLoad(block: suspend () -> Unit) {
        notifyLoading(true)
        layerScope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (isActive) notifyFailed()
                }
            }
        }
    }

    /** 切回主线程执行；已 close/detach 自动跳过。 */
    protected suspend fun onMain(block: () -> Unit) {
        withContext(Dispatchers.Main) {
            if (isActive) block()
        }
    }

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

    /**
     * 透明度调节（0f~1f，由 MapLayerManager.setOpacity 触发）。
     * 默认空实现（无透明度概念的图层忽略）；有透明度语义的子类覆盖，
     * 对其 style layers 的 opacity 属性做等比调整。
     */
    protected open fun applyAlpha(alpha: Float) {}

    /** 框架/管理器调用入口。 */
    fun setLayerAlpha(alpha: Float) = applyAlpha(alpha.coerceIn(0f, 1f))
}

/** 可选能力：支持外部触发刷新（[MapLayerManager.refresh] 入口）。 */
interface RefreshableLayer {
    fun refresh()
}

/**
 * 可选能力：支持软显隐（不卸载数据，仅隐藏/显示）。
 * BaseWildLifeLayer 已基于既有 setVisible 实现——子类无需重复劳动；
 * 未实现本接口的图层在控制面板上"关"= 整层卸载。
 */
interface VisibilityControllableLayer {
    fun setSoftVisible(visible: Boolean)
}

/**
 * 地图图层管理器（框架级单例）。
 *
 * 注册两阶段（业务侧零 pluginId、零注册代码——继承即注册）：
 *  · MapRuntime.openPluginSession 创建 Session 后回调 [onPluginSessionBound]：
 *      1) pending 区中 ClassLoader 匹配的手动注册归队（兼容旧写法）
 *      2) IO 线程扫描插件类（优先 ComboLite 类索引，兜底 dex 遍历），
 *         [LibreMapLayer] 非抽象子类自动注册
 *  · fullId = "pluginId:layerId"，扫描完成后 states 更新，UI 开关自动出现
 *  · 跨插件 layerId 冲突：响亮警告并跳过（不会静默覆盖）
 */
object MapLayerManager {

    private class Registration(
        val layerId: String,
        val factory: () -> LibreMapLayer,
        val factoryClassLoader: ClassLoader?,
    )

    /** 管理器内部作用域：Session 绑定时异步扫描插件类 */
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 待绑定：插件 Session 未注册前的手动注册（key = layerId） */
    private val pending = ConcurrentHashMap<String, Registration>()

    /** 正式注册（key = fullId） */
    private val registrations = ConcurrentHashMap<String, Registration>()

    /** layerId → fullId 反查（toggle/refresh 支持裸 layerId） */
    private val boundLayerIds = ConcurrentHashMap<String, String>()

    private val liveLayers = ConcurrentHashMap<String, LibreMapLayer>()

    /** 软显隐状态（false = 数据保留但隐藏）；toggle 关闭分支时清除 */
    private val softHidden = ConcurrentHashMap<String, Boolean>()

    /** 各图层透明度（0f~1f）；toggle 关闭分支时清除 */
    private val opacityOf = ConcurrentHashMap<String, Float>()

    private val _states = MutableStateFlow<Map<String, MapLayerState>>(emptyMap())
    val states: StateFlow<Map<String, MapLayerState>> = _states.asStateFlow()

    /**
     * 控制面板修订计数：states 之外的变更（软显隐/透明度）内容不体现在
     * MapLayerState 里，StateFlow 会按 equals 去重吞掉事件——
     * 故单独维护自增计数，UI 以此驱动重组。
     */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private fun bumpRevision() {
        _revision.value += 1
    }

    // -------------------------------------------------------------------------
    // 注册 / 绑定
    // -------------------------------------------------------------------------

    fun fullId(ownerPluginId: String, layerId: String) = "$ownerPluginId:$layerId"

    /**
     * 手动注册（可选）：无需 pluginId，Session 绑定时按 ClassLoader 自动归队。
     * 重复注册覆盖；同一插件内 layerId 必须唯一。
     * （继承即自动注册的场景不需要调本方法。）
     */
    fun register(layerId: String, factory: () -> LibreMapLayer) {
        pending[layerId] = Registration(layerId, factory, factory.javaClass.classLoader)
    }

    /** 旧重载保留：显式指定 owner，立即正式注册（特殊场景/调试）。 */
    @Synchronized
    fun register(ownerPluginId: String, layerId: String, factory: () -> LibreMapLayer) {
        val fid = fullId(ownerPluginId, layerId)
        registrations[fid] = Registration(layerId, factory, factory.javaClass.classLoader)
        boundLayerIds[layerId] = fid
        update(fid, _states.value[fid] ?: MapLayerState())
        pending.remove(layerId)
    }

    /**
     * 插件 Session 注册回调（MapRuntime.openPluginSession 调用，业务侧勿直接用）：
     *  1) pending 手动注册按 ClassLoader 归队
     *  2) IO 线程扫描插件类，继承 [LibreMapLayer] 的类自动注册
     */
    @Synchronized
    internal fun onPluginSessionBound(pluginId: String, pluginClassLoader: ClassLoader?) {
        // 1) pending 归队
        val bound = mutableListOf<String>()
        if (pluginClassLoader != null) {
            for ((layerId, reg) in pending) {
                if (reg.factoryClassLoader === pluginClassLoader) {
                    bindLocked(pluginId, layerId, reg)
                    bound.add(layerId)
                }
            }
        }
        bound.forEach { pending.remove(it) }
        if (pending.isNotEmpty()) {
            Log.w(
                "MapLayerManager",
                "以下手动注册未绑定到任何插件 Session: ${pending.keys}；" +
                        "请确认这些类由插件 ClassLoader 加载",
            )
        }

        // 2) 继承扫描自动注册（异步；完成后 states 更新，UI 自动出现开关）
        pluginClassLoader?.let { cl ->
            managerScope.launch {
                for ((layerId, factory) in MapLayerScanner.scan(pluginId, cl)) {
                    synchronized(this@MapLayerManager) {
                        val reg = Registration(layerId, factory, cl)
                        val fid = fullId(pluginId, layerId)
                        val existing = boundLayerIds[layerId]
                        when {
                            // 同插件重复绑定（Session 重开等）：幂等刷新
                            existing != null && existing.startsWith("$pluginId:") ->
                                bindLocked(pluginId, layerId, reg)
                            // 跨插件 layerId 冲突：fullId 注册照常（UI 用 states 的
                            // fullId key 开关，功能完整）；仅裸 layerId 反查让位给先
                            // 绑定者，并响亮警告引导显式 id
                            existing != null -> {
                                registrations[fid] = reg
                                update(fid, _states.value[fid] ?: MapLayerState())
                                Log.w(
                                    "MapLayerManager",
                                    "layerId「$layerId」在插件「${existing.substringBefore(':')}」" +
                                            "已存在；「$pluginId」的同名图层已按「$fid」注册" +
                                            "（UI 开关正常），但裸 layerId 调用将解析到前者。" +
                                            "建议用 @GeoKoriLayer 显式指定不同 id",
                                )
                            }
                            else ->
                                bindLocked(pluginId, layerId, reg)
                        }
                    }
                }
            }
        }
    }

    private fun bindLocked(pluginId: String, layerId: String, reg: Registration) {
        val fid = fullId(pluginId, layerId)
        registrations[fid] = reg
        boundLayerIds[layerId] = fid
        update(fid, _states.value[fid] ?: MapLayerState())
    }

    fun isRegistered(id: String): Boolean =
        registrations.containsKey(id) || boundLayerIds.containsKey(id)

    fun isActive(fullId: String): Boolean = liveLayers.containsKey(fullId)

    fun liveLayerIds(): Set<String> = liveLayers.keys.toSet()

    /** 已存活图层的 fullId 反查（图层自引用 fullId 用）。 */
    fun fullIdOf(layer: LibreMapLayer): String? =
        liveLayers.entries.firstOrNull { it.value === layer }?.key

    // -------------------------------------------------------------------------
    // 业务图层控制（供图层控制面板使用）
    // -------------------------------------------------------------------------

    /** 控制面板条目 */
    data class RegisteredLayer(
        val fullId: String,
        val layerId: String,
        val displayName: String?,
        val active: Boolean,
        val visible: Boolean,
        /** 当前透明度（0f~1f），控制面板滑杆回显用 */
        val alpha: Float,
    )

    /** 全部已注册业务图层（active=存活，visible=软显隐状态，alpha=透明度） */
    fun registeredLayers(): List<RegisteredLayer> =
        registrations.keys.sorted().map { fid ->
            RegisteredLayer(
                fullId = fid,
                layerId = fid.substringAfter(':'),
                displayName = liveLayers[fid]?.displayName,
                active = liveLayers.containsKey(fid),
                visible = softHidden[fid] != true,
                alpha = opacityOf[fid] ?: 1f,
            )
        }

    /**
     * 软显隐：图层保持存活、数据不卸载，仅切换可见性。
     * 图层实现 [VisibilityControllableLayer] 时生效返回 true；
     * 未实现（或图层未存活且要求显示）返回 false —— 调用方可回落 toggle。
     */
    fun setSoftVisible(id: String, visible: Boolean): Boolean {
        val fid = resolveFullId(id) ?: return false
        val layer = liveLayers[fid] as? VisibilityControllableLayer ?: return false
        runCatching { layer.setSoftVisible(visible) }
        if (visible) softHidden.remove(fid) else softHidden[fid] = true
        bumpRevision()   // states 内容无变化，靠 revision 驱动面板重组
        return true
    }

    /** 查询软显隐状态（默认 true） */
    fun isSoftVisible(id: String): Boolean = softHidden[resolveFullId(id)] != true

    /** 透明度调节（0f~1f）；图层未覆盖 applyAlpha 时为空操作，返回是否存活。 */
    fun setOpacity(id: String, alpha: Float): Boolean {
        val fid = resolveFullId(id) ?: return false
        val layer = liveLayers[fid] ?: return false
        val a = alpha.coerceIn(0f, 1f)
        runCatching { layer.setLayerAlpha(a) }
            .onFailure { Log.e("MapLayerManager", "setOpacity($fid, $a) 应用失败", it) }
        opacityOf[fid] = a
        bumpRevision()
        return true
    }

    // -------------------------------------------------------------------------
    // 开关 / 刷新 / 卸载
    // -------------------------------------------------------------------------

    /**
     * 开关切换（UI 点击唯一入口）。
     * [id] 支持 fullId（"pluginId:layerId"）或裸 layerId。
     */
    @Synchronized
    fun toggle(id: String, context: Context): Boolean {
        val fid = resolveFullId(id) ?: run {
            Log.w("MapLayerManager", "toggle 未找到图层「$id」（尚未扫描绑定？）")
            return false
        }
        val reg = registrations[fid] ?: return false
        val appCtx = context.applicationContext
        appContextRef = appCtx

        // ★ 关闭分支
        liveLayers[fid]?.let { layer ->
            runCatching { layer.detachInternal() }
            liveLayers.remove(fid)
            softHidden.remove(fid)
            opacityOf.remove(fid)
            update(fid, MapLayerState(active = false, loading = false))
            Toast.makeText(appCtx, "已关闭「${layer.displayName}」", Toast.LENGTH_SHORT).show()
            return true
        }

        // ★ 开启分支：Session 由 fullId 的 owner 段解析
        val session = MapRuntime.pluginSession(fid.substringBefore(':'))
        if (session == null) {
            Toast.makeText(appCtx, "插件未加载，请稍后再试", Toast.LENGTH_SHORT).show()
            return true
        }
        val layer = reg.factory()
        liveLayers[fid] = layer
        update(fid, MapLayerState(active = false, loading = true))
        Toast.makeText(appCtx, "「${layer.displayName}」加载中…", Toast.LENGTH_SHORT).show()
        layer.attachInternal(session, appCtx)
        return true
    }

    /** 触发存活图层的刷新（需实现 [RefreshableLayer]）；[id] 同 [toggle]。 */
    fun refresh(id: String): Boolean {
        val fid = resolveFullId(id) ?: return false
        val layer = liveLayers[fid] as? RefreshableLayer ?: return false
        return runCatching {
            layer.refresh()
            true
        }.getOrDefault(false)
    }

    /** 插件卸载：关闭该插件的全部图层（不传 owner 则全部）。 */
    @Synchronized
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
    // 内部
    // -------------------------------------------------------------------------

    private fun resolveFullId(id: String): String? =
        registrations.keys.firstOrNull { it == id } ?: boundLayerIds[id]

    /**
     * 查询图层开关状态（UI 合并 checked/loading 用）。
     * [id] 支持 fullId（"pluginId:layerId"）或裸 layerId；未注册/未绑定返回 null。
     */
    fun layerStateOf(id: String): MapLayerState? =
        _states.value[resolveFullId(id)]

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