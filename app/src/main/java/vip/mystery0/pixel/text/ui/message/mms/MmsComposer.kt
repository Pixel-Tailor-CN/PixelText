package vip.mystery0.pixel.text.ui.message.mms

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import vip.mystery0.pixel.text.R
import vip.mystery0.pixel.text.data.repository.OutgoingMmsRepository
import vip.mystery0.pixel.text.mms.outgoing.*
import vip.mystery0.pixel.text.ui.component.AppEmptyState
import vip.mystery0.pixel.text.viewmodel.MmsComposerViewModel
import java.util.UUID

/** 外部分享与历史草稿的宿主，只复用统一编辑器，不再暴露底层准备表单。 */
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
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (resendRequestId != null) R.string.msg_mms_composer_reedit
                            else R.string.msg_mms_composer_new_message,
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { viewModel.close(onClose) }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.msg_mms_composer_back_cd))
                    }
                },
                actions = {
                    IconButton(enabled = !state.busy, onClick = { confirmDiscard = true }) {
                        Icon(Icons.Rounded.Delete, stringResource(R.string.msg_mms_composer_delete_draft_cd))
                    }
                },
            )
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                AppEmptyState(
                    text = stringResource(R.string.msg_mms_composer_empty_state),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
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
    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false },
        title = { Text(stringResource(R.string.msg_mms_composer_discard_title)) },
        text = { Text(stringResource(R.string.msg_mms_composer_discard_body)) },
        confirmButton = { TextButton(onClick = { confirmDiscard = false; viewModel.discard() }) { Text(stringResource(R.string.msg_action_delete)) } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.msg_action_keep)) } },
    )
}

@Composable
fun OutgoingMmsStatus(message: OutgoingMmsMessage, coordinator: MmsSendCoordinator = koinInject()) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var resendSession by remember { mutableStateOf<String?>(null) }
    var confirmCleanup by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val label = stringResource(
        when (message.state) {
            MmsSendState.PREPARING, MmsSendState.READY -> R.string.msg_mms_send_state_preparing
            MmsSendState.DISPATCHING, MmsSendState.AWAITING_RESULT -> R.string.msg_mms_send_state_sending
            MmsSendState.SENT -> if (message.providerSyncPending) R.string.msg_mms_send_state_sent_syncing else R.string.msg_mms_send_state_sent
            MmsSendState.FAILED -> R.string.msg_mms_send_state_failed
            MmsSendState.UNKNOWN -> R.string.msg_mms_send_state_unknown
            MmsSendState.CANCELLED -> R.string.msg_mms_send_state_cancelled
        }
    )
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, end = 4.dp), horizontalAlignment = Alignment.End) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.msg_mms_status_label, label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (message.state != MmsSendState.SENT) Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.MoreHoriz, stringResource(R.string.msg_mms_send_actions_cd), Modifier.size(18.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (message.state in setOf(MmsSendState.PREPARING, MmsSendState.READY)) DropdownMenuItem(
                        text = { Text(stringResource(R.string.msg_mms_action_cancel_send)) },
                        onClick = {
                            menu = false
                            scope.launch {
                                if (!coordinator.cancel(message.requestId)) {
                                    error = resources.getString(R.string.msg_mms_error_cannot_recall)
                                }
                            }
                        },
                    )
                    if (!message.deleted && message.state == MmsSendState.FAILED) DropdownMenuItem(
                        text = { Text(stringResource(R.string.msg_mms_action_manual_retry)) },
                        onClick = {
                            menu = false
                            scope.launch {
                                try {
                                    coordinator.retryFailed(message.requestId)
                                } catch (e: Exception) {
                                    error = (e as? MmsSendException)?.userMessage
                                        ?: resources.getString(R.string.msg_mms_error_retry_rejected)
                                }
                            }
                        },
                    )
                    if (!message.deleted && message.state in setOf(MmsSendState.UNKNOWN, MmsSendState.FAILED, MmsSendState.CANCELLED)) DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (message.state == MmsSendState.UNKNOWN) R.string.msg_mms_action_review_resend
                                    else R.string.msg_mms_action_resume_editing,
                                )
                            )
                        },
                        onClick = { menu = false; resendSession = UUID.randomUUID().toString() },
                    )
                    if (message.state == MmsSendState.UNKNOWN && message.pduPath != null) DropdownMenuItem(
                        text = { Text(stringResource(R.string.msg_mms_action_clean_payload)) },
                        onClick = { menu = false; confirmCleanup = true },
                    )
                    if (message.state in setOf(MmsSendState.DISPATCHING, MmsSendState.AWAITING_RESULT)) DropdownMenuItem(
                        text = { Text(stringResource(R.string.msg_mms_error_cannot_recall)) },
                        enabled = false,
                        onClick = {},
                    )
                }
            }
        }
        message.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    if (confirmCleanup) AlertDialog(
        onDismissRequest = { confirmCleanup = false },
        title = { Text(stringResource(R.string.msg_mms_clean_payload_title)) },
        text = { Text(stringResource(R.string.msg_mms_clean_payload_body)) },
        confirmButton = {
            TextButton(onClick = { confirmCleanup = false; scope.launch { coordinator.releaseUnknownPayload(message.requestId) } }) {
                Text(stringResource(R.string.msg_action_clean_up))
            }
        },
        dismissButton = {
            TextButton(onClick = { confirmCleanup = false }) { Text(stringResource(R.string.msg_action_keep)) }
        },
    )
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
    TextButton(onClick = { show = true }) { Text(stringResource(R.string.msg_mms_drafts_pending_btn, visibleDrafts.size, pending.size)) }
    if (show) AlertDialog(
        onDismissRequest = { show = false },
        title = { Text(stringResource(R.string.msg_mms_drafts_pending_title)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            visibleDrafts.forEach { draft ->
                OutlinedButton(onClick = { selectedDraftId = draft.id; session = UUID.randomUUID().toString(); show = false }, modifier = Modifier.fillMaxWidth()) {
                    val recipientText = draft.recipientAddress.ifBlank { stringResource(R.string.msg_mms_no_recipient) }
                    Text(stringResource(R.string.msg_mms_draft_item, recipientText, draft.attachments.size))
                }
            }
            pending.forEach { message ->
                Text(
                    if (message.deleted) stringResource(R.string.msg_mms_deleted_leftover) else message.snapshot.recipientAddress,
                    style = MaterialTheme.typography.labelMedium,
                )
                OutgoingMmsStatus(message)
            }
        } },
        confirmButton = { TextButton(onClick = { show = false }) { Text(stringResource(R.string.msg_action_close)) } },
    )
    selectedDraftId?.let { id -> MmsComposerDialog(session, onClose = { selectedDraftId = null }, existingDraftId = id) }
}
