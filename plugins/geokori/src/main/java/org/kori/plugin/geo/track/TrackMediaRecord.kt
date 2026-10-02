package org.kori.plugin.geo.track

/**
 * 记录过程中的媒体附件。
 *
 * ## 存储布局
 *
 * 每个附件由**两个文件**组成：
 *  · 媒体文件本身（`media/photo-20261002-153045.jpg`）
 *  · sidecar JSON（`media/photo-20261002-153045.json`）—— 本类的序列化形式
 *
 * ## 时间与定位
 *
 * [timestampMs] / [lat] / [lng] 记录的是**拍摄/录音那一刻**的时间与位置。
 * 如果那一刻拿不到 GPS（如室内），退化为 [TrackRecordingEngine.lastKnownLocation]。
 *
 * ## 类型
 *
 *  · PHOTO：`.jpg`，无时长
 *  · VIDEO：`.mp4`，有时长
 *  · AUDIO：`.m4a`，有时长
 */
data class TrackMediaRecord(
    /** 媒体类型。 */
    val type: Type,

    /**
     * 文件相对路径（相对于会话目录）。
     *
     * 示例：`"media/photo-20261002-153045.jpg"`
     *
     * **不包含** sidecar JSON 路径——JSON 由 `filePath` 推导：
     * 去掉扩展名 + `.json`。
     */
    val filePath: String,

    /** 墙钟时间戳（毫秒）。 */
    val timestampMs: Long,

    /** 拍摄/录音点的纬度。 */
    val lat: Double,

    /** 拍摄/录音点的经度。 */
    val lng: Double,

    /** 定位精度（米），null = 定位不准或不可用。 */
    val accuracyM: Float? = null,

    /** 时长（秒），仅 VIDEO / AUDIO 有值。 */
    val durationSec: Double? = null,

    /** 用户备注（可后续编辑）。 */
    val note: String? = null,
) {
    enum class Type {
        /** 照片（.jpg）。 */
        PHOTO,

        /** 视频（.mp4）。 */
        VIDEO,

        /** 录音（.m4a）。 */
        AUDIO,
    }

    /** 是否含时长。 */
    val hasDuration: Boolean
        get() = type != Type.PHOTO && durationSec != null

    /**
     * 从 [filePath] 推导 sidecar JSON 的**相对路径**。
     *
     * `media/photo-20261002-153045.jpg` → `media/photo-20261002-153045.json`
     */
    val sidecarPath: String
        get() {
            val dot = filePath.lastIndexOf('.')
            return if (dot >= 0) filePath.substring(0, dot) + ".json"
            else filePath + ".json"
        }

    /**
     * 从 [filePath] 推导文件名（含扩展名）。
     *
     * `media/photo-20261002-153045.jpg` → `photo-20261002-153045.jpg`
     */
    val fileName: String
        get() = filePath.substringAfterLast('/')

    /**
     * 从 [filePath] 推导扩展名（不含点）。
     *
     * `media/photo-20261002-153045.jpg` → `jpg`
     */
    val extension: String
        get() = filePath.substringAfterLast('.', "")

    /**
     * 生成一个默认文件名提示，用于创建新文件。
     *
     * @param timestampMs 时间戳（毫秒）
     */
    companion object {
        /** 生成文件名前缀（不含扩展名），例如 `"photo-20261002-153045"`。 */
        fun fileNameHint(type: Type, timestampMs: Long): String {
            val prefix = when (type) {
                Type.PHOTO -> "photo"
                Type.VIDEO -> "video"
                Type.AUDIO -> "audio"
            }
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(java.util.Date(timestampMs))
            return "$prefix-$stamp"
        }

        /** 生成完整的相对路径，例如 `"media/photo-20261002-153045.jpg"`。 */
        fun relativePath(type: Type, timestampMs: Long): String {
            val ext = when (type) {
                Type.PHOTO -> "jpg"
                Type.VIDEO -> "mp4"
                Type.AUDIO -> "m4a"
            }
            return "media/${fileNameHint(type, timestampMs)}.$ext"
        }
    }
}