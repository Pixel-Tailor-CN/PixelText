package vip.mystery0.pixel.text.domain.spam

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import vip.mystery0.pixel.text.domain.settings.AppSettingsRepository
import vip.mystery0.pixel.text.domain.spam.learning.PersonalLearningRepository
import vip.mystery0.pixel.text.domain.spam.learning.SpamModelEngine

data class SpamPrediction(val score: Float, val source: String, val modelIdentity: String,
    val personalGeneration: Long?, val revision: Long)

/** 会话独占模型；公共模型先处理全文，个人参数有效时以个人结果为准。 */
class SpamClassifier(private val engine: SpamModelEngine,
    private val learning: PersonalLearningRepository,
    private val settings: AppSettingsRepository,
    private val recoverParameters: suspend (Long) -> Unit) : AutoCloseable {
    suspend fun predict(text: String): SpamPrediction {
        currentCoroutineContext().ensureActive()
        var revision = learning.stateRevision()
        var parameters = if (settings.settings.value.personalLearningEnabled) learning.currentParameters(engine.identity) else null
        val publicScore = engine.inferPublic(text)
        val score = try {
            parameters?.let { engine.inferPersonal(text, it.payload) } ?: publicScore
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            recoverParameters(revision)
            revision = learning.stateRevision()
            parameters = null
            publicScore
        }
        currentCoroutineContext().ensureActive()
        return SpamPrediction(score, if (parameters == null) "public" else "personal", engine.identity, parameters?.generation, revision)
    }
    suspend fun classify(text: String): Float = try { predict(text).score }
    catch (error: CancellationException) { throw error }
    catch (_: Exception) { Log.e("SpamClassifier", "inference failed"); -1f }
    override fun close() = engine.close()
}
