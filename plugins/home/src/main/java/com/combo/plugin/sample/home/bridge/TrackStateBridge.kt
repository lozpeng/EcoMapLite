package com.combo.plugin.sample.home.bridge

import android.util.Log
import com.combo.core.runtime.PluginManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.cwcc.open.geokori.api.ITrackRecordingStateApi
import timber.log.Timber

/**
 * home 插件侧：按需解析 geokori 的 [ITrackRecordingStateApi]。
 *
 * 命令发送不需要本类（直接 sendInternalBroadcast）；
 * 这里只解决"状态流订阅"一件事。
 *
 * ★ 注意两处必须与实际代码一致：
 *  1. IMPL_CLASS 的完全限定名 = geokori 插件中实现类的真实包名
 *  2. ITrackRecordingStateApi 的 import = 宿主 lib 中的真实包名（两端必须相同）
 */
object TrackStateBridge {

    private const val TAG = "TrackStateBridge"

    /**
     * ★★ 关键：必须与 geokori 插件中 GeoTrackStateApiImpl 的实际包名一致。
     * 该类目前定义在 org.kori.plugin.geo 包下（与 TrackCommandReceiver 同文件），
     * 所以这里是 org.kori.plugin.geo.GeoTrackStateApiImpl。
     * 若后续把实现类挪到 org.kori.plugin.geo.api 包，这里要同步改。
     */
    private const val IMPL_CLASS = "org.kori.plugin.geo.GeoTrackStateApiImpl"

    /**
     * 即时解析。PluginManager 内部走全局类索引，O(1) 开销。
     * geokori 未加载 / 类索引无此类 / 接口包名不一致 → 返回 null（不抛异常）。
     */
    private val api: ITrackRecordingStateApi?
        get() = runCatching {
            PluginManager.getInterface(ITrackRecordingStateApi::class.java, IMPL_CLASS)
        }.onFailure {
            Timber.tag(TAG).w("获取轨迹状态 API 失败: ${it.message}")
        }.getOrNull()

    /** geokori 的轨迹状态服务是否可用（UI 可据此决定按钮默认态）。 */
    private val _available = MutableStateFlow(false)
    val available: StateFlow<Boolean> = _available

    fun refreshAvailability() {
        val ok = api != null
        if (ok != _available.value) {
            _available.value = ok
            Timber.tag(TAG).d("轨迹状态 API 可用性: $ok")
        }
    }

    /** 订阅录制状态；API 不可用时返回常量 false 流。 */
    fun isRecordingFlow(): StateFlow<Boolean> =
        api?.isRecording ?: MutableStateFlow(false)

    fun isPausedFlow(): StateFlow<Boolean> =
        api?.isPaused ?: MutableStateFlow(false)
}