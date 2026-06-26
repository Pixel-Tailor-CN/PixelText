package vip.mystery0.pixel.text.domain.hub

data class HubArtifact(
    val version: String,
    val sha256: String,
    val sizeBytes: Long,
    val downloadUrl: String,
    val releaseNotes: String,
)

data class HubFileArtifact(
    val sha256: String,
    val sizeBytes: Long,
    val downloadUrl: String,
)

data class HubSpamModelArtifact(
    val version: String,
    val model: HubFileArtifact,
    val vocab: HubFileArtifact,
    val releaseNotes: String,
)

data class HubResourceManifest(
    val rules: HubArtifact?,
    val spamModel: HubSpamModelArtifact?,
)

data class ResourceUpdateDetail(
    val modelVersion: String,
    val modelSizeBytes: Long?,
    val ruleVersion: String,
    val ruleSizeBytes: Long?,
    val releaseNotes: String,
)

sealed interface ResourceUpdateAvailability {
    data class Available(
        val manifest: HubResourceManifest,
        val detail: ResourceUpdateDetail,
    ) : ResourceUpdateAvailability

    data class NoUpdate(
        val message: String,
    ) : ResourceUpdateAvailability
}

data class SampleSubmissionRequest(
    val deviceId: String,
    val content: String,
    val sender: String?,
    val category: String,
    val appVersion: String,
    val ruleVersion: String,
    val modelVersion: String,
)

sealed interface HubOperationResult {
    data object Success : HubOperationResult
    data class Failure(val message: String) : HubOperationResult
}
