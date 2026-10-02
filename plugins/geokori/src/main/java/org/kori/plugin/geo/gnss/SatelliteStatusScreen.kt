package org.kori.plugin.geo.gnss

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * 定位卫星状态屏（Compose 覆盖层，参考 GPSTest 思路自实现的精简版）。
 *
 * ## 展示内容
 *
 *  · 汇总：可见卫星数 / 参与定位数 / 最强信号
 *  · 列表：每颗卫星的星座（彩色标识）、SVID、方位/仰角、CN0 信号条、是否参与定位
 *
 * ## 生命周期
 *
 * 组合进入时启动 [GnssStatusProvider]，离开时停止；每 1s 刷新一次快照到 Compose 状态。
 *
 * ## 使用
 *
 * ```kotlin
 * var show by remember { mutableStateOf(false) }
 * if (show) {
 *     SatelliteStatusScreen(onClose = { show = false })
 * }
 * ```
 */
@Composable
fun SatelliteStatusScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val provider = remember { GnssStatusProvider(context) }

    var snapshot by remember { mutableStateOf(provider.snapshot) }

    // 启动采集 + 1s 轮询刷新
    LaunchedEffect(Unit) {
        provider.start()
        while (true) {
            snapshot = provider.snapshot
            delay(1000)
        }
    }
    DisposableEffect(Unit) {
        onDispose { provider.stop() }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            // ---- 标题栏 ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "定位卫星",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "关闭",
                    )
                }
            }

            val snap = snapshot
            if (snap == null) {
                // ---- 无数据 ----
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "等待卫星数据…\n（需授予定位权限，且设备支持 GNSS 状态回调）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            // ---- 汇总行 ----
            Text(
                text = "可见 ${snap.satellites.size} 颗 · 参与定位 ${snap.usedCount} 颗" +
                        " · 最强信号 ${"%.1f".format(snap.maxCn0DbHz)} dB-Hz",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.height(12.dp))

            // ---- 卫星列表（按星座分组排序，组内按信号降序）----
            val sorted = remember(snap) {
                snap.satellites.sortedWith(
                    compareBy({ it.constellation }, { -it.cn0DbHz }),
                )
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(sorted, key = { "${it.constellation}-${it.svid}" }) { sat ->
                    SatelliteRow(sat)
                }
            }
        }
    }
}

/** 单颗卫星行：彩色星座点 + SVID + 方位/仰角 + 信号条 + 定位标记。 */
@Composable
private fun SatelliteRow(sat: GnssStatusProvider.Sat) {
    val (name, color) = constellationInfo(sat.constellation)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 星座色点
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(color, CircleShape),
        )

        // SVID（固定宽度对齐）
        Text(
            text = "${name.take(1)}${sat.svid}",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(44.dp),
            maxLines = 1,
        )

        // 方位 / 仰角
        Text(
            text = "方位 %3.0f° · 仰角 %2.0f°".format(sat.azimuthDeg, sat.elevationDeg),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )

        Spacer(modifier = Modifier.weight(1f))

        // CN0 信号条（10~45 dB-Hz 映射为 0~1）
        val progress = ((sat.cn0DbHz - 10f) / 35f).coerceIn(0f, 1f)
        Box(
            modifier = Modifier
                .width(72.dp)
                .height(6.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(3.dp),
                ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .height(6.dp)
                    .background(
                        if (sat.usedInFix) Color(0xFF43A047) else color,
                        RoundedCornerShape(3.dp),
                    ),
            )
        }

        Text(
            text = "%4.1f".format(sat.cn0DbHz),
            style = MaterialTheme.typography.bodySmall,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(38.dp),
            maxLines = 1,
        )

        // 参与定位标记
        if (sat.usedInFix) {
            Text(
                text = "✓",
                color = Color(0xFF43A047),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Spacer(modifier = Modifier.width(14.dp))
        }
    }
}

/** 星座类型 → (简称, 标识色)。 */
private fun constellationInfo(constellation: Int): Pair<String, Color> = when (constellation) {
    android.location.GnssStatus.CONSTELLATION_GPS -> "GPS" to Color(0xFF1A73E8)
    android.location.GnssStatus.CONSTELLATION_SBAS -> "SBAS" to Color(0xFF9E9E9E)
    android.location.GnssStatus.CONSTELLATION_GLONASS -> "格洛纳斯" to Color(0xFFD32F2F)
    android.location.GnssStatus.CONSTELLATION_QZSS -> "QZSS" to Color(0xFF9334E6)
    android.location.GnssStatus.CONSTELLATION_BEIDOU -> "北斗" to Color(0xFFF9A825)
    android.location.GnssStatus.CONSTELLATION_GALILEO -> "伽利略" to Color(0xFF00897B)
    android.location.GnssStatus.CONSTELLATION_IRNSS -> "IRNSS" to Color(0xFF43A047)
    else -> "未知" to Color(0xFF9E9E9E)
}