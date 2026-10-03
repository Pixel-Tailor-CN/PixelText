package vip.mystery0.pixel.text.ui.message.mms

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import vip.mystery0.pixel.text.BuildConfig
import vip.mystery0.pixel.text.data.repository.OutgoingMmsRepository
import vip.mystery0.pixel.text.mms.outgoing.*
import vip.mystery0.pixel.text.viewmodel.MmsComposerViewModel
import java.util.UUID

/** 外部分享与历史草稿的宿主，只复用统一编辑器，不再暴露底层准备表单。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MmsComposerDialog(
    sessionId: String, onClose: () -> Unit, initialRecipient: String = "", initialBody: String = "",
    initialSubject: String = "", initialUris: List<Uri> = emptyList(), inputError: String? = null,
    resendRequestId: String? = null, existingDraftId: String? = null, isExternalInput: Boolean = false,
    startFresh: Boolean = false, onAccepted: () -> Unit = {},
) {
    val viewModel: MmsComposerViewModel = koinViewModel(key = "mms-composer-$sessionId")
    val state by viewModel.state.collectAsState()
    var confirmDiscard by remember { mutableStateOf(false) }
    LaunchedEffect(state.discarded) { if (state.discarded) onClose() }
    Dialog(onDismissRequest = { viewModel.close(onClose) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(modifier = Modifier.fillMaxSize().imePadding(), topBar = {
            TopAppBar(title = { Text(if (resendRequestId != null) "重新编辑" else "新消息") }, navigationIcon = {
                IconButton(onClick = { viewModel.close(onClose) }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "保存草稿并返回") }
            }, actions = { IconButton(enabled = !state.busy, onClick = { confirmDiscard = true }) { Icon(Icons.Rounded.Delete, "删除草稿") } })
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.ChatBubbleOutline, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.outline)
                    Text("开始一段对话", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                MessageComposer(
                    recipient = initialRecipient, initialBody = initialBody, initialSubject = initialSubject,
                    initialUris = initialUris, inputError = inputError, retryOfRequestId = resendRequestId,
                    existingDraftId = existingDraftId, isExternalInput = isExternalInput || startFresh,
                    showRecipient = true, sessionKey = sessionId, resetOnSubmission = false,
                    onSubmitted = { onAccepted(); onClose() }, viewModel = viewModel,
                )
            }
        }
    }
    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false }, title = { Text("删除此草稿？") }, text = { Text("删除本地编辑内容和不再使用的附件") },
        confirmButton = { TextButton(onClick = { confirmDiscard = false; viewModel.discard() }) { Text("删除") } }, dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("保留") } })
}

@Composable
fun OutgoingMmsStatus(message: OutgoingMmsMessage, coordinator: MmsSendCoordinator = koinInject()) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var resendSession by remember { mutableStateOf<String?>(null) }
    var confirmCleanup by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val label = when (message.state) {
        MmsSendState.PREPARING, MmsSendState.READY -> "准备发送"
        MmsSendState.DISPATCHING, MmsSendState.AWAITING_RESULT -> "发送中"
        MmsSendState.SENT -> if (message.providerSyncPending) "已发送，记录同步中" else "已发送"
        MmsSendState.FAILED -> "发送失败"
        MmsSendState.UNKNOWN -> "发送结果待确认"
        MmsSendState.CANCELLED -> "已取消发送"
    }
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, end = 4.dp), horizontalAlignment = Alignment.End) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("彩信 · $label", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (message.state != MmsSendState.SENT) Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.MoreHoriz, "发送操作", Modifier.size(18.dp)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (message.state in setOf(MmsSendState.PREPARING, MmsSendState.READY)) DropdownMenuItem(text = { Text("取消发送") }, onClick = {
                        menu = false; scope.launch { if (!coordinator.cancel(message.requestId)) error = "已交给系统，无法撤回" }
                    })
                    if (BuildConfig.MMS_SENDING_ENABLED && !message.deleted && message.state == MmsSendState.FAILED) DropdownMenuItem(text = { Text("手动重试") }, onClick = {
                        menu = false; scope.launch {
                            try { coordinator.retryFailed(message.requestId) } catch (e: Exception) { error = (e as? MmsSendException)?.userMessage ?: "重试未接纳，请检查记录" }
                        }
                    })
                    if (BuildConfig.MMS_SENDING_ENABLED && !message.deleted && message.state in setOf(MmsSendState.UNKNOWN, MmsSendState.FAILED, MmsSendState.CANCELLED)) DropdownMenuItem(
                        text = { Text(if (message.state == MmsSendState.UNKNOWN) "查看重发风险" else "恢复编辑") },
                        onClick = { menu = false; resendSession = UUID.randomUUID().toString() },
                    )
                    if (message.state == MmsSendState.UNKNOWN && message.pduPath != null) DropdownMenuItem(text = { Text("清理遗留发送文件") }, onClick = { menu = false; confirmCleanup = true })
                    if (message.state in setOf(MmsSendState.DISPATCHING, MmsSendState.AWAITING_RESULT)) DropdownMenuItem(text = { Text("已交给系统，无法撤回") }, enabled = false, onClick = {})
                }
            }
        }
        message.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    if (confirmCleanup) AlertDialog(onDismissRequest = { confirmCleanup = false }, title = { Text("清理遗留发送文件？") },
        text = { Text("清理不能撤回已经提交的彩信，可能中断系统仍在读取的文件。发送结果仍保留为待确认，不会自动重发。") },
        confirmButton = { TextButton(onClick = { confirmCleanup = false; scope.launch { coordinator.releaseUnknownPayload(message.requestId) } }) { Text("清理") } },
        dismissButton = { TextButton(onClick = { confirmCleanup = false }) { Text("保留") } })
    resendSession?.let { MmsComposerDialog(it, onClose = { resendSession = null }, resendRequestId = message.requestId) }
}

/** 草稿不是系统消息；列表入口也可发现尚未建 Provider 行的失败请求。 */
@Composable
fun MmsDraftsAndPendingButton(repository: OutgoingMmsRepository = koinInject()) {
    val drafts by remember(repository) { repository.observeDrafts() }.collectAsState(emptyList())
    val messages by remember(repository) { repository.observeMessages() }.collectAsState(emptyList())
    val visibleDrafts = drafts.filter { it.body.isNotBlank() || it.subject.isNotBlank() || it.attachments.isNotEmpty() }
    val pending = messages.filter { !it.deleted && (it.state != MmsSendState.SENT || it.providerSyncPending) || it.deleted && it.state == MmsSendState.UNKNOWN && it.pduPath != null }
    var show by remember { mutableStateOf(false) }
    var selectedDraftId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var session by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    if (visibleDrafts.isEmpty() && pending.isEmpty()) return
    TextButton(onClick = { show = true }) { Text("草稿 ${visibleDrafts.size} · 待处理 ${pending.size}") }
    if (show) AlertDialog(onDismissRequest = { show = false }, title = { Text("草稿与发送记录") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            visibleDrafts.forEach { draft ->
                OutlinedButton(onClick = { selectedDraftId = draft.id; session = UUID.randomUUID().toString(); show = false }, modifier = Modifier.fillMaxWidth()) {
                    Text("草稿 · ${draft.recipientAddress.ifBlank { "未选择收件人" }} · ${draft.attachments.size} 个附件")
                }
            }
            pending.forEach { message ->
                Text(if (message.deleted) "已删除消息的遗留发送文件" else message.snapshot.recipientAddress, style = MaterialTheme.typography.labelMedium)
                OutgoingMmsStatus(message)
            }
        } }, confirmButton = { TextButton(onClick = { show = false }) { Text("关闭") } })
    selectedDraftId?.let { id -> MmsComposerDialog(session, onClose = { selectedDraftId = null }, existingDraftId = id) }
}
