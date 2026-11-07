package vip.mystery0.pixel.text.data.source

import android.content.ContentResolver
import android.content.ContentUris
import android.provider.Telephony
import vip.mystery0.pixel.text.data.repository.mirror.toMessageModel
import vip.mystery0.pixel.text.domain.model.mirror.MessageTransport
import vip.mystery0.pixel.text.domain.model.mirror.SourceMessageKey
import vip.mystery0.pixel.text.domain.repository.MessageMirrorRepository
import vip.mystery0.pixel.text.domain.spam.learning.LearningMessageKey

/** 读取当前身份，防止 Provider 编号复用后继承纠正或单次识别缓存。 */
class LearningMessageSource(
    private val resolver: ContentResolver,
    private val mirror: MessageMirrorRepository? = null,
) {
    fun threadIdsByRecent(ids: Collection<Long>): List<Long> {
        val dates = mutableMapOf<Long, Long>()
        for (mms in listOf(false, true)) {
            val sourceIds = ids.filter { if (mms) it < 0 else it > 0 }.map { if (mms) -it else it }
            for (batch in sourceIds.chunked(500)) {
                resolver.query(if (mms) Telephony.Mms.CONTENT_URI else Telephony.Sms.CONTENT_URI,
                    arrayOf("thread_id", "date"), "_id IN (${batch.joinToString(",") { "?" }})",
                    batch.map(Long::toString).toTypedArray(), null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val timestamp = cursor.getLong(1) * if (mms) 1000L else 1L
                        dates.merge(cursor.getLong(0), timestamp, ::maxOf)
                    }
                }
            }
        }
        return dates.entries.sortedByDescending { it.value }.map { it.key }
    }

    suspend fun keys(ids: Collection<Long>): Map<Long, LearningMessageKey> = buildMap {
        for (batch in ids.filter { it > 0 }.chunked(500)) {
            resolver.query(Telephony.Sms.CONTENT_URI, arrayOf("_id", "address", "date", "body"),
                "_id IN (${batch.joinToString(",") { "?" }})", batch.map(Long::toString).toTypedArray(), null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    put(id, LearningMessageKey.create("SMS", id, cursor.getString(1).orEmpty(), cursor.getLong(2), cursor.getString(3).orEmpty()))
                }
            }
        }
        for (id in ids.filter { it < 0 && it != Long.MIN_VALUE }) {
            val snapshot = mirror?.getMessage(SourceMessageKey(MessageTransport.MMS, -id)) ?: continue
            val original = snapshot.rawValues.associate { it.column to it.value }
            // 仅采用镜像已索引的正文；Provider 再核对身份字段，拒绝已删除或被替换的消息。
            val columns = arrayOf("date", "thread_id", "m_type", "m_id", "tr_id", "ct_l", "sub", "sub_cs", "msg_box")
            val matches = resolver.query(ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, -id),
                columns, null, null, null)?.use { cursor ->
                cursor.moveToFirst() && columns.indices.all { index ->
                    cursor.getString(index) == original[columns[index]]
                }
            } == true
            if (!matches) continue
            val message = snapshot.toMessageModel()
            put(id, LearningMessageKey.create("MMS", id, message.sender, message.timestamp, message.content))
        }
    }
}
