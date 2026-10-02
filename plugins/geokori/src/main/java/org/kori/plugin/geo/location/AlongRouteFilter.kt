package org.kori.plugin.geo.location

/**
 * 沿路线的一维卡尔曼滤波。
 *
 * 状态量：沿路线位置 m（米）+ 其方差
 *  · predict: m += v · dt（用 [SpeedKalman] 的 v 积分）
 *  · update:  m ← 用吸附后的 fix 按卡尔曼增益修正
 *
 * 关键参数 [measurementNoise] 随 GPS 报告的精度变化：
 *  · 4m 精度的 fix → 高增益（几乎直接采用）
 *  · 25m 精度的 fix → 低增益（几乎不动，避免一车长的抖动）
 */
class AlongRouteFilter {

    var alongM: Double = 0.0
        private set

    private var variance: Double = 100.0
    private val processNoise: Double = 0.4

    fun reseed(measuredM: Double, accuracyM: Float? = null) {
        alongM = measuredM
        variance = (accuracyM?.toDouble()?.let { it * it } ?: 25.0).coerceAtLeast(1.0)
    }

    /** 沿路线前进 [deltaMeters]（由速度 × 时间得到）。 */
    fun predict(deltaMeters: Double, dtSeconds: Double) {
        alongM += deltaMeters.coerceAtLeast(0.0)
        variance += processNoise * dtSeconds
    }

    /**
     * 用测量值 [measuredM]（吸附到路线的投影点）修正估计。
     * [accuracyM] 越小（fix 越准），增益越高。
     */
    fun update(measuredM: Double, accuracyM: Float? = null) {
        val noise = (accuracyM?.toDouble()?.let { it * it } ?: 25.0).coerceAtLeast(1.0)
        val k = variance / (variance + noise)
        alongM += k * (measuredM - alongM)
        variance = (1.0 - k) * variance
    }

    /** 复位（路线切换、导航结束）。 */
    fun reset() {
        alongM = 0.0
        variance = 100.0
    }
}