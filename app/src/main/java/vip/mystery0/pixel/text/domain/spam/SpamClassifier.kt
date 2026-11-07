package vip.mystery0.pixel.text.domain.spam

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import vip.mystery0.pixel.text.domain.settings.AppSettingsRepository
import vip.mystery0.pixel.text.domain.spam.learning.PersonalLearningRepository
import vip.mystery0.pixel.text.domain.spam.learning.PersonalParameters
import vip.mystery0.pixel.text.domain.spam.learning.SpamModelEngine

data class SpamPrediction(val score: Float, val source: String, val modelIdentity: String,
    val personalGeneration: Long?, val revision: Long)

/** 批次持有参数快照，写入结果前由调用方复核模型、设置与学习代次。 */
data class SpamPredictionSession(
    val modelIdentity: String,
    val revision: Long,
    val personalEnabled: Boolean,
    val parameters: PersonalParameters?,
)

/** 会话独占模型；个人参数有效时直接推理，失败后回退公共模型。 */
class SpamClassifier(private val engine: SpamModelEngine,
    private val learning: PersonalLearningRepository,
    private val settings: AppSettingsRepository,
    private val recoverParameters: suspend (Long) -> Unit) : AutoCloseable {
    val modelIdentity: String get() = engine.identity

    suspend fun openSession(): SpamPredictionSession = learning.withDecision {
        val enabled = settings.settings.value.personalLearningEnabled
        SpamPredictionSession(engine.identity, learning.stateRevision(), enabled,
            if (enabled) learning.currentParameters(engine.identity) else null)
    }

    suspend fun predict(text: String): SpamPrediction = predict(text, openSession())

    suspend fun predict(text: String, session: SpamPredictionSession): SpamPrediction {
        currentCoroutineContext().ensureActive()
        require(session.modelIdentity == engine.identity)
        var revision = session.revision
        var parameters = session.parameters
        val score = try {
            parameters?.let { engine.inferPersonal(text, it.payload) } ?: engine.inferPublic(text)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (parameters == null) throw error
            recoverParameters(revision)
            revision = learning.stateRevision()
            parameters = null
            engine.inferPublic(text)
        }
        currentCoroutineContext().ensureActive()
        return SpamPrediction(score, if (parameters == null) "public" else "personal", engine.identity, parameters?.generation, revision)
    }
    suspend fun classify(text: String): Float = try { predict(text).score }
    catch (error: CancellationException) { throw error }
    catch (_: Exception) { Log.e("SpamClassifier", "inference failed"); -1f }
    override fun close() = engine.close()
}
