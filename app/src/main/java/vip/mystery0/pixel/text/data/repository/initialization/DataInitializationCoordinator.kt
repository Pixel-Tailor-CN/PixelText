package vip.mystery0.pixel.text.data.repository.initialization

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import vip.mystery0.pixel.text.data.repository.mirror.MessageMirrorSynchronizer
import vip.mystery0.pixel.text.data.repository.mms.MmsTextIndexer
import vip.mystery0.pixel.text.domain.model.*
import vip.mystery0.pixel.text.domain.repository.VerificationCodeRepository
import vip.mystery0.pixel.text.domain.spam.SpamRepository

class DataInitializationCoordinator(
    private val repository: DataInitializationRepository,
    private val guard: DataInitializationGuard,
    private val synchronizer: MessageMirrorSynchronizer,
    private val mms: MmsTextIndexer,
    private val spam: InitializationSpamScanner,
    private val spamRepository: SpamRepository,
    private val verification: VerificationCodeRepository,
) {
    suspend fun runSlice(timeBudgetMillis: Long = 60_000L): InitializationStepResult =
        guard.withStableInputs {
            val deadline = SystemClock.elapsedRealtime() + timeBudgetMillis.coerceAtLeast(1)
            repository.prepare()
            while (SystemClock.elapsedRealtime() < deadline) {
                currentCoroutineContext().ensureActive()
                val state = repository.read()
                if (state.isCurrent) return@withStableInputs InitializationStepResult.Complete
                if (state.isNewerVersion || state.targetVersion > CURRENT_DATA_VERSION) {
                    return@withStableInputs InitializationStepResult.Blocked("newer_data_version")
                }
                if (!repository.save(
                        state.epoch,
                        state.copy(status = InitializationStatus.RUNNING, errorCategory = null)
                    )
                ) {
                    return@withStableInputs InitializationStepResult.More
                }
                Log.i(
                    "DataInitialization",
                    "initialization stage target=${state.targetVersion} phase=${state.nextPhase} checkpoint=${state.afterLocalId}"
                )
                val next = when (state.nextPhase) {
                    InitializationPhase.MIRROR -> {
                        val result = synchronizer.reconcileForInitialization(
                            state.targetVersion, state.epoch,
                            (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1)
                        )
                        if (result != InitializationStepResult.Complete) return@withStableInputs result
                        continue
                    }

                    InitializationPhase.MMS_TEXT -> {
                        val batch = mms.indexInitializationBatch(
                            state.afterLocalId,
                            requireNotNull(state.upperLocalId)
                        )
                        if (batch.complete) state.copy(
                            nextPhase = InitializationPhase.SPAM,
                            afterLocalId = 0
                        )
                        else state.copy(afterLocalId = batch.afterLocalId)
                    }

                    InitializationPhase.SPAM -> {
                        if (!spamRepository.isEnabled()) {
                            state.copy(
                                nextPhase = InitializationPhase.VERIFICATION,
                                afterLocalId = 0,
                                skipReason = "spam_disabled"
                            )
                        } else {
                            val batch = spam.scanBatch(
                                state.afterLocalId,
                                requireNotNull(state.upperLocalId)
                            )
                            if (batch.complete) state.copy(
                                nextPhase = InitializationPhase.VERIFICATION, afterLocalId = 0,
                                skipReason = if (spamRepository.isEnabled()) null else "spam_disabled"
                            )
                            else state.copy(afterLocalId = batch.afterLocalId)
                        }
                    }

                    InitializationPhase.VERIFICATION -> {
                        verification.rebuildAll()
                        state.copy(
                            nextPhase = InitializationPhase.COMPLETE,
                            status = InitializationStatus.COMPLETE,
                            completedVersion = state.targetVersion,
                            errorCategory = null
                        )
                    }

                    InitializationPhase.COMPLETE -> return@withStableInputs InitializationStepResult.Blocked(
                        "invalid_completion_state"
                    )
                }
                if (!repository.save(
                        state.epoch,
                        next.copy(status = if (next.isCurrent) InitializationStatus.COMPLETE else InitializationStatus.RUNNING)
                    )
                ) return@withStableInputs InitializationStepResult.More
                if (next.isCurrent) {
                    Log.i(
                        "DataInitialization",
                        "initialization completed version=${next.completedVersion}"
                    )
                    return@withStableInputs InitializationStepResult.Complete
                }
            }
            InitializationStepResult.More
        }
}
