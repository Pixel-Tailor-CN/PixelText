package vip.mystery0.pixel.text.data.db.mirror

import androidx.room.Entity
import androidx.room.PrimaryKey
import vip.mystery0.pixel.text.domain.model.DataInitializationState
import vip.mystery0.pixel.text.domain.model.InitializationPhase
import vip.mystery0.pixel.text.domain.model.InitializationStatus

/** 与镜像同库保存，不属于可移植的用户设置。 */
@Entity(tableName = "data_initialization")
data class DataInitializationEntity(
    @PrimaryKey val id: Int = 1,
    val completedVersion: Int,
    val targetVersion: Int,
    val nextPhase: String,
    val status: String,
    val epoch: Long,
    val mirrorRound: Long?,
    val afterLocalId: Long,
    val upperLocalId: Long?,
    val errorCategory: String?,
    val skipReason: String?,
    val updatedAt: Long,
) {
    fun toState() = DataInitializationState(
        completedVersion,
        targetVersion,
        InitializationPhase.entries.firstOrNull { it.name == nextPhase }
            ?: InitializationPhase.MIRROR,
        InitializationStatus.entries.firstOrNull { it.name == status }
            ?: InitializationStatus.FAILED,
        epoch,
        mirrorRound,
        afterLocalId,
        upperLocalId,
        errorCategory,
        skipReason,
        updatedAt,
    )
}

fun DataInitializationState.toEntity() = DataInitializationEntity(
    completedVersion = completedVersion, targetVersion = targetVersion,
    nextPhase = nextPhase.name, status = status.name, epoch = epoch,
    mirrorRound = mirrorRound, afterLocalId = afterLocalId, upperLocalId = upperLocalId,
    errorCategory = errorCategory, skipReason = skipReason, updatedAt = updatedAt,
)
