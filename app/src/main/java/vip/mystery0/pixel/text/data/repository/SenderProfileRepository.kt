package vip.mystery0.pixel.text.data.repository

import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import vip.mystery0.pixel.text.BuildConfig
import vip.mystery0.pixel.text.R
import vip.mystery0.pixel.text.data.db.ConversationCacheDatabase
import vip.mystery0.pixel.text.data.db.SenderProfileEntity
import vip.mystery0.pixel.text.data.db.SenderProfileGenerationEntity
import vip.mystery0.pixel.text.data.db.SenderProfileNumberEntity
import vip.mystery0.pixel.text.data.db.SenderProfileStateEntity
import vip.mystery0.pixel.text.data.db.toDomain
import vip.mystery0.pixel.text.data.resource.SenderProfileStore
import vip.mystery0.pixel.text.data.source.PixelTextHubClient
import vip.mystery0.pixel.text.data.source.SenderProfileDownloadException
import vip.mystery0.pixel.text.domain.model.SenderProfileManifest
import vip.mystery0.pixel.text.domain.model.SenderProfileMatch
import vip.mystery0.pixel.text.domain.model.SenderProfileUpdateInfo
import vip.mystery0.pixel.text.util.AppStrings
import java.io.FileInputStream

class SenderProfileRepository(
    private val database: ConversationCacheDatabase,
    private val store: SenderProfileStore,
    private val client: PixelTextHubClient,
) {
    private val dao = database.senderProfileDao()

    suspend fun currentVersion(): String? = withContext(Dispatchers.IO) {
        dao.getState()?.activeVersion
    }

    suspend fun checkUpdate(): SenderProfileUpdateAvailability = withContext(Dispatchers.IO) {
        val manifest = client.fetchSenderProfileManifest()
            ?: return@withContext SenderProfileUpdateAvailability.NoUpdate(
                AppStrings.get(R.string.domain_sender_profile_not_published),
            )
        validateManifest(manifest)
        val current = dao.getState()?.activeVersion
        if (current == manifest.version) {
            SenderProfileUpdateAvailability.NoUpdate(
                AppStrings.get(R.string.domain_sender_profile_already_latest),
            )
        } else if (manifest.minAppVersionCode > BuildConfig.VERSION_CODE) {
            SenderProfileUpdateAvailability.NoUpdate(
                AppStrings.get(R.string.domain_sender_profile_requires_app_update),
            )
        } else {
            SenderProfileUpdateAvailability.Available(
                info = SenderProfileUpdateInfo(
                    version = manifest.version,
                    sizeBytes = manifest.sizeBytes,
                    releaseNotes = manifest.releaseNotes.ifBlank {
                        AppStrings.get(R.string.domain_hub_no_release_notes)
                    },
                ),
                manifest = manifest,
            )
        }
    }

    suspend fun install(
        expectedManifest: SenderProfileManifest,
        onProgress: (message: String, progress: Float) -> Unit = { _, _ -> },
    ): SenderProfileInstallResult = withContext(Dispatchers.IO) {
        var targetVersion = expectedManifest.version
        runCatching {
            onProgress(AppStrings.get(R.string.domain_sender_profile_progress_fetching_url), 0.03f)
            var manifest = client.fetchSenderProfileManifest()
                ?: error(AppStrings.get(R.string.domain_sender_profile_not_published))
            validateManifest(manifest)
            ensureSameArtifact(expectedManifest, manifest)
            targetVersion = manifest.version
            val tempFile = store.tempBundleFile(manifest.version)
            try {
                onProgress(AppStrings.get(R.string.domain_sender_profile_progress_downloading), 0.08f)
                try {
                    download(manifest, tempFile, onProgress)
                } catch (error: SenderProfileDownloadException) {
                    if (error.statusCode != 401 && error.statusCode != 403) throw error
                    manifest = client.fetchSenderProfileManifest()
                        ?: error(AppStrings.get(R.string.domain_sender_profile_not_published))
                    validateManifest(manifest)
                    ensureSameArtifact(expectedManifest, manifest)
                    download(manifest, tempFile, onProgress)
                }
                onProgress(AppStrings.get(R.string.domain_sender_profile_progress_verifying), 0.58f)
                store.verifyBundle(tempFile, manifest.sizeBytes, manifest.sha256)
                val installed = FileInputStream(tempFile).use { input ->
                    store.installBundle(input, manifest.version)
                }
                onProgress(AppStrings.get(R.string.domain_sender_profile_progress_importing), 0.72f)
                importBundle(installed.bundle)
                onProgress(AppStrings.get(R.string.domain_sender_profile_progress_cleaning), 0.9f)
                runCatching { cleanupOldGenerations() }
                    .onFailure { error ->
                        Log.w(
                            TAG,
                            "sender profile cleanup failed error=${error::class.java.simpleName}",
                            error,
                        )
                    }
                onProgress(AppStrings.get(R.string.domain_sender_profile_progress_complete), 1f)
                Log.i(
                    TAG,
                    "sender profile bundle activated version=${installed.bundle.version} profiles=${installed.bundle.profiles.size}",
                )
                SenderProfileInstallResult.Success(installed.bundle.version)
            } finally {
                tempFile.delete()
            }
        }.getOrElse { error ->
            Log.e(TAG, "sender profile install failed error=${error::class.java.simpleName}", error)
            val activeVersion = runCatching { dao.getState()?.activeVersion }.getOrNull()
            if (activeVersion != targetVersion) {
                runCatching { store.deleteVersion(targetVersion) }
            }
            SenderProfileInstallResult.Failure(
                error.message ?: AppStrings.get(R.string.domain_sender_profile_install_failed),
            )
        }
    }

    suspend fun findByNumber(address: String): SenderProfileMatch? = withContext(Dispatchers.IO) {
        if (address.isBlank()) null else dao.findActiveByNumber(address)?.toDomain()
    }

    suspend fun findByNumbers(addresses: Collection<String>): Map<String, SenderProfileMatch> =
        withContext(Dispatchers.IO) {
            val distinct = addresses.filter { it.isNotBlank() }.distinct()
            if (distinct.isEmpty()) return@withContext emptyMap()
            dao.findActiveByNumbers(distinct).associate { it.number to it.toDomain() }
        }

    private suspend fun download(
        manifest: SenderProfileManifest,
        target: java.io.File,
        onProgress: (String, Float) -> Unit,
    ) {
        client.downloadSenderProfileTo(
            url = manifest.downloadUrl,
            target = target,
            expectedSizeBytes = manifest.sizeBytes,
            maxSizeBytes = MAX_BUNDLE_BYTES,
        ) { bytes ->
            val fraction = if (manifest.sizeBytes <= 0L) 0f
            else bytes.toFloat() / manifest.sizeBytes.toFloat()
            onProgress(
                AppStrings.get(R.string.domain_sender_profile_progress_downloading),
                0.08f + fraction.coerceIn(0f, 1f) * 0.46f,
            )
        }
    }

    private suspend fun importBundle(bundle: vip.mystery0.pixel.text.domain.model.SenderProfileBundle) {
        database.withTransaction {
            val previousState = dao.getState()
            dao.insertGeneration(
                SenderProfileGenerationEntity(
                    version = bundle.version,
                    importedAt = System.currentTimeMillis(),
                )
            )
            bundle.profiles.forEach { profile ->
                val profileId = dao.insertProfile(
                    SenderProfileEntity(
                        generationVersion = bundle.version,
                        displayName = profile.displayName,
                        avatarPath = profile.avatar.path,
                        avatarSha256 = profile.avatar.sha256,
                    )
                )
                dao.insertNumbers(
                    profile.numbers.map { number ->
                        SenderProfileNumberEntity(
                            generationVersion = bundle.version,
                            number = number,
                            senderProfileId = profileId,
                        )
                    }
                )
            }
            dao.upsertState(
                SenderProfileStateEntity(
                    activeVersion = bundle.version,
                    previousVersion = previousState?.activeVersion
                        ?.takeIf { it != bundle.version },
                )
            )
        }
    }

    private fun validateManifest(manifest: SenderProfileManifest) {
        if (manifest.schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            error(AppStrings.get(R.string.domain_sender_profile_unsupported_schema, manifest.schemaVersion))
        }
        if (manifest.version.isBlank()) error(AppStrings.get(R.string.domain_sender_profile_empty_version))
        if (manifest.sizeBytes !in 1..MAX_BUNDLE_BYTES) {
            error(AppStrings.get(R.string.domain_sender_profile_invalid_size))
        }
        if (!SHA256.matches(manifest.sha256)) error(AppStrings.get(R.string.domain_sender_profile_invalid_hash))
        if (!manifest.downloadUrl.startsWith("https://")) error(AppStrings.get(R.string.domain_sender_profile_insecure_url))
    }

    private fun ensureSameArtifact(
        expected: SenderProfileManifest,
        actual: SenderProfileManifest,
    ) {
        if (
            expected.version != actual.version ||
            expected.schemaVersion != actual.schemaVersion ||
            expected.sizeBytes != actual.sizeBytes ||
            !expected.sha256.equals(actual.sha256, ignoreCase = true)
        ) {
            error(AppStrings.get(R.string.domain_sender_profile_version_changed))
        }
    }

    private suspend fun cleanupOldGenerations() {
        val state = dao.getState() ?: return
        val keep = setOfNotNull(state.activeVersion, state.previousVersion)
        val obsolete = dao.getGenerationVersions().filterNot(keep::contains)
        if (obsolete.isEmpty()) return
        database.withTransaction {
            dao.deleteGenerations(obsolete)
        }
        obsolete.forEach { version -> runCatching { store.deleteVersion(version) } }
    }

    private companion object {
        private const val TAG = "SenderProfileRepo"
        private const val SUPPORTED_SCHEMA_VERSION = 2
        private const val MAX_BUNDLE_BYTES = 10L * 1024L * 1024L
        private val SHA256 = Regex("^[A-Fa-f0-9]{64}$")
    }
}

sealed interface SenderProfileUpdateAvailability {
    data class Available(
        val info: SenderProfileUpdateInfo,
        val manifest: SenderProfileManifest,
    ) : SenderProfileUpdateAvailability

    data class NoUpdate(val message: String) : SenderProfileUpdateAvailability
}

sealed interface SenderProfileInstallResult {
    data class Success(val version: String) : SenderProfileInstallResult
    data class Failure(val message: String) : SenderProfileInstallResult
}
