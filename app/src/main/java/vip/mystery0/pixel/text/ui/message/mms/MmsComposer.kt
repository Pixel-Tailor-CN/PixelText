package vip.mystery0.pixel.text.ui.message.mms

import android.net.Uri
import android.telephony.SubscriptionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import vip.mystery0.pixel.text.BuildConfig
import vip.mystery0.pixel.text.data.repository.OutgoingMmsRepository
import vip.mystery0.pixel.text.mms.outgoing.*
import vip.mystery0.pixel.text.viewmodel.MmsComposerViewModel
import java.io.File
import java.util.UUID

@Composable
fun MmsComposerDialog(
    sessionId: String, onClose: () -> Unit, initialRecipient: String = "", initialBody: String = "",
    initialSubject: String = "", initialUris: List<Uri> = emptyList(), inputError: String? = null,
    resendRequestId: String? = null, existingDraftId: String? = null, isExternalInput: Boolean = false,
    startFresh: Boolean = false, onAccepted: () -> Unit = {},
) {
    val viewModel: MmsComposerViewModel = koinViewModel(key = "mms-composer-$sessionId")
    val state by viewModel.state.collectAsState()
    val owner = LocalLifecycleOwner.current
    val context = LocalContext.current
    var confirmSend by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    LaunchedEffect(viewModel) { viewModel.open(initialRecipient, initialBody, initialSubject, initialUris, inputError, resendRequestId, existingDraftId, isExternalInput || startFresh) }
    LaunchedEffect(state.accepted) { if (state.accepted) { onAccepted(); onClose() } }
    LaunchedEffect(state.discarded) { if (state.discarded) onClose() }
    DisposableEffect(owner, viewModel) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshSims() }
        owner.lifecycle.addObserver(observer)
        val manager = context.getSystemService(SubscriptionManager::class.java)
        val listener = object : SubscriptionManager.OnSubscriptionsChangedListener() { override fun onSubscriptionsChanged() = viewModel.refreshSims() }
        runCatching { manager?.addOnSubscriptionsChangedListener(context.mainExecutor, listener) }
        onDispose { owner.lifecycle.removeObserver(observer); runCatching { manager?.removeOnSubscriptionsChangedListener(listener) } }
    }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { viewModel.importAttachments(it) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { viewModel.importAttachments(it) }
    Dialog(onDismissRequest = { viewModel.close(onClose) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(modifier = Modifier.fillMaxSize().imePadding(), topBar = {
            TopAppBar(title = { Text("编辑彩信 · 单人") }, navigationIcon = {
                IconButton(onClick = { viewModel.close(onClose) }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "保存草稿并返回") }
            }, actions = { IconButton(enabled = !state.busy, onClick = { confirmDiscard = true }) { Icon(Icons.Rounded.Delete, "删除草稿") } })
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val draft = state.draft
                if (draft == null) { CircularProgressIndicator(); state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }; return@Column }
                if (!BuildConfig.MMS_SENDING_ENABLED) Text("此构建尚未开启彩信发送，正在进行运营商验收", color = MaterialTheme.colorScheme.error)
                OutlinedTextField(draft.recipientAddress, { text -> viewModel.edit { it.copy(recipientAddress = text) } },
                    label = { Text("确认一个电话号码") }, singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
                Text("仅支持单人彩信，不支持群发或 CC/BCC。请确认号码属于你要联系的人", style = MaterialTheme.typography.bodySmall)
                if (state.sims.isEmpty()) Text("未能读取有效 SIM，请检查电话权限和 SIM 状态", color = MaterialTheme.colorScheme.error)
                state.sims.forEach { sim ->
                    FilterChip(selected = draft.subscriptionId == sim.subscriptionId,
                        onClick = { viewModel.edit { it.copy(subscriptionId = sim.subscriptionId) } },
                        label = { Text("${sim.displayName} · 卡${sim.slotIndex + 1}") }, enabled = !state.busy)
                }
                if (draft.subscriptionId >= 0 && state.sims.none { it.subscriptionId == draft.subscriptionId }) Text("之前选择的 SIM 已失效，请明确重新选卡", color = MaterialTheme.colorScheme.error)
                OutlinedTextField(draft.subject, { text -> viewModel.edit { it.copy(subject = text) } }, label = { Text("主题（可选）") }, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(draft.body, { text -> viewModel.edit { it.copy(body = text) } }, label = { Text("正文") }, enabled = !state.busy, minLines = 3, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !state.busy && draft.attachments.size < 10, onClick = { photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }) { Text("图片 / 视频") }
                    OutlinedButton(enabled = !state.busy && draft.attachments.size < 10, onClick = { files.launch(arrayOf("*/*")) }) { Text("音频 / 文件") }
                }
                Text("附件 ${draft.attachments.size}/10 · 音视频与文件只发送合规原件，原件可能保留元数据", style = MaterialTheme.typography.bodySmall)
                draft.attachments.forEach { original ->
                    val item = state.prepared?.attachments?.firstOrNull { it.id == original.id } ?: original
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if ((item.preparedMime ?: item.originalMime).startsWith("image/")) AsyncImage(
                                model = File(item.preparedPath ?: item.originalPath), contentDescription = "附件预览", modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp))
                            Text(item.displayName); Text("${item.preparedMime ?: item.originalMime} · ${(item.preparedSize ?: item.originalSize) / 1024} KiB", style = MaterialTheme.typography.bodySmall)
                            item.preparationNote?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                            item.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            TextButton(enabled = !state.busy, onClick = { viewModel.removeAttachment(item.id) }) { Text("移除附件") }
                        }
                    }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.unsaved) {
                    Text("草稿尚未保存", style = MaterialTheme.typography.bodySmall)
                    if (state.error != null) TextButton(onClick = { viewModel.edit { it } }) { Text("重试保存") }
                }
                if (state.importBlocked) {
                    Text("有附件未能导入。请重新选择，或明确移除这些未导入附件后继续", color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = viewModel::acknowledgeImportRemoval, enabled = !state.busy) { Text("移除未导入附件并继续") }
                }
                if (state.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); TextButton(onClick = { viewModel.cancelPreparation() }) { Text("取消本地准备") } }
                state.pduBytes?.let { Text("完整彩信大小：$it 字节 / ${state.prepared?.policy?.maxMessageBytes} 字节") }
                if (state.prepared == null) Button(onClick = viewModel::prepare, enabled = !state.busy && !state.unsaved && !state.importBlocked && MmsRecipient.normalize(draft.recipientAddress) != null && draft.subscriptionId in state.sims.map { it.subscriptionId }, modifier = Modifier.fillMaxWidth()) { Text("准备附件并检查彩信") }
                else Button(onClick = { confirmSend = true }, enabled = !state.busy && BuildConfig.MMS_SENDING_ENABLED, modifier = Modifier.fillMaxWidth()) { Text("确认发送彩信") }
                Text("离开页面会保留已保存草稿。系统接管后无法撤回；彩信可能产生运营商费用", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (confirmSend) AlertDialog(onDismissRequest = { confirmSend = false }, title = { Text(if (state.possibleDuplicateOf != null) "仍然重发？" else "发送彩信？") },
        text = { Text("发送给 ${state.prepared?.recipientAddress}，使用 ${state.sims.firstOrNull { it.subscriptionId == state.prepared?.subscriptionId }?.displayName ?: "所选 SIM"}。可能产生运营商费用。" + if (state.possibleDuplicateOf != null) "原彩信可能已经发出，这次会生成独立消息，可能重复送达和收费。" else "") },
        confirmButton = { TextButton(onClick = { confirmSend = false; viewModel.send() }) { Text("发送") } }, dismissButton = { TextButton(onClick = { confirmSend = false }) { Text("取消") } })
    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false }, title = { Text("删除此草稿？") }, text = { Text("删除本地编辑内容和不再使用的附件") },
        confirmButton = { TextButton(onClick = { confirmDiscard = false; viewModel.discard() }) { Text("删除") } }, dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("保留") } })
}

@Composable
fun OutgoingMmsStatus(message: OutgoingMmsMessage, coordinator: MmsSendCoordinator = koinInject()) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var resendSession by remember { mutableStateOf<String?>(null) }
    var confirmCleanup by remember { mutableStateOf(false) }
    val label = when (message.state) {
        MmsSendState.PREPARING, MmsSendState.READY -> "准备发送"
        MmsSendState.DISPATCHING, MmsSendState.AWAITING_RESULT -> "发送中"
        MmsSendState.SENT -> if (message.providerSyncPending) "已发送，记录同步中" else "已发送（不代表已送达）"
        MmsSendState.FAILED -> "发送失败"
        MmsSendState.UNKNOWN -> "发送结果待确认"
        MmsSendState.CANCELLED -> "已取消发送"
    }
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text("彩信 · SIM ${message.snapshot.subscriptionId} · $label", style = MaterialTheme.typography.labelSmall)
        message.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row {
            if (message.state in setOf(MmsSendState.PREPARING, MmsSendState.READY)) TextButton(onClick = { scope.launch { if (!coordinator.cancel(message.requestId)) error = "已交给系统，无法撤回" } }) { Text("取消发送") }
            if (BuildConfig.MMS_SENDING_ENABLED && !message.deleted && message.state == MmsSendState.FAILED) TextButton(onClick = { scope.launch {
                try { coordinator.retryFailed(message.requestId) } catch (e: Exception) { error = (e as? MmsSendException)?.userMessage ?: "重试未接纳，请检查记录" }
            } }) { Text("手动重试") }
            if (BuildConfig.MMS_SENDING_ENABLED && !message.deleted && message.state in setOf(MmsSendState.UNKNOWN, MmsSendState.FAILED, MmsSendState.CANCELLED)) TextButton(onClick = { resendSession = UUID.randomUUID().toString() }) { Text(if (message.state == MmsSendState.UNKNOWN) "查看重发风险" else "恢复编辑") }
        }
    }
    if (message.state == MmsSendState.UNKNOWN && message.pduPath != null) TextButton(onClick = { confirmCleanup = true }) { Text("清理遗留发送文件") }
    if (confirmCleanup) AlertDialog(onDismissRequest = { confirmCleanup = false }, title = { Text("清理遗留发送文件？") },
        text = { Text("清理不能撤回已经提交的彩信，可能中断系统仍在读取的文件。发送结果仍保留为待确认，不会自动重发。") },
        confirmButton = { TextButton(onClick = { confirmCleanup = false; scope.launch { coordinator.releaseUnknownPayload(message.requestId) } }) { Text("清理") } },
        dismissButton = { TextButton(onClick = { confirmCleanup = false }) { Text("保留") } })
    resendSession?.let { MmsComposerDialog(it, onClose = { resendSession = null }, resendRequestId = message.requestId) }
}

@Composable
fun MmsDraftHint(recipient: String, onOpen: () -> Unit, repository: OutgoingMmsRepository = koinInject()) {
    val drafts by remember(repository) { repository.observeDrafts() }.collectAsState(emptyList())
    if (drafts.any { it.recipientAddress == MmsRecipient.normalize(recipient) && (it.body.isNotBlank() || it.subject.isNotBlank() || it.attachments.isNotEmpty()) }) {
        TextButton(onClick = onOpen) { Text("继续编辑彩信草稿") }
    }
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
    TextButton(onClick = { show = true }) { Text("彩信草稿 ${visibleDrafts.size} · 待处理 ${pending.size}") }
    if (show) AlertDialog(onDismissRequest = { show = false }, title = { Text("彩信草稿与发送记录") },
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
