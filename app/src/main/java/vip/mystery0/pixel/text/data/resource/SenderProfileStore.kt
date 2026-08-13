package vip.mystery0.pixel.text.data.resource

import android.content.Context
import android.graphics.BitmapFactory
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream
import vip.mystery0.pixel.text.domain.model.SenderProfileBundle
import vip.mystery0.pixel.text.domain.model.SenderProfileBundleAvatar
import vip.mystery0.pixel.text.domain.model.SenderProfileBundleItem

class SenderProfileStore(context: Context) {
    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, ROOT_DIR)
    private val moshi = Moshi.Builder().build()
    private val documentAdapter = moshi.adapter(BundleDocumentResponse::class.java)

    fun tempBundleFile(version: String): File =
        File(root, "tmp/${safeVersion(version)}-${System.nanoTime()}.zip.part")

    fun verifyBundle(file: File, expectedSizeBytes: Long, expectedSha256: String) {
        if (!file.isFile || file.length() != expectedSizeBytes) {
            error("sender profile bundle size mismatch")
        }
        if (!sha256(file).equals(expectedSha256, ignoreCase = true)) {
            error("sender profile bundle hash mismatch")
        }
    }

    fun installBundle(input: InputStream, expectedVersion: String): InstalledSenderProfileBundle {
        val tempRoot = File(root, "tmp/${safeVersion(expectedVersion)}-${System.nanoTime()}")
        val extractedRoot = File(tempRoot, "extracted")
        extractedRoot.mkdirs()
        try {
            extractSafely(input, extractedRoot)
            val documentFile = File(extractedRoot, DOCUMENT_FILE)
            if (!documentFile.isFile) error("sender profile document missing")
            val document = documentAdapter.fromJson(documentFile.readText(Charsets.UTF_8))
                ?: error("sender profile document empty")
            if (document.schemaVersion != SUPPORTED_SCHEMA_VERSION) {
                error("sender profile schema unsupported version=${document.schemaVersion}")
            }
            if (document.version != expectedVersion) {
                error("sender profile version mismatch expected=$expectedVersion actual=${document.version}")
            }
            val seenNumbers = mutableSetOf<String>()
            val profiles = document.profiles.map { profile ->
                val displayName = profile.displayName.trim()
                if (displayName.isBlank() || displayName.length > MAX_DISPLAY_NAME_LENGTH) {
                    error("sender profile name invalid")
                }
                if (profile.numbers.isEmpty()) error("sender profile number missing")
                profile.numbers.forEach { number ->
                    if (number.isEmpty() || number.any(Char::isISOControl) || !seenNumbers.add(number)) {
                        error("sender profile number invalid number=$number")
                    }
                }
                val avatarRelativePath = validateAvatarPath(profile.avatar.path)
                val avatarFile = File(extractedRoot, avatarRelativePath)
                if (!avatarFile.isFile) error("sender avatar missing path=$avatarRelativePath")
                if (avatarFile.length() > MAX_AVATAR_BYTES) error("sender avatar too large")
                if (!sha256(avatarFile).equals(profile.avatar.sha256, ignoreCase = true)) {
                    error("sender avatar hash mismatch path=$avatarRelativePath")
                }
                if (BitmapFactory.decodeFile(avatarFile.absolutePath) == null) {
                    error("sender avatar decode failed path=$avatarRelativePath")
                }
                SenderProfileBundleItem(
                    displayName = displayName,
                    avatar = SenderProfileBundleAvatar(
                        path = avatarRelativePath,
                        sha256 = profile.avatar.sha256.lowercase(Locale.ROOT),
                    ),
                    numbers = profile.numbers,
                )
            }
            val versionDir = versionDirectory(expectedVersion)
            val stagedVersionDir = File(tempRoot, "version")
            val avatarsDir = File(stagedVersionDir, "avatars")
            avatarsDir.mkdirs()
            profiles.map { it.avatar.path }.distinct().forEach { path ->
                val source = File(extractedRoot, path)
                val target = File(stagedVersionDir, path)
                target.parentFile?.mkdirs()
                source.copyTo(target, overwrite = true)
            }
            if (versionDir.exists() && !versionDir.deleteRecursively()) {
                error("sender profile old version delete failed")
            }
            versionDir.parentFile?.mkdirs()
            if (!stagedVersionDir.renameTo(versionDir)) {
                stagedVersionDir.copyRecursively(versionDir, overwrite = true)
                stagedVersionDir.deleteRecursively()
            }
            return InstalledSenderProfileBundle(
                bundle = SenderProfileBundle(
                    schemaVersion = document.schemaVersion,
                    version = document.version,
                    generatedAt = document.generatedAt,
                    profiles = profiles.map { item ->
                        item.copy(
                            avatar = item.avatar.copy(
                                path = File(versionDir, item.avatar.path).absolutePath,
                            )
                        )
                    },
                ),
                versionDirectory = versionDir,
            )
        } catch (error: Throwable) {
            tempRoot.deleteRecursively()
            throw error
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    fun versionDirectory(version: String): File = File(root, "versions/${safeVersion(version)}")

    fun deleteVersion(version: String) {
        val directory = versionDirectory(version)
        if (directory.exists() && !directory.deleteRecursively()) {
            error("sender profile version delete failed version=$version")
        }
    }

    private fun extractSafely(input: InputStream, targetRoot: File) {
        val rootPath = targetRoot.canonicalFile.toPath()
        val seenPaths = mutableSetOf<String>()
        var entries = 0
        var extractedBytes = 0L
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries++
                if (entries > MAX_ENTRIES) error("sender profile zip entries exceeded")
                val name = entry.name
                if (entry.isDirectory || name.contains('\\') || name.startsWith('/') || name.contains("..")) {
                    error("sender profile zip path invalid path=$name")
                }
                val folded = name.lowercase(Locale.ROOT)
                if (!seenPaths.add(folded)) error("sender profile zip duplicate path=$name")
                if (name != DOCUMENT_FILE && !AVATAR_PATH.matches(name)) {
                    error("sender profile zip entry invalid path=$name")
                }
                val output = File(targetRoot, name).canonicalFile
                if (!output.toPath().startsWith(rootPath)) error("sender profile zip path escaped")
                output.parentFile?.mkdirs()
                output.outputStream().use { stream ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        extractedBytes += read
                        if (extractedBytes > MAX_EXTRACTED_BYTES) {
                            error("sender profile zip extracted size exceeded")
                        }
                        stream.write(buffer, 0, read)
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private fun validateAvatarPath(path: String): String {
        if (!AVATAR_PATH.matches(path)) error("sender avatar path invalid path=$path")
        return path
    }

    private fun safeVersion(version: String): String =
        version.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        private const val ROOT_DIR = "sender_profiles"
        private const val DOCUMENT_FILE = "sender-profiles.json"
        private const val SUPPORTED_SCHEMA_VERSION = 2
        private const val MAX_ENTRIES = 6000
        private const val MAX_EXTRACTED_BYTES = 30L * 1024L * 1024L
        private const val MAX_AVATAR_BYTES = 256L * 1024L
        private const val MAX_DISPLAY_NAME_LENGTH = 100
        private val AVATAR_PATH = Regex("^avatars/[1-9][0-9]*\\.webp$")
    }
}

data class InstalledSenderProfileBundle(
    val bundle: SenderProfileBundle,
    val versionDirectory: File,
)

@JsonClass(generateAdapter = true)
internal data class BundleDocumentResponse(
    val schemaVersion: Int,
    val version: String,
    val generatedAt: String,
    val profiles: List<BundleProfileResponse>,
)

@JsonClass(generateAdapter = true)
internal data class BundleProfileResponse(
    val displayName: String,
    val avatar: BundleAvatarResponse,
    val numbers: List<String>,
)

@JsonClass(generateAdapter = true)
internal data class BundleAvatarResponse(
    val path: String,
    val sha256: String,
)
