package org.kori.plugin.geo.service

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.combo.core.component.activity.BasePluginActivity
import com.combo.core.utils.startPluginActivity
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.kori.plugin.geo.track.di.TrackMediaRecord
import org.kori.plugin.geo.track.TrackRecordingEngine
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 拍照 / 录音 Activity（ComboLite 插件版）。
 *
 * ## ComboLite 代理模式的限制
 *
 *  · 本类**不是** `ComponentActivity`，而是 [IPluginActivity] 的实现
 *  · 一切系统能力走 [proxyActivity]（由框架注入，指向宿主的 HostActivity）
 *  · 不能用 `registerForActivityResult(...)`——时机限制无法满足
 *  · 改用 `proxyActivity.activityResultRegistry.register(key, lifecycleOwner, contract) { }`
 *
 * ## 与旧版的差异
 *
 *  · 不再使用系统相机 + FileProvider（ComboLite 下 FileProvider 跨包名
 *    有严重兼容问题），改为 **CameraX ImageCapture 直接写文件**
 *  · 界面全部改为 Compose（原录音界面是纯 View，无法在代理 Activity
 *    下正常工作）
 *  · VIDEO 类型直接转发到 [VideoCaptureActivity]
 *
 * ## UI 归属
 *
 * 所有 Compose UI 挂在 `proxyActivity.setContent { ... }` 上。Compose
 * 内 `LocalContext.current` = `proxyActivity`，`LocalLifecycleOwner.current`
 * = `proxyActivity`。
 *
 * ## 会话目录
 *
 * 所有媒体文件写在 [TrackRecordingEngine.currentSessionDir] 下的
 * `media/` 子目录里。若当前不在记录中（`currentSessionDir == null`），
 * 直接提示并结束。
 */
class TrackMediaCaptureActivity : BasePluginActivity() {

    private var captureKind: String? = null

    private var cameraPermLauncher: ActivityResultLauncher<String>? = null
    private var audioPermLauncher: ActivityResultLauncher<String>? = null

    // ---- 录音状态 ----
    private var audioRecorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var audioStartMs: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val host = proxyActivity as? ComponentActivity ?: return finish0()

        captureKind = host.intent?.getStringExtra(EXTRA_CAPTURE_KIND)

        // 用 activityResultRegistry 而不是 registerForActivityResult
        cameraPermLauncher = host.activityResultRegistry.register(
            KEY_CAMERA_PERM,
            host,
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            if (granted) startPhotoCapture() else denyAndFinish("需要相机权限")
        }
        audioPermLauncher = host.activityResultRegistry.register(
            KEY_AUDIO_PERM,
            host,
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            if (granted) startAudioCapture() else denyAndFinish("需要录音权限")
        }

        when (captureKind) {
            KIND_PHOTO -> requestOrRun(Manifest.permission.CAMERA, cameraPermLauncher)
            KIND_AUDIO -> requestOrRun(Manifest.permission.RECORD_AUDIO, audioPermLauncher)
            KIND_VIDEO -> {
                // 转发到录像 Activity。startPluginActivity 是 ComboLite
                // 的 Context 扩展函数，会自动走 ProxyManager 分发。
                host.startPluginActivity(VideoCaptureActivity::class.java)
                finish0()
            }
            else -> finish0()
        }
    }

    override fun onDestroy() {
        runCatching {
            audioRecorder?.stop()
            audioRecorder?.release()
        }
        audioRecorder = null
        super.onDestroy()
    }

    // =========================================================================================
    // 权限
    // =========================================================================================

    private fun requestOrRun(
        perm: String,
        launcher: ActivityResultLauncher<String>?,
    ) {
        val host = proxyActivity ?: return finish0()
        if (ContextCompat.checkSelfPermission(host, perm) == PackageManager.PERMISSION_GRANTED) {
            when (captureKind) {
                KIND_PHOTO -> startPhotoCapture()
                KIND_AUDIO -> startAudioCapture()
            }
        } else {
            launcher?.launch(perm)
        }
    }

    private fun denyAndFinish(msg: String) {
        val host = proxyActivity ?: return finish0()
        // ★ 区分两种情况：
        //  1. 普通拒绝 → 只提示
        //  2. 勾选了"不再询问"的系统静默拒绝 → 引导到系统设置页手动开启
        //     （此时再调 launch() 也不会弹框，用户会误以为功能坏了）
        val permanentlyDenied = !host.shouldShowRequestPermissionRationale(
            when (captureKind) {
                KIND_PHOTO -> Manifest.permission.CAMERA
                KIND_AUDIO -> Manifest.permission.RECORD_AUDIO
                else -> Manifest.permission.CAMERA
            },
        )
        if (permanentlyDenied) {
            Toast.makeText(host, "$msg（已选择不再询问，请在系统设置中开启）", Toast.LENGTH_LONG).show()
            runCatching {
                host.startActivity(
                    android.content.Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.fromParts("package", host.packageName, null),
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        } else {
            Toast.makeText(host, msg, Toast.LENGTH_SHORT).show()
        }
        finish0()
    }

    private fun finish0() {
        proxyActivity?.finish()
    }

    // =========================================================================================
    // 拍照（CameraX ImageCapture）
    // =========================================================================================

    private fun startPhotoCapture() {
        val host = proxyActivity as? ComponentActivity ?: return finish0()
        host.setContent {
            PhotoCaptureScreen(
                onCaptured = { file -> finishPhotoCapture(file) },
                onCancel = { finish0() },
            )
        }
    }

    private fun finishPhotoCapture(file: File) {
        val loc = TrackRecordingEngine.lastKnownLocation
        val record = TrackMediaRecord(
            type = TrackMediaRecord.Type.PHOTO,
            filePath = "media/${file.name}",
            timestampMs = System.currentTimeMillis(),
            lat = loc?.latitude ?: 0.0,
            lng = loc?.longitude ?: 0.0,
            accuracyM = if (loc?.hasAccuracy() == true) loc.accuracy else null,
        )
        writeMediaSidecar(record)
        TrackRecordingEngine.notifyMediaAdded(record)
        proxyActivity?.let { Toast.makeText(it, "已保存", Toast.LENGTH_SHORT).show() }
        finish0()
    }

    // =========================================================================================
    // 录音（MediaRecorder）
    // =========================================================================================

    private fun startAudioCapture() {
        val host = proxyActivity as? ComponentActivity ?: return finish0()
        val sessionDir = TrackRecordingEngine.currentSessionDir ?: run {
            Toast.makeText(host, "未在记录中", Toast.LENGTH_SHORT).show()
            finish0(); return
        }
        val mediaDir = File(sessionDir, "media").apply { mkdirs() }
        val file = File(mediaDir, "audio-${timestamp()}.m4a")

        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            MediaRecorder(host) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(96_000)
                setAudioSamplingRate(44_100)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
        } catch (e: Exception) {
            runCatching { recorder.release() }
            Toast.makeText(host, "录音失败: ${e.message}", Toast.LENGTH_SHORT).show()
            finish0(); return
        }

        audioRecorder = recorder
        audioFile = file
        audioStartMs = System.currentTimeMillis()

        host.setContent {
            AudioRecordingScreen(
                startMs = audioStartMs,
                onStop = { stopAudioCapture() },
            )
        }
    }

    private fun stopAudioCapture() {
        val host = proxyActivity as? ComponentActivity ?: return finish0()
        val recorder = audioRecorder ?: return
        val file = audioFile ?: return
        val durationSec = (System.currentTimeMillis() - audioStartMs) / 1000.0

        runCatching {
            recorder.stop()
            recorder.release()
        }
        audioRecorder = null

        val loc = TrackRecordingEngine.lastKnownLocation
        val record = TrackMediaRecord(
            type = TrackMediaRecord.Type.AUDIO,
            filePath = "media/${file.name}",
            timestampMs = System.currentTimeMillis(),
            lat = loc?.latitude ?: 0.0,
            lng = loc?.longitude ?: 0.0,
            accuracyM = if (loc?.hasAccuracy() == true) loc.accuracy else null,
            durationSec = durationSec,
        )
        writeMediaSidecar(record)
        TrackRecordingEngine.notifyMediaAdded(record)
        Toast.makeText(host, "录音已保存 (%.1f s)".format(durationSec), Toast.LENGTH_SHORT).show()
        finish0()
    }

    // =========================================================================================
    // 工具
    // =========================================================================================

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
                record.note?.let { put("note", it) }
            }.toString(2),
        )
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    companion object {
        const val EXTRA_CAPTURE_KIND = "capture_kind"

        const val KIND_PHOTO = "PHOTO"
        const val KIND_AUDIO = "AUDIO"
        const val KIND_VIDEO = "VIDEO"

        private const val KEY_CAMERA_PERM = "track_media_camera_perm"
        private const val KEY_AUDIO_PERM = "track_media_audio_perm"
    }
}

// =================================================================================================
// Compose 界面
// =================================================================================================

@Composable
private fun PhotoCaptureScreen(
    onCaptured: (File) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val previewView = remember { PreviewView(context) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var capturing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            imageCapture = capture
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    capture,
                )
            }
        }, ContextCompat.getMainExecutor(context))
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView({ previewView }, Modifier.fillMaxSize())

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
                onClick = onCancel,
                colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
            ) { Text("取消", color = Color.White) }

            Button(
                onClick = {
                    val capture = imageCapture ?: return@Button
                    if (capturing) return@Button
                    capturing = true

                    val sessionDir = TrackRecordingEngine.currentSessionDir
                    if (sessionDir == null) {
                        Toast.makeText(context, "未在记录中", Toast.LENGTH_SHORT).show()
                        capturing = false
                        return@Button
                    }
                    val mediaDir = File(sessionDir, "media").apply { mkdirs() }
                    val file = File(
                        mediaDir,
                        "photo-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.jpg",
                    )

                    val options = ImageCapture.OutputFileOptions.Builder(file).build()
                    capture.takePicture(
                        options,
                        ContextCompat.getMainExecutor(context),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                onCaptured(file)
                            }

                            override fun onError(exception: ImageCaptureException) {
                                capturing = false
                                Toast.makeText(
                                    context,
                                    "拍照失败: ${exception.message}",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                    )
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (capturing) Color.Gray else Color.White,
                ),
                shape = CircleShape,
                modifier = Modifier.size(72.dp),
                contentPadding = PaddingValues(0.dp),
            ) {
                Box(
                    Modifier
                        .size(48.dp)
                        .background(Color.Red, CircleShape),
                )
            }
        }
    }
}

@Composable
private fun AudioRecordingScreen(
    startMs: Long,
    onStop: () -> Unit,
) {
    var elapsedMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        while (true) {
            elapsedMs = System.currentTimeMillis() - startMs
            delay(200)
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color(0xCC000000)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "● 录音中 ${formatDurationMs(elapsedMs)}",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(32.dp))
            Button(onClick = onStop) { Text("停止录音") }
        }
    }
}

private fun formatDurationMs(ms: Long): String {
    val s = ms / 1000
    return "%02d:%02d".format(s / 60, s % 60)
}