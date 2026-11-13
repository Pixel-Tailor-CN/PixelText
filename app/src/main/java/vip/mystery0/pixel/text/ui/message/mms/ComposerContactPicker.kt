package vip.mystery0.pixel.text.ui.message.mms

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import vip.mystery0.pixel.text.R
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
    val resources = LocalResources.current
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
                    latestError(resources.getString(R.string.msg_contact_picker_no_permission))
                } catch (error: IllegalStateException) {
                    latestError(error.message ?: resources.getString(R.string.msg_contact_picker_export_retry))
                } catch (_: Exception) {
                    latestError(resources.getString(R.string.msg_contact_picker_export_failed))
                } finally {
                    if (!handedOff) { result?.file?.delete(); controller.busy = false }
                }
            }
        }
    }
    val openPicker: () -> Unit = {
        runCatching { picker.launch(null) }.onFailure { latestError(resources.getString(R.string.msg_contact_picker_no_app)) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) openPicker() else latestError(resources.getString(R.string.msg_contact_picker_perm_denied))
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
