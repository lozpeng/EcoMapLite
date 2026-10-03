package org.kori.plugin.geo.service

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.combo.core.component.activity.BasePluginActivity
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.kori.plugin.geo.track.di.TrackMediaRecord
import org.kori.plugin.geo.track.TrackRecordingEngine
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 视频录制 Activity（ComboLite 插件版，CameraX）。
 *
 * ## 与旧版的差异
 *
 *  · 继承 [BasePluginActivity]（代理模式）
 *  · 一切系统 API 走 [proxyActivity]（`this` 不是 Activity）
 *  · 权限请求改用 `proxyActivity.activityResultRegistry.register(...)`
 *  · 界面全 Compose
 *
 * ## PreviewView 的生命周期
 *
 * CameraX 的 [Preview] 需要 [PreviewView.surfaceProvider] 才能渲染。
 * 但 Compose 的 `AndroidView.factory` 创建 PreviewView 的时机可能晚于
 * `bindToLifecycle`，所以两条路径都要尝试挂载：
 *
 *  · [bindCamera] 里若 `previewViewRef != null` 直接挂
 *  · `VideoCaptureScreen` 创建 PreviewView 时通知外部挂
 *
 * ## 代理 Activity 主题
 *
 * 由于 ComboLite 所有插件 Activity 都跑在宿主的 `HostActivity` 上，
 * 插件的 `android:theme` 声明**不生效**。视频预览是全屏不透明，
 * 因此无需特殊主题——但如果你想给 [TrackMediaCaptureActivity] 的录音
 * 界面做半透明效果，需要宿主侧改 `HostActivity` 主题或另开一个宿主
 * 代理 Activity。
 */
class VideoCaptureActivity : BasePluginActivity() {

    private var cameraProvider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK

    private var recording: Recording? = null
    private var videoFile: File? = null
    private var startMs: Long = 0L

    /** 由 Compose 创建的 PreviewView 回填。 */
    private var previewViewRef: PreviewView? = null

    private var permLauncher: ActivityResultLauncher<Array<String>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val host = proxyActivity as? ComponentActivity ?: return finish0()

        permLauncher = host.activityResultRegistry.register(
            KEY_PERMS,
            host,
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { result ->
            if (result.values.all { it }) {
                initCamera(host)
            } else {
                Toast.makeText(host, "需要相机和麦克风权限", Toast.LENGTH_SHORT).show()
                finish0()
            }
        }

        val needed = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(host, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            initCamera(host)
        } else {
            permLauncher?.launch(missing.toTypedArray())
        }
    }

    private fun initCamera(host: ComponentActivity) {
        val future = ProcessCameraProvider.getInstance(host)
        future.addListener({
            runCatching {
                val provider = future.get()
                cameraProvider = provider

                previewUseCase = Preview.Builder().build()

                videoCapture = VideoCapture.withOutput(
                    Recorder.Builder()
                        .setQualitySelector(
                            QualitySelector.fromOrderedList(
                                listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD),
                                FallbackStrategy.lowerQualityOrHigherThan(Quality.SD),
                            ),
                        )
                        .build(),
                )

                bindCamera(host)
                showUI(host)
            }.onFailure {
                Toast.makeText(host, "相机初始化失败: ${it.message}", Toast.LENGTH_SHORT).show()
                finish0()
            }
        }, ContextCompat.getMainExecutor(host))
    }

    private fun bindCamera(host: ComponentActivity) {
        val provider = cameraProvider ?: return
        val preview = previewUseCase ?: return
        val capture = videoCapture ?: return

        val selector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(host, selector, preview, capture)
        }

        // 如果 PreviewView 已就绪，立刻挂
        previewViewRef?.let { preview.setSurfaceProvider(it.surfaceProvider) }
    }

    private fun switchCamera(host: ComponentActivity) {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
            CameraSelector.LENS_FACING_FRONT
        else
            CameraSelector.LENS_FACING_BACK
        bindCamera(host)
    }

    private fun showUI(host: ComponentActivity) {
        host.setContent {
            VideoCaptureScreen(
                onPreviewViewReady = { pv ->
                    previewViewRef = pv
                    previewUseCase?.setSurfaceProvider(pv.surfaceProvider)
                },
                onCancel = {
                    runCatching { recording?.stop() }
                    recording = null
                    finish0()
                },
                onToggleRecord = { wantRecording ->
                    if (wantRecording) startRecording() else stopRecording()
                },
                onSwitchCamera = { switchCamera(host) },
            )
        }
    }

    private fun startRecording() {
        val host = proxyActivity as? ComponentActivity ?: return
        val capture = videoCapture ?: run {
            Toast.makeText(host, "相机未就绪", Toast.LENGTH_SHORT).show()
            return
        }
        val sessionDir = TrackRecordingEngine.currentSessionDir ?: run {
            Toast.makeText(host, "未在记录中", Toast.LENGTH_SHORT).show()
            finish0(); return
        }
        val mediaDir = File(sessionDir, "media").apply { mkdirs() }
        val file = File(mediaDir, "video-${timestamp()}.mp4")
        videoFile = file
        startMs = System.currentTimeMillis()

        val options = FileOutputOptions.Builder(file).build()
        val hasAudio = ContextCompat.checkSelfPermission(
            host, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

        val pending = capture.output
            .prepareRecording(host, options)
            .apply { if (hasAudio) withAudioEnabled() }

        recording = pending.start(ContextCompat.getMainExecutor(host)) { event ->
            when (event) {
                is VideoRecordEvent.Finalize -> {
                    if (event.hasError()) {
                        Toast.makeText(host, "录制失败: ${event.error}", Toast.LENGTH_SHORT).show()
                        file.delete()
                    } else {
                        finishVideoCapture(file)
                    }
                }
                else -> Unit
            }
        }
    }

    private fun stopRecording() {
        runCatching { recording?.stop() }
        recording = null
    }

    private fun finishVideoCapture(file: File) {
        val host = proxyActivity as? ComponentActivity ?: return
        val durationSec = (System.currentTimeMillis() - startMs) / 1000.0
        val loc = TrackRecordingEngine.lastKnownLocation
        val record = TrackMediaRecord(
            type = TrackMediaRecord.Type.VIDEO,
            filePath = "media/${file.name}",
            timestampMs = System.currentTimeMillis(),
            lat = loc?.latitude ?: 0.0,
            lng = loc?.longitude ?: 0.0,
            accuracyM = if (loc?.hasAccuracy() == true) loc.accuracy else null,
            durationSec = durationSec,
        )
        writeMediaSidecar(record)
        TrackRecordingEngine.notifyMediaAdded(record)
        Toast.makeText(host, "视频已保存 (%.1fs)".format(durationSec), Toast.LENGTH_SHORT).show()
        finish0()
    }

    private fun writeMediaSidecar(record: TrackMediaRecord) {
        val dir = TrackRecordingEngine.currentSessionDir ?: return
        val base = record.filePath.substringAfterLast('/').substringBeforeLast('.')
        File(dir, "media/$base.json").writeText(
            JSONObject().apply {
                put("type", record.type.name)
                put("filePath", record.filePath)
                put("timestampMs", record.timestampMs)
                put("lat", record.lat)
                put("lng", record.lng)
                record.accuracyM?.let { put("accuracyM", it.toDouble()) }
                record.durationSec?.let { put("durationSec", it) }
            }.toString(2),
        )
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    private fun finish0() {
        proxyActivity?.finish()
    }

    override fun onDestroy() {
        runCatching { recording?.close() }
        recording = null
        cameraProvider?.unbindAll()
        cameraProvider = null
        previewUseCase = null
        videoCapture = null
        previewViewRef = null
        super.onDestroy()
    }

    companion object {
        private const val KEY_PERMS = "video_capture_perms"
    }
}

// =================================================================================================
// Compose 界面
// =================================================================================================

@Composable
private fun VideoCaptureScreen(
    onPreviewViewReady: (PreviewView) -> Unit,
    onCancel: () -> Unit,
    onToggleRecord: (Boolean) -> Unit,
    onSwitchCamera: () -> Unit,
) {
    val context = LocalContext.current

    var isRecording by remember { mutableStateOf(false) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var recordStartMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(isRecording) {
        if (isRecording) {
            recordStartMs = System.currentTimeMillis()
            while (true) {
                elapsedMs = System.currentTimeMillis() - recordStartMs
                delay(100)
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    onPreviewViewReady(this)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (isRecording) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 48.dp)
                    .background(Color(0xCC000000), CircleShape)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    "● ${formatDurationMs(elapsedMs)}",
                    color = Color.White,
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0x99000000))
                .padding(vertical = 24.dp, horizontal = 32.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = {
                    if (isRecording) onToggleRecord(false)
                    onCancel()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
            ) { Text("取消", color = Color.White) }

            Button(
                onClick = {
                    isRecording = !isRecording
                    onToggleRecord(isRecording)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRecording) Color.Red else Color.White,
                ),
                shape = CircleShape,
                modifier = Modifier.size(72.dp),
                contentPadding = PaddingValues(0.dp),
            ) {
                Box(
                    Modifier
                        .size(if (isRecording) 24.dp else 48.dp)
                        .background(
                            color = if (isRecording) Color.White else Color.Red,
                            shape = if (isRecording) RoundedCornerShape(4.dp) else CircleShape,
                        ),
                )
            }

            Button(
                onClick = onSwitchCamera,
                colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
            ) { Text("切换", color = Color.White) }
        }
    }
}

private fun formatDurationMs(ms: Long): String {
    val s = ms / 1000
    return "%02d:%02d".format(s / 60, s % 60)
}