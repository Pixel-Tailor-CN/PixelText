package vip.mystery0.pixel.text.domain.spam

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.flex.FlexDelegate
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class SpamClassifier(context: Context) : AutoCloseable {
    companion object {
        private const val TAG = "SpamClassifier"
        private const val MODEL_FILE = "spam_classifier.tflite"
    }

    private val flexDelegate = FlexDelegate()
    private val interpreter: Interpreter

    init {
        val model = loadModel(context)
        val options = Interpreter.Options().addDelegate(flexDelegate)
        interpreter = Interpreter(model, options)
    }

    private fun loadModel(context: Context): MappedByteBuffer {
        val fd = context.assets.openFd(MODEL_FILE)
        return FileInputStream(fd.fileDescriptor).channel.map(
            FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength
        )
    }

    fun classify(text: String): Float {
        return try {
            val input = Array(1) { Array(1) { text } }
            val output = Array(1) { FloatArray(1) }
            interpreter.run(input, output)
            output[0][0]
        } catch (e: Exception) {
            Log.e(TAG, "推理失败: ${e.message}")
            -1f
        }
    }

    override fun close() {
        interpreter.close()
        flexDelegate.close()
    }
}
