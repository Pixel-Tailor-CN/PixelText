package vip.mystery0.pixel.text.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import vip.mystery0.pixel.text.R
import vip.mystery0.pixel.text.data.db.outgoing.MmsAttemptEntity
import vip.mystery0.pixel.text.data.db.outgoing.MmsCallbackEntity
import vip.mystery0.pixel.text.data.db.outgoing.MmsCleanupEntity
import vip.mystery0.pixel.text.data.db.outgoing.MmsDraftEntity
import vip.mystery0.pixel.text.data.db.outgoing.MmsRequestEntity
import vip.mystery0.pixel.text.data.db.outgoing.OutgoingMmsDatabase
import vip.mystery0.pixel.text.mms.outgoing.*
import vip.mystery0.pixel.text.util.AppStrings
import java.util.UUID

/** 所有跨实体更新在 Room 事务中完成；WorkManager 唯一任务不能替代提交栅栏。 */
class OutgoingMmsRepository(private val database: OutgoingMmsDatabase) {
    private val dao = database.dao()
    private fun MmsDraftEntity.model() = MmsSnapshotCodec.decodeDraft(id, revision, updatedAt, content)
    private fun MmsDraft.entity(accepted: Boolean = false) = MmsDraftEntity(id, revision, recipientAddress, MmsSnapshotCodec.encodeDraft(this), updatedAt, accepted)
    private fun model(request: MmsRequestEntity, attempt: MmsAttemptEntity) = OutgoingMmsMessage(
        request.id, request.draftId, request.draftRevision, MmsSnapshotCodec.decode(request.snapshot),
        attempt.token, attempt.transactionId, MmsSendState.valueOf(attempt.state), request.sourceId,
        request.providerReady, attempt.pduPath, attempt.pduSha256, attempt.submittedAt, attempt.resultCode,
        attempt.httpStatus, attempt.responseStatus, attempt.messageId, attempt.confirmation,
        if (request.lateSuccess) AppStrings.get(R.string.domain_mms_send_late_success_duplicate) else attempt.error,
        request.providerSyncPending, request.deleted, request.possibleDuplicateOf,
    )
    fun observeDrafts(): Flow<List<MmsDraft>> = dao.observeDrafts().map { rows -> rows.map { it.model() } }
    fun observeMessages(): Flow<List<OutgoingMmsMessage>> = combine(dao.observeRequests(), dao.observeAttempts()) { requests, attempts ->
        val byToken = attempts.associateBy { it.token }
        requests.mapNotNull { request -> byToken[request.currentAttempt]?.let { model(request, it) } }
    }
    suspend fun getDraft(id: String): MmsDraft? = dao.draft(id)?.takeUnless { it.accepted }?.model()
    suspend fun createDraft(recipient: String): MmsDraft = database.withTransaction {
        MmsDraft(UUID.randomUUID().toString(), recipientAddress = MmsRecipient.normalize(recipient).orEmpty()).also { dao.insertDraft(it.entity()) }
    }
    suspend fun openDraft(recipient: String, existingId: String? = null): MmsDraft = database.withTransaction {
        existingId?.let { dao.draft(it)?.takeUnless { row -> row.accepted }?.let { row -> return@withTransaction row.model() } }
        val normalized = MmsRecipient.normalize(recipient).orEmpty()
        dao.latestDraft(normalized)?.let { return@withTransaction it.model() }
        MmsDraft(UUID.randomUUID().toString(), recipientAddress = normalized).also { dao.insertDraft(it.entity()) }
    }
    suspend fun copyToDraft(requestId: String): MmsDraft = database.withTransaction {
        val original = get(requestId)
            ?: throw MmsSendException(AppStrings.get(R.string.domain_mms_send_origin_unavailable))
        if (dao.request(requestId)?.lateSuccess == true) {
            throw MmsSendException(AppStrings.get(R.string.domain_mms_send_previous_late_success))
        }
        if (original.deleted || original.state !in setOf(MmsSendState.UNKNOWN, MmsSendState.FAILED, MmsSendState.CANCELLED)) {
            throw MmsSendException(AppStrings.get(R.string.domain_mms_send_origin_state_changed))
        }
        val snapshot = original.snapshot
        MmsDraft(UUID.randomUUID().toString(), recipientAddress = snapshot.recipientAddress, body = snapshot.body,
            subject = snapshot.subject, subscriptionId = snapshot.subscriptionId, attachments = snapshot.attachments,
            possibleDuplicateOf = original.requestId.takeIf { original.state == MmsSendState.UNKNOWN }, originRequestId = original.requestId).also { dao.insertDraft(it.entity()) }
    }
    suspend fun saveDraft(value: MmsDraft, expectedRevision: Long): MmsDraft? = database.withTransaction {
        val current = dao.draft(value.id) ?: return@withTransaction null
        if (current.accepted || current.revision != expectedRevision) return@withTransaction null
        check(value.attachments.size <= 10 && value.attachments.sumOf { it.originalSize } <= 64L * 1024 * 1024)
        val next = value.copy(revision = expectedRevision + 1, updatedAt = System.currentTimeMillis())
        dao.updateDraft(next.entity()); next
    }
    suspend fun discardDraft(id: String, revision: Long): Boolean = database.withTransaction {
        val draft = dao.draft(id) ?: return@withTransaction false
        if (draft.accepted || draft.revision != revision) return@withTransaction false
        draft.model().attachments.forEach { attachment ->
            dao.addCleanup(MmsCleanupEntity(attachment.originalPath))
            attachment.preparedPath?.let { dao.addCleanup(MmsCleanupEntity(it)) }
        }
        dao.deleteDraft(id); true
    }
    suspend fun accept(id: String, revision: Long, snapshot: MmsSendSnapshot, possibleDuplicateOf: String? = null): String = database.withTransaction {
        dao.accepted(id, revision)?.let { return@withTransaction it.id }
        val draft = dao.draft(id)?.takeUnless { it.accepted }?.model()
            ?: throw MmsSendException(AppStrings.get(R.string.domain_mms_send_draft_updated))
        if (draft.revision != revision) throw MmsSendException(AppStrings.get(R.string.domain_mms_send_content_changed))
        if (draft.importError != null) throw MmsSendException(AppStrings.get(R.string.domain_mms_send_attachment_not_imported))
        if (MmsRecipient.normalize(draft.recipientAddress) != snapshot.recipientAddress || draft.body != snapshot.body ||
            draft.subject != snapshot.subject || draft.subscriptionId != snapshot.subscriptionId ||
            draft.attachments.map { it.id to it.originalPath } != snapshot.attachments.map { it.id to it.originalPath }) {
            throw MmsSendException(AppStrings.get(R.string.domain_mms_send_content_changed))
        }
        if (snapshot.body.isBlank() && snapshot.attachments.isEmpty()) {
            throw MmsSendException(AppStrings.get(R.string.domain_mms_send_empty_content))
        }
        if (snapshot.attachments.any { it.preparedPath == null || it.preparedSha256 == null || it.error != null }) {
            throw MmsSendException(AppStrings.get(R.string.domain_mms_send_attachment_not_ready))
        }
        if (draft.originRequestId != null && draft.possibleDuplicateOf == null) {
            val origin = get(draft.originRequestId)
                ?: throw MmsSendException(AppStrings.get(R.string.domain_mms_send_origin_unavailable))
            if (dao.request(draft.originRequestId)?.lateSuccess == true || origin.state !in setOf(MmsSendState.FAILED, MmsSendState.CANCELLED)) {
                throw MmsSendException(AppStrings.get(R.string.domain_mms_send_origin_state_changed_risk))
            }
        }
        if (possibleDuplicateOf != draft.possibleDuplicateOf) {
            throw MmsSendException(AppStrings.get(R.string.domain_mms_send_duplicate_source_changed))
        }
        draft.possibleDuplicateOf?.let {
            val old = get(it) ?: throw MmsSendException(AppStrings.get(R.string.domain_mms_send_origin_unavailable))
            if (old.state != MmsSendState.UNKNOWN) {
                throw MmsSendException(AppStrings.get(R.string.domain_mms_send_origin_state_changed))
            }
        }
        if (dao.attempts().count { it.state in setOf("PREPARING", "READY", "DISPATCHING", "AWAITING_RESULT") || it.state == "UNKNOWN" && it.pduPath != null } >= 10) {
            throw MmsSendException(AppStrings.get(R.string.domain_mms_send_queue_limit))
        }
        val requestId = UUID.randomUUID().toString(); val token = UUID.randomUUID().toString()
        dao.insertRequest(MmsRequestEntity(requestId, id, revision, MmsSnapshotCodec.encode(snapshot), token, possibleDuplicateOf = draft.possibleDuplicateOf, originRequestId = draft.originRequestId))
        dao.insertAttempt(MmsAttemptEntity(token, requestId, 1, newTransaction(), subscriptionId = snapshot.subscriptionId,
            state = MmsSendState.PREPARING.name, createdAt = System.currentTimeMillis()))
        dao.deleteDraft(draft.id); requestId
    }
    suspend fun get(id: String): OutgoingMmsMessage? {
        val request = dao.request(id) ?: return null
        return dao.attempt(request.currentAttempt)?.let { model(request, it) }
    }
    suspend fun all(): List<OutgoingMmsMessage> = dao.requests().mapNotNull { get(it.id) }
    suspend fun attempt(token: String) = dao.attempt(token)
    suspend fun pendingCallbacks(id: String) = dao.pendingCallbacks(id)
    suspend fun previousTransaction(token: String) = dao.attempt(token)?.previousTransactionId
    suspend fun previousProviderReady(token: String): Boolean {
        val attempt = dao.attempt(token) ?: return false
        return dao.attempts().singleOrNull { it.requestId == attempt.requestId && it.transactionId == attempt.previousTransactionId }?.providerReady ?: false
    }
    suspend fun setSource(id: String, token: String, sourceId: Long): Boolean = database.withTransaction {
        val request = dao.request(id) ?: return@withTransaction false
        if (request.currentAttempt != token || request.deleted || (request.sourceId != null && request.sourceId != sourceId)) return@withTransaction false
        dao.updateRequest(request.copy(sourceId = sourceId)); true
    }
    suspend fun ready(id: String, token: String, pduPath: String, sha256: String): Boolean = database.withTransaction {
        val request = dao.request(id) ?: return@withTransaction false
        val attempt = dao.attempt(token) ?: return@withTransaction false
        if (request.currentAttempt != token || request.deleted || attempt.state != "PREPARING") return@withTransaction false
        dao.updateRequest(request.copy(providerReady = true))
        dao.updateAttempt(attempt.copy(state = "READY", pduPath = pduPath, pduSha256 = sha256, providerReady = true)); true
    }
    /** 此方法返回 true 才有权调用一次平台；提交前崩溃宁可 UNKNOWN，不能自动补发。 */
    suspend fun claim(id: String, token: String): Boolean = database.withTransaction {
        val request = dao.request(id) ?: return@withTransaction false
        val attempt = dao.attempt(token) ?: return@withTransaction false
        if (request.lateSuccess && attempt.state == "READY") {
            dao.updateAttempt(attempt.copy(state = "FAILED", error = AppStrings.get(R.string.domain_mms_send_paused_late_success)))
            return@withTransaction false
        }
        if (request.deleted || request.currentAttempt != token || !request.providerReady || attempt.state != "READY" ||
            dao.activeForSubscription(attempt.subscriptionId) != 0) return@withTransaction false
        if (request.originRequestId != null && request.possibleDuplicateOf == null) {
            val origin = dao.request(request.originRequestId)
            val originAttempt = origin?.let { dao.attempt(it.currentAttempt) }
            if (origin?.lateSuccess == true || originAttempt?.state !in setOf("FAILED", "CANCELLED")) {
                dao.updateAttempt(attempt.copy(state = "FAILED", error = AppStrings.get(R.string.domain_mms_send_paused_origin_changed)))
                return@withTransaction false
            }
        }
        dao.updateAttempt(attempt.copy(state = "DISPATCHING", submittedAt = System.currentTimeMillis())); true
    }
    suspend fun awaiting(token: String) = database.withTransaction {
        val attempt = dao.attempt(token) ?: return@withTransaction
        if (attempt.state == "DISPATCHING" && !attempt.callbackRecorded) dao.updateAttempt(attempt.copy(state = "AWAITING_RESULT"))
    }
    suspend fun failBeforeDispatch(token: String, reason: String) = database.withTransaction {
        val attempt = dao.attempt(token) ?: return@withTransaction
        if (attempt.state in setOf("PREPARING", "READY")) dao.updateAttempt(attempt.copy(state = "FAILED", error = reason))
    }
    suspend fun unknown(token: String, reason: String) = database.withTransaction {
        val attempt = dao.attempt(token) ?: return@withTransaction
        if (attempt.state in setOf("DISPATCHING", "AWAITING_RESULT")) {
            dao.updateAttempt(attempt.copy(state = "UNKNOWN", error = reason))
            dao.request(attempt.requestId)?.let { dao.updateRequest(it.copy(providerSyncPending = true)) }
        }
    }
    suspend fun cancel(id: String): Boolean = database.withTransaction {
        val request = dao.request(id) ?: return@withTransaction false
        val attempt = dao.attempt(request.currentAttempt) ?: return@withTransaction false
        if (attempt.state !in setOf("PREPARING", "READY")) return@withTransaction false
        dao.updateAttempt(attempt.copy(state = "CANCELLED", error = AppStrings.get(R.string.domain_mms_send_cancelled)))
        dao.updateRequest(request.copy(providerSyncPending = true)); true
    }
    suspend fun retryFailed(id: String): String = database.withTransaction {
        val request = dao.request(id)
            ?: throw MmsSendException(AppStrings.get(R.string.domain_mms_send_record_unavailable))
        val old = dao.attempt(request.currentAttempt)
            ?: throw MmsSendException(AppStrings.get(R.string.domain_mms_send_record_unavailable))
        if (request.deleted || old.state != "FAILED" || request.lateSuccess) {
            throw MmsSendException(AppStrings.get(R.string.domain_mms_send_cannot_retry))
        }
        if (request.sourceId != null && request.providerSyncPending) {
            throw MmsSendException(AppStrings.get(R.string.domain_mms_send_syncing_failed))
        }
        val token = UUID.randomUUID().toString()
        dao.insertAttempt(MmsAttemptEntity(token, id, old.number + 1, newTransaction(), old.transactionId,
            old.subscriptionId, "PREPARING", System.currentTimeMillis()))
        dao.updateRequest(request.copy(currentAttempt = token, providerReady = false, providerSyncPending = true)); id
    }
    suspend fun recordCallback(token: String, resultCode: Int, httpStatus: Int?, bytes: ByteArray?): Boolean = database.withTransaction {
        val attempt = dao.attempt(token) ?: return@withTransaction false
        if (attempt.submittedAt == null || attempt.state == "CANCELLED") return@withTransaction false
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update("$resultCode:$httpStatus:${bytes == null}:".toByteArray(Charsets.US_ASCII))
        if (bytes != null) digest.update(bytes)
        val fingerprint = digest.digest().joinToString("") { "%02x".format(it) }
        dao.insertCallback(MmsCallbackEntity(attemptToken = token, fingerprint = fingerprint,
            resultCode = resultCode, httpStatus = httpStatus, response = bytes?.copyOf(), receivedAt = System.currentTimeMillis()))
        // 相同事实去重，不同的迟到结果单独保存，旧失败后又报告成功也不能丢失。
        if (!attempt.callbackRecorded) dao.updateAttempt(attempt.copy(resultCode = resultCode, httpStatus = httpStatus,
            response = bytes?.copyOf(), callbackRecorded = true))
        true
    }
    suspend fun applyResult(token: String, state: MmsSendState, responseStatus: Int?, messageId: String?, confirmation: String?, error: String?, callbackId: Long? = null) = database.withTransaction {
        val attempt = dao.attempt(token) ?: return@withTransaction
        if (!attempt.callbackRecorded) return@withTransaction
        val fact = callbackId?.let { dao.callback(it) }
        if (callbackId != null && (fact == null || fact.attemptToken != token || fact.processed)) return@withTransaction
        fact?.let { dao.updateCallback(it.copy(processed = true, resolvedState = state.name, responseStatus = responseStatus, confirmation = confirmation, response = null)) }
        if (attempt.state == "SENT") return@withTransaction
        dao.updateAttempt(attempt.copy(state = state.name, responseStatus = responseStatus, messageId = messageId,
            confirmation = confirmation, error = error, response = null, resultCode = fact?.resultCode ?: attempt.resultCode, httpStatus = fact?.httpStatus ?: attempt.httpStatus))
        val request = dao.request(attempt.requestId) ?: return@withTransaction
        if (request.currentAttempt == token) dao.updateRequest(request.copy(providerSyncPending = true))
        else if (state == MmsSendState.SENT) dao.updateRequest(request.copy(lateSuccess = true))
        if (state == MmsSendState.SENT) dao.requests().filter { it.originRequestId == request.id && it.possibleDuplicateOf == null }
            .forEach { dao.updateRequest(it.copy(lateSuccess = true)) }
    }
    suspend fun recordProviderConflict(id: String, token: String) = database.withTransaction {
        val request = dao.request(id) ?: return@withTransaction
        val attempt = dao.attempt(token) ?: return@withTransaction
        if (request.currentAttempt == token && !request.deleted) dao.updateAttempt(attempt.copy(
            error = AppStrings.get(R.string.domain_mms_send_provider_conflict)))
    }
    suspend fun markSynced(id: String, token: String) = database.withTransaction {
        val request = dao.request(id) ?: return@withTransaction
        if (request.currentAttempt == token) dao.updateRequest(request.copy(providerSyncPending = false))
    }
    suspend fun markDeleted(id: String) = database.withTransaction {
        val request = dao.request(id) ?: return@withTransaction
        val snapshot = MmsSnapshotCodec.decode(request.snapshot)
        snapshot.attachments.forEach { attachment ->
            dao.addCleanup(MmsCleanupEntity(attachment.originalPath))
            attachment.preparedPath?.let { dao.addCleanup(MmsCleanupEntity(it)) }
        }
        // 删除后仅保留关联与结果事实，不保留第二份可见消息内容；未确认PDU单独管理。
        val tombstone = MmsSnapshotCodec.encode(snapshot.copy(recipientAddress = "", body = "", subject = "", attachments = emptyList()))
        dao.updateRequest(request.copy(deleted = true, providerSyncPending = false, snapshot = tombstone))
        val attempt = dao.attempt(request.currentAttempt) ?: return@withTransaction
        if (attempt.state in setOf("PREPARING", "READY")) dao.updateAttempt(attempt.copy(state = "CANCELLED", error = AppStrings.get(R.string.domain_mms_send_message_deleted)))
    }
    suspend fun releaseUnknownPayload(id: String): Boolean = database.withTransaction {
        val request = dao.request(id) ?: return@withTransaction false
        val attempt = dao.attempt(request.currentAttempt) ?: return@withTransaction false
        if (attempt.state != "UNKNOWN") return@withTransaction false
        val path = attempt.pduPath ?: return@withTransaction true
        dao.addCleanup(MmsCleanupEntity(path))
        // 清理不改变 UNKNOWN，不授权重发，事务/结果事实继续保留。
        dao.updateAttempt(attempt.copy(pduPath = null)); true
    }
    suspend fun enqueueCleanup(path: String) = dao.addCleanup(MmsCleanupEntity(path))
    suspend fun cleanupCandidates(): List<String> = dao.cleanup().map { it.path }
    suspend fun cleaned(path: String) = dao.removeCleanup(path)
    suspend fun referencedPaths(): Set<String> = database.withTransaction { buildSet {
        dao.drafts().filterNot { it.accepted }.forEach { draft -> draft.model().attachments.forEach { add(it.originalPath); it.preparedPath?.let(::add) } }
        dao.requests().filterNot { it.deleted }.forEach { request ->
            val attempt = dao.attempt(request.currentAttempt)
            if (attempt?.state != "SENT" || request.providerSyncPending) MmsSnapshotCodec.decode(request.snapshot).attachments.forEach { add(it.originalPath); it.preparedPath?.let(::add) }
        }
        dao.attempts().filter { it.state in setOf("DISPATCHING", "AWAITING_RESULT", "UNKNOWN") }.forEach { it.pduPath?.let(::add) }
    } }
    private fun newTransaction() = "pt-" + UUID.randomUUID().toString().replace("-", "")
}
