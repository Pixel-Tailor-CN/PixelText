package vip.mystery0.pixel.text.data.resource

import android.content.Context
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import android.util.AtomicFile
import org.json.JSONObject
import vip.mystery0.pixel.text.BuildConfig
import vip.mystery0.pixel.text.domain.hub.HubSpamModelArtifact
import java.util.UUID
import vip.mystery0.pixel.text.data.spam.learning.ModelContract

class HubResourceStore(
    private val context: Context,
) {
    private val root: File = File(context.filesDir, ROOT_DIR)

    fun activeRulesFile(): File = File(root, "rules/rules.json")
    private val bundles = File(context.noBackupFilesDir, "spam_bundles_v2")
    private val pointer get() = AtomicFile(File(bundles, "active.json"))
    private var bundledSnapshot: HubModelBundle? = null
    private val verifiedBundles = mutableMapOf<String, List<FileStamp>>()

    private data class FileStamp(val size: Long, val modifiedAt: Long)

    fun tempFile(name: String): File = File(root, "tmp/$name")

    fun activateRules(tempFile: File) {
        val target = activeRulesFile()
        target.parentFile?.mkdirs()
        val atomic = AtomicFile(target)
        val output = atomic.startWrite()
        try {
            tempFile.inputStream().use { it.copyTo(output) }
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
        tempFile.delete()
    }

    /** 一次读取指针，调用方在整个推理／训练期间持有此不可变快照。 */
    @Synchronized
    fun activeModelBundle(): HubModelBundle? = runCatching {
        val value = JSONObject(pointer.openRead().bufferedReader().use { it.readText() })
        val name = value.getString("directory")
        require(name.matches(Regex("[A-Za-z0-9_-]+")))
        HubModelBundle(File(bundles, name), value.getString("version")).also(::validateBundle)
    }.getOrNull()

    fun currentModelBundle(): HubModelBundle = activeModelBundle() ?: builtInModelBundle()

    @Synchronized
    fun builtInModelBundle(): HubModelBundle {
        bundledSnapshot?.let { cached ->
            if (runCatching { validateBundle(cached) }.isSuccess) return cached
            bundledSnapshot = null
        }
        val contractBytes = context.assets.open("model_contract.json").use { it.readBytes() }
        val identity = MessageDigest.getInstance("SHA-256").digest(contractBytes)
            .joinToString("") { "%02x".format(it) }
        val bundle = HubModelBundle(File(bundles, "builtin_$identity"), "builtin-v2")
        if (runCatching { validateBundle(bundle) }.isSuccess) {
            bundledSnapshot = bundle
            return bundle
        }
        val staged = HubModelBundle(File(bundles, "staging_${UUID.randomUUID()}"), bundle.version)
        staged.directory.mkdirs()
        try {
            listOf("spam_classifier.tflite", "vocab.txt", "model_contract.json").forEach { name ->
                context.assets.open(name).use { input ->
                    File(staged.directory, name).outputStream().use { output ->
                        input.copyTo(output)
                        output.fd.sync()
                    }
                }
            }
            validateBundle(staged)
            // 损坏的同名目录不会被运行中的解释器引用，保留它用于诊断。
            if (bundle.directory.exists()) {
                check(bundle.directory.renameTo(File(bundles, "invalid_${UUID.randomUUID()}")))
            }
            check(staged.directory.renameTo(bundle.directory))
            validateBundle(bundle)
            bundledSnapshot = bundle
            return bundle
        } finally {
            staged.directory.deleteRecursively()
        }
    }

    fun stageModelBundle(model: File, vocab: File, contract: File, artifact: HubSpamModelArtifact): HubModelBundle {
        requireCompatible(artifact)
        listOf(model to artifact.model, vocab to artifact.vocab, contract to artifact.contract).forEach { (file, info) ->
            require(info.sizeBytes > 0)
            verifySize(file, info.sizeBytes)
            verifySha256(file, info.sha256)
        }
        val candidate = HubModelBundle(File(bundles, "bundle_${UUID.randomUUID()}"), artifact.version)
        candidate.directory.mkdirs()
        try {
            listOf(model to candidate.modelFile, vocab to candidate.vocabFile, contract to candidate.contractFile)
                .forEach { (source, target) ->
                    source.inputStream().use { input ->
                        target.outputStream().use { output -> input.copyTo(output); output.fd.sync() }
                    }
                }
            validateBundle(candidate)
            val json = JSONObject(candidate.contractFile.readText())
            require(json.getInt("modelContractVersion") == artifact.modelContractVersion)
            require(json.getString("preprocessingVersion") == artifact.preprocessingVersion)
            require(json.getString("personalParameterVersion") == artifact.personalParameterVersion)
            require(json.getString("featureCompatibilityId") == artifact.featureCompatibilityId)
            return candidate
        } catch (error: Throwable) {
            candidate.directory.deleteRecursively()
            throw error
        }
    }

    /** 必须由学习协调器在完成候选训练、复核样本及设置代次后调用。 */
    @Synchronized
    fun activateModelBundle(candidate: HubModelBundle) {
        require(candidate.directory.canonicalFile.parentFile == bundles.canonicalFile)
        validateBundle(candidate)
        bundles.mkdirs()
        val atomic = pointer
        val previous = try {
            atomic.openRead().use { it.readBytes() }
        } catch (error: FileNotFoundException) {
            // 仅首次激活允许没有指针；权限或旧文件损坏不能被当作新安装。
            if (atomic.baseFile.exists() || File(atomic.baseFile.path + ".bak").exists()) throw error
            null
        }
        val expected = JSONObject().put("directory", candidate.directory.name)
            .put("version", candidate.version).toString().toByteArray(Charsets.UTF_8)
        try {
            writeVerifiedPointer(atomic, expected)
            val actual = JSONObject(atomic.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
            check(actual.getString("directory") == candidate.directory.name &&
                actual.getString("version") == candidate.version) { "bundle pointer verification failed" }
        } catch (error: Throwable) {
            try {
                if (previous != null) {
                    writeVerifiedPointer(atomic, previous)
                } else {
                    atomic.delete()
                    check(!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) {
                        "bundle pointer rollback failed"
                    }
                }
            } catch (rollbackError: Throwable) {
                error.addSuppressed(rollbackError)
            }
            throw error
        }
        // 旧目录保留，正在运行的推理与训练快照仍可安全读取。
    }

    private fun writeVerifiedPointer(atomic: AtomicFile, bytes: ByteArray) {
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            // AtomicFile 会仅记录部分同步／重命名错误，因此显式同步并回读提交结果。
            output.fd.sync()
            atomic.finishWrite(output)
            check(atomic.openRead().use { it.readBytes() }.contentEquals(bytes)) {
                "bundle pointer write failed"
            }
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
    }

    fun requireCompatible(artifact: HubSpamModelArtifact) {
        require(artifact.minAppVersionCode in 0..BuildConfig.VERSION_CODE) { "当前版本不支持此模型" }
        require(artifact.modelContractVersion == 2 &&
            artifact.preprocessingVersion == "unicode-codepoint-v2" &&
            artifact.personalParameterVersion == "linear-head-v1" &&
            artifact.featureCompatibilityId.matches(Regex("[a-fA-F0-9]{64}"))) { "模型契约不兼容" }
    }

    @Synchronized
    fun validateBundle(bundle: HubModelBundle) {
        val key = bundle.directory.absolutePath
        val files = listOf(bundle.modelFile, bundle.vocabFile, bundle.contractFile)
        fun stamps(): List<FileStamp> = files.map { file ->
            require(file.isFile) { "bundle file missing" }
            FileStamp(file.length(), file.lastModified())
        }
        val before = try {
            stamps()
        } catch (error: Throwable) {
            verifiedBundles.remove(key)
            throw error
        }
        // 私有目录中的组合发布后不再修改；重新建解释器仍会执行完整哈希校验。
        if (verifiedBundles[key] == before) return
        verifiedBundles.remove(key)
        ModelContract.parse(bundle.contractFile.readText(Charsets.UTF_8))
            .validateFiles(bundle.modelFile, bundle.vocabFile)
        check(stamps() == before) { "bundle changed during validation" }
        verifiedBundles[key] = before
    }

    fun deleteActiveRules() {
        activeRulesFile().deleteIfExists()
    }

    fun verifySha256(file: File, expected: String) {
        val actual = sha256(file)
        if (!actual.equals(expected, ignoreCase = true)) {
            throw IllegalStateException("sha256 mismatch expected=$expected actual=$actual")
        }
    }

    fun verifySize(file: File, expectedSizeBytes: Long) {
        if (expectedSizeBytes <= 0L) return
        val actualSizeBytes = file.length()
        if (actualSizeBytes != expectedSizeBytes) {
            throw IllegalStateException(
                "size mismatch expected=$expectedSizeBytes actual=$actualSizeBytes"
            )
        }
    }

    fun sha256(file: File): String {
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

    private fun File.deleteIfExists() {
        if (exists() && !delete()) {
            throw IllegalStateException("delete file failed path=$absolutePath")
        }
    }

    private companion object {
        private const val ROOT_DIR = "hub_resources"
    }
}

/** 三个文件来自同一不可变目录，禁止分别读取活动指针组装快照。 */
data class HubModelBundle(val directory: File, val version: String) {
    val modelFile: File get() = File(directory, "spam_classifier.tflite")
    val vocabFile: File get() = File(directory, "vocab.txt")
    val contractFile: File get() = File(directory, "model_contract.json")
}
