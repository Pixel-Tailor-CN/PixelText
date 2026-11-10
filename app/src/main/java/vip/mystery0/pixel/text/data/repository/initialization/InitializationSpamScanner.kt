package vip.mystery0.pixel.text.data.repository.initialization

import vip.mystery0.pixel.text.data.repository.MirrorSpamScanner
import vip.mystery0.pixel.text.data.repository.MirrorSpamScanSession
import vip.mystery0.pixel.text.domain.model.InitializationBatchResult

/** 前置准备或扫描快照暂不可用，保留检查点并等待下一次初始化调度。 */
class InitializationSpamPendingException(val category: String) : Exception(category)

/** 镜像阶段完成后只识别镜像，不在分类阶段重新读取系统数据。 */
class InitializationSpamScanner(private val scanner: MirrorSpamScanner) {
    suspend fun openSession(): MirrorSpamScanSession {
        requireReady()
        return scanner.openSession()
    }

    private suspend fun requireReady() {
        if (!scanner.isReady()) throw InitializationSpamPendingException("spam_projection_not_ready")
    }

    suspend fun scanBatch(
        afterLocalId: Long,
        upperLocalId: Long,
        limit: Int = 200,
        classifier: MirrorSpamScanSession? = null,
    ): InitializationBatchResult {
        if (!scanner.isEnabled()) return InitializationBatchResult(afterLocalId, complete = true)
        requireReady()
        suspend fun scan(session: MirrorSpamScanSession): InitializationBatchResult {
            val batch = scanner.scanBatch(session, afterLocalId, upperLocalId, limit)
            if (batch.stale) throw InitializationSpamPendingException("stale_spam_prediction")
            return InitializationBatchResult(batch.afterLocalId, complete = batch.complete || batch.paused)
        }
        return if (classifier != null) scan(classifier) else openSession().use { scan(it) }
    }
}
