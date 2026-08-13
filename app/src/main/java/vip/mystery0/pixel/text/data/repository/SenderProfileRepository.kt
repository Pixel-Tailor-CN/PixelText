package vip.mystery0.pixel.text.data.repository

import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import vip.mystery0.pixel.text.BuildConfig
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
            ?: return@withContext SenderProfileUpdateAvailability.NoUpdate("服务端暂未发布发件方资料")
        validateManifest(manifest)
        val current = dao.getState()?.activeVersion
        if (current == manifest.version) {
            SenderProfileUpdateAvailability.NoUpdate("发件方资料已经是最新版本")
        } else if (manifest.minAppVersionCode > BuildConfig.VERSION_CODE) {
            SenderProfileUpdateAvailability.NoUpdate("该资料版本需要更新 PixelText 后才能安装")
        } else {
            SenderProfileUpdateAvailability.Available(
                info = SenderProfileUpdateInfo(
                    version = manifest.version,
                    sizeBytes = manifest.sizeBytes,
                    releaseNotes = manifest.releaseNotes.ifBlank { "暂无版本说明" },
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
            onProgress("正在获取最新下载地址", 0.03f)
            var manifest = client.fetchSenderProfileManifest()
                ?: error("服务端暂未发布发件方资料")
            validateManifest(manifest)
            ensureSameArtifact(expectedManifest, manifest)
            targetVersion = manifest.version
            val tempFile = store.tempBundleFile(manifest.version)
            try {
                onProgress("正在下载发件方资料", 0.08f)
                try {
                    download(manifest, tempFile, onProgress)
                } catch (error: SenderProfileDownloadException) {
                    if (error.statusCode != 401 && error.statusCode != 403) throw error
                    manifest = client.fetchSenderProfileManifest()
                        ?: error("服务端暂未发布发件方资料")
                    validateManifest(manifest)
                    ensureSameArtifact(expectedManifest, manifest)
                    download(manifest, tempFile, onProgress)
                }
                onProgress("正在校验发件方资料", 0.58f)
                store.verifyBundle(tempFile, manifest.sizeBytes, manifest.sha256)
                val installed = FileInputStream(tempFile).use { input ->
                    store.installBundle(input, manifest.version)
                }
                onProgress("正在导入发件方资料", 0.72f)
                importBundle(installed.bundle)
                onProgress("正在清理旧版发件方资料", 0.9f)
                runCatching { cleanupOldGenerations() }
                    .onFailure { error ->
                        Log.w(
                            TAG,
                            "sender profile cleanup failed error=${error::class.java.simpleName}",
                            error,
                        )
                    }
                onProgress("发件方资料安装完成", 1f)
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
            SenderProfileInstallResult.Failure(error.message ?: "发件方资料安装失败")
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
            onProgress("正在下载发件方资料", 0.08f + fraction.coerceIn(0f, 1f) * 0.46f)
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
            error("不支持的发件方资料格式：${manifest.schemaVersion}")
        }
        if (manifest.version.isBlank()) error("发件方资料版本为空")
        if (manifest.sizeBytes <= 0L || manifest.sizeBytes > MAX_BUNDLE_BYTES) {
            error("发件方资料大小不合法")
        }
        if (!SHA256.matches(manifest.sha256)) error("发件方资料哈希不合法")
        if (!manifest.downloadUrl.startsWith("https://")) error("发件方资料下载地址不安全")
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
            error("发件方资料版本已变化，请重新检查更新")
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
