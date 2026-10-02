package org.kori.plugin.geo.location
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * 从加速度计方差 + GPS 速度推断运动类型。
 *
 * ## 判定依据
 *
 *  1. **加速度方差**：静止 < 0.5，步行 0.5-3，骑行 3-8，驾车 > 8
 *  2. **GPS 速度**：> 15 m/s 直接判定驾车（骑车极限约 15 m/s = 54 km/h）
 *  3. **时间迟滞**：状态切换需持续 [STABLE_MS]，避免红绿灯/颠簸时抖动
 *
 * ## 线程安全
 *
 * [SensorEventListener] 的回调线程由系统决定（部分三星/小米设备派发到独立 SensorThread），
 * [onGpsFix] 由主线程调用。所有跨线程字段用 `@Volatile` 保护；
 * `accelWindow` 是 `ArrayDeque`，**非线程安全**，所有访问用 [accelLock] 保护。
 */
class ActivityDetector(context: Context) {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    @Volatile private var current: ActivityProfile = ActivityProfile.STILL
    @Volatile private var candidate: ActivityProfile = ActivityProfile.STILL
    @Volatile private var candidateSinceMs: Long = 0L

    /** 保护 accelWindow 的锁。 */
    private val accelLock = Any()
    /** 加速度滑动窗口（记录模长）。所有访问必须在 accelLock 内。 */
    private val accelWindow = ArrayDeque<Float>(WINDOW_SIZE + 1)

    val profile: ActivityProfile get() = current

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val x = e.values[0]; val y = e.values[1]; val z = e.values[2]
            val mag = sqrt(x * x + y * y + z * z)
            synchronized(accelLock) {
                accelWindow.addLast(mag)
                while (accelWindow.size > WINDOW_SIZE) accelWindow.removeFirst()
            }
            reevaluate(fromAccel = true)
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    fun start() {
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        sm.unregisterListener(listener)
    }

    /** 每次 GPS fix 时调用，用 GPS 速度修正/加速检测。 */
    fun onGpsFix(speedMps: Float?) {
        if (speedMps == null) return
        if (speedMps > 15f) {
            forceSet(ActivityProfile.DRIVING)
            return
        }
        reevaluate(fromAccel = false, gpsSpeed = speedMps)
    }

    private fun reevaluate(fromAccel: Boolean, gpsSpeed: Float? = null) {
        val variance = accelVariance()
        val byAccel = when {
            variance < 0.5f -> ActivityProfile.STILL
            variance < 3.0f -> ActivityProfile.WALKING
            variance < 8.0f -> ActivityProfile.CYCLING
            else -> ActivityProfile.DRIVING
        }
        val bySpeed = gpsSpeed?.let {
            when {
                it < 0.3f -> ActivityProfile.STILL
                it < 3.5f -> ActivityProfile.WALKING
                it < 8.0f -> ActivityProfile.CYCLING
                else -> ActivityProfile.DRIVING
            }
        }
        val want = when {
            bySpeed == null -> byAccel
            bySpeed == ActivityProfile.STILL && byAccel == ActivityProfile.STILL -> ActivityProfile.STILL
            else -> bySpeed
        }
        if (want == current) {
            candidateSinceMs = 0L    // 重置迟滞计时即可
            return
        }
        val now = System.currentTimeMillis()
        if (want != candidate) {
            candidate = want
            candidateSinceMs = now
            return
        }
        if (now - candidateSinceMs >= STABLE_MS) {
            current = want
        }
    }

    private fun forceSet(p: ActivityProfile) {
        if (p != current) {
            current = p
            candidate = p
            candidateSinceMs = 0L
        }
    }

    private fun accelVariance(): Float {
        synchronized(accelLock) {
            if (accelWindow.size < 10) return 0f
            var sum = 0f
            for (v in accelWindow) sum += v
            val mean = sum / accelWindow.size
            var varSum = 0f
            for (v in accelWindow) {
                val d = v - mean
                varSum += d * d
            }
            return varSum / accelWindow.size
        }
    }

    companion object {
        private const val WINDOW_SIZE = 50
        private const val STABLE_MS = 3_000L
    }
}