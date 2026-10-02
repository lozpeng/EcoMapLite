package org.kori.plugin.geo.location

/**
 * 速度的一维卡尔曼滤波：融合 GPS 测量与加速度计预测。
 *
 * 状态量：速度 v (m/s)
 *  · predict: v += a · dt（加速度计积分）
 *  · update:  v ← 用 GPS 测量值按卡尔曼增益修正
 *  · decay:   v ← 向 0 衰减（长时间无测量时不再假设还在移动）
 *
 * 相比"直接信 GPS 速度"的优势：
 *  · 红灯急刹时，预测步先减 v，显示不会滞后一整秒才降到 0
 *  · GPS 抖动时不显示跳动
 *  · **真正调用 predict()** —— [LocationTracker] 每 100ms 用加速度计前向加速度积分
 *  · **参数按 profile 调整** —— 驾车时的 processNoise 更大（急加减速更频繁）
 * ## 并发安全
 *
 * [predict] 从 ticker 线程（Dispatchers.Default）以 10Hz 调用；
 * [update] 从主线程（LocationManager 回调）以 ~1Hz 调用。
 * 两者都对 `speed` / `variance` 做读-改-写，**必须加锁**——
 * 否则一次 predict 和一次 update 交错会导致速度值跳变。
 *
 * 加锁开销可忽略：每次调用只持锁几十纳秒的算术操作。
 */
class SpeedKalman {

    private val lock = Any()

    private var _speed: Double = 0.0
    private var _variance: Double = 1.0
    private var _processNoise: Double = 0.5
    private var _measurementNoise: Double = 1.2

    /** 当前速度（m/s）。加锁读取，跨线程可见。 */
    val speed: Double
        get() = synchronized(lock) { _speed }

    /**
     * 按活动类型调整过程噪声。
     *
     * 驾车状态变化快（急加减速频繁），模型噪声大；步行状态稳定，噪声小。
     */
    fun configure(profile: ActivityProfile) {
        synchronized(lock) {
            _processNoise = when (profile) {
                ActivityProfile.STILL -> 0.1
                ActivityProfile.WALKING -> 0.3
                ActivityProfile.CYCLING -> 0.6
                ActivityProfile.DRIVING -> 1.2
            }
        }
    }

    fun reset() {
        synchronized(lock) {
            _speed = 0.0
            _variance = 1.0
        }
    }

    /**
     * 用加速度计前向加速度 [fwdAccelMps2] 预测 [dtSeconds] 后的速度。
     *
     * @param fwdAccelMps2 前向加速度（m/s²）。正值加速，负值刹车。会被 clamp 到 [-8, 8]。
     */
    fun predict(fwdAccelMps2: Double, dtSeconds: Double) {
        synchronized(lock) {
            val a = fwdAccelMps2.coerceIn(-8.0, 8.0)
            _speed = (_speed + a * dtSeconds).coerceAtLeast(0.0)
            _variance += _processNoise * dtSeconds
        }
    }

    /**
     * 用 GPS 测量的速度 [measuredMps] 修正估计。
     *
     * 卡尔曼增益 = 当前方差 / (当前方差 + 测量噪声)。
     * 测量前不确定性越大，测量值的权重越高。
     */
    fun update(measuredMps: Double) {
        synchronized(lock) {
            val k = _variance / (_variance + _measurementNoise)
            _speed = _speed + k * (measuredMps - _speed)
            _variance = (1.0 - k) * _variance
        }
    }

    /**
     * 长时间无测量时向 0 衰减。
     *
     * @param dtSeconds 时长
     * @param tau 时间常数（秒）。默认 5s，dt=5s 后速度衰减到 37%。
     */
    fun decay(dtSeconds: Double, tau: Double = 5.0) {
        synchronized(lock) {
            val factor = kotlin.math.exp(-dtSeconds / tau)
            _speed *= factor
            _variance += _processNoise * dtSeconds
        }
    }
}