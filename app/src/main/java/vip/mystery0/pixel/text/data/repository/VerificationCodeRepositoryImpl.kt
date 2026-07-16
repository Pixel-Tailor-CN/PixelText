package vip.mystery0.pixel.text.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import vip.mystery0.pixel.text.data.db.VerificationCodeIndexDatabase
import vip.mystery0.pixel.text.data.db.VerificationCodeIndexEntity
import vip.mystery0.pixel.text.data.db.VerificationCodeMetadataEntity
import vip.mystery0.pixel.text.data.db.SmsScanStateEntity
import vip.mystery0.pixel.text.data.source.SmsIndexSummaryRow
import vip.mystery0.pixel.text.data.source.TelephonyDataSource
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
    private val database: VerificationCodeIndexDatabase,
    private val telephonyDataSource: TelephonyDataSource,
    private val parser: MessageParser,
    private val settings: AppSettingsRepository,
    private val contactDataSource: ContactDataSource,
) : VerificationCodeRepository {
    private val dao = database.verificationCodeIndexDao()
    private val writeMutex = Mutex()

    override fun observeMonths(): Flow<List<VerificationCodeMonthModel>> = dao.observeMonths()

    override fun observeMonth(monthKey: String): Flow<List<VerificationCodeIndexModel>> =
        dao.observeMonth(monthKey).map { messages ->
            messages.map { it.copy(displayName = contactDataSource.getDisplayName(it.address)) }
        }.flowOn(Dispatchers.IO)

    override suspend fun getMessageBody(messageId: Long): String? = withContext(Dispatchers.IO) {
        telephonyDataSource.getSmsBody(messageId)
    }

    override suspend fun indexMessage(
        messageId: Long,
        threadId: Long,
        address: String,
        body: String,
        timestamp: Long,
    ) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val metadata = ensureMetadata()
            indexIntoGeneration(
                summary = SmsIndexSummaryRow(messageId, threadId, address, timestamp, body.sha256()),
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
                bodyFingerprint = body.sha256(),
            )))
        }
    }

    override suspend fun rebuildAll() = withContext(Dispatchers.IO) {
        writeMutex.withLock { rebuildAllLocked() }
    }

    private suspend fun rebuildAllLocked() {
        val metadata = ensureMetadata()
        val generation = metadata.activeGeneration + 1
        val ruleVersion = currentRuleVersion()
        try {
            dao.deleteGeneration(generation)
            dao.deleteScanGeneration(generation)
            forEachSmsSummaryPage { summaries ->
                processPage(summaries, generation, ruleVersion, null)
            }
            database.activateGeneration(generation, ruleVersion, System.currentTimeMillis())
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

    private suspend fun reconcileLocked() {
        val metadata = ensureMetadata()
        val ruleVersion = currentRuleVersion()
        if (metadata.lastFullScanRuleVersion != ruleVersion) {
            rebuildAllLocked()
            return
        }

        val generation = metadata.activeGeneration + 1
        try {
            dao.deleteGeneration(generation)
            dao.deleteScanGeneration(generation)
            forEachSmsSummaryPage { summaries ->
                processPage(summaries, generation, ruleVersion, metadata.activeGeneration)
            }
            database.activateGeneration(generation, ruleVersion, System.currentTimeMillis())
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                dao.deleteGeneration(generation)
                dao.deleteScanGeneration(generation)
            }
            throw error
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
        var beforeMessageId: Long? = null
        while (true) {
            val summaries = telephonyDataSource.getSmsIndexSummaries(
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
        val result = parser.parse(summary.address, body) as? ParsedResult.VerificationCode
        if (result == null) {
            dao.deleteMessage(generation, summary.id)
            return
        }
        dao.upsertEntries(
            listOf(
                VerificationCodeIndexEntity(
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
            )
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
        val oldEntries = sourceGeneration?.let { dao.getEntries(it, ids) }
            .orEmpty().associateBy { it.messageId }
        val copiedEntries = mutableListOf<VerificationCodeIndexEntity>()
        val scanStates = summaries.map { summary ->
            val old = oldStates[summary.id]
            val unchanged = old != null && old.threadId == summary.threadId &&
                old.address == summary.address && old.timestamp == summary.date &&
                old.bodyFingerprint == summary.bodyFingerprint
            if (unchanged) {
                oldEntries[summary.id]?.let { copiedEntries += it.copy(generation = generation) }
            } else {
                telephonyDataSource.getSmsBody(summary.id)?.let { body ->
                    indexIntoGeneration(summary, body, generation, ruleVersion)
                }
            }
            SmsScanStateEntity(
                generation = generation,
                messageId = summary.id,
                threadId = summary.threadId,
                address = summary.address,
                timestamp = summary.date,
                bodyFingerprint = summary.bodyFingerprint,
            )
        }
        if (copiedEntries.isNotEmpty()) dao.upsertEntries(copiedEntries)
        dao.upsertScanStates(scanStates)
    }

    private fun String.sha256(): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun currentRuleVersion(): String = settings.getRuleResourceVersion()

    private companion object {
        const val MAX_DATABASE_ARGS = 900
        const val SMS_SCAN_BATCH_SIZE = 500
        val monthFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM")
    }
}
