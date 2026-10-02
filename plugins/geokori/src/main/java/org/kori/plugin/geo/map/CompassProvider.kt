package org.kori.plugin.geo.map

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs

/**
 * 手机罗盘朝向提供者（旋转矢量主源 + 加速度计/磁力计辅助纠偏）。
 *
 * ## 数据来源与融合策略
 *
 *  · **主源**：`TYPE_ROTATION_VECTOR`（陀螺+加速度计+磁力计融合，姿态平滑、抗短时抖动）
 *  · **辅源**：`TYPE_ACCELEROMETER` + `TYPE_MAGNETIC_FIELD` 经典解算
 *    （`getRotationMatrix` + `getOrientation`，天然倾斜补偿）
 *  · **融合**：两路方位角按 [AM_WEIGHT]（25%）加权融合——旋转矢量的方位角会随陀螺
 *    积分缓慢漂移，磁力计解算负责持续拉回真北；无旋转矢量的设备直接退化为纯
 *    加速度计+磁力计方案（低端机/部分模拟器兼容性提升）
 *
 * ## 平放防抖
 *
 * 手机接近水平时（姿态角 pitch/roll 超阈值），方位角可观测性变差、抖动加剧。
 * 此时自动切换为更强的时间低通（[FILTER_FACTOR_FLAT]），显著抑制地图来回摆动。
 *
 * ## 输出约定
 *
 * [headingDeg]：手机**顶部指向的方位角**（0=北，顺时针，与地图 bearing 一致），
 * 已做跨 0/360 最短角路径的时间低通。
 *
 * ## 注意
 *
 * orientation[0] 以设备**自然朝向**为参考（手机=竖屏）。平板横屏自然朝向的设备
 * 如需精确罗盘，应加 `SensorManager.remapCoordinateFrame(...)`。
 */
class CompassProvider(context: Context) {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    // ---- 旋转矢量主源 ----
    private val rotMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    // ---- 加速度计 + 磁力计辅源 ----
    private val accel = FloatArray(3)
    private val magnet = FloatArray(3)
    private val amMatrix = FloatArray(9)
    private var hasAccel = false
    private var hasMagnet = false

    /** 当前手机朝向（度，0=北，顺时针）。 */
    @Volatile
    var headingDeg: Float = 0f
        private set

    /** 是否有可用传感器（旋转矢量 或 加速度计+磁力计 至少满足其一）。 */
    @Volatile
    var isSupported: Boolean = false
        private set

    private var started = false

    // =============================================================================================
    // 监听器
    // =============================================================================================

    private val rvListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            SensorManager.getRotationMatrixFromVector(rotMatrix, e.values)
            SensorManager.getOrientation(rotMatrix, orientation)
            rvHeading = (Math.toDegrees(orientation[0].toDouble()).toFloat() + 360f) % 360f
            // 平放检测：pitch/roll 任一超阈值视为接近水平
            val pitchDeg = Math.toDegrees(orientation[1].toDouble())
            val rollDeg = Math.toDegrees(orientation[2].toDouble())
            isFlat = abs(pitchDeg) > FLAT_THRESHOLD_DEG || abs(rollDeg) > FLAT_THRESHOLD_DEG
            updateHeading()
        }

        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    private val accelListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            // 重力向量的时间低通（接近静态重力，滤掉手部抖动）
            lowPass(e.values, accel, ACCEL_LP)
            hasAccel = true
            updateAccelMagHeading()
        }

        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    private val magnetListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            lowPass(e.values, magnet, MAGNET_LP)
            hasMagnet = true
            updateAccelMagHeading()
        }

        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    // =============================================================================================
    // 状态
    // =============================================================================================

    /** 旋转矢量解算的最新方位角（null = 尚无数据）。 */
    private var rvHeading: Float? = null

    /** 加速度计+磁力计解算的最新方位角（null = 数据不全）。 */
    private var amHeading: Float? = null

    /** 手机是否接近水平（用于平放防抖）。 */
    private var isFlat = false

    // =============================================================================================
    // 生命周期
    // =============================================================================================

    /** 开始监听。幂等。 */
    fun start() {
        if (started) return
        var any = false

        sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sm.registerListener(rvListener, it, SensorManager.SENSOR_DELAY_UI)
            any = true
        }
        // 辅源：加速度计 + 磁力计（任一缺失则该路不可用，主源兜底）
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sm.registerListener(accelListener, it, SensorManager.SENSOR_DELAY_UI)
            any = true
        }
        sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
            sm.registerListener(magnetListener, it, SensorManager.SENSOR_DELAY_UI)
            any = true
        }

        started = true
        isSupported = any
    }

    /** 停止监听。幂等。 */
    fun stop() {
        if (!started) return
        sm.unregisterListener(rvListener)
        sm.unregisterListener(accelListener)
        sm.unregisterListener(magnetListener)
        started = false
        hasAccel = false
        hasMagnet = false
        rvHeading = null
        amHeading = null
    }

    // =============================================================================================
    // 解算
    // =============================================================================================

    /** 用经典方法（加速度计+磁力计）解算方位角，天然倾斜补偿。 */
    private fun updateAccelMagHeading() {
        if (!hasAccel || !hasMagnet) return
        val ok = SensorManager.getRotationMatrix(amMatrix, null, accel, magnet)
        if (!ok) return
        SensorManager.getOrientation(amMatrix, orientation)
        amHeading = (Math.toDegrees(orientation[0].toDouble()).toFloat() + 360f) % 360f
        updateHeading()
    }

    /**
     * 融合两路方位角并做时间低通，写入 [headingDeg]。
     *
     * 优先级：双路融合 > 旋转矢量 > 加速度计+磁力计。
     */
    private fun updateHeading() {
        val rv = rvHeading
        val am = amHeading

        val raw = when {
            rv != null && am != null ->
                // 磁力计辅助纠偏：把 am 拉向 rv 的最短角差按权重混入，
                // 抑制旋转矢量的陀螺漂移，且不引入磁力计的慢速抖动
                (rv + angleDelta(am, rv) * AM_WEIGHT + 360f) % 360f
            rv != null -> rv
            am != null -> am
            else -> return
        }

        // 平放时自动加大低通强度，抑制水平姿态下的方位角抖动
        val factor = if (isFlat) FILTER_FACTOR_FLAT else FILTER_FACTOR
        headingDeg = (headingDeg + angleDelta(raw, headingDeg) * factor + 360f) % 360f
    }

    // =============================================================================================
    // 工具
    // =============================================================================================

    /** 向量低通：out = out*(1-alpha) + in*alpha。 */
    private fun lowPass(input: FloatArray, output: FloatArray, alpha: Float) {
        for (i in 0..2) {
            output[i] = output[i] * (1f - alpha) + input[i] * alpha
        }
    }

    /** from → target 的最短角差（-180..180）。 */
    private fun angleDelta(target: Float, from: Float): Float =
        ((target - from + 540f) % 360f) - 180f

    companion object {
        /** 常规时间低通系数（0.15 ≈ 较强平滑；越小越稳但越滞后）。 */
        private const val FILTER_FACTOR = 0.15f

        /** 平放姿态下的低通系数（更强平滑，抑制抖动摆动）。 */
        private const val FILTER_FACTOR_FLAT = 0.06f

        /** 磁力计解算在融合中的权重（纠偏陀螺漂移）。 */
        private const val AM_WEIGHT = 0.25f

        /** 重力向量低通系数（接近静态重力）。 */
        private const val ACCEL_LP = 0.1f

        /** 磁力计低通系数（滤环境磁场突变）。 */
        private const val MAGNET_LP = 0.3f

        /** 平放判定阈值：pitch 或 roll 超过该角度（度）视为接近水平。 */
        private const val FLAT_THRESHOLD_DEG = 60.0
    }
}