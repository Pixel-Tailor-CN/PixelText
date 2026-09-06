package vip.mystery0.pixel.text.domain.model.mirror

enum class MirrorInitializationPhase { NOT_STARTED, SCANNING, PARTIAL, COMPLETE }

data class MirrorCollectionState(
    val collection: String, val generation: Long, val checkpoint: Long?, val complete: Boolean,
    val lastSuccessTime: Long?, val error: String?, val columns: Set<String>, val processedCount: Long,
)

data class MirrorSyncState(
    val phase: MirrorInitializationPhase = MirrorInitializationPhase.NOT_STARTED,
    val completedCollections: Set<String> = emptySet(),
    val incompleteStructureCount: Int = 0,
    val pendingDownloadCount: Int = 0,
    val attachmentFailureCount: Int = 0,
    val pendingAttachmentCount: Int = 0,
    val lastSuccessTime: Long? = null,
    val collections: List<MirrorCollectionState> = emptyList(),
)
