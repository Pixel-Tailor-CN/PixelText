package vip.mystery0.pixel.text.mms

import android.content.Context
import android.provider.Telephony
import android.util.Log
import androidx.core.net.toUri
import org.json.JSONArray
import org.json.JSONObject

/** 报告事件独立持久化；消息编号只是候选条件，参与方与源卡共同确定关联。 */
internal class MmsReceptionReports(private val context: Context) {
    private val journal = MmsReceptionJournal(context, "mms_reception_reports")
    fun states(mmsId: Long): List<MmsReceptionReportState> = journal.entries()
        .filter { it.second.optLong("source_id") == mmsId }.map { (key, value) ->
            MmsReceptionReportState(key, mmsId, value.optInt("type"), value.optInt("status"),
                value.optLong("date"), value.optString("phase"))
        }
    fun receive(event: MmsIncomingEvent.Report, bytes: ByteArray, subId: Int) {
        val record = JSONObject().put("type", event.type).put("sub", subId)
            .put("message_id", MmsReceptionJournal.encode(event.messageId)).put("date", event.date)
            .put("from", event.from?.let(MmsAddresses::normalized).orEmpty())
            .put("to", JSONArray(event.to.map(MmsAddresses::normalized).distinct().sorted()))
            .put("status", event.status).put("version", event.version)
        val key = MmsReceptionJournal.hash(record.toString().toByteArray())
        val existing = journal.get(key)
        if (existing != null) { associate(key, existing); return }
        record.put("received", System.currentTimeMillis()).put("raw", MmsReceptionJournal.encode(bytes))
            .put("phase", "unmatched")
        journal.put(key, record)
        associate(key, record)
        Log.i("MmsReports", "mms report type=${event.type} status=${event.status} phase=${record.optString("phase")}")
    }

    fun recover() {
        journal.entries().forEach { (key, record) ->
            try {
                val sourceId = record.optLong("source_id", -1)
                if (sourceId > 0) {
                    val exists = context.contentResolver.query("content://mms/$sourceId".toUri(),
                        arrayOf("_id"), null, null, null)?.use { it.moveToFirst() }
                        ?: error("mms report source unavailable")
                    // 已关联源消息被删除后，同时回收报告原始字节，避免重新成为孤立事件。
                    if (!exists) { journal.remove(key); return@forEach }
                }
                // 报告不是用户正文；保留近期事件，避免无上限增长。
                if (System.currentTimeMillis() - record.optLong("received") > 90L * 86400000) journal.remove(key)
                else associate(key, record)
            } catch (_: Exception) { Log.w("MmsReports", "mms report recovery unavailable") }
        }
    }

    private fun associate(key: String, record: JSONObject) {
        val id = MmsReceptionJournal.decode(record.getString("message_id")).toString(Charsets.ISO_8859_1)
        val sub = record.getInt("sub")
        val related = if (record.getInt("type") == 136) setOf(record.getString("from")) else {
            val to = record.getJSONArray("to")
            (0 until to.length()).map(to::getString).toSet()
        }.map(MmsAddresses::normalized).filter { it.isNotBlank() }.toSet()
        val candidates = context.contentResolver.query(Telephony.Mms.CONTENT_URI, arrayOf("_id"),
            "m_id = ? AND sub_id = ? AND m_type = ? AND msg_box != ?", arrayOf(id, sub.toString(), "128", "1"), null)
            ?.use { cursor -> buildList<Long> { while (cursor.moveToNext()) add(cursor.getLong(0)) } }
            ?: error("mms report association unavailable")
        val matches = if (sub < 0 || related.isEmpty()) emptyList() else candidates.filter { message ->
            val recipients = context.contentResolver.query("content://mms/$message/addr".toUri(), arrayOf("address"),
                "type IN (151,130,129)", null, null)?.use { cursor -> buildSet<String> {
                    while (cursor.moveToNext()) add(MmsAddresses.normalized(cursor.getString(0)))
                } } ?: error("mms report addresses unavailable")
            recipients.containsAll(related)
        }
        record.put("phase", if (matches.size == 1) "matched" else "unmatched")
        record.put("source_id", matches.singleOrNull() ?: -1)
        // 防御 OEM 已保存同类报告：只记录其关联，不再插入另一份 Provider 行。
        val statusColumn = if (record.getInt("type") == 136) "read_status" else "st"
        val providerReports = context.contentResolver.query(Telephony.Mms.CONTENT_URI, arrayOf("_id", statusColumn),
            "m_id = ? AND sub_id = ? AND m_type = ? AND date = ?",
            arrayOf(id, sub.toString(), record.getInt("type").toString(), record.getLong("date").toString()), null)
            ?.use { cursor -> buildList<Long> {
                while (cursor.moveToNext()) if (cursor.getInt(1) == record.getInt("status")) add(cursor.getLong(0))
            } }.orEmpty().filter { reportId ->
                val role = if (record.getInt("type") == 136) 137 else 151
                val reportedAddresses = context.contentResolver.query("content://mms/$reportId/addr".toUri(),
                    arrayOf("address"), "type = ?", arrayOf(role.toString()), null)?.use { cursor ->
                    buildSet { while (cursor.moveToNext()) add(MmsAddresses.normalized(cursor.getString(0))) }
                }.orEmpty()
                reportedAddresses == related
            }
        record.put("provider_id", providerReports.singleOrNull() ?: -1)
        journal.put(key, record)
    }
}
