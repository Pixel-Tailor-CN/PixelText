package vip.mystery0.pixel.text.data.resource

import android.content.Context
import java.io.File
import java.security.MessageDigest

class HubResourceStore(
    context: Context,
) {
    private val root: File = File(context.filesDir, ROOT_DIR)

    fun activeRulesFile(): File = File(root, "rules/rules.json")
    fun activeModelFile(): File = File(root, "model/spam_classifier.tflite")
    fun activeVocabFile(): File = File(root, "model/vocab.txt")
    fun tempFile(name: String): File = File(root, "tmp/$name")

    fun hasActiveRules(): Boolean = activeRulesFile().isFile

    fun hasActiveModelAndVocab(): Boolean =
        activeModelFile().isFile && activeVocabFile().isFile

    fun activateRules(tempFile: File) {
        moveIntoPlace(tempFile, activeRulesFile())
    }

    fun activateModelAndVocab(modelTemp: File, vocabTemp: File) {
        moveIntoPlace(modelTemp, activeModelFile())
        moveIntoPlace(vocabTemp, activeVocabFile())
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

    private fun moveIntoPlace(source: File, target: File) {
        target.parentFile?.mkdirs()
        if (target.exists()) target.delete()
        if (!source.renameTo(target)) {
            source.copyTo(target, overwrite = true)
            source.delete()
        }
    }

    private companion object {
        private const val ROOT_DIR = "hub_resources"
    }
}
