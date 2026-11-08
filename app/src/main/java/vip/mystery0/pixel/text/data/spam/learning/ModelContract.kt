package vip.mystery0.pixel.text.data.spam.learning

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** 仅接受当前客户端实现的公开契约，未知预处理不能猜测兼容。 */
class ModelContract private constructor(private val json: JSONObject, raw: String) {
    val contractVersion = json.getInt("modelContractVersion")
    val preprocessingVersion: String = json.getString("preprocessingVersion")
    val personalParameterVersion: String = json.getString("personalParameterVersion")
    val featureCompatibilityId: String = json.getString("featureCompatibilityId")
    val identity = digest(raw.toByteArray(Charsets.UTF_8))
    val signatures: JSONObject = json.getJSONObject("signatures")
    val parameterCount = signatures.getJSONObject("reset_personal").getJSONObject("outputs")
        .getJSONObject("parameters").getJSONArray("shape").getInt(0)
    val learningRate = json.getJSONObject("personalTraining").getDouble("learningRate").toFloat()
    val regularization = json.getJSONObject("personalTraining").getDouble("regularization").toFloat()
    val epochs = json.getJSONObject("personalTraining").getInt("epochs")
    val threshold = json.getDouble("publicDecisionThreshold").toFloat()

    init {
        require(contractVersion == 2 && preprocessingVersion == "unicode-codepoint-v2")
        require(personalParameterVersion == "linear-head-v1")
        require(featureCompatibilityId.matches(Regex("[a-f0-9]{64}")))
        require(featureCompatibilityId == digest((json.getJSONObject("model").getString("sha256") + ":" + json.getJSONObject("vocab").getString("sha256")).toByteArray(Charsets.UTF_8)))
        require(parameterCount in 1..65536)
        require(learningRate.isFinite() && learningRate > 0f && learningRate <= 1f)
        require(regularization.isFinite() && regularization in 0f..10f && epochs in 1..100)
        require(threshold.isFinite() && threshold == 0.7f)
        val encoding = json.getJSONObject("inputEncoding")
        require(encoding.getInt("paddingId") == 0 && encoding.getInt("unknownTokenId") == 1)
        require(!encoding.getBoolean("truncate"))
        val whites = encoding.getJSONArray("whitespaceCodePoints")
        require((0 until whites.length()).map { whites.getInt(it) } == WHITESPACE.toList())
        require(json.getJSONObject("runtime").getString("operators") == "TFLITE_BUILTINS")
        require(signatures.keys().asSequence().toSet() == setOf("infer_public", "infer_personal", "train_personal", "reset_personal"))
        validateSignature("infer_public", mapOf("tokens" to Spec("int32", listOf(1, -1))), mapOf("score" to Spec("float32", listOf(1, 1))))
        val personal = mapOf("tokens" to Spec("int32", listOf(1, -1)), "parameters" to Spec("float32", listOf(parameterCount)))
        validateSignature("infer_personal", personal, mapOf("score" to Spec("float32", listOf(1, 1))))
        validateSignature("train_personal", personal + mapOf("label" to Spec("float32", listOf(1, 1)), "learning_rate" to Spec("float32", emptyList()), "regularization" to Spec("float32", emptyList())), mapOf("parameters" to Spec("float32", listOf(parameterCount))))
        validateSignature("reset_personal", emptyMap(), mapOf("parameters" to Spec("float32", listOf(parameterCount)), "vocab_size" to Spec("int32", emptyList())))
    }

    private data class Spec(val dtype: String, val shape: List<Int>)
    private fun validateSignature(name: String, inputs: Map<String, Spec>, outputs: Map<String, Spec>) {
        for ((direction, expected) in mapOf("inputs" to inputs, "outputs" to outputs)) {
            val actual = signatures.getJSONObject(name).getJSONObject(direction)
            require(actual.keys().asSequence().toSet() == expected.keys)
            expected.forEach { (key, spec) ->
                val tensor = actual.getJSONObject(key)
                val shape = tensor.getJSONArray("shape")
                require(tensor.getString("dtype") == spec.dtype)
                require((0 until shape.length()).map { shape.getInt(it) } == spec.shape)
            }
        }
    }

    fun validateFiles(model: File, vocab: File) {
        for ((name, file) in listOf("model" to model, "vocab" to vocab)) {
            val expected = json.getJSONObject(name)
            require(file.isFile && file.length() == expected.getLong("sizeBytes")) { "invalid artifact size" }
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
            }
            require(hash.digest().joinToString("") { "%02x".format(it) } == expected.getString("sha256")) { "invalid artifact hash" }
        }
        val tokens = vocab.readLines(Charsets.UTF_8)
        require(tokens.isNotEmpty() && tokens.first() == "[UNK]" && tokens.size == tokens.toSet().size)
        require(tokens.drop(1).all { it.codePointCount(0, it.length) == 1 })
    }

    companion object {
        val WHITESPACE = linkedSetOf(9, 10, 11, 12, 13, 28, 29, 30, 31, 32, 133, 160, 5760, 8192, 8193, 8194, 8195, 8196, 8197, 8198, 8199, 8200, 8201, 8202, 8232, 8233, 8239, 8287, 12288)
        fun parse(json: String): ModelContract = ModelContract(JSONObject(json), json)
        private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun normalize(text: String): String {
            val result = StringBuilder()
            var previousWhitespace = false
            text.codePoints().forEachOrdered { point ->
                if (point in WHITESPACE) {
                    if (!previousWhitespace) result.append(' ')
                    previousWhitespace = true
                } else {
                    result.appendCodePoint(point)
                    previousWhitespace = false
                }
            }
            require(result.any { it != ' ' }) { "empty learning text" }
            return result.toString()
        }
    }
}
