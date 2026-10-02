package org.kori.plugin.geo.gnss

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * GNSS 卫星状态提供者（API 24+，参考 GPSTest 思路自实现的精简版）。
 *
 * ## 数据来源
 *
 * `LocationManager.registerGnssStatusCallback` 系统回调，每次卫星状态变化时
 * 把 `GnssStatus` 拷贝为不可变的 [Snapshot]（回调里的 status 对象会被系统复用，
 * 必须深拷贝）。
 *
 * ## 线程
 *
 * 系统回调在 main looper；[snapshot] 用 @Volatile 发布，读取端无需锁。
 * [start] 可在任意线程调用（内部绑定 main Handler）。
 *
 * ## 权限
 *
 * 需要 `ACCESS_FINE_LOCATION`。无权限时 [start] 静默失败（runCatching），
 * snapshot 保持 null。
 *
 * ## 与 GPSTest 的关系
 *
 * GPSTest（github.com/barbeau/gpstest，Apache-2.0）是完整的 GNSS 测试工具。
 * 本类只取其"卫星状态快照"这一核心数据流，未使用其代码；如需嵌入其
 * 星空图 / NMEA / 图表等完整功能，请遵循 Apache-2.0 保留署名与许可证。
 */
class GnssStatusProvider(context: Context) {

    private val appContext = context.applicationContext
    private val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val lock = Any()
    private var callback: GnssStatus.Callback? = null

    /** 最近一次卫星状态快照。null = 尚未收到数据 / 未启动 / 无权限。 */
    @Volatile
    var snapshot: Snapshot? = null
        private set

    /** 是否已注册回调。 */
    val isRunning: Boolean
        get() = synchronized(lock) { callback != null }

    /** 启动监听。幂等。API < 24 静默不可用。 */
    @SuppressLint("MissingPermission")
    fun start() {
        synchronized(lock) {
            if (callback != null) return
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return

            val cb = object : GnssStatus.Callback() {
                override fun onSatelliteStatusChanged(status: GnssStatus) {
                    // status 会被系统复用，深拷贝后再发布
                    runCatching { snapshot = status.toSnapshot() }
                }

                override fun onStarted() {}
                override fun onStopped() {
                    snapshot = null
                }
            }

            runCatching {
                // 两参重载：显式绑定 main looper，调用方无需在主线程
                lm.registerGnssStatusCallback(cb, mainHandler)
            }.onSuccess {
                callback = cb
            }
        }
    }

    /** 停止监听。幂等。 */
    fun stop() {
        synchronized(lock) {
            val cb = callback ?: return
            runCatching { lm.unregisterGnssStatusCallback(cb) }
            callback = null
            snapshot = null
        }
    }

    // =============================================================================================
    // 快照模型
    // =============================================================================================

    /** 一颗卫星的观测状态。 */
    data class Sat(
        /** 星座类型（GnssStatus.CONSTELLATION_*）。 */
        val constellation: Int,
        /** 卫星 ID（PRN/SVID）。 */
        val svid: Int,
        /** 方位角（度）。 */
        val azimuthDeg: Float,
        /** 仰角（度，>0 在天顶方向可见）。 */
        val elevationDeg: Float,
        /** 载噪比（dB-Hz，常见范围 10~45，越高信号越好）。 */
        val cn0DbHz: Float,
        /** 是否参与当前定位解算。 */
        val usedInFix: Boolean,
    )

    /** 一次卫星状态快照。 */
    data class Snapshot(
        val satellites: List<Sat>,
        /** 采集时间（elapsedRealtime 毫秒，仅用于判断新旧）。 */
        val capturedElapsedMs: Long,
        /** 参与定位的卫星数。 */
        val usedCount: Int,
        /** 最强信号（dB-Hz）。 */
        val maxCn0DbHz: Float,
    )

    private fun GnssStatus.toSnapshot(): Snapshot {
        val n = satelliteCount
        val list = ArrayList<Sat>(n)
        var used = 0
        var maxCn0 = 0f
        for (i in 0 until n) {
            val cn0 = getCn0DbHz(i)
            val inFix = usedInFix(i)
            if (inFix) used++
            if (cn0 > maxCn0) maxCn0 = cn0
            list.add(
                Sat(
                    constellation = getConstellationType(i),
                    svid = getSvid(i),
                    azimuthDeg = getAzimuthDegrees(i),
                    elevationDeg = getElevationDegrees(i),
                    cn0DbHz = cn0,
                    usedInFix = inFix,
                ),
            )
        }
        return Snapshot(
            satellites = list,
            capturedElapsedMs = SystemClock.elapsedRealtime(),
            usedCount = used,
            maxCn0DbHz = maxCn0,
        )
    }
}