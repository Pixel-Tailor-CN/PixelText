package vip.mystery0.pixel.text.data.spam.learning

import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import vip.mystery0.pixel.text.data.resource.HubModelBundle
import vip.mystery0.pixel.text.domain.spam.learning.SpamModelEngine

/** 一个实例只能由一个会话使用；训练与预测使用不同实例。 */
class TfliteSpamModelEngine(bundle: HubModelBundle) : SpamModelEngine {
    private val contract = ModelContract.parse(bundle.contractFile.readText(Charsets.UTF_8))
    override val identity = contract.identity
    override val modelVersion = bundle.version
    override val parameterCount = contract.parameterCount
    override val epochs = contract.epochs
    private val vocabulary: Map<String, Int>
    private val interpreter: Interpreter
    private val initial: ResetValues

    init {
        contract.validateFiles(bundle.modelFile, bundle.vocabFile)
        initial = ConstantPersonalReset.read(bundle.modelFile, parameterCount)
        vocabulary = bundle.vocabFile.readLines(Charsets.UTF_8).mapIndexed { index, token -> token to index + 1 }.toMap()
        interpreter = Interpreter(bundle.modelFile, Interpreter.Options().setNumThreads(2))
        try {
            validateRuntimeSignatures()
            val initial = reset()
            // 动态长度和四个签名在激活前均以合成内容实际执行。
            inferPublic("验")
            inferPublic("验证全文输入".repeat(8))
            inferPersonal("验证", initial)
            train("验证", 0f, initial)
        } catch (error: Throwable) {
            interpreter.close()
            throw error
        }
    }

    private fun validateRuntimeSignatures() {
        require(interpreter.signatureKeys.toSet() == contract.signatures.keys().asSequence().toSet())
        contract.signatures.keys().forEach { name ->
            val signature = contract.signatures.getJSONObject(name)
            for (direction in listOf("inputs", "outputs")) {
                val expected = signature.getJSONObject(direction)
                val actual = if (direction == "inputs") interpreter.getSignatureInputs(name) else interpreter.getSignatureOutputs(name)
                require(actual.toSet() == expected.keys().asSequence().toSet())
                expected.keys().forEach { key ->
                    val tensor = if (direction == "inputs") interpreter.getInputTensorFromSignature(key, name) else interpreter.getOutputTensorFromSignature(key, name)
                    val descriptor = expected.getJSONObject(key)
                    require(tensor.dataType() == if (descriptor.getString("dtype") == "int32") DataType.INT32 else DataType.FLOAT32)
                    val shape = descriptor.getJSONArray("shape")
                    require(tensor.shapeSignature().toList() == (0 until shape.length()).map { shape.getInt(it) })
                }
            }
        }
    }

    override fun normalize(text: String): String = ModelContract.normalize(text)
    private fun tokens(text: String): Array<IntArray> = arrayOf(normalize(text).codePoints().toArray().map { code ->
        vocabulary[String(Character.toChars(code))] ?: 1
    }.toIntArray())

    private fun checked(parameters: FloatArray): FloatArray {
        require(parameters.size == parameterCount && parameters.all(Float::isFinite)) { "invalid personal parameters" }
        return parameters
    }

    override fun reset(): FloatArray {
        // Java 运行时不接受零输入签名；只读取已证明为零算子图的不可变常量。
        require(initial.vocabSize > vocabulary.size) { "vocabulary size mismatch" }
        return checked(initial.parameters.copyOf())
    }

    override fun train(text: String, label: Float, parameters: FloatArray): FloatArray {
        require(label == 0f || label == 1f)
        val output = FloatArray(parameterCount)
        interpreter.runSignature(mapOf(
            "tokens" to tokens(text), "parameters" to checked(parameters), "label" to arrayOf(floatArrayOf(label)),
            "learning_rate" to contract.learningRate, "regularization" to contract.regularization,
        ), mapOf("parameters" to output), "train_personal")
        return checked(output)
    }

    override fun inferPublic(text: String): Float = infer("infer_public", mapOf("tokens" to tokens(text)))
    override fun inferPersonal(text: String, parameters: FloatArray): Float = infer("infer_personal", mapOf("tokens" to tokens(text), "parameters" to checked(parameters)))
    private fun infer(signature: String, input: Map<String, Any>): Float {
        val output = arrayOf(FloatArray(1))
        interpreter.runSignature(input, mapOf("score" to output), signature)
        return output[0][0].also { require(it.isFinite() && it in 0f..1f) { "invalid inference score" } }
    }

    override fun close() = interpreter.close()
}
