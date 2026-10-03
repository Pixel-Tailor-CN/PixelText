package vip.mystery0.pixel.text.ui.message.mms

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import vip.mystery0.pixel.text.data.source.mms.MmsContactExporter

internal class ComposerContactPicker {
    var busy by mutableStateOf(false)
        internal set
    internal var open: () -> Unit = {}
    fun launch() = open()
}

@Composable
internal fun rememberComposerContactPicker(
    onAttachment: (Uri, () -> Unit) -> Unit,
    onError: (String) -> Unit,
): ComposerContactPicker {
    val context = LocalContext.current
    val exporter: MmsContactExporter = koinInject()
    val scope = rememberCoroutineScope()
    val controller = remember { ComposerContactPicker() }
    val latestAttachment by rememberUpdatedState(onAttachment)
    val latestError by rememberUpdatedState(onError)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri ->
        if (uri != null && !controller.busy) {
            controller.busy = true
            scope.launch {
                var result: MmsContactExporter.Attachment? = null
                var handedOff = false
                try {
                    val attachment = exporter.export(uri)
                    result = attachment
                    latestAttachment(attachment.uri) {
                        attachment.file.delete()
                        controller.busy = false
                    }
                    handedOff = true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: SecurityException) {
                    latestError("无法读取联系人，请允许通讯录权限后重试")
                } catch (error: IllegalStateException) {
                    latestError(error.message ?: "无法导出联系人，请重新选择")
                } catch (_: Exception) {
                    latestError("无法导出联系人，请检查权限和存储空间后重试")
                } finally {
                    if (!handedOff) { result?.file?.delete(); controller.busy = false }
                }
            }
        }
    }
    val openPicker: () -> Unit = {
        runCatching { picker.launch(null) }.onFailure { latestError("没有可用的联系人选择应用") }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) openPicker() else latestError("未获得通讯录权限，已有消息内容已保留")
    }
    SideEffect {
        controller.open = {
            if (!controller.busy) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) openPicker()
                else permission.launch(Manifest.permission.READ_CONTACTS)
            }
        }
    }
    return controller
}
