package vip.mystery0.pixel.text.ui.message.mms

import android.content.Context
import android.media.MediaPlayer
import android.media.ThumbnailUtils
import android.net.Uri
import android.telephony.SubscriptionManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import vip.mystery0.pixel.text.BuildConfig
import vip.mystery0.pixel.text.mms.outgoing.MmsRecipient
import vip.mystery0.pixel.text.mms.outgoing.MmsPayloadStore
import vip.mystery0.pixel.text.mms.outgoing.MmsAttachment
import vip.mystery0.pixel.text.viewmodel.MmsComposerViewModel
import java.io.File
import java.util.Locale

/** 会话和系统分享共用同一份持久草稿，不再在弹窗间复制正文。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageComposer(
    recipient: String,
    modifier: Modifier = Modifier,
    initialBody: String = "",
    initialSubject: String = "",
    initialUris: List<Uri> = emptyList(),
    inputError: String? = null,
    retryOfRequestId: String? = null,
    existingDraftId: String? = null,
    isExternalInput: Boolean = false,
    showRecipient: Boolean = false,
    sessionKey: String = recipient,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    placeholder: String = "短信",
    resetOnSubmission: Boolean = true,
    onSubmitted: () -> Unit = {},
    viewModel: MmsComposerViewModel = koinViewModel(key = "message-composer-$sessionKey"),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val consent = remember(context) { context.getSharedPreferences("mms_send_consent", Context.MODE_PRIVATE) }
    var menu by remember { mutableStateOf(false) }
    var simMenu by remember { mutableStateOf(false) }
    var confirm by rememberSaveable { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    val latestSubmitted by rememberUpdatedState(onSubmitted)
    val capture = rememberComposerCapture(
        onCaptured = { uri, cleanup -> viewModel.importAttachments(listOf(uri), cleanup) },
        onError = { localError = it },
    )
    val contactPicker = rememberComposerContactPicker(
        onAttachment = { uri, cleanup ->
            if (viewModel.state.value.busy || viewModel.state.value.draft == null) {
                cleanup()
                localError = "草稿正在处理，请稍后重新选择联系人"
            } else viewModel.importAttachments(listOf(uri), cleanup)
        },
        onError = { localError = it },
    )
    val media = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> viewModel.importAttachments(uris) }
    LaunchedEffect(viewModel) {
        viewModel.open(recipient, initialBody, initialSubject, initialUris, inputError, retryOfRequestId, existingDraftId, isExternalInput)
    }
    LaunchedEffect(state.accepted) {
        if (state.accepted) {
            if (resetOnSubmission) viewModel.startNextMessage()
            latestSubmitted()
        }
    }
    DisposableEffect(owner, viewModel) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshSims() }
        owner.lifecycle.addObserver(observer)
        val manager = context.getSystemService(SubscriptionManager::class.java)
        val listener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
            override fun onSubscriptionsChanged() = viewModel.refreshSims()
        }
        runCatching { manager?.addOnSubscriptionsChangedListener(context.mainExecutor, listener) }
        onDispose {
            owner.lifecycle.removeObserver(observer)
            runCatching { manager?.removeOnSubscriptionsChangedListener(listener) }
        }
    }
    val draft = state.draft
    val isMms = draft?.let { it.attachments.isNotEmpty() || it.subject.isNotBlank() } == true
    val enabled = draft != null && !state.busy && !state.accepted && !capture.recording && !contactPicker.busy
    val canSend = enabled && !state.unsaved && !state.importBlocked &&
        MmsRecipient.normalize(draft.recipientAddress) != null && draft.subscriptionId in state.sims.map { it.subscriptionId } &&
        (draft.body.isNotBlank() || draft.attachments.isNotEmpty())
    fun submit() {
        localError = null
        if (isMms && !BuildConfig.MMS_SENDING_ENABLED) {
            localError = "当前版本尚未开放彩信发送，内容已保留"
        } else if ((isMms && !consent.getBoolean("acknowledged", false)) || state.possibleDuplicateOf != null) {
            confirm = true
        } else viewModel.submit()
    }

    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (showRecipient) {
            OutlinedTextField(
                value = draft?.recipientAddress ?: recipient,
                onValueChange = { viewModel.edit { draft -> draft.copy(recipientAddress = it) } },
                enabled = enabled,
                label = { Text("收件人") },
                placeholder = { Text("输入一个手机号码") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Surface(shape = RoundedCornerShape(28.dp), color = containerColor) {
            Column(Modifier.fillMaxWidth().padding(6.dp)) {
                if (draft != null && draft.attachments.isNotEmpty()) {
                    LazyRow(
                        contentPadding = PaddingValues(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        items(draft.attachments, key = { it.id }) { attachment ->
                            ComposerAttachment(attachment, enabled) { viewModel.removeAttachment(attachment.id) }
                        }
                    }
                }
                // 历史草稿和外部分享可能带主题，必须显示，不能切换界面时无声丢失。
                if (draft?.subject?.isNotBlank() == true) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextField(
                            value = draft.subject,
                            onValueChange = { viewModel.edit { draft -> draft.copy(subject = it) } },
                            enabled = enabled,
                            label = { Text("彩信主题") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                        IconButton(onClick = { viewModel.edit { it.copy(subject = "") } }, enabled = enabled) { Icon(Icons.Default.Close, "移除主题") }
                    }
                }
                if (capture.recording) {
                    Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = capture::cancelRecording) { Icon(Icons.Default.DeleteOutline, "取消录音") }
                        Icon(Icons.Default.Mic, null, tint = MaterialTheme.colorScheme.error)
                        Text("${capture.seconds / 60}:${(capture.seconds % 60).toString().padStart(2, '0')} · 最长 1 分钟", Modifier.weight(1f).padding(horizontal = 8.dp))
                        FilledIconButton(onClick = capture::stopRecording) { Icon(Icons.Default.Stop, "结束录音并添加附件") }
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                        IconButton(onClick = { menu = true }, enabled = enabled && BuildConfig.MMS_SENDING_ENABLED) {
                            Icon(Icons.Default.Add, "添加附件")
                        }
                        BasicTextField(
                            value = draft?.body ?: initialBody,
                            onValueChange = { viewModel.edit { draft -> draft.copy(body = it) } },
                            enabled = enabled,
                            maxLines = 6,
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                            modifier = Modifier.weight(1f).padding(vertical = 13.dp, horizontal = 4.dp).heightIn(min = 24.dp),
                            decorationBox = { field ->
                                Box {
                                    if ((draft?.body ?: initialBody).isEmpty()) Text(if (isMms) "添加消息" else placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    field()
                                }
                            },
                        )
                        FilledIconButton(onClick = ::submit, enabled = canSend, modifier = Modifier.size(48.dp)) {
                            if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Icon(Icons.AutoMirrored.Filled.Send, if (isMms) "发送彩信" else "发送短信", Modifier.size(22.dp))
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (isMms) "MMS · 彩信" else "SMS · 短信", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Box {
                val sim = state.sims.firstOrNull { it.subscriptionId == draft?.subscriptionId }
                TextButton(onClick = { simMenu = true }, enabled = enabled, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp), modifier = Modifier.height(28.dp)) {
                    Icon(Icons.Default.SimCard, null, Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(sim?.displayName ?: "选择 SIM", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    Icon(Icons.Default.ArrowDropDown, null, Modifier.size(16.dp))
                }
                DropdownMenu(expanded = simMenu, onDismissRequest = { simMenu = false }) {
                    state.sims.forEach { card ->
                        DropdownMenuItem(text = { Text(card.displayName) }, onClick = { simMenu = false; viewModel.edit { it.copy(subscriptionId = card.subscriptionId) } })
                    }
                    if (state.sims.isEmpty()) DropdownMenuItem(text = { Text("未检测到可用 SIM") }, enabled = false, onClick = {})
                }
            }
        }
        if ((state.busy || contactPicker.busy) && !state.accepted) {
            Text("正在处理，请稍候…", Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val error = localError ?: state.error
        if (error != null) {
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.ErrorOutline, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                Text(error, Modifier.weight(1f).padding(start = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        if (state.importBlocked && !state.busy) {
            TextButton(onClick = viewModel::acknowledgeImportRemoval) { Text("移除导入失败项，保留现有内容") }
        }
    }
    if (menu) {
        ModalBottomSheet(onDismissRequest = { menu = false }) {
            Text("添加到消息", Modifier.padding(start = 24.dp, bottom = 16.dp), style = MaterialTheme.typography.titleLarge)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                AttachmentAction("照片与视频", Icons.Default.PhotoLibrary) { menu = false; media.launch(arrayOf("image/*", "video/*")) }
                AttachmentAction("拍照", Icons.Default.PhotoCamera) { menu = false; localError = null; capture.photo() }
                AttachmentAction("文件", Icons.Default.AttachFile) { menu = false; media.launch(arrayOf("*/*")) }
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                AttachmentAction("音频", Icons.Default.AudioFile) { menu = false; media.launch(arrayOf("audio/*")) }
                AttachmentAction("录音", Icons.Default.Mic) { menu = false; localError = null; capture.record() }
                AttachmentAction("联系人", Icons.Default.Person) { menu = false; localError = null; contactPicker.launch() }
            }
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            icon = { Icon(if (state.possibleDuplicateOf != null) Icons.Default.WarningAmber else Icons.Default.Info, null) },
            title = { Text(if (state.possibleDuplicateOf != null) "仍要再次发送？" else "发送彩信") },
            text = {
                Text(if (state.possibleDuplicateOf != null) "上一条消息的结果未知，对方可能已经收到。再次发送可能造成重复送达和重复收费。"
                    else "将向 ${draft?.recipientAddress.orEmpty()} 发送彩信，使用 ${state.sims.firstOrNull { it.subscriptionId == draft?.subscriptionId }?.displayName ?: "所选 SIM"}。运营商可能收取费用，后续彩信不再重复提示。")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    // 同一 UI 事件内提交，避免等待偏好写盘时又编辑了已确认的收件人或内容。
                    // 若异步保存失败，下次再次提示费用，不放宽发送权限。
                    if (isMms) consent.edit().putBoolean("acknowledged", true).apply()
                    viewModel.submit()
                }) { Text(if (state.possibleDuplicateOf != null) "仍然发送" else "确认发送") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun AttachmentAction(label: String, icon: ImageVector, onClick: () -> Unit) {
    Column(Modifier.width(88.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(56.dp)) { Icon(icon, label) }
        Text(label, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ComposerAttachment(attachment: MmsAttachment, enabled: Boolean, onRemove: () -> Unit) {
    val store = koinInject<MmsPayloadStore>()
    val file = remember(attachment.originalPath) { runCatching { store.file(attachment.originalPath) }.getOrNull() }
    val visual = attachment.originalMime.startsWith("image/") || attachment.originalMime.startsWith("video/")
    val audio = attachment.originalMime.startsWith("audio/")
    var playing by remember(attachment.id) { mutableStateOf(false) }
    var player by remember(attachment.id) { mutableStateOf<MediaPlayer?>(null) }
    var playbackError by remember(attachment.id) { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    fun stop() { runCatching { player?.release() }; player = null; playing = false }
    DisposableEffect(attachment.id, owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) stop() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); stop() }
    }
    LaunchedEffect(enabled) { if (!enabled) stop() }
    val thumbnail by produceState<ImageBitmap?>(null, file, attachment.originalMime) {
        if (file != null && attachment.originalMime.startsWith("video/")) value = withContext(Dispatchers.IO) {
            runCatching { ThumbnailUtils.createVideoThumbnail(file, Size(256, 256), null).asImageBitmap() }.getOrNull()
        }
    }
    Box(Modifier.width(if (visual) 112.dp else 216.dp).height(112.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        if (visual) {
            if (attachment.originalMime.startsWith("image/")) AsyncImage(file, "图片附件", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else {
                thumbnail?.let { Image(it, "视频附件", Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                Icon(Icons.Default.PlayCircle, "视频附件", Modifier.align(Alignment.Center).size(36.dp), tint = if (thumbnail != null) Color.White else MaterialTheme.colorScheme.onSurface)
                Text("视频 · ${attachmentSize(attachment.originalSize)}",
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().background(MaterialTheme.colorScheme.surface.copy(alpha = .9f)).padding(6.dp),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
            }
        } else {
            Row(Modifier.align(Alignment.Center).padding(start = 12.dp, end = 30.dp), verticalAlignment = Alignment.CenterVertically) {
                if (audio) IconButton(onClick = {
                    if (player != null) stop() else {
                        playbackError = false
                        runCatching {
                            val next = MediaPlayer()
                            player = next
                            next.setDataSource(file?.absolutePath ?: error("missing audio"))
                            next.setOnCompletionListener { stop() }
                            next.setOnErrorListener { _, _, _ -> playbackError = true; stop(); true }
                            next.setOnPreparedListener { if (player === it) { it.start(); playing = true } }
                            playing = true
                            next.prepareAsync()
                        }.onFailure { playbackError = true; stop() }
                    }
                }, enabled = enabled && file != null) { Icon(if (playing) Icons.Default.StopCircle else Icons.Default.PlayCircle, if (playing) "停止试听" else "试听音频") }
                else Icon(if (attachment.originalMime in setOf("text/vcard", "text/x-vcard", "application/vcard", "application/x-vcard")) Icons.Default.ContactPage else Icons.AutoMirrored.Filled.InsertDriveFile,
                    null, Modifier.size(32.dp))
                Column(Modifier.padding(start = 6.dp)) {
                    Text(attachment.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                    Text(if (playbackError) "无法试听" else attachmentSize(attachment.originalSize), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        FilledIconButton(onClick = onRemove, enabled = enabled, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(28.dp), shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .9f))) {
            Icon(Icons.Default.Close, "移除附件 ${attachment.displayName}", Modifier.size(16.dp))
        }
        attachment.error?.let { Text(it, Modifier.align(Alignment.BottomStart).background(MaterialTheme.colorScheme.errorContainer).padding(4.dp), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.labelSmall, maxLines = 2) }
    }
}

private fun attachmentSize(bytes: Long): String = if (bytes >= 1024 * 1024) String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024)) else "${(bytes + 1023) / 1024} KB"
