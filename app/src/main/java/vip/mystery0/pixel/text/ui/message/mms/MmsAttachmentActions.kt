package vip.mystery0.pixel.text.ui.message.mms

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import vip.mystery0.pixel.text.R
import vip.mystery0.pixel.text.data.source.mms.MmsAttachmentExportException
import vip.mystery0.pixel.text.data.source.mms.MmsAttachmentExporter
import vip.mystery0.pixel.text.data.source.mms.MmsAttachmentFailureReason
import vip.mystery0.pixel.text.data.source.mms.SharedMmsAttachment
import vip.mystery0.pixel.text.data.source.mms.suggestedMmsAttachmentName
import vip.mystery0.pixel.text.domain.model.mirror.MessageTransport
import vip.mystery0.pixel.text.domain.model.mms.MmsPartContent
import vip.mystery0.pixel.text.domain.model.mms.MmsPartKey
import vip.mystery0.pixel.text.domain.model.mirror.SourceMessageKey

private enum class ActiveAttachmentAction { OPEN, SAVE, SHARE }

/** 打开和分享都只向外部应用授予本次随机分享副本的单 URI 临时读权限。 */
@Composable
fun MmsAttachmentActions(
    part: MmsPartContent,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    exporter: MmsAttachmentExporter = koinInject(),
    onFeedback: ((String) -> Unit)? = null,
    openLabel: String? = null,
    menuOnly: Boolean = false,
) {
    val resolvedOpenLabel = openLabel ?: stringResource(R.string.msg_mms_open_external)
    val context = androidx.compose.ui.platform.LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val currentPart by rememberUpdatedState(part)
    val currentFeedback by rememberUpdatedState(onFeedback)
    var activeAction by remember(part.key) { mutableStateOf<ActiveAttachmentAction?>(null) }
    var pendingSaveKey by rememberSaveable { mutableStateOf<String?>(null) }

    fun feedback(message: String) {
        currentFeedback?.invoke(message)
            ?: Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun runAction(action: ActiveAttachmentAction, block: suspend () -> Unit) {
        if (activeAction != null) return
        activeAction = action
        scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: ActivityNotFoundException) {
                feedback(resources.getString(R.string.msg_mms_no_app_for_attachment))
            } catch (failure: MmsAttachmentExportException) {
                feedback(failure.userMessage(context))
            } catch (_: SecurityException) {
                feedback(resources.getString(R.string.msg_mms_no_permission_attachment))
            } catch (_: Exception) {
                feedback(resources.getString(R.string.msg_mms_attachment_action_failed))
            } finally {
                activeAction = null
            }
        }
    }

    val createDocument = remember(part.mimeType) {
        ActivityResultContracts.CreateDocument(part.mimeType)
    }
    val saveLauncher = rememberLauncherForActivityResult(createDocument) { destination ->
        val requestedPart = pendingSaveKey?.let(::decodePartKey)
        pendingSaveKey = null
        // 用户取消系统文件选择器时 destination 为空，不显示错误。
        if (destination != null) {
            if (requestedPart == null) {
                feedback(resources.getString(R.string.msg_mms_save_request_expired))
            } else {
                runAction(ActiveAttachmentAction.SAVE) {
                    exporter.export(requestedPart, destination)
                    feedback(resources.getString(R.string.msg_mms_attachment_saved))
                }
            }
        }
    }
    val actionsEnabled = enabled && part.statusInfo().actionable && activeAction == null

    var expanded by remember(part.key) { mutableStateOf(false) }
    fun open() = runAction(ActiveAttachmentAction.OPEN) {
        val shared = exporter.prepareShare(currentPart.key)
        context.startActivity(openIntent(shared))
    }
    fun share() = runAction(ActiveAttachmentAction.SHARE) {
        val shared = exporter.prepareShare(currentPart.key)
        context.startActivity(shareIntent(context, shared))
    }
    fun save() {
        pendingSaveKey = encodePartKey(currentPart.key)
        try {
            saveLauncher.launch(suggestedMmsAttachmentName(currentPart))
        } catch (_: ActivityNotFoundException) {
            pendingSaveKey = null
            feedback(resources.getString(R.string.msg_mms_no_save_app))
        } catch (_: SecurityException) {
            pendingSaveKey = null
            feedback(resources.getString(R.string.msg_mms_no_permission_save_ui))
        } catch (_: Exception) {
            pendingSaveKey = null
            feedback(resources.getString(R.string.msg_mms_cannot_open_save_ui))
        }
    }
    if (menuOnly) {
        Box(modifier) {
            IconButton(enabled = actionsEnabled, onClick = { expanded = true }) {
                Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.msg_mms_attachment_actions_cd, part.displayName))
            }
            DropdownMenu(expanded = expanded && actionsEnabled, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { Text(resolvedOpenLabel) }, leadingIcon = {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, null)
                }, onClick = { expanded = false; open() })
                DropdownMenuItem(text = { Text(stringResource(R.string.msg_action_save)) }, leadingIcon = {
                    Icon(Icons.Rounded.SaveAlt, null)
                }, onClick = { expanded = false; save() })
                DropdownMenuItem(text = { Text(stringResource(R.string.msg_action_share)) }, leadingIcon = {
                    Icon(Icons.Rounded.Share, null)
                }, onClick = { expanded = false; share() })
            }
        }
    } else {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(enabled = actionsEnabled, onClick = ::open, contentPadding = PaddingValues(8.dp)) {
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp)); Text(resolvedOpenLabel, style = MaterialTheme.typography.labelMedium)
            }
            TextButton(enabled = actionsEnabled, onClick = ::save, contentPadding = PaddingValues(8.dp)) {
                Icon(Icons.Rounded.SaveAlt, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.msg_action_save), style = MaterialTheme.typography.labelMedium)
            }
            TextButton(enabled = actionsEnabled, onClick = ::share, contentPadding = PaddingValues(8.dp)) {
                Icon(Icons.Rounded.Share, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.msg_action_share), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

private fun encodePartKey(key: MmsPartKey): String =
    "${key.message.transport.name}:${key.message.sourceId}:${key.partId}"

private fun decodePartKey(value: String): MmsPartKey? {
    val fields = value.split(':')
    if (fields.size != 3) return null
    val transport = runCatching { MessageTransport.valueOf(fields[0]) }.getOrNull() ?: return null
    val messageId = fields[1].toLongOrNull() ?: return null
    val partId = fields[2].toLongOrNull() ?: return null
    return MmsPartKey(SourceMessageKey(transport, messageId), partId)
}

private fun openIntent(shared: SharedMmsAttachment): Intent = Intent(Intent.ACTION_VIEW).apply {
    setDataAndType(shared.uri, shared.mimeType)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    clipData = ClipData.newRawUri(shared.displayName, shared.uri)
}

private fun shareIntent(context: Context, shared: SharedMmsAttachment): Intent {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = shared.mimeType
        putExtra(Intent.EXTRA_STREAM, shared.uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = ClipData.newRawUri(shared.displayName, shared.uri)
    }
    return Intent.createChooser(send, context.getString(R.string.msg_mms_share_chooser_title)).apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

private fun MmsAttachmentExportException.userMessage(context: Context): String = context.getString(
    when (reason) {
        MmsAttachmentFailureReason.MESSAGE_UNAVAILABLE,
        MmsAttachmentFailureReason.PART_UNAVAILABLE,
        MmsAttachmentFailureReason.SOURCE_MISSING -> R.string.msg_mms_export_err_missing
        MmsAttachmentFailureReason.NOT_READY -> R.string.msg_mms_export_err_not_ready
        MmsAttachmentFailureReason.PERMISSION_DENIED -> R.string.msg_mms_export_err_permission
        MmsAttachmentFailureReason.NO_SPACE -> R.string.msg_mms_export_err_no_space
        MmsAttachmentFailureReason.WRITE_FAILED -> R.string.msg_mms_export_err_write_failed
    }
)
