package vip.mystery0.pixel.text.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import vip.mystery0.pixel.text.data.db.mirror.MessageMirrorDatabase
import vip.mystery0.pixel.text.data.db.mirror.MirrorSpamLegacyImporter
import vip.mystery0.pixel.text.data.repository.mirror.MessageMirrorSynchronizer
import vip.mystery0.pixel.text.data.resource.HubResourceStore
import vip.mystery0.pixel.text.data.source.LearningMessageSource
import vip.mystery0.pixel.text.data.spam.learning.ModelContract
import vip.mystery0.pixel.text.domain.model.mirror.MessageTransport
import vip.mystery0.pixel.text.domain.model.mirror.SourceMessageKey
import vip.mystery0.pixel.text.domain.settings.AppSettingsRepository
import vip.mystery0.pixel.text.domain.spam.*
import vip.mystery0.pixel.text.domain.spam.learning.*

/** 最终分类直接读取镜像列；模型与消息身份仅在写入或后台扫描时校验。 */
class SpamRepositoryImpl(
    database: MessageMirrorDatabase,
    private val settingsRepository: AppSettingsRepository,
    private val whitelist: SenderWhitelistRepositoryImpl,
    private val learning: PersonalLearningRepositoryImpl,
    private val resources: HubResourceStore,
    private val messages: LearningMessageSource,
    private val synchronizer: MessageMirrorSynchronizer,
    private val importer: MirrorSpamLegacyImporter,
    private val keywords: KeywordSpamRepositoryImpl,
) : SpamRepository {
    private val dao = database.spamDao()

    override suspend fun ensureReady() = withContext(Dispatchers.IO) {
        importer.ensureImported()
        whitelist.reconcileMessageWhitelistIfNeeded()
        learning.reconcileMessageCorrectionsIfNeeded()
        keywords.reconcileMessageKeywordsIfNeeded()
    }

    private fun identity() = ModelContract.parse(resources.currentModelBundle().contractFile.readText()).identity
    private suspend fun generation(identity: String) =
        if (settingsRepository.settings.value.personalLearningEnabled) learning.currentParameters(identity)?.generation else null

    override suspend fun isPredictionCurrent(prediction: SpamPrediction): Boolean {
        val identity = identity()
        return prediction.modelIdentity == identity && prediction.revision == learning.stateRevision() &&
            prediction.personalGeneration == generation(identity)
    }

    override suspend fun savePrediction(
        messageId: Long,
        prediction: SpamPrediction,
        key: LearningMessageKey,
        allowDisabled: Boolean,
    ): Boolean = withContext(Dispatchers.IO) {
        ensureReady()
        if (messageId == 0L || messageId == Long.MIN_VALUE || key.sourceId != messageId) return@withContext false
        val source = SourceMessageKey(if (messageId < 0) MessageTransport.MMS else MessageTransport.SMS, kotlin.math.abs(messageId))
        // 收信检测可能早于镜像观察者；先落入消息，再写最终标记，不能静默丢失 UPDATE。
        if (!synchronizer.refreshInitializationMessage(source)) return@withContext false
        val row = dao.rows(listOf(messageId)).firstOrNull() ?: return@withContext false
        learning.withDecision {
            if ((!isEnabled() && !allowDisabled) || messages.keys(listOf(messageId))[messageId] != key ||
                !prediction.score.isFinite() || prediction.score !in 0f..1f || !isPredictionCurrent(prediction)) {
                return@withDecision false
            }
            if (messageId > 0) keywords.updateMessageMatch(messageId)
            whitelist.withMessageDecision(messageId) {
                // 即使当前人工覆盖有效，也保留模型分数；DAO 保持人工／规则优先级。
                dao.savePrediction(messageId, prediction.score, prediction.modelIdentity, prediction.revision,
                    prediction.personalGeneration, allowDisabled, row.messageRevision, row.localId)
            }
        }
    }

    // 此方法用于已持有收信决策锁的副作用入口，调用者已校验 key，不重复获取锁。
    override suspend fun scoreForKey(key: LearningMessageKey): Float? = dao.getScore(key.sourceId)

    override suspend fun getScore(messageId: Long): Float? = withContext(Dispatchers.IO) {
        ensureReady()
        dao.getScore(messageId)
    }

    override suspend fun getScores(messageIds: List<Long>): Map<Long, Float> = withContext(Dispatchers.IO) {
        ensureReady()
        dao.getScores(messageIds)
    }

    override suspend fun delete(messageIds: Set<Long>) {
        // 分类列随镜像消息删除；此回调也可能已持有同步锁，不再发起同步或导入。
        if (messageIds.isEmpty()) return
        withContext(Dispatchers.IO) {
            val current = messages.keys(messageIds)
            learning.linkedMessageKeys().filter { it.sourceId in messageIds && current[it.sourceId] != it }
                .forEach { learning.unlinkMessage(it) }
        }
    }

    override fun observeChanges(): Flow<Unit> = dao.observeChanges().map { }
    override fun isEnabled(): Boolean = settingsRepository.isSpamDetectionEnabled()
}
