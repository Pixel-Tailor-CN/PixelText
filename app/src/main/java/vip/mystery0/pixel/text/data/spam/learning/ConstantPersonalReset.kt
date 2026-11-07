package vip.mystery0.pixel.text.data.spam.learning

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class ResetValues(val parameters: FloatArray, val vocabSize: Int)

/**
 * Java 运行库拒绝零输入签名；仅对已经证明为零算子、非变量常量图的 reset 读取原始数据。
 * 字段编号来自 TFLite schema.fbs；不执行、不推断网络层，也不接受外部缓冲区。
 */
object ConstantPersonalReset {
    fun read(model: File, parameterCount: Int): ResetValues {
        require(model.length() in 8..64L * 1024 * 1024) { "invalid model size" }
        return read(model.readBytes(), parameterCount)
    }

    fun read(bytes: ByteArray, parameterCount: Int): ResetValues {
        require(parameterCount in 1..65536)
        val reader = Reader(bytes)
        require(bytes.size >= 8 && bytes.copyOfRange(4, 8).contentEquals("TFL3".toByteArray(Charsets.US_ASCII)))
        val model = reader.table(reader.indirect(0))
        require(model.int(0) == 3) { "unsupported flatbuffer version" }
        val signatures = model.vector(7, 4)
        val resets = (0 until signatures.size).map { reader.table(reader.indirect(signatures.at(it))) }
            .filter { it.string(2) == "reset_personal" }
        require(resets.size == 1) { "reset signature missing or duplicated" }
        val reset = resets.single()
        require(reset.vector(0, 4).size == 0)
        val graphs = model.vector(2, 4)
        val graph = reader.table(reader.indirect(graphs.at(reset.int(4))))
        require(graph.vector(1, 4).size == 0 && graph.vector(3, 4).size == 0) { "reset is not a constant graph" }
        val outputs = reset.vector(1, 4)
        require(outputs.size == 2)
        val mapping = (0 until outputs.size).map {
            val entry = reader.table(reader.indirect(outputs.at(it)))
            entry.string(0) to entry.int(1)
        }
        require(mapping.map { it.first }.toSet() == setOf("parameters", "vocab_size"))
        val graphOutputs = graph.vector(2, 4)
        require(graphOutputs.size == 2 && (0 until graphOutputs.size).map { reader.int(graphOutputs.at(it)) }.toSet() == mapping.map { it.second }.toSet())
        require(mapping.map { it.second }.toSet().size == 2)
        val tensors = graph.vector(0, 4)
        val buffers = model.vector(4, 4)
        fun constant(name: String, type: Int, shape: List<Int>, expectedBytes: Int): ByteBuffer {
            val tensorIndex = mapping.single { it.first == name }.second
            val tensor = reader.table(reader.indirect(tensors.at(tensorIndex)))
            require(tensor.byte(1) == type && tensor.byte(5) == 0) { "invalid reset tensor" }
            val dimensions = tensor.vector(0, 4)
            require((0 until dimensions.size).map { reader.int(dimensions.at(it)) } == shape)
            val signature = tensor.vector(7, 4)
            require(signature.size == 0 || (0 until signature.size).map { reader.int(signature.at(it)) } == shape)
            // 量化和稀疏编码不属于此常量契约。
            require(tensor.field(6) == null)
            tensor.field(4)?.let { location ->
                val quantization = reader.table(reader.indirect(location))
                require((0..3).all { quantization.vector(it, if (it == 3) 8 else 4).size == 0 })
                require(quantization.byte(4) == 0 && quantization.field(5) == null && quantization.int(6) == 0)
            }
            val buffer = reader.table(reader.indirect(buffers.at(tensor.int(2))))
            require(buffer.long(1) == 0L && buffer.long(2) == 0L) { "external reset buffer unsupported" }
            val data = buffer.vector(0, 1)
            require(data.size == expectedBytes) { "invalid reset buffer size" }
            return ByteBuffer.wrap(bytes, data.start, data.size).slice().order(ByteOrder.LITTLE_ENDIAN)
        }
        val values = constant("parameters", 0, listOf(parameterCount), parameterCount * 4)
        val parameters = FloatArray(parameterCount) { values.float }
        require(parameters.all(Float::isFinite))
        val vocabSize = constant("vocab_size", 2, emptyList(), 4).int
        require(vocabSize > 1)
        return ResetValues(parameters, vocabSize)
    }

    private class Reader(private val bytes: ByteArray) {
        private val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun range(position: Long, count: Long): Int {
            require(position >= 0 && count >= 0 && position <= bytes.size.toLong() - count) { "invalid flatbuffer bounds" }
            return position.toInt()
        }
        fun int(position: Int): Int = data.getInt(range(position.toLong(), 4))
        fun short(position: Int): Int = data.getShort(range(position.toLong(), 2)).toInt() and 0xffff
        fun indirect(position: Int): Int {
            val offset = int(position).toLong() and 0xffffffffL
            require(offset >= 4)
            return range(position.toLong() + offset, 4)
        }
        fun table(position: Int): Table = Table(position)

        inner class Table(private val position: Int) {
            private val vtable = range(position.toLong() - this@Reader.int(position), 4)
            private val vtableSize = short(vtable)
            private val objectSize = short(vtable + 2)
            init {
                require(vtableSize >= 4 && vtableSize % 2 == 0 && objectSize >= 4)
                range(vtable.toLong(), vtableSize.toLong())
                range(position.toLong(), objectSize.toLong())
            }
            fun field(index: Int, width: Int = 4): Int? {
                val slot = 4 + index * 2
                if (slot + 2 > vtableSize) return null
                val offset = short(vtable + slot)
                if (offset == 0) return null
                require(offset >= 4 && offset <= objectSize - width)
                return range(position.toLong() + offset, width.toLong())
            }
            fun int(index: Int): Int = field(index)?.let { this@Reader.int(it) } ?: 0
            fun byte(index: Int): Int = field(index, 1)?.let { data.get(it).toInt() and 0xff } ?: 0
            fun long(index: Int): Long = field(index, 8)?.let { data.getLong(it) } ?: 0L
            fun vector(index: Int, width: Int): Vector {
                val field = field(index) ?: return Vector(0, 0, width)
                val vector = indirect(field)
                val count = this@Reader.int(vector)
                val start = range(vector.toLong() + 4, count.toLong() * width)
                return Vector(start, count, width)
            }
            fun string(index: Int): String {
                val value = vector(index, 1)
                require(value.size in 1..1024)
                require(data.get(range(value.start.toLong() + value.size, 1)) == 0.toByte())
                return String(bytes, value.start, value.size, Charsets.UTF_8)
            }
        }
        inner class Vector(val start: Int, val size: Int, private val width: Int) {
            fun at(index: Int): Int {
                require(index in 0 until size) { "invalid flatbuffer index" }
                return range(start.toLong() + index.toLong() * width, width.toLong())
            }
        }
    }
}
