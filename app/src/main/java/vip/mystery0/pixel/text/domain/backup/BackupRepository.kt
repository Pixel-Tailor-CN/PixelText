package vip.mystery0.pixel.text.domain.backup

import com.squareup.moshi.JsonClass
import kotlinx.coroutines.flow.StateFlow

@JsonClass(generateAdapter = false)
enum class BackupSection(val label: String) { SETTINGS("设置与主题"), RULES("关键词与白名单"), SMS("短信及归档、放行状态") }
enum class BackupPhase {
    IDLE, SYNCING_MIRROR, SNAPSHOTTING, EXPORTING, VALIDATING, PREVIEW,
    RESTORING, REBUILDING, COMPLETED, INTERRUPTED, FAILED
}
data class BackupPreview(val token: String, val sections: Set<BackupSection>, val smsCount: Long, val createdAt: Long)
data class BackupSummary(
    val inserted: Long = 0, val existing: Long = 0, val failed: Long = 0,
    val remaining: Long = 0, val convertedOutgoing: Long = 0,
    val skippedAssociations: Long = 0, val completedSections: Set<BackupSection> = emptySet(),
)
data class BackupOperationState(
    val phase: BackupPhase = BackupPhase.IDLE,
    val processed: Long = 0, val total: Long? = null,
    val preview: BackupPreview? = null, val summary: BackupSummary = BackupSummary(),
    val errorMessage: String? = null, val restoreProtected: Boolean = false,
    val authorizationRequired: Boolean = false,
) {
    val busy: Boolean get() = phase in setOf(BackupPhase.SYNCING_MIRROR, BackupPhase.SNAPSHOTTING,
        BackupPhase.EXPORTING, BackupPhase.VALIDATING, BackupPhase.RESTORING, BackupPhase.REBUILDING)
}
interface BackupRepository {
    val state: StateFlow<BackupOperationState>
    fun exportTo(uri: String, sections: Set<BackupSection>, password: CharArray?)
    fun inspect(uri: String, password: CharArray?)
    fun restore(token: String, sections: Set<BackupSection>)
    fun cancel()
    fun acknowledgeResult()

    /** 关闭完成结果提示，回到初始状态。 */
    fun dismissResult()

    /** 只清除当前错误提示，保留中断进度与保护状态。 */
    fun clearError()
}
