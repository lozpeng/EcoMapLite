package org.kori.plugin.geo.track

/**
 * 轨迹记录过程中的事件。
 *
 * ## 用途
 *
 * 把"暂停 / 继续"等过程性动作作为**一等数据**记录下来（写入 session.json 的
 * `events` 数组），供时间线、回放等界面精确展示——不靠时间间隙启发式猜测。
 *
 *  · [PAUSE]：用户点击暂停的时刻
 *  · [RESUME]：用户点击继续的时刻
 *
 * 一次暂停的时长 = 配对的 RESUME − PAUSE（录制在暂停中结束时无 RESUME，
 * 用最后一个轨迹点的时间作为结束）。
 */
enum class TrackEventType {
    PAUSE,
    RESUME,
}

data class TrackEvent(
    /** 事件类型。 */
    val type: TrackEventType,
    /** 发生时间（墙钟毫秒）。 */
    val timestampMs: Long,
    /** 发生位置纬度（取当时最新轨迹点；无点时可能为 0.0）。 */
    val lat: Double,
    /** 发生位置经度。 */
    val lng: Double,
)