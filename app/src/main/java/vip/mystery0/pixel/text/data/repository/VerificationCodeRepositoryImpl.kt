package vip.mystery0.pixel.text.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import vip.mystery0.pixel.text.data.db.mirror.MessageMirrorDatabase
import vip.mystery0.pixel.text.data.db.VerificationCodeIndexEntity
import vip.mystery0.pixel.text.data.db.VerificationCodeMetadataEntity
import vip.mystery0.pixel.text.data.db.SmsScanStateEntity
import vip.mystery0.pixel.text.data.source.SmsIndexSummaryRow
import vip.mystery0.pixel.text.data.source.ContactDataSource
import vip.mystery0.pixel.text.domain.model.ParsedResult
import vip.mystery0.pixel.text.domain.model.VerificationCodeIndexModel
import vip.mystery0.pixel.text.domain.model.VerificationCodeMonthModel
import vip.mystery0.pixel.text.domain.parser.MessageParser
import vip.mystery0.pixel.text.domain.repository.VerificationCodeRepository
import vip.mystery0.pixel.text.domain.settings.AppSettingsRepository
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class VerificationCodeRepositoryImpl(
    private val database: MessageMirrorDatabase,
    private val parser: MessageParser,
    private val settings: AppSettingsRepository,
    private val contactDataSource: ContactDataSource,
) : VerificationCodeRepository {
    private val dao = database.verificationCodeIndexDao()
    private val writeMutex = Mutex()

    override fun observeMonths(): Flow<List<VerificationCodeMonthModel>> = dao.observeMonths()

    override fun observeMonth(monthKey: String): Flow<List<VerificationCodeIndexModel>> =
        combine(dao.observeMonth(monthKey), contactDataSource.changes) { messages, _ ->
            contactDataSource.warmUp()
            val displayNames = contactDataSource.getCachedDisplayNames(messages.map { it.address })
            messages.map { it.copy(displayName = displayNames[it.address]) }
        }.flowOn(Dispatchers.IO)

    override suspend fun getMessageBody(messageId: Long): String? = withContext(Dispatchers.IO) {
        dao.getMessageBody(messageId)
    }

    override suspend fun indexMessage(
        messageId: Long,
        threadId: Long,
        address: String,
        body: String,
        timestamp: Long,
    ) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            database.withTransaction {
                val metadata = ensureMetadata()
                val summary = SmsIndexSummaryRow(
                    messageId,
                    threadId,
                    address,
                    timestamp,
                    timestamp,
                    android.provider.Telephony.Sms.MESSAGE_TYPE_INBOX,
                )
                indexIntoGeneration(
                    summary = summary,
                    body = body,
                    generation = metadata.activeGeneration,
                    ruleVersion = currentRuleVersion(),
                )
                dao.upsertScanStates(listOf(SmsScanStateEntity(
                    generation = metadata.activeGeneration,
                    messageId = messageId,
                    threadId = threadId,
                    address = address,
                    timestamp = timestamp,
                    metadataFingerprint = summary.metadataFingerprint(),
                )))
            }
        }
    }

    override suspend fun rebuildAll(onProgress: ((Long, Long?) -> Unit)?) = withContext(Dispatchers.IO) {
        writeMutex.withLock { rebuildAllLocked(onProgress) }
    }

    private suspend fun rebuildAllLocked(onProgress: ((Long, Long?) -> Unit)? = null) {
        val metadata = ensureMetadata()
        val generation = metadata.activeGeneration + 1
        val ruleVersion = currentRuleVersion()
        try {
            dao.deleteGeneration(generation)
            dao.deleteScanGeneration(generation)
            var processedCount = 0L
            var totalCount = if (onProgress != null) dao.mirrorSmsCount() else null
            onProgress?.invoke(processedCount, totalCount)
            forEachSmsSummaryPage { summaries ->
                processPage(summaries, generation, ruleVersion, null)
                processedCount += summaries.size
                // 扫描期间可能新收到短信，不能把过期总数继续显示成准确上限。
                if (totalCount != null && processedCount > requireNotNull(totalCount)) totalCount = null
                onProgress?.invoke(processedCount, totalCount)
            }
            database.activateVerificationGeneration(generation, ruleVersion, System.currentTimeMillis())
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                dao.deleteGeneration(generation)
                dao.deleteScanGeneration(generation)
            }
            throw error
        }
    }

    override suspend fun reconcile() = withContext(Dispatchers.IO) {
        writeMutex.withLock { reconcileLocked() }
    }

    override suspend fun getExpiredMessageIds(cutoffTimestamp: Long): List<Long> =
        withContext(Dispatchers.IO) {
            dao.getExpiredMessageIds(cutoffTimestamp)
        }

    private suspend fun reconcileLocked() {
        val metadata = ensureMetadata()
        val ruleVersion = currentRuleVersion()
        if (metadata.lastFullScanRuleVersion != ruleVersion) {
            rebuildAllLocked()
            return
        }

        // 写锁覆盖整轮比对，活动代的状态与索引不会被本应用的增删操作拆开。
        // 逐条核对后再比较总数，同时覆盖等量替换和只删除短信的情况。
        if (!hasSmsMetadataChanges(metadata.activeGeneration)) {
            dao.updateLastReconciledAt(System.currentTimeMillis())
            return
        }

        val generation = metadata.activeGeneration + 1
        try {
            dao.deleteGeneration(generation)
            dao.deleteScanGeneration(generation)
            forEachSmsSummaryPage { summaries ->
                processPage(summaries, generation, ruleVersion, metadata.activeGeneration)
            }
            database.activateVerificationGeneration(generation, ruleVersion, System.currentTimeMillis())
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                dao.deleteGeneration(generation)
                dao.deleteScanGeneration(generation)
            }
            throw error
        }
    }

    private suspend fun hasSmsMetadataChanges(generation: Long): Boolean {
        // 兼容旧版单条入库中断后遗留的索引，不能因 Provider 与扫描状态一致而跳过清理。
        if (dao.hasEntriesWithoutScanState(generation)) return true
        val previousCount = dao.getScanStateCount(generation)
        var scannedCount = 0L
        var beforeMessageId: Long? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            val summaries = dao.mirrorSmsSummaries(
                beforeMessageId = beforeMessageId,
                limit = SMS_SCAN_BATCH_SIZE,
            )
            if (summaries.isEmpty()) return scannedCount != previousCount
            val oldStates = dao.getScanStates(generation, summaries.map { it.id })
                .associateBy { it.messageId }
            for (summary in summaries) {
                currentCoroutineContext().ensureActive()
                val old = oldStates[summary.id] ?: return true
                if (old.threadId != summary.threadId || old.address != summary.address ||
                    old.timestamp != summary.date ||
                    old.metadataFingerprint != summary.metadataFingerprint()
                ) return true
            }
            scannedCount += summaries.size
            if (summaries.size < SMS_SCAN_BATCH_SIZE) return scannedCount != previousCount
            beforeMessageId = summaries.last().id
        }
    }

    override suspend fun deleteMessageIds(messageIds: Collection<Long>) =
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                messageIds.distinct().chunked(MAX_DATABASE_ARGS).forEach { chunk ->
                    dao.deleteMessageIds(chunk)
                }
            }
        }

    override suspend fun deleteThreadIds(threadIds: Collection<Long>) =
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                threadIds.distinct().chunked(MAX_DATABASE_ARGS).forEach { chunk ->
                    dao.deleteThreadIds(chunk)
                }
            }
        }

    private suspend fun forEachSmsSummaryPage(
        block: suspend (List<SmsIndexSummaryRow>) -> Unit,
    ) {
        // 索引只消费已经同步的本地镜像，角色和系统权限变化不阻断已有数据浏览。
        var beforeMessageId: Long? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            val summaries = dao.mirrorSmsSummaries(
                beforeMessageId = beforeMessageId,
                limit = SMS_SCAN_BATCH_SIZE,
            )
            if (summaries.isEmpty()) return
            block(summaries)
            if (summaries.size < SMS_SCAN_BATCH_SIZE) return
            beforeMessageId = summaries.last().id
        }
    }

    private suspend fun ensureMetadata(): VerificationCodeMetadataEntity {
        dao.getMetadata()?.let { return it }
        return VerificationCodeMetadataEntity(
            activeGeneration = 0,
            lastFullScanRuleVersion = null,
            lastReconciledAt = null,
        ).also { dao.upsertMetadata(it) }
    }

    private suspend fun indexIntoGeneration(
        summary: SmsIndexSummaryRow,
        body: String,
        generation: Long,
        ruleVersion: String,
    ) {
        val entry = parseIndexEntry(summary, body, generation, ruleVersion)
        if (entry == null) {
            dao.deleteMessage(generation, summary.id)
            return
        }
        dao.upsertEntries(listOf(entry))
    }

    private fun parseIndexEntry(
        summary: SmsIndexSummaryRow,
        body: String,
        generation: Long,
        ruleVersion: String,
    ): VerificationCodeIndexEntity? {
        val result = parser.parse(summary.address, body) as? ParsedResult.VerificationCode
            ?: return null
        return VerificationCodeIndexEntity(
            messageId = summary.id,
            threadId = summary.threadId,
            address = summary.address,
            timestamp = summary.date,
            monthKey = monthFormatter.format(
                Instant.ofEpochMilli(summary.date).atZone(ZoneId.systemDefault())
            ),
            code = result.code,
            signature = result.signature,
            ruleVersion = ruleVersion,
            generation = generation,
        )
    }

    private suspend fun processPage(
        summaries: List<SmsIndexSummaryRow>,
        generation: Long,
        ruleVersion: String,
        sourceGeneration: Long?,
    ) {
        val ids = summaries.map { it.id }
        val oldStates = sourceGeneration?.let { dao.getScanStates(it, ids) }
            .orEmpty().associateBy { it.messageId }
        val fingerprints = summaries.associate { it.id to it.metadataFingerprint() }
        val unchangedIds = summaries.filter { summary ->
            val old = oldStates[summary.id]
            old != null && old.threadId == summary.threadId &&
                old.address == summary.address && old.timestamp == summary.date &&
                old.metadataFingerprint == fingerprints.getValue(summary.id)
        }.mapTo(mutableSetOf()) { it.id }
        val bodies = dao.mirrorSmsBodies(ids.filterNot { it in unchangedIds })
            .associate { it.messageId to it.body }
        val entries = mutableListOf<VerificationCodeIndexEntity>()
        val scanStates = summaries.map { summary ->
            currentCoroutineContext().ensureActive()
            if (summary.id !in unchangedIds) {
                bodies[summary.id]?.let { body ->
                    parseIndexEntry(summary, body, generation, ruleVersion)?.let { entries += it }
                }
            }
            SmsScanStateEntity(
                generation = generation,
                messageId = summary.id,
                threadId = summary.threadId,
                address = summary.address,
                timestamp = summary.date,
                metadataFingerprint = fingerprints.getValue(summary.id),
            )
        }
        // 本轮目标代已清空；未命中和已删除短信无需逐条删除，旧代在切换时统一清理。
        database.withTransaction {
            if (sourceGeneration != null && unchangedIds.isNotEmpty()) {
                dao.copyEntries(sourceGeneration, generation, unchangedIds.toList())
            }
            if (entries.isNotEmpty()) dao.upsertEntries(entries)
            dao.upsertScanStates(scanStates)
        }
    }

    /**
     * SMS 在写入后按正文不可变处理。增量扫描使用镜像元数据定位候选，
     * 同 ID 正文若被外部篡改，需要通过完整重建重新识别。
     */
    private fun SmsIndexSummaryRow.metadataFingerprint(): String {
        val metadata = "$id\u0000$threadId\u0000$address\u0000$date\u0000$dateSent\u0000$type"
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(metadata.toByteArray(Charsets.UTF_8))
        val hex = CharArray(digest.size * 2)
        digest.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            hex[index * 2] = HEX_DIGITS[value ushr 4]
            hex[index * 2 + 1] = HEX_DIGITS[value and 0x0f]
        }
        return hex.concatToString()
    }

    private fun currentRuleVersion(): String = settings.getRuleResourceVersion()

    private companion object {
        const val MAX_DATABASE_ARGS = 900
        const val SMS_SCAN_BATCH_SIZE = 500
        const val HEX_DIGITS = "0123456789abcdef"
        val monthFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM")
    }
}
