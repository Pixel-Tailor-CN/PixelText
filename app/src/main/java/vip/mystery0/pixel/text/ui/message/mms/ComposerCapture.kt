package vip.mystery0.pixel.text.ui.message.mms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
import java.io.File
import java.util.UUID
import vip.mystery0.pixel.text.R

/** 仅管理采集文件；成功后仍需经过统一附件导入，不能把相机 URI 当作持久草稿。 */
class ComposerCapture internal constructor(private val context: Context) {
    var recording by mutableStateOf(false)
        private set
    var seconds by mutableIntStateOf(0)
        internal set
    internal var startedAt = 0L
    internal var takePhoto: () -> Unit = {}
    internal var requestRecording: () -> Unit = {}
    internal var onCaptured: (Uri, () -> Unit) -> Unit = { _, cleanup -> cleanup() }
    internal var onError: (String) -> Unit = {}
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null

    fun photo() = takePhoto()
    fun record() = requestRecording()

    internal fun startRecording() {
        if (recording) return
        val target = newFile(context, "m4a")
        try {
            val next = MediaRecorder(context)
            recorder = next
            recordingFile = target
            next.setAudioSource(MediaRecorder.AudioSource.MIC)
            next.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            next.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            next.setAudioEncodingBitRate(24_000)
            next.setAudioSamplingRate(22_050)
            next.setOutputFile(target.absolutePath)
            next.setMaxDuration(60_000)
            next.setMaxFileSize(256 * 1024L)
            next.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED ||
                    what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) stopRecording()
            }
            next.prepare()
            next.start()
            startedAt = SystemClock.elapsedRealtime()
            seconds = 0
            recording = true
        } catch (_: Exception) {
            cancelRecording()
            onError(context.getString(R.string.msg_capture_record_error))
        }
    }

    fun stopRecording() {
        if (!recording) return
        val file = recordingFile
        val valid = runCatching { recorder?.stop(); file != null && file.length() > 0 }.getOrDefault(false)
        release()
        if (valid && file != null) onCaptured(uri(context, file)) { file.delete() }
        else { file?.delete(); onError(context.getString(R.string.msg_capture_record_too_short)) }
    }

    fun cancelRecording() {
        val file = recordingFile
        runCatching { recorder?.stop() }
        release()
        file?.delete()
    }

    private fun release() {
        runCatching { recorder?.release() }
        recorder = null
        recordingFile = null
        recording = false
    }

    internal companion object {
        fun newFile(context: Context, extension: String): File =
            File(context.cacheDir, "mms-share/capture/${UUID.randomUUID()}.$extension").also { it.parentFile?.mkdirs() }
        fun uri(context: Context, file: File): Uri =
            FileProvider.getUriForFile(context, "${context.packageName}.mms.attachment", file,
                if (file.extension == "m4a") context.getString(R.string.msg_capture_voice_filename)
                else context.getString(R.string.msg_capture_photo_filename))
    }
}

@Composable
fun rememberComposerCapture(
    onCaptured: (Uri, () -> Unit) -> Unit,
    onError: (String) -> Unit,
): ComposerCapture {
    val context = LocalContext.current
    val resources = LocalResources.current
    val owner = LocalLifecycleOwner.current
    val controller = remember(context) { ComposerCapture(context) }
    val latestCaptured by rememberUpdatedState(onCaptured)
    val latestError by rememberUpdatedState(onError)
    var cameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val path = cameraPath
        cameraPath = null
        path?.let { File(it) }?.let { file ->
            if (success && file.isFile && file.length() > 0) latestCaptured(ComposerCapture.uri(context, file)) { file.delete() }
            else file.delete()
        }
    }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) controller.startRecording() else latestError(resources.getString(R.string.msg_capture_mic_denied))
    }
    SideEffect {
        controller.onCaptured = { uri, cleanup -> latestCaptured(uri, cleanup) }
        controller.onError = { latestError(it) }
        controller.takePhoto = {
            val file = ComposerCapture.newFile(context, "jpg")
            cameraPath = file.path
            runCatching { camera.launch(ComposerCapture.uri(context, file)) }.onFailure {
                file.delete(); cameraPath = null; latestError(resources.getString(R.string.msg_capture_no_camera))
            }
        }
        controller.requestRecording = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) controller.startRecording()
            else microphone.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    LaunchedEffect(controller.recording) {
        while (controller.recording) {
            controller.seconds = ((SystemClock.elapsedRealtime() - controller.startedAt) / 1000).toInt()
            delay(200.milliseconds)
        }
    }
    DisposableEffect(owner, controller) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE && controller.recording) {
                controller.cancelRecording()
                latestError(resources.getString(R.string.msg_capture_record_cancelled))
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); controller.cancelRecording() }
    }
    return controller
}
