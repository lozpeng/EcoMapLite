package org.kori.plugin.geo.track

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 轨迹导出与分享工具。
 *
 * ## 导出
 *
 * 用 [TrackExporter] 把会话导出为 GPX / KML / GeoJSON / CSV，写入
 * `cacheDir/track_exports/`，然后调起系统分享（微信聊天在分享面板里选）。
 *
 * ## 分享 URI 的两条路径（ComboLite 插件环境的 FileProvider 限制）
 *
 * 插件没有自己的 FileProvider（Provider 必须由宿主 manifest 注册），因此：
 *
 *  1. **宿主 FileProvider 优先**：若宿主声明了 `${applicationId}.fileprovider`
 *     且 file_paths 覆盖 cache 路径，直接用它生成 content:// URI
 *  2. **MediaStore 兜底（API 29+）**：复制到 `Downloads/tracks/` 拿到
 *     MediaStore 的 content:// URI——无需任何 FileProvider，微信可直接接收
 *  3. 都不满足 → Toast 提示导出文件位置
 */
object TrackShare {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * 导出并分享一个会话。
     *
     * @param format 导出格式（默认 GPX，微信/户外工具通用）
     * @param track  原始 or 平滑轨迹
     */
    fun exportAndShare(
        context: Context,
        session: TrackSession,
        format: TrackExporter.Format = TrackExporter.Format.GPX,
        track: TrackExporter.Track = TrackExporter.Track.RAW,
    ) {
        val ctx = context.applicationContext
        scope.launch {
            try {
                val outDir = File(ctx.cacheDir, "track_exports").apply { mkdirs() }
                val ext = when (format) {
                    TrackExporter.Format.CSV -> "csv"
                    TrackExporter.Format.GPX -> "gpx"
                    TrackExporter.Format.KML -> "kml"
                    TrackExporter.Format.GEOJSON -> "geojson"
                }
                val outFile = File(outDir, "${session.id}.$ext")
                withContext(Dispatchers.IO) {
                    TrackExporter.export(session, format, track, outFile)
                }
                val mime = mimeOf(format)
                shareFile(ctx, outFile, mime)
            } catch (e: Exception) {
                Log.e("TrackShare", "导出失败: ${e.message}", e)
                Toast.makeText(ctx, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 仅导出到 cache 目录，返回文件（不分享）。 */
    fun exportOnly(
        context: Context,
        session: TrackSession,
        format: TrackExporter.Format = TrackExporter.Format.GPX,
        track: TrackExporter.Track = TrackExporter.Track.RAW,
        onDone: (File) -> Unit = {},
    ) {
        val ctx = context.applicationContext
        scope.launch {
            try {
                val outDir = File(ctx.cacheDir, "track_exports").apply { mkdirs() }
                val ext = when (format) {
                    TrackExporter.Format.CSV -> "csv"
                    TrackExporter.Format.GPX -> "gpx"
                    TrackExporter.Format.KML -> "kml"
                    TrackExporter.Format.GEOJSON -> "geojson"
                }
                val outFile = File(outDir, "${session.id}.$ext")
                withContext(Dispatchers.IO) {
                    TrackExporter.export(session, format, track, outFile)
                }
                onDone(outFile)
            } catch (e: Exception) {
                Log.e("TrackShare", "导出失败: ${e.message}", e)
                Toast.makeText(ctx, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // =============================================================================================
    // 分享
    // =============================================================================================

    private fun shareFile(ctx: Context, file: File, mime: String) {
        val uri = buildShareUri(ctx, file, mime)
        if (uri == null) {
            Toast.makeText(
                ctx,
                "已导出到 ${file.absolutePath}（无可用分享方式）",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
        }
        runCatching {
            ctx.startActivity(
                Intent.createChooser(send, "分享轨迹到").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /**
     * 生成分享用的 content:// URI。
     *
     * 宿主 FileProvider 优先；API 29+ 用 MediaStore 兜底。
     */
    private fun buildShareUri(ctx: Context, file: File, mime: String): android.net.Uri? {
        // 1) 宿主 FileProvider
        runCatching {
            val authority = "${ctx.packageName}.fileprovider"
            val resolved = ctx.packageManager.resolveContentProvider(authority, 0)
            if (resolved != null) {
                return FileProvider.getUriForFile(ctx, authority, file)
            }
        }
        // 2) MediaStore（API 29+，复制到公共 Downloads，微信可直接打开）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(
                        MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/tracks",
                    )
                }
                val resolver = ctx.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                resolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                } ?: return null
                return uri
            }
        }
        return null
    }

    // =============================================================================================
    // 媒体查看（时间线/详情页播放用）
    // =============================================================================================

    /**
     * 用系统播放器打开一个媒体文件（时间线的"播放"）。
     *
     * URI 生成与分享同一策略：宿主 FileProvider 优先，API 29+ MediaStore 兜底。
     */
    fun openMedia(context: Context, file: File, mime: String) {
        val ctx = context.applicationContext
        val uri = buildShareUri(ctx, file, mime)
        if (uri == null) {
            Toast.makeText(
                ctx,
                "无法生成播放地址（${file.absolutePath}）",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        runCatching {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            Toast.makeText(ctx, "未找到可播放该文件的应用", Toast.LENGTH_SHORT).show()
        }
    }

    private fun mimeOf(format: TrackExporter.Format): String = when (format) {
        TrackExporter.Format.GPX -> "application/gpx+xml"
        TrackExporter.Format.KML -> "application/vnd.google-earth.kml+xml"
        TrackExporter.Format.GEOJSON -> "application/geo+json"
        TrackExporter.Format.CSV -> "text/csv"
    }
}