package vip.mystery0.pixel.text.data.repository.initialization

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import vip.mystery0.pixel.text.data.repository.mirror.MirrorSynchronizationLock
import vip.mystery0.pixel.text.domain.spam.SenderWhitelistRepository
import vip.mystery0.pixel.text.data.db.mirror.MessageMirrorDatabase
import vip.mystery0.pixel.text.domain.model.InitializationBatchResult
import vip.mystery0.pixel.text.domain.spam.SpamClassifier
import vip.mystery0.pixel.text.domain.spam.SpamClassifierFactory
import vip.mystery0.pixel.text.domain.spam.SpamRepository
import java.io.IOException

/** 只重算自动分类；人工关键词与白名单继续由既有仓库优先处理。 */
class InitializationSpamScanner(
    private val database: MessageMirrorDatabase,
    private val repository: SpamRepository,
    private val classifiers: SpamClassifierFactory,
    private val whitelist: SenderWhitelistRepository,
) {
    /** 会话由调用方独占并关闭，不在扫描器中保存跨任务可变实例。 */
    suspend fun openSession(): SpamClassifier {
        currentCoroutineContext().ensureActive()
        return classifiers.create()
    }

    suspend fun scanBatch(
        afterLocalId: Long,
        upperLocalId: Long,
        limit: Int = 200,
        classifier: SpamClassifier? = null,
    ): InitializationBatchResult {
        require(limit > 0)
        // 协调器会记录禁用原因；过程中关闭时也不擅自开启分类。
        if (!repository.isEnabled()) return InitializationBatchResult(afterLocalId, complete = true)
        val dao = database.mirrorDao()
        val rows = dao.initializationBatch("SMS", afterLocalId, upperLocalId, limit)
        if (rows.isEmpty()) return InitializationBatchResult(afterLocalId, complete = true)
        suspend fun scanWithClassifier(activeClassifier: SpamClassifier): InitializationBatchResult {
            var checkpoint = afterLocalId
            for (row in rows) {
                currentCoroutineContext().ensureActive()
                if (!repository.isEnabled()) return InitializationBatchResult(
                    checkpoint,
                    complete = true
                )
                val sms = row.sms ?: throw IOException("sms_structure_missing")
                val prediction = if (whitelist.isAllowed(row.message.sourceId) || sms.body.isNullOrBlank()) null
                    else activeClassifier.predict(sms.body.orEmpty())
                // 分类期间被修改的数据由下一批重读，不以旧内容推进检查点。
                val unchanged = MirrorSynchronizationLock.mutex.withLock {
                    val latest = dao.getLocal(row.message.localId)
                    if (latest != null) {
                        if (latest.message.revision != row.message.revision) {
                            return@withLock false
                        }
                        if (prediction != null && !repository.savePrediction(row.message.sourceId, row.message.threadId ?: -1, prediction, vip.mystery0.pixel.text.domain.spam.learning.LearningMessageKey.create(
                                "SMS", row.message.sourceId, sms.address.orEmpty(), row.message.originalDate ?: 0L, sms.body.orEmpty()))) {
                            throw IOException("stale_spam_prediction")
                        }
                    }
                    true
                }
                if (!unchanged) return InitializationBatchResult(checkpoint, complete = false)
                checkpoint = row.message.localId
            }
            return InitializationBatchResult(checkpoint, complete = rows.size < limit)
        }
        // 传入的会话只借用；兼容旧调用时仍负责关闭本批创建的分类器。
        return if (classifier != null) scanWithClassifier(classifier)
        else classifiers.create().use { scanWithClassifier(it) }
    }
}
