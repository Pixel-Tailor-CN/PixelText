package vip.mystery0.pixel.text.data.source

import android.content.ContentResolver
import android.provider.Telephony
import androidx.core.net.toUri
import java.security.MessageDigest

/** 只读取身份元数据，不把短信正文用于白名单匹配或持久化。 */
data class WhitelistMessageIdentity(val id: Long, val sender: String, val fingerprint: String)

class WhitelistMessageSource(private val resolver: ContentResolver) {
    fun load(ids: Collection<Long>? = null): List<WhitelistMessageIdentity> {
        if (ids != null && ids.isEmpty()) return emptyList()
        return buildList {
            loadTransport(false, ids?.filter { it > 0 }, this)
            loadTransport(true, ids?.filter { it < 0 }?.map { -it }, this)
        }
    }

    private fun loadTransport(
        mms: Boolean,
        sourceIds: List<Long>?,
        result: MutableList<WhitelistMessageIdentity>,
    ) {
        if (sourceIds != null && sourceIds.isEmpty()) return
        val chunks = sourceIds?.chunked(800) ?: listOf(null)
        for (chunk in chunks) {
            val projection = if (mms) arrayOf("_id", "date", "date_sent")
                else arrayOf("_id", "address", "date", "date_sent")
            val selection = chunk?.let { "_id IN (${it.joinToString(",") { "?" }})" }
            val uri = if (mms) Telephony.Mms.CONTENT_URI else Telephony.Sms.CONTENT_URI
            val cursor = resolver.query(uri, projection, selection, chunk?.map(Long::toString)?.toTypedArray(), null)
                ?: error("无法读取消息，请检查短信权限")
            cursor.use {
                while (it.moveToNext()) {
                    val sourceId = it.getLong(it.getColumnIndexOrThrow("_id"))
                    val id = if (mms) -sourceId else sourceId
                    val sender = if (mms) mmsSender(sourceId)
                        else it.getString(it.getColumnIndexOrThrow("address")).orEmpty()
                    val date = it.getLong(it.getColumnIndexOrThrow("date"))
                    val sent = it.getLong(it.getColumnIndexOrThrow("date_sent"))
                    // 同一 Provider ID 被删除后复用时，不能继承上一条消息的放行状态。
                    val fingerprint = whitelistMessageFingerprint(id, sender, date, sent)
                    result += WhitelistMessageIdentity(id, sender, fingerprint)
                }
            }
        }
    }

    private fun mmsSender(id: Long): String {
        val cursor = resolver.query(
            "content://mms/$id/addr".toUri(), arrayOf("address", "type"),
            "type IN (137,151,130,129)", null, null,
        ) ?: error("无法读取彩信发件人")
        cursor.use {
            var fallback = ""
            while (it.moveToNext()) {
                val address = it.getString(0).orEmpty()
                if (address.isBlank() || address == "insert-address-token") continue
                if (it.getInt(1) == 137) return address
                if (fallback.isEmpty()) fallback = address
            }
            return fallback
        }
    }
}

/** Provider 决策和镜像只读筛选共享同一身份算法。 */
fun whitelistMessageFingerprint(id: Long, sender: String, date: Long, sent: Long): String {
    val raw = "$id:${sender.length}:$sender:$date:$sent"
    return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}