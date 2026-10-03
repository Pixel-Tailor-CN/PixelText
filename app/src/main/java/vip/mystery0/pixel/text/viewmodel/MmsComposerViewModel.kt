package vip.mystery0.pixel.text.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import vip.mystery0.pixel.text.data.repository.OutgoingMmsRepository
import vip.mystery0.pixel.text.mms.outgoing.*
import vip.mystery0.pixel.text.util.SimInfo
import vip.mystery0.pixel.text.util.SimInfoProvider

/** 页面只编辑持久草稿与接纳命令；平台发送生命周期由 Worker/Receiver 管理。 */
class MmsComposerViewModel(
    private val context: Context,
    private val savedState: SavedStateHandle,
    private val repository: OutgoingMmsRepository,
    private val preparer: MmsAttachmentPreparer,
    private val payloads: MmsPayloadStore,
    private val coordinator: MmsSendCoordinator,
) : ViewModel() {
    data class State(val draft: MmsDraft? = null, val busy: Boolean = false, val error: String? = null,
        val prepared: MmsSendSnapshot? = null, val pduBytes: Int? = null, val accepted: Boolean = false,
        val sims: List<SimInfo> = emptyList(), val possibleDuplicateOf: String? = null, val importBlocked: Boolean = false, val unsaved: Boolean = false, val discarded: Boolean = false)
    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    private val edits = Mutex()
    private var work: Job? = null
    private var opened = false
    private var editGeneration = 0L
    fun open(recipient: String, body: String = "", subject: String = "", uris: List<Uri> = emptyList(), inputError: String? = null, resendRequestId: String? = null, existingDraftId: String? = null, isExternalInput: Boolean = false) {
        if (opened) return
        opened = true
        work = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(busy = true)
            try {
                val restored = savedState.get<String>("mmsDraftId") ?: existingDraftId
                var draft = when {
                    restored != null -> repository.openDraft(recipient, restored)
                    resendRequestId != null -> repository.copyToDraft(resendRequestId)
                    isExternalInput -> repository.createDraft(recipient)
                    else -> repository.openDraft(recipient)
                }
                savedState["mmsDraftId"] = draft.id
                val sims = SimInfoProvider.getActiveSimList(context)
                // 已存的无效订阅绝不换卡；仅全新草稿应用有效系统默认或唯一激活卡。
                if (draft.revision == 0L && draft.subscriptionId < 0) {
                    val default = SimInfoProvider.getDefaultSmsSubscriptionId()
                    val subId = sims.firstOrNull { it.subscriptionId == default }?.subscriptionId
                        ?: sims.singleOrNull()?.subscriptionId ?: -1
                    draft = repository.saveDraft(draft.copy(body = body, subject = subject, subscriptionId = subId, importError = inputError?.takeIf { it.contains("附件") }), draft.revision) ?: draft
                }
                mutableState.value = State(draft = draft, sims = sims, error = inputError,
                    possibleDuplicateOf = draft.possibleDuplicateOf,
                    importBlocked = draft.importError != null)
                // 重建页面不重复导入分享，也不重建发送命令。
                if (restored == null && uris.isNotEmpty()) importAttachments(uris)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutableState.value = mutableState.value.copy(error = safeError(error), busy = false) }
        }
    }
    fun refreshSims() {
        viewModelScope.launch {
            val sims = withContext(Dispatchers.IO) { SimInfoProvider.getActiveSimList(context) }
            val current = mutableState.value
            mutableState.value = current.copy(sims = sims,
                prepared = if (current.draft?.subscriptionId in sims.map { it.subscriptionId }) current.prepared else null)
        }
    }
    fun edit(transform: (MmsDraft) -> MmsDraft) {
        val state = mutableState.value
        if (state.busy) return
        val before = state.draft ?: return
        val edited = transform(before)
        val generation = ++editGeneration
        // IME 必须在同一帧收到新文字；串行落盘只更新revision，不能覆盖随后输入。
        mutableState.value = state.copy(draft = edited, prepared = null, pduBytes = null, error = null, importBlocked = edited.importError != null, unsaved = true)
        viewModelScope.launch {
            edits.withLock {
                if (generation != editGeneration) return@withLock
                val current = mutableState.value.draft ?: return@withLock
                try {
                    val updated = repository.saveDraft(current, current.revision)
                        ?: throw MmsSendException("草稿已在另一个窗口更新，请关闭后重新打开")
                    val latest = mutableState.value
                    if (latest.draft?.id == updated.id) {
                        mutableState.value = latest.copy(draft = latest.draft.copy(revision = updated.revision, updatedAt = updated.updatedAt), unsaved = generation != editGeneration)
                    }
                } catch (error: Exception) { mutableState.value = mutableState.value.copy(error = safeError(error)) }
            }
        }
    }
    fun importAttachments(uris: List<Uri>) {
        if (uris.isEmpty() || mutableState.value.busy) return
        work = viewModelScope.launch {
            edits.withLock {
                var draft = mutableState.value.draft ?: return@withLock
                if (uris.size + draft.attachments.size > 10) { mutableState.value = mutableState.value.copy(error = "每条彩信最多 10 个附件，请重新选择"); return@withLock }
                draft = repository.saveDraft(draft.copy(importError = "附件导入未完成，请重新选择或明确移除"), draft.revision)
                    ?: return@withLock
                mutableState.value = mutableState.value.copy(draft = draft, busy = true, prepared = null, pduBytes = null, error = null, importBlocked = true)
                val imported = mutableListOf<MmsAttachment>()
                var saved = false
                try {
                    uris.forEach { uri ->
                        val item = withContext(Dispatchers.IO) { preparer.importAttachment(uri) }
                        imported += item
                        if (draft.attachments.sumOf { it.originalSize } + imported.sumOf { it.originalSize } > 64L * 1024 * 1024) throw MmsSendException("附件原件总大小超过 64 MiB")
                    }
                    val next = repository.saveDraft(draft.copy(attachments = draft.attachments + imported, importError = null), draft.revision)
                        ?: throw MmsSendException("草稿已更新，请重新选择附件")
                    saved = true
                    mutableState.value = mutableState.value.copy(draft = next, importBlocked = false)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { mutableState.value = mutableState.value.copy(error = safeError(error), importBlocked = true) }
                finally {
                    if (!saved) withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                        imported.forEach { repository.enqueueCleanup(it.originalPath) }; coordinator.scheduleRecovery()
                    }
                    mutableState.value = mutableState.value.copy(busy = false)
                }
            }
        }
    }
    fun removeAttachment(id: String) {
        val old = mutableState.value.draft?.attachments?.firstOrNull { it.id == id } ?: return
        edit { it.copy(attachments = it.attachments.filterNot { item -> item.id == id }) }
        viewModelScope.launch { repository.enqueueCleanup(old.originalPath); old.preparedPath?.let { repository.enqueueCleanup(it) }; coordinator.scheduleRecovery() }
    }
    fun prepare() {
        if (mutableState.value.busy || mutableState.value.importBlocked || mutableState.value.unsaved) return
        work = viewModelScope.launch {
            edits.withLock {
                val draft = mutableState.value.draft ?: return@withLock
                mutableState.value = mutableState.value.copy(busy = true, error = null, prepared = null)
                var preparedResult: MmsSendSnapshot? = null
                var savedResult = false
                try {
                    val snapshot = coordinator.prepare(draft)
                    preparedResult = snapshot
                    val latest = repository.getDraft(draft.id)
                    if (latest?.revision != draft.revision) throw MmsSendException("草稿已更新，请重新准备")
                    val saved = repository.saveDraft(draft.copy(attachments = snapshot.attachments), draft.revision)
                        ?: throw MmsSendException("草稿已更新，请重新准备")
                    draft.attachments.mapNotNull { it.preparedPath }.filterNot { path -> snapshot.attachments.any { it.preparedPath == path } }
                        .forEach { repository.enqueueCleanup(it) }
                    mutableState.value = mutableState.value.copy(draft = saved, prepared = snapshot, pduBytes = coordinator.preparedSize(snapshot))
                    savedResult = true
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { mutableState.value = mutableState.value.copy(error = safeError(error)) }
                finally {
                    if (!savedResult) withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                        preparedResult?.attachments?.mapNotNull { it.preparedPath }?.forEach { repository.enqueueCleanup(it) }
                        coordinator.scheduleRecovery()
                    }
                    mutableState.value = mutableState.value.copy(busy = false)
                }
            }
        }
    }
    fun send() {
        if (mutableState.value.busy) return
        work = viewModelScope.launch {
            edits.withLock {
                val state = mutableState.value; val draft = state.draft ?: return@withLock
                val snapshot = state.prepared ?: return@withLock
                mutableState.value = state.copy(busy = true, error = null)
                try {
                    coordinator.accept(draft.id, draft.revision, snapshot, state.possibleDuplicateOf)
                    savedState.remove<String>("mmsDraftId")
                    mutableState.value = mutableState.value.copy(accepted = true)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { mutableState.value = mutableState.value.copy(error = safeError(error), prepared = null) }
                finally { mutableState.value = mutableState.value.copy(busy = false) }
            }
        }
    }
    fun discard() {
        if (mutableState.value.busy) return
        viewModelScope.launch { edits.withLock {
            val draft = mutableState.value.draft ?: return@withLock
            if (repository.discardDraft(draft.id, draft.revision)) {
                savedState.remove<String>("mmsDraftId"); coordinator.scheduleRecovery()
                mutableState.value = mutableState.value.copy(discarded = true)
            }
        } }
    }
    fun close(onClosed: () -> Unit) {
        work?.cancel()
        viewModelScope.launch {
            edits.withLock { }
            if (mutableState.value.unsaved) mutableState.value = mutableState.value.copy(error = "输入尚未成功保存，请重试保存或明确删除草稿后返回")
            else onClosed()
        }
    }
    fun cancelPreparation() { work?.cancel() }
    fun acknowledgeImportRemoval() { edit { it.copy(importError = null) } }
    private fun safeError(error: Exception) = (error as? MmsSendException)?.userMessage ?: "彩信准备失败，草稿已保留，请重试"
}
