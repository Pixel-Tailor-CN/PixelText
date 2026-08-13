package vip.mystery0.pixel.text.domain.model

data class SenderProfileMatch(
    val displayName: String,
    val avatarPath: String,
    val avatarSha256: String,
)

data class SenderProfileBundle(
    val schemaVersion: Int,
    val version: String,
    val generatedAt: String,
    val profiles: List<SenderProfileBundleItem>,
)

data class SenderProfileBundleItem(
    val displayName: String,
    val avatar: SenderProfileBundleAvatar,
    val numbers: List<String>,
)

data class SenderProfileBundleAvatar(
    val path: String,
    val sha256: String,
)

data class SenderProfileManifest(
    val version: String,
    val schemaVersion: Int,
    val sha256: String,
    val sizeBytes: Long,
    val downloadUrl: String,
    val releaseNotes: String,
    val minAppVersionCode: Int,
    val publishedAt: String?,
)

data class SenderProfileUpdateInfo(
    val version: String,
    val sizeBytes: Long,
    val releaseNotes: String,
)
