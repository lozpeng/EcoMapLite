package org.kori.plugin.geo.location

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.cos
import kotlin.math.sin

/**
 * 提供世界坐标系下的前向加速度。
 *
 * 手机传感器给出的是**设备坐标系**加速度（屏幕 x/y/z）。要得到"沿运动方向的加速度"，
 * 需要：
 *  1. 用 TYPE_LINEAR_ACCELERATION 得到去掉重力的线性加速度（设备坐标）
 *  2. 用 TYPE_ROTATION_VECTOR 得到设备→世界的旋转
 *  3. 把线性加速度转到世界坐标系（东/北/上）
 *  4. 投影到 GPS bearing 方向，得到前向加速度
 *
 * 输出给 [SpeedKalman.predict] 使用：急刹时 v 立刻减，不必等下一秒 GPS fix。
 * ## 数据流
 *
 * ```
 * TYPE_LINEAR_ACCELERATION (设备坐标)
 *          +
 * TYPE_ROTATION_VECTOR     (设备→世界旋转)
 *          ↓
 *      旋转到世界坐标 (east, north, up)
 *          ↓
 *   投影到 bearing 方向
 *          ↓
 *    前向加速度 (m/s²)
 * ```
 *
 * ## 硬件缺失降级
 *
 * 部分廉价设备或模拟器没有 `TYPE_LINEAR_ACCELERATION` 或 `TYPE_ROTATION_VECTOR`。
 * [isReady] 会在两个传感器都就绪时为 true；否则 [forwardAccel] 返回 0。
 * 调用方（[LocationTracker]）据此跳过 ticker 里的 predict 调用。
 */
class MotionProvider(context: Context) {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val rotMatrix = FloatArray(9)
    private val rotVec = FloatArray(4)
    private val accelDev = FloatArray(3)
    private val accelWorld = FloatArray(3)
    private val tmp = FloatArray(3)

    @Volatile private var hasRotation: Boolean = false
    @Volatile private var hasAccel: Boolean = false

    /** 是否有线性加速度传感器（设备坐标系）。 */
    @Volatile var hasAccelSensor: Boolean = false
        private set

    /** 是否有旋转矢量传感器。 */
    @Volatile var hasRotationSensor: Boolean = false
        private set

    /**
     * 是否两个传感器都已就绪并能正常工作。
     * 调用方在 false 时应跳过 [forwardAccel] 的调用。
     */
    @Volatile var isReady: Boolean = false
        private set

    private val rotListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            System.arraycopy(e.values, 0, rotVec, 0, 4)
            SensorManager.getRotationMatrixFromVector(rotMatrix, rotVec)
            hasRotation = true
            updateReady()
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    private val accelListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            System.arraycopy(e.values, 0, accelDev, 0, 3)
            hasAccel = true
            if (hasRotation) rotateToWorld()
            updateReady()
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    fun start() {
        sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let {
            sm.registerListener(accelListener, it, SensorManager.SENSOR_DELAY_GAME)
            hasAccelSensor = true
        }
        sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sm.registerListener(rotListener, it, SensorManager.SENSOR_DELAY_GAME)
            hasRotationSensor = true
        }
    }

    fun stop() {
        sm.unregisterListener(accelListener)
        sm.unregisterListener(rotListener)
        hasAccelSensor = false
        hasRotationSensor = false
        hasAccel = false
        hasRotation = false
        isReady = false
    }

    private fun updateReady() {
        // 两个传感器都至少收到过一次数据 → 就绪
        isReady = hasAccel && hasRotation
    }

    private fun rotateToWorld() {
        // rotMatrix 是 row-major 的设备→世界旋转
        // 把设备坐标 (x,y,z) 转到世界坐标 (east, north, up)
        tmp[0] = rotMatrix[0] * accelDev[0] + rotMatrix[1] * accelDev[1] + rotMatrix[2] * accelDev[2]
        tmp[1] = rotMatrix[3] * accelDev[0] + rotMatrix[4] * accelDev[1] + rotMatrix[5] * accelDev[2]
        tmp[2] = rotMatrix[6] * accelDev[0] + rotMatrix[7] * accelDev[1] + rotMatrix[8] * accelDev[2]
        accelWorld[0] = tmp[0]
        accelWorld[1] = tmp[1]
        accelWorld[2] = tmp[2]
    }

    /**
     * 沿 [bearingDeg] 方向的前向加速度（m/s²）。
     *
     * @param bearingDeg 运动方位角（0=北，90=东）
     * @return 前向加速度；传感器未就绪时返回 0
     */
    fun forwardAccel(bearingDeg: Float): Float {
        if (!isReady) return 0f
        val rad = Math.toRadians(bearingDeg.toDouble())
        val fx = sin(rad).toFloat()   // 东向分量
        val fy = cos(rad).toFloat()   // 北向分量
        return accelWorld[0] * fx + accelWorld[1] * fy
    }
}