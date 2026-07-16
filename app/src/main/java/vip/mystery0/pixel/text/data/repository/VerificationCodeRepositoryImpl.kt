package vip.mystery0.pixel.text.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import vip.mystery0.pixel.text.data.db.VerificationCodeIndexDatabase
import vip.mystery0.pixel.text.data.db.VerificationCodeIndexEntity
import vip.mystery0.pixel.text.data.db.VerificationCodeMetadataEntity
import vip.mystery0.pixel.text.data.source.SmsIndexSummaryRow
import vip.mystery0.pixel.text.data.source.TelephonyDataSource
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
) : VerificationCodeRepository {
    private val dao = database.verificationCodeIndexDao()

    override fun observeMonths(): Flow<List<VerificationCodeMonthModel>> = dao.observeMonths()

    override fun observeMonth(monthKey: String): Flow<List<VerificationCodeIndexModel>> =
        dao.observeMonth(monthKey)

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
        val metadata = ensureMetadata()
        indexIntoGeneration(
            summary = SmsIndexSummaryRow(messageId, threadId, address, timestamp),
            body = body,
            generation = metadata.activeGeneration,
            ruleVersion = currentRuleVersion(),
        )
    }

    override suspend fun rebuildAll() = withContext(Dispatchers.IO) {
        val metadata = ensureMetadata()
        val generation = maxOf(System.currentTimeMillis(), metadata.activeGeneration + 1)
        val ruleVersion = currentRuleVersion()
        try {
            telephonyDataSource.getSmsIndexSummaries().forEach { summary ->
                val body = telephonyDataSource.getSmsBody(summary.id) ?: return@forEach
                indexIntoGeneration(summary, body, generation, ruleVersion)
            }
            database.activateGeneration(generation, ruleVersion, System.currentTimeMillis())
        } catch (error: Throwable) {
            dao.deleteGeneration(generation)
            throw error
        }
    }

    override suspend fun reconcile() = withContext(Dispatchers.IO) {
        val metadata = ensureMetadata()
        val ruleVersion = currentRuleVersion()
        if (metadata.lastFullScanRuleVersion != ruleVersion) {
            rebuildAll()
            return@withContext
        }

        val summaries = telephonyDataSource.getSmsIndexSummaries()
        val summariesById = summaries.associateBy(SmsIndexSummaryRow::id)
        val indexedById = dao.getActiveEntries().associateBy(VerificationCodeIndexEntity::messageId)

        summaries.forEach { summary ->
            val indexed = indexedById[summary.id]
            val unchanged = indexed != null &&
                indexed.threadId == summary.threadId &&
                indexed.address == summary.address &&
                indexed.timestamp == summary.date &&
                indexed.ruleVersion == ruleVersion
            if (!unchanged) {
                val body = telephonyDataSource.getSmsBody(summary.id)
                if (body == null) {
                    dao.deleteMessage(metadata.activeGeneration, summary.id)
                } else {
                    indexIntoGeneration(summary, body, metadata.activeGeneration, ruleVersion)
                }
            }
        }

        val missingIndexedIds = indexedById.keys - summariesById.keys
        missingIndexedIds.chunked(MAX_DATABASE_ARGS).forEach { chunk ->
            dao.deleteMessageIds(chunk)
        }
        dao.upsertMetadata(metadata.copy(lastReconciledAt = System.currentTimeMillis()))
    }

    override suspend fun deleteMessageIds(messageIds: Collection<Long>) =
        withContext(Dispatchers.IO) {
            messageIds.distinct().chunked(MAX_DATABASE_ARGS).forEach { chunk ->
                dao.deleteMessageIds(chunk)
            }
        }

    override suspend fun deleteThreadIds(threadIds: Collection<Long>) =
        withContext(Dispatchers.IO) {
            threadIds.distinct().chunked(MAX_DATABASE_ARGS).forEach { chunk ->
                dao.deleteThreadIds(chunk)
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

    private fun currentRuleVersion(): String = settings.getRuleResourceVersion()

    private companion object {
        const val MAX_DATABASE_ARGS = 900
        val monthFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM")
    }
}
