package vip.mystery0.pixel.text.mms.outgoing

import android.Manifest
import android.app.PendingIntent
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.content.FileProvider
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import vip.mystery0.pixel.text.BuildConfig
import vip.mystery0.pixel.text.data.repository.OutgoingMmsRepository
import vip.mystery0.pixel.text.data.repository.mirror.MessageMirrorSynchronizer
import vip.mystery0.pixel.text.domain.model.mirror.MessageTransport
import vip.mystery0.pixel.text.domain.model.mirror.SourceMessageKey
import vip.mystery0.pixel.text.worker.MessageMirrorScheduler
import vip.mystery0.pixel.text.worker.MmsSendWorker
import java.util.concurrent.TimeUnit

fun interface MmsPlatformTransport {
    fun send(context: Context, subscriptionId: Int, pduUri: Uri, callback: PendingIntent)
}

class AndroidMmsPlatformTransport : MmsPlatformTransport {
    override fun send(context: Context, subscriptionId: Int, pduUri: Uri, callback: PendingIntent) {
        val manager = context.getSystemService(SmsManager::class.java)?.createForSubscriptionId(subscriptionId)
            ?: throw MmsSendException("设备不支持彩信")
        // 平台按指定订阅选择 APN/MMSC，并为系统及 carrier service 转授权该 PDU。
        manager.sendMultimediaMessage(context, pduUri, null, null, callback)
    }
}

class MmsSendCoordinator(
    private val context: Context,
    private val repository: OutgoingMmsRepository,
    private val attachments: MmsAttachmentPreparer,
    private val policies: MmsSendPolicyResolver,
    private val composer: MmsSendPduComposer,
    private val payloads: MmsPayloadStore,
    private val provider: MmsOutgoingProviderWriter,
    private val mirror: MessageMirrorSynchronizer,
    private val mirrorScheduler: MessageMirrorScheduler,
    private val transport: MmsPlatformTransport,
) {
    suspend fun prepare(draft: MmsDraft): MmsSendSnapshot = withContext(Dispatchers.IO) {
        checkPrerequisites()
        val recipient = MmsRecipient.normalize(draft.recipientAddress) ?: throw MmsSendException("请确认一个有效电话号码")
        if (draft.body.isBlank() && draft.attachments.isEmpty()) throw MmsSendException("请添加正文或附件")
        if (draft.attachments.size > 10 || draft.attachments.sumOf { it.originalSize } > 64L * 1024 * 1024) throw MmsSendException("附件超过本地准备上限")
        val policy = policies.resolve(draft.subscriptionId)
        val base = MmsSendSnapshot(recipient, draft.body, draft.subject, draft.subscriptionId, policy, emptyList())
        // 为地址、主题、SMIL和part头保留有界余量，最终门槛仍是实际完整PDU字节。
        val reserve = draft.body.toByteArray(Charsets.UTF_8).size + draft.subject.toByteArray(Charsets.UTF_8).size + 4096 + draft.attachments.size * 1024
        var remaining = policy.maxMessageBytes - reserve
        if (remaining < 0) throw MmsSendException("正文或主题超过彩信上限")
        val prepared = mutableListOf<MmsAttachment>()
        try {
            val (images, originals) = draft.attachments.partition { attachments.isAdaptableImage(it) }
            originals.forEach { attachment ->
                val result = attachments.prepare(attachment, policy, remaining)
                prepared += result; remaining -= (result.preparedSize ?: 0).toInt()
            }
            images.forEachIndexed { index, attachment ->
                val result = attachments.prepare(attachment, policy, remaining / (images.size - index))
                prepared += result; remaining -= (result.preparedSize ?: 0).toInt()
            }
            val snapshot = base.copy(attachments = draft.attachments.map { original -> prepared.single { it.id == original.id } })
            composer.compose(snapshot, PREVIEW_TRANSACTION)
            snapshot
        } catch (error: Exception) {
            prepared.mapNotNull { it.preparedPath }.filterNot { path -> draft.attachments.any { it.originalPath == path || it.preparedPath == path } }
                .forEach { runCatching { payloads.deleteOwned(it) } }
            throw error
        }
    }
    suspend fun preparedSize(snapshot: MmsSendSnapshot): Int = withContext(Dispatchers.IO) { composer.compose(snapshot, PREVIEW_TRANSACTION).bytes.size }
    suspend fun accept(draftId: String, revision: Long, snapshot: MmsSendSnapshot, possibleDuplicateOf: String? = null): String = withContext(Dispatchers.IO) {
        if (!BuildConfig.MMS_SENDING_ENABLED) throw MmsSendException("彩信发送尚未完成运营商验收，此构建未开启")
        checkPrerequisites()
        if (policies.resolve(snapshot.subscriptionId).fingerprint != snapshot.policy.fingerprint) throw MmsSendException("SIM 或运营商配置已变化，请重新准备并确认")
        composer.compose(snapshot, PREVIEW_TRANSACTION)
        repository.accept(draftId, revision, snapshot, possibleDuplicateOf).also { scheduleRecovery() }
    }
    suspend fun retryFailed(requestId: String) {
        if (!BuildConfig.MMS_SENDING_ENABLED) throw MmsSendException("此构建未开启彩信发送")
        val old = repository.get(requestId) ?: throw MmsSendException("发送记录不可用")
        checkPrerequisites()
        if (policies.resolve(old.snapshot.subscriptionId).fingerprint != old.snapshot.policy.fingerprint) throw MmsSendException("SIM 或配置已变化，请恢复草稿重新确认")
        repository.retryFailed(requestId); scheduleRecovery()
    }
    suspend fun releaseUnknownPayload(requestId: String): Boolean {
        val message = repository.get(requestId) ?: return false
        val path = message.pduPath
        if (!repository.releaseUnknownPayload(requestId)) return false
        if (path != null) runCatching {
            context.revokeUriPermission(FileProvider.getUriForFile(context, "${context.packageName}.mms.send", payloads.resolveOwned(path)), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        scheduleRecovery(); return true
    }
    suspend fun cancel(requestId: String): Boolean = repository.cancel(requestId).also { if (it) scheduleRecovery() }
    fun scheduleRecovery(delayMillis: Long = 0) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            if (delayMillis == 0L) "outgoing-mms-immediate" else "outgoing-mms-deadline",
            if (delayMillis == 0L) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<MmsSendWorker>().setInitialDelay(delayMillis, TimeUnit.MILLISECONDS).build(),
        )
    }
    suspend fun recover(): Boolean = withContext(Dispatchers.IO) {
        processing.withLock {
            var pendingLocal = false
            val now = System.currentTimeMillis()
            repository.all().forEach { initial ->
                try {
                    // 先读取所有尝试的事实，旧尝试成功不能覆盖当前关联字段。
                    var message = repository.get(initial.requestId) ?: return@forEach
                    interpretCallbacks(message.requestId)
                    message = repository.get(message.requestId) ?: return@forEach
                    if (message.deleted) {
                        // 删除只隐藏消息，不能让此尝试永久占用同 SIM 的提交名额。
                        when (message.state) {
                            MmsSendState.DISPATCHING -> repository.unknown(message.attemptToken, "提交期间消息已删除，发送结果不明")
                            MmsSendState.AWAITING_RESULT -> if (now - (message.submittedAt ?: now) >= RESULT_WAIT_MILLIS) {
                                repository.unknown(message.attemptToken, "消息已删除，暂未收到发送结果")
                            }
                            else -> Unit
                        }
                        message.snapshot.attachments.forEach { attachment ->
                            repository.enqueueCleanup(attachment.originalPath)
                            attachment.preparedPath?.let { repository.enqueueCleanup(it) }
                        }
                        cleanupTerminal(repository.get(message.requestId) ?: message)
                        return@forEach
                    }
                    when (message.state) {
                        MmsSendState.DISPATCHING -> repository.unknown(message.attemptToken, "提交期间应用中断，可能已经发出")
                        MmsSendState.AWAITING_RESULT -> if (now - (message.submittedAt ?: now) >= RESULT_WAIT_MILLIS) {
                            repository.unknown(message.attemptToken, "暂未收到发送结果，可能已经发出")
                        }
                        MmsSendState.PREPARING -> {
                            if (!BuildConfig.MMS_SENDING_ENABLED) repository.failBeforeDispatch(message.attemptToken, "此构建未开启彩信发送")
                            else prepareRequest(message)
                        }
                        else -> Unit
                    }
                    message = repository.get(message.requestId) ?: return@forEach
                    if (message.state == MmsSendState.READY) dispatch(message)
                    message = repository.get(message.requestId) ?: return@forEach
                    if (message.state in TERMINAL_STATES && message.providerSyncPending) syncProvider(message)
                    cleanupTerminal(repository.get(message.requestId) ?: message)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: MmsProviderDeleted) {
                    repository.markDeleted(initial.requestId)
                } catch (_: MmsProviderConflict) {
                    repository.failBeforeDispatch(initial.attemptToken, "系统消息已更改，请保留记录并重新检查")
                    repository.recordProviderConflict(initial.requestId, initial.attemptToken)
                    pendingLocal = true
                } catch (error: MmsSendException) {
                    repository.failBeforeDispatch(initial.attemptToken, error.userMessage)
                    pendingLocal = true
                } catch (_: Exception) {
                    // 权限/Provider/存储失败不等于删除，不靠再次联网来修复本地状态。
                    pendingLocal = true
                }
            }
            cleanupFiles()
            val messages = repository.all()
            val nextDeadline = messages.filter { it.state == MmsSendState.AWAITING_RESULT }
                .mapNotNull { it.submittedAt?.plus(RESULT_WAIT_MILLIS) }.minOrNull()
            if (nextDeadline != null) scheduleRecovery((nextDeadline - System.currentTimeMillis()).coerceAtLeast(1000))
            if (messages.any { !it.deleted && it.state in setOf(MmsSendState.PREPARING, MmsSendState.READY) }) pendingLocal = true
            pendingLocal
        }
    }
    private suspend fun prepareRequest(message: OutgoingMmsMessage) {
        checkPrerequisites()
        if (policies.resolve(message.snapshot.subscriptionId).fingerprint != message.snapshot.policy.fingerprint) throw MmsSendException("SIM 或配置已变化，请重新确认")
        val composed = composer.compose(message.snapshot, message.transactionId)
        val source = provider.prepare(message, composed, repository.previousTransaction(message.attemptToken), repository.previousProviderReady(message.attemptToken)) { id ->
            repository.setSource(message.requestId, message.attemptToken, id)
        }
        val pdu = payloads.writePdu(message.attemptToken, composed.bytes)
        if (repository.ready(message.requestId, message.attemptToken, pdu.absolutePath, payloads.sha256(composed.bytes))) {
            mirror.markDirty(SourceMessageKey(MessageTransport.MMS, source)); mirrorScheduler.schedule()
        } else repository.enqueueCleanup(pdu.absolutePath)
    }
    private suspend fun dispatch(message: OutgoingMmsMessage) {
        if (!BuildConfig.MMS_SENDING_ENABLED) throw MmsSendException("此构建未开启彩信发送")
        checkPrerequisites()
        if (policies.resolve(message.snapshot.subscriptionId).fingerprint != message.snapshot.policy.fingerprint) throw MmsSendException("SIM 或配置已变化，请重新确认")
        val composed = composer.compose(message.snapshot, message.transactionId)
        provider.verify(message, composed)
        val path = message.pduPath ?: throw MmsSendException("发送文件不可用")
        val bytes = payloads.read(path, message.snapshot.policy.maxMessageBytes)
        if (payloads.sha256(bytes) != message.pduSha256 || !bytes.contentEquals(composed.bytes)) throw MmsSendException("发送文件已变化，请重新确认")
        if (!repository.claim(message.requestId, message.attemptToken)) return
        try {
            val pdu = payloads.resolveOwned(path)
            val pduUri = FileProvider.getUriForFile(context, "${context.packageName}.mms.send", pdu)
            val callback = PendingIntent.getBroadcast(context, 0,
                Intent(context, MmsSentReceiver::class.java).setAction(MmsSentReceiver.ACTION)
                    .setData(Uri.parse("pixeltext://mms-send/${message.attemptToken}")),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            transport.send(context, message.snapshot.subscriptionId, pduUri, callback)
            repository.awaiting(message.attemptToken)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { repository.unknown(message.attemptToken, "系统提交结果不明，可能已经发出") }
    }
    private suspend fun interpretCallbacks(requestId: String) {
        repository.pendingCallbacks(requestId).forEach { fact ->
            val attempt = repository.attempt(fact.attemptToken) ?: return@forEach
            val outcome = MmsSendResult.interpret(fact.resultCode, fact.response, attempt.transactionId)
            repository.applyResult(attempt.token, outcome.state, outcome.responseStatus, outcome.messageId, outcome.confirmation, outcome.error, fact.id)
        }
    }
    private suspend fun syncProvider(message: OutgoingMmsMessage) {
        if (message.sourceId == null) { repository.markSynced(message.requestId, message.attemptToken); return }
        checkPrerequisites()
        provider.complete(message, composer.compose(message.snapshot, message.transactionId))
        mirror.markDirty(SourceMessageKey(MessageTransport.MMS, message.sourceId))
        mirrorScheduler.schedule(); repository.markSynced(message.requestId, message.attemptToken)
    }
    private suspend fun cleanupTerminal(message: OutgoingMmsMessage) {
        if (message.state !in setOf(MmsSendState.SENT, MmsSendState.FAILED, MmsSendState.CANCELLED)) return
        message.pduPath?.let { path ->
            runCatching {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.mms.send", payloads.resolveOwned(path))
                context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            repository.enqueueCleanup(path)
        }
        if (message.state == MmsSendState.SENT && !message.providerSyncPending) message.snapshot.attachments.forEach {
            repository.enqueueCleanup(it.originalPath); it.preparedPath?.let { path -> repository.enqueueCleanup(path) }
        }
    }
    private suspend fun cleanupFiles() {
        val referenced = repository.referencedPaths()
        payloads.orphanCandidates().filterNot { it in referenced }.forEach { path ->
            runCatching { payloads.deleteOrphan(path) }
        }
        repository.cleanupCandidates().filterNot { it in referenced }.forEach { path ->
            runCatching { if (payloads.deleteOwned(path)) repository.cleaned(path) }
        }
    }
    private fun checkPrerequisites() {
        val defaultSmsPackage = Telephony.Sms.getDefaultSmsPackage(context)
        val holdsSmsRole = context.getSystemService(RoleManager::class.java)
            ?.isRoleHeld(RoleManager.ROLE_SMS) == true
        if (defaultSmsPackage != context.packageName && !holdsSmsRole) {
            throw MmsSendException("请先将 PixelText 设为默认短信应用")
        }
        if (context.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) throw MmsSendException("请先授予短信权限")
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) throw MmsSendException("请先授予电话状态权限以确认 SIM")
        val feature = if (Build.VERSION.SDK_INT >= 33) PackageManager.FEATURE_TELEPHONY_MESSAGING else PackageManager.FEATURE_TELEPHONY
        if (!context.packageManager.hasSystemFeature(feature)) throw MmsSendException("设备不支持短信和彩信")
    }
    companion object {
        private val processing = Mutex()
        private const val RESULT_WAIT_MILLIS = 15 * 60 * 1000L
        private const val PREVIEW_TRANSACTION = "pt-00000000000000000000000000000000"
        private val TERMINAL_STATES = setOf(MmsSendState.SENT, MmsSendState.FAILED, MmsSendState.UNKNOWN, MmsSendState.CANCELLED)
    }
}
