package vip.mystery0.pixel.text.ui.message.mms

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
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
) {
    val context = androidx.compose.ui.platform.LocalContext.current
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
                feedback("没有可处理此附件的应用")
            } catch (failure: MmsAttachmentExportException) {
                feedback(failure.userMessage())
            } catch (_: SecurityException) {
                feedback("没有权限访问附件，请重试")
            } catch (_: Exception) {
                feedback("附件处理失败，请重试")
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
                feedback("保存请求已失效，请重新选择附件")
            } else {
                runAction(ActiveAttachmentAction.SAVE) {
                    exporter.export(requestedPart, destination)
                    feedback("附件已保存")
                }
            }
        }
    }
    val actionsEnabled = enabled && part.statusInfo().actionable && activeAction == null

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        TextButton(
            enabled = actionsEnabled,
            onClick = {
                runAction(ActiveAttachmentAction.OPEN) {
                    val shared = exporter.prepareShare(currentPart.key)
                    context.startActivity(openIntent(shared))
                }
            },
        ) {
            Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null)
            Text("打开")
        }
        TextButton(
            enabled = actionsEnabled,
            onClick = {
                pendingSaveKey = encodePartKey(currentPart.key)
                saveLauncher.launch(suggestedMmsAttachmentName(currentPart))
            },
        ) {
            Icon(Icons.Rounded.SaveAlt, contentDescription = null)
            Text("保存")
        }
        TextButton(
            enabled = actionsEnabled,
            onClick = {
                runAction(ActiveAttachmentAction.SHARE) {
                    val shared = exporter.prepareShare(currentPart.key)
                    context.startActivity(shareIntent(shared))
                }
            },
        ) {
            Icon(Icons.Rounded.Share, contentDescription = null)
            Text("分享")
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

private fun shareIntent(shared: SharedMmsAttachment): Intent {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = shared.mimeType
        putExtra(Intent.EXTRA_STREAM, shared.uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = ClipData.newRawUri(shared.displayName, shared.uri)
    }
    return Intent.createChooser(send, "分享附件").apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

private fun MmsAttachmentExportException.userMessage(): String = when (reason) {
    MmsAttachmentFailureReason.MESSAGE_UNAVAILABLE,
    MmsAttachmentFailureReason.PART_UNAVAILABLE,
    MmsAttachmentFailureReason.SOURCE_MISSING -> "附件已删除或失效，请刷新后重试"
    MmsAttachmentFailureReason.NOT_READY -> "附件仍在准备中，请稍后重试"
    MmsAttachmentFailureReason.PERMISSION_DENIED -> "没有权限访问附件或目标位置，请重试"
    MmsAttachmentFailureReason.NO_SPACE -> "存储空间不足，请清理后重试"
    MmsAttachmentFailureReason.WRITE_FAILED -> "附件写入失败，请重试"
}
