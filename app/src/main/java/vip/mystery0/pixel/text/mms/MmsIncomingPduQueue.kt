package vip.mystery0.pixel.text.mms

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** 广播只持久化有界原事件，不等待解析、Provider 或正文保存的锁。 */
internal class MmsIncomingPduQueue(context: Context) {
    private val root = File(context.filesDir, "mms-incoming").apply { mkdirs() }
    data class Event(val data: ByteArray, val sub: Int, val received: Long, val auto: Boolean)

    fun enqueue(event: Event) {
        require(event.data.size in 1..MAX_PUSH_BYTES)
        val id = UUID.randomUUID().toString()
        val temporary = File(root, "$id.tmp")
        try {
            temporary.outputStream().use { file ->
                val output = DataOutputStream(file)
                output.writeInt(1)
                output.writeInt(event.sub)
                output.writeLong(event.received)
                output.writeBoolean(event.auto)
                output.writeInt(event.data.size)
                output.write(event.data)
                output.flush()
                file.fd.sync()
            }
            Files.move(temporary.toPath(), File(root, "$id.push").toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally { temporary.delete() }
    }

    fun pending(): List<File> = root.listFiles().orEmpty().filter { it.extension == "push" }.sortedBy { it.lastModified() }

    fun read(file: File): Event = DataInputStream(file.inputStream()).use { input ->
        require(file.length() in 22L..(MAX_PUSH_BYTES + 21L))
        require(input.readInt() == 1)
        val sub = input.readInt()
        val received = input.readLong()
        val auto = input.readBoolean()
        val length = input.readInt()
        require(received > 0 && length in 1..MAX_PUSH_BYTES && file.length() == length + 21L)
        Event(ByteArray(length).also(input::readFully), sub, received, auto)
    }

    fun remove(file: File) { check(file.delete() || !file.exists()) { "mms incoming cleanup unavailable" } }
    fun quarantine(file: File) {
        Files.move(file.toPath(), File(root, "${file.nameWithoutExtension}.invalid").toPath(), StandardCopyOption.ATOMIC_MOVE)
    }
    fun cleanup() {
        val now = System.currentTimeMillis()
        root.listFiles().orEmpty().filter {
            it.extension in setOf("tmp", "invalid") && now - it.lastModified() > 30L * 86400000
        }.forEach(::remove)
    }
    companion object { const val MAX_PUSH_BYTES = 1024 * 1024 }
}
