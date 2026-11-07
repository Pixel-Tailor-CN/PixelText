package vip.mystery0.pixel.text.data.repository.initialization

import vip.mystery0.pixel.text.data.repository.MirrorSpamScanner
import vip.mystery0.pixel.text.data.repository.MirrorSpamScanSession
import vip.mystery0.pixel.text.domain.model.InitializationBatchResult
import java.io.IOException

/** 镜像阶段完成后只识别镜像，不在分类阶段重新读取系统数据。 */
class InitializationSpamScanner(private val scanner: MirrorSpamScanner) {
    suspend fun openSession(): MirrorSpamScanSession = scanner.openSession()

    suspend fun scanBatch(
        afterLocalId: Long,
        upperLocalId: Long,
        limit: Int = 200,
        classifier: MirrorSpamScanSession? = null,
    ): InitializationBatchResult {
        if (!scanner.isEnabled()) return InitializationBatchResult(afterLocalId, complete = true)
        if (!scanner.isReady()) throw IOException("spam_projection_not_ready")
        suspend fun scan(session: MirrorSpamScanSession): InitializationBatchResult {
            val batch = scanner.scanBatch(session, afterLocalId, upperLocalId, limit)
            if (batch.stale) throw IOException("stale_spam_prediction")
            return InitializationBatchResult(batch.afterLocalId, complete = batch.complete || batch.paused)
        }
        return if (classifier != null) scan(classifier) else openSession().use { scan(it) }
    }
}
