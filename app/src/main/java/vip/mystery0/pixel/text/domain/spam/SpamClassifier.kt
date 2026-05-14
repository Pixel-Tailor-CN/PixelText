package vip.mystery0.pixel.text.domain.spam

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class SpamClassifier(context: Context) : AutoCloseable {
    companion object {
        private const val TAG = "SpamClassifier"
        private const val MODEL_FILE = "spam_classifier.tflite"
        private const val VOCAB_FILE = "vocab.txt"
        private const val SEQ_LEN = 40
        private const val UNKNOWN_TOKEN_ID = 1
    }

    private val interpreter: Interpreter
    private val vocabulary: Map<String, Int>

    init {
        val model = loadModel(context)
        val options = Interpreter.Options()
        interpreter = Interpreter(model, options)
        vocabulary = loadVocabulary(context)
    }

    private fun loadModel(context: Context): MappedByteBuffer {
        val fd = context.assets.openFd(MODEL_FILE)
        return FileInputStream(fd.fileDescriptor).channel.map(
            FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength
        )
    }

    private fun loadVocabulary(context: Context): Map<String, Int> {
        return context.assets.open(VOCAB_FILE).bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.mapIndexed { index, token -> token to index + 1 }.toMap()
        }
    }

    fun classify(text: String): Float {
        return try {
            val input = Array(1) { encode(text) }
            val output = Array(1) { FloatArray(1) }
            interpreter.run(input, output)
            output[0][0]
        } catch (e: Exception) {
            Log.e(TAG, "推理失败: ${e.message}")
            -1f
        }
    }

    private fun encode(text: String): IntArray {
        val encoded = IntArray(SEQ_LEN)
        var index = 0
        val iterator = text.codePoints().iterator()
        while (iterator.hasNext() && index < SEQ_LEN) {
            val token = String(Character.toChars(iterator.nextInt()))
            encoded[index] = vocabulary[token] ?: UNKNOWN_TOKEN_ID
            index++
        }
        return encoded
    }

    override fun close() {
        interpreter.close()
    }
}
