package org.kori.plugin.geo.track

import org.kori.plugin.geo.track.di.TrackSession

/**
 * 断点续录候选：一次**非人为结束**（崩溃 / 进程被杀）的未闭合会话。
 *
 * 由 [TrackRecordingEngine] 在 init 时通过崩溃锁检测并发布到
 * [TrackRecordingEngine.resumeCandidate]；UI 据此询问用户是否继续。
 *
 * @param session  被中断的会话（含统计、段、媒体、事件）
 * @param lastLat  最后一个轨迹点的纬度（读取失败为 null）
 * @param lastLng  最后一个轨迹点的经度
 */
data class ResumeCandidate(
    val session: TrackSession,
    val lastLat: Double?,
    val lastLng: Double?,
)

/** 用户对断点续录询问的决定。 */
enum class ResumeAction {
    /** 继续上次轨迹：新段写入同一会话目录（段号续编、统计累计）。 */
    RESUME,

    /** 放弃续录，开启全新会话（候选标记清除）。 */
    START_NEW,

    /** 取消本次启动（候选标记清除，不开始记录）。 */
    CANCEL,
}