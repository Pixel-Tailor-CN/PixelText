package vip.mystery0.pixel.text.data.repository.initialization

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import vip.mystery0.pixel.text.data.repository.mirror.MirrorSynchronizationLock
import vip.mystery0.pixel.text.domain.spam.SenderWhitelistRepository
import vip.mystery0.pixel.text.data.db.mirror.MessageMirrorDatabase
import vip.mystery0.pixel.text.domain.model.InitializationBatchResult
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
    suspend fun scanBatch(
        afterLocalId: Long,
        upperLocalId: Long,
        limit: Int = 200,
    ): InitializationBatchResult {
        require(limit > 0)
        // 协调器会记录禁用原因；过程中关闭时也不擅自开启分类。
        if (!repository.isEnabled()) return InitializationBatchResult(afterLocalId, complete = true)
        val dao = database.mirrorDao()
        val rows = dao.initializationBatch("SMS", afterLocalId, upperLocalId, limit)
        if (rows.isEmpty()) return InitializationBatchResult(afterLocalId, complete = true)
        var checkpoint = afterLocalId
        classifiers.create().use { classifier ->
            for (row in rows) {
                currentCoroutineContext().ensureActive()
                if (!repository.isEnabled()) return InitializationBatchResult(
                    checkpoint,
                    complete = true
                )
                val sms = row.sms ?: throw IOException("sms_structure_missing")
                val score =
                    if (whitelist.isAllowed(row.message.sourceId)) 0f else classifier.classify(sms.body.orEmpty())
                if (!score.isFinite() || score < 0f) throw IOException("spam_classification_unavailable")
                // 分类期间被修改的数据由下一批重读，不以旧内容推进检查点。
                val unchanged = MirrorSynchronizationLock.mutex.withLock {
                    val latest = dao.getLocal(row.message.localId)
                    if (latest != null) {
                        if (latest.message.revision != row.message.revision) {
                            return@withLock false
                        }
                        repository.save(row.message.sourceId, row.message.threadId ?: -1, score)
                    }
                    true
                }
                if (!unchanged) return InitializationBatchResult(checkpoint, complete = false)
                checkpoint = row.message.localId
            }
        }
        return InitializationBatchResult(checkpoint, complete = rows.size < limit)
    }
}
