package org.kori.plugin.geo.track

import org.kori.plugin.geo.track.di.TrackPoint
import java.io.File

/**
 * 轨迹文件读写（CSV 格式）。
 *
 * ## ★ B7 修复：补回传感器列
 *
 * 旧版 `write` 只写 8 列，导致 `TrackPoint` 里的 accel/gyro/mag 共 9 个
 * 传感器字段虽然被 `SensorSampler` 采集，却**从未落盘**——与 `TrackPoint`
 * 的 KDoc "CSV 用空字段表示 null" 自相矛盾。
 *
 * 现在表头扩展为 17 列（原 8 列 + 9 个传感器列）：
 *
 * ```
 * # vela-track v2
 * # started 1696204800000
 * # ended   1696208400000
 * # points  1234
 * timestamp_ms,elapsed_nanos,lat,lng,alt_m,speed_mps,bearing_deg,accuracy_m,acc_x,acc_y,acc_z,gyro_x,gyro_y,gyro_z,mag_x,mag_y,mag_z
 * 1696204800123,1234567890123,39.9091234,116.3976543,45.2,1.35,128.7,4.2,,,,,,,,
 * ```
 *
 * [read] 向后兼容：只要求 ≥8 列（v1 文件），传感器列缺失时解析为 null。
 *
 * CSV 而非二进制：可读、可调试、易分享、跨平台。
 */
object TrackStore {

    private const val HEADER_V1 =
        "timestamp_ms,elapsed_nanos,lat,lng,alt_m,speed_mps,bearing_deg,accuracy_m"
    private const val HEADER_V2 =
        "$HEADER_V1,acc_x,acc_y,acc_z,gyro_x,gyro_y,gyro_z,mag_x,mag_y,mag_z"

    fun write(file: File, points: List<TrackPoint>) {
        file.parentFile?.mkdirs()
        file.bufferedWriter().use { w ->
            w.write("# vela-track v2\n")
            w.write("# started ${points.firstOrNull()?.timestampMs ?: 0}\n")
            w.write("# ended ${points.lastOrNull()?.timestampMs ?: 0}\n")
            w.write("# points ${points.size}\n")
            w.write("$HEADER_V2\n")
            for (p in points) {
                w.write("${p.timestampMs},${p.elapsedNanos},${p.lat},${p.lng},")
                w.write("${p.altitudeM ?: ""},${p.speedMps ?: ""},${p.bearingDeg ?: ""},${p.accuracyM ?: ""},")
                // ---- 传感器列（v2 新增）----
                w.write("${p.accelX ?: ""},${p.accelY ?: ""},${p.accelZ ?: ""},")
                w.write("${p.gyroX ?: ""},${p.gyroY ?: ""},${p.gyroZ ?: ""},")
                w.write("${p.magX ?: ""},${p.magY ?: ""},${p.magZ ?: ""}\n")
            }
        }
    }

    fun read(file: File): List<TrackPoint> {
        if (!file.exists()) return emptyList()
        val out = ArrayList<TrackPoint>()
        file.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val t = line.trim()
                if (t.isEmpty() || t.startsWith("#") ||
                    t.startsWith("timestamp_ms")
                ) return@forEach
                val parts = t.split(',')
                if (parts.size < 8) return@forEach
                runCatching {
                    out.add(
                        TrackPoint(
                            lat = parts[2].toDouble(),
                            lng = parts[3].toDouble(),
                            altitudeM = parts[4].takeIf { it.isNotBlank() }?.toDouble(),
                            speedMps = parts[5].takeIf { it.isNotBlank() }?.toFloat(),
                            bearingDeg = parts[6].takeIf { it.isNotBlank() }?.toFloat(),
                            accuracyM = parts[7].takeIf { it.isNotBlank() }?.toFloat(),
                            timestampMs = parts[0].toLong(),
                            elapsedNanos = parts[1].toLong(),
                            // ---- 传感器列（v2；旧文件没有则 null）----
                            accelX = parts.getOrNull(8)?.takeIf { it.isNotBlank() }?.toFloat(),
                            accelY = parts.getOrNull(9)?.takeIf { it.isNotBlank() }?.toFloat(),
                            accelZ = parts.getOrNull(10)?.takeIf { it.isNotBlank() }?.toFloat(),
                            gyroX = parts.getOrNull(11)?.takeIf { it.isNotBlank() }?.toFloat(),
                            gyroY = parts.getOrNull(12)?.takeIf { it.isNotBlank() }?.toFloat(),
                            gyroZ = parts.getOrNull(13)?.takeIf { it.isNotBlank() }?.toFloat(),
                            magX = parts.getOrNull(14)?.takeIf { it.isNotBlank() }?.toFloat(),
                            magY = parts.getOrNull(15)?.takeIf { it.isNotBlank() }?.toFloat(),
                            magZ = parts.getOrNull(16)?.takeIf { it.isNotBlank() }?.toFloat(),
                        ),
                    )
                }
            }
        }
        return out
    }

    /** 列出目录下所有轨迹文件，按开始时间倒序。 */
    fun list(dir: File): List<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".csv") }
            ?.sortedByDescending { it.name }
            .orEmpty()

    /** 生成新轨迹文件。 */
    fun newFile(dir: File, nameHint: String? = null): File {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val safe = nameHint?.replace(Regex("[^A-Za-z0-9_-]"), "_")?.take(24) ?: "track"
        return File(dir, "$safe-$stamp.csv")
    }
}