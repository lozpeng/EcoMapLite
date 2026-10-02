package org.kori.plugin.geo.track

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * 传感器采样器。
 *
 * ## 设计
 *
 * 以独立 rate 采样加速度计 / 陀螺仪 / 磁力计，**缓存最近一次值**。
 * [snapshot] 被 [SegmentedTrackRecorder] 调用时把这些"最近值"打包进 [TrackPoint]。
 *
 * ## 为什么不是 sensor 回调里直接写 TrackPoint？
 *
 *  · GPS fix 频率 ~1 Hz，传感器频率 50 Hz，直接写会产生 50x 数据量
 *  · 传感器时间戳与 GPS 时间戳不同步，需要"就近取样"
 *  · 缓存方案：传感器更新缓存，GPS fix 读缓存——保证每个轨迹点都携带最新传感器数据
 *
 * ## 采样频率
 *
 * [SensorManager.SENSOR_DELAY_GAME] = ~50 Hz。用于事后姿态分析足够。
 * 若要更精细，可改 [SensorManager.SENSOR_DELAY_FASTEST]（~200 Hz），但耗电明显。
 *
 * ## 硬件缺失降级
 *
 * 部分设备（模拟器、便宜机型）无传感器。所有字段为 `null` 是合法的——
 * [snapshot] 返回 null 数组，[TrackPoint] 对应字段为 null，CSV 空字段。
 */
class SensorSampler(context: Context) {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    // ---- 最近一次采样值（每次覆盖） ----
    @Volatile private var accel: FloatArray? = null
    @Volatile private var gyro: FloatArray? = null
    @Volatile private var mag: FloatArray? = null

    // ---- 硬件存在性 ----
    @Volatile var hasAccelSensor: Boolean = false
        private set
    @Volatile var hasGyroSensor: Boolean = false
        private set
    @Volatile var hasMagSensor: Boolean = false
        private set

    /** 是否至少有一个传感器就绪。 */
    val hasAnySensor: Boolean
        get() = hasAccelSensor || hasGyroSensor || hasMagSensor

    // ---- 监听器 ----

    private val accelListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            accel = floatArrayOf(e.values[0], e.values[1], e.values[2])
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    private val gyroListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            gyro = floatArrayOf(e.values[0], e.values[1], e.values[2])
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    private val magListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            mag = floatArrayOf(e.values[0], e.values[1], e.values[2])
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    // =============================================================================================
    // 生命周期
    // =============================================================================================

    /**
     * 注册所有可用传感器。
     *
     * 幂等——多次调用只会注册一次（用 `hasXxxSensor` 标志防重）。
     */
    fun start() {
        if (!hasAccelSensor) {
            sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let {
                sm.registerListener(accelListener, it, SensorManager.SENSOR_DELAY_GAME)
                hasAccelSensor = true
            }
        }
        if (!hasGyroSensor) {
            sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
                sm.registerListener(gyroListener, it, SensorManager.SENSOR_DELAY_GAME)
                hasGyroSensor = true
            }
        }
        if (!hasMagSensor) {
            sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
                sm.registerListener(magListener, it, SensorManager.SENSOR_DELAY_GAME)
                hasMagSensor = true
            }
        }
    }

    /** 注销所有监听器，清空缓存。 */
    fun stop() {
        sm.unregisterListener(accelListener)
        sm.unregisterListener(gyroListener)
        sm.unregisterListener(magListener)
        accel = null
        gyro = null
        mag = null
        hasAccelSensor = false
        hasGyroSensor = false
        hasMagSensor = false
    }

    // =============================================================================================
    // 快照
    // =============================================================================================

    /**
     * 快照当前所有传感器的最近值。
     *
     * 返回的 [SensorSnapshot] 内字段可能为 null（无硬件 / 未采样到）。
     * 每次返回**新的数组副本**，调用方可以安全持有。
     */
    fun snapshot(): SensorSnapshot = SensorSnapshot(
        accel = accel?.copyOf(),
        gyro = gyro?.copyOf(),
        mag = mag?.copyOf(),
    )

    /**
     * 传感器快照。
     */
    data class SensorSnapshot(
        /** 线性加速度 [x, y, z]（m/s²），null = 不可用。 */
        val accel: FloatArray?,
        /** 陀螺仪 [x, y, z]（rad/s），null = 不可用。 */
        val gyro: FloatArray?,
        /** 磁力计 [x, y, z]（μT），null = 不可用。 */
        val mag: FloatArray?,
    ) {
        /** 是否包含任何数据。 */
        val hasAny: Boolean
            get() = accel != null || gyro != null || mag != null

        // FloatArray 的 data class equals 是引用比较，重写为值比较
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SensorSnapshot) return false
            return accel.contentEqualsOrNull(other.accel) &&
                    gyro.contentEqualsOrNull(other.gyro) &&
                    mag.contentEqualsOrNull(other.mag)
        }

        override fun hashCode(): Int {
            var result = accel?.contentHashCode() ?: 0
            result = 31 * result + (gyro?.contentHashCode() ?: 0)
            result = 31 * result + (mag?.contentHashCode() ?: 0)
            return result
        }

        private fun FloatArray?.contentEqualsOrNull(other: FloatArray?): Boolean = when {
            this == null && other == null -> true
            this == null || other == null -> false
            else -> this.contentEquals(other)
        }
    }
}