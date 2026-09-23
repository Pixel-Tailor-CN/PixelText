package vip.mystery0.pixel.text.domain.model

/** 数据处理契约版本，与应用版本和 Room 结构版本独立。 */
const val CURRENT_DATA_VERSION = 1

enum class InitializationPhase { MIRROR, MMS_TEXT, SPAM, VERIFICATION, COMPLETE }
enum class InitializationStatus { PENDING, RUNNING, WAITING_PERMISSION, FAILED, COMPLETE }

data class DataInitializationState(
    val completedVersion: Int = 0,
    val targetVersion: Int = CURRENT_DATA_VERSION,
    val nextPhase: InitializationPhase = InitializationPhase.MIRROR,
    val status: InitializationStatus = InitializationStatus.PENDING,
    val epoch: Long = 0,
    val mirrorRound: Long? = null,
    val afterLocalId: Long = 0,
    val upperLocalId: Long? = null,
    val errorCategory: String? = null,
    val skipReason: String? = null,
    val updatedAt: Long = 0,
) {
    val isCurrent: Boolean get() = completedVersion == CURRENT_DATA_VERSION && targetVersion <= CURRENT_DATA_VERSION
    val isNewerVersion: Boolean get() = completedVersion > CURRENT_DATA_VERSION || targetVersion > CURRENT_DATA_VERSION
}

sealed interface InitializationStepResult {
    data object Complete : InitializationStepResult
    data object More : InitializationStepResult
    data class Blocked(val category: String) : InitializationStepResult
}

data class InitializationBatchResult(
    val afterLocalId: Long,
    val complete: Boolean,
    val unavailableCount: Int = 0,
)
