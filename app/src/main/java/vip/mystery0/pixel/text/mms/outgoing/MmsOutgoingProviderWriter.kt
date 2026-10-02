package vip.mystery0.pixel.text.mms.outgoing

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import vip.mystery0.pixel.text.mms.vendor.pdu.PduHeaders
import java.security.MessageDigest

class MmsProviderDeleted : Exception()
class MmsProviderConflict : Exception()

/** 只管理有私有请求身份的单人 SendReq；从不写 OUTBOX，不接管历史失败消息。 */
class MmsOutgoingProviderWriter(private val context: Context) {
    private val resolver get() = context.contentResolver
    private val root = Uri.parse("content://mms")
    private fun uri(id: Long) = Uri.parse("content://mms/$id")
    private data class Header(val id: Long, val transaction: String, val subId: Int, val type: Int, val subject: String, val date: Long, val contentType: String, val box: Int)
    // 查询为空与 Cursor 不可用必须区分；前者才证明已删除。
    private fun header(id: Long): Header? {
        val cursor = resolver.query(uri(id), arrayOf("_id", "tr_id", "sub_id", "m_type", "sub", "date", "ct_t", "msg_box"), null, null, null)
            ?: error("mms header query unavailable")
        return cursor.use { c -> if (!c.moveToFirst()) null else Header(c.getLong(0), c.getString(1).orEmpty(), c.getInt(2), c.getInt(3), c.getString(4).orEmpty(), c.getLong(5), c.getString(6).orEmpty(), c.getInt(7)) }
    }
    private fun assertUniqueTransaction(message: OutgoingMmsMessage, id: Long) {
        val cursor = resolver.query(root, arrayOf("_id"), "tr_id = ? AND sub_id = ? AND m_type = ?",
            arrayOf(message.transactionId, message.snapshot.subscriptionId.toString(), PduHeaders.MESSAGE_TYPE_SEND_REQ.toString()), null)
            ?: error("mms ownership query unavailable")
        cursor.use { c ->
            if (!c.moveToFirst() || c.getLong(0) != id || c.moveToNext()) throw MmsProviderConflict()
        }
    }
    private fun subject(value: String) = value.toByteArray(Charsets.UTF_8).toString(Charsets.ISO_8859_1)
    private fun checkHeader(value: Header, message: OutgoingMmsMessage, contentType: String, previousTransaction: String? = null) {
        if (value.transaction !in setOfNotNull(message.transactionId, previousTransaction) || value.subId != message.snapshot.subscriptionId ||
            value.type != PduHeaders.MESSAGE_TYPE_SEND_REQ || value.subject != subject(message.snapshot.subject) ||
            value.date != message.snapshot.createdAt / 1000 || value.contentType != contentType) throw MmsProviderConflict()
    }
    suspend fun prepare(message: OutgoingMmsMessage, composed: ComposedMms, previousTransaction: String?, previousProviderReady: Boolean, saveSource: suspend (Long) -> Boolean): Long {
        check(!message.deleted && message.state == MmsSendState.PREPARING)
        val id = message.sourceId ?: run {
            val cursor = resolver.query(root, arrayOf("_id"), "tr_id = ? AND sub_id = ? AND m_type = ?",
                arrayOf(message.transactionId, message.snapshot.subscriptionId.toString(), PduHeaders.MESSAGE_TYPE_SEND_REQ.toString()), null)
                ?: error("mms candidate query unavailable")
            val candidates = cursor.use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
            if (candidates.size > 1) throw MmsProviderConflict()
            candidates.singleOrNull() ?: insert(message, composed.contentType)
        }
        val found = header(id) ?: throw MmsProviderDeleted()
        checkHeader(found, message, composed.contentType, previousTransaction)
        if (!saveSource(id)) throw MmsProviderConflict()
        if (found.transaction != message.transactionId) {
            if (previousTransaction == null || found.box != Telephony.Mms.MESSAGE_BOX_FAILED) throw MmsProviderConflict()
            if (previousProviderReady) verify(message.copy(sourceId = id, transactionId = previousTransaction), composed)
            else verifyPartial(message.copy(sourceId = id, transactionId = previousTransaction), composed)
            val changes = ContentValues().apply {
                put("tr_id", message.transactionId); put("msg_box", Telephony.Mms.MESSAGE_BOX_DRAFTS)
                putNull("m_id"); putNull("resp_st"); put("date_sent", 0)
            }
            if (resolver.update(uri(id), changes, "tr_id = ? AND sub_id = ? AND m_type = ?",
                    arrayOf(found.transaction, message.snapshot.subscriptionId.toString(), PduHeaders.MESSAGE_TYPE_SEND_REQ.toString())) != 1) throw MmsProviderConflict()
        } else if (found.box != Telephony.Mms.MESSAGE_BOX_DRAFTS) throw MmsProviderConflict()
        val ensureOwner = {
            checkHeader(header(id) ?: throw MmsProviderDeleted(), message, composed.contentType)
        }
        prepareAddresses(id, message.snapshot.recipientAddress, ensureOwner)
        prepareParts(id, composed.parts, ensureOwner)
        verify(message.copy(sourceId = id), composed)
        return id
    }
    private fun insert(message: OutgoingMmsMessage, contentType: String): Long {
        val snapshot = message.snapshot
        val threadId = Telephony.Threads.getOrCreateThreadId(context, setOf(snapshot.recipientAddress))
        val values = ContentValues().apply {
            put("thread_id", threadId); put("msg_box", Telephony.Mms.MESSAGE_BOX_DRAFTS)
            put("m_type", PduHeaders.MESSAGE_TYPE_SEND_REQ); put("v", PduHeaders.CURRENT_MMS_VERSION)
            put("sub_id", snapshot.subscriptionId); put("date", snapshot.createdAt / 1000)
            put("read", 1); put("seen", 1); put("tr_id", message.transactionId)
            put("ct_t", contentType); put("sub", subject(snapshot.subject)); put("sub_cs", 106)
            put("d_rpt", PduHeaders.VALUE_NO); put("rr", PduHeaders.VALUE_NO)
        }
        return resolver.insert(root, values)?.lastPathSegment?.toLongOrNull() ?: error("mms insert unavailable")
    }
    private fun addressRows(id: Long): List<Pair<Int, String>> {
        val cursor = resolver.query(Uri.parse("content://mms/$id/addr"), arrayOf("type", "address"), null, null, null)
            ?: error("mms address query unavailable")
        return cursor.use { c -> buildList { while (c.moveToNext()) add(c.getInt(0) to c.getString(1).orEmpty()) } }
    }
    private fun prepareAddresses(id: Long, recipient: String, ensureOwner: () -> Unit) {
        val target = listOf(PduHeaders.FROM to "insert-address-token", PduHeaders.TO to recipient)
        val existing = addressRows(id)
        if (existing.any { it !in target } || existing.distinct().size != existing.size) throw MmsProviderConflict()
        target.filterNot { it in existing }.forEach { (type, value) ->
            ensureOwner()
            resolver.insert(Uri.parse("content://mms/$id/addr"), ContentValues().apply {
                put("type", type); put("address", value); put("charset", 106)
            }) ?: error("mms address insert unavailable")
        }
    }
    private data class PartRow(val id: Long, val seq: Int, val mime: String, val cid: String, val location: String, val charset: Int, val text: String?)
    private fun parts(id: Long): List<PartRow> {
        val cursor = resolver.query(Uri.parse("content://mms/$id/part"), arrayOf("_id", "seq", "ct", "cid", "cl", "chset", "text"), null, null, "seq ASC")
            ?: error("mms part query unavailable")
        return cursor.use { c -> buildList { while (c.moveToNext()) add(PartRow(c.getLong(0), c.getInt(1), c.getString(2).orEmpty(), c.getString(3).orEmpty(), c.getString(4).orEmpty(), c.getInt(5), c.getString(6))) } }
    }
    private fun inline(part: MmsPayloadPart) = part.mimeType in setOf("text/plain", "application/smil") && part.charset == 106
    private fun seq(index: Int, part: MmsPayloadPart) = if (part.mimeType == "application/smil") -1 else index
    private fun metadataMatches(row: PartRow, index: Int, part: MmsPayloadPart): Boolean = row.seq == seq(index, part) &&
        row.mime == part.mimeType && row.cid == part.contentId && row.location == part.contentLocation && row.charset == part.charset
    private fun assertPartOwner(parentId: Long, partId: Long, index: Int, expected: MmsPayloadPart) {
        val cursor = resolver.query(Uri.parse("content://mms/part/$partId"), arrayOf("mid", "seq", "ct", "cid", "cl", "chset"), null, null, null)
            ?: error("mms part identity query unavailable")
        cursor.use { c ->
            if (!c.moveToFirst() || c.getLong(0) != parentId) throw MmsProviderConflict()
            val row = PartRow(partId, c.getInt(1), c.getString(2).orEmpty(), c.getString(3).orEmpty(), c.getString(4).orEmpty(), c.getInt(5), null)
            if (!metadataMatches(row, index, expected) && !(row.location.isEmpty() && metadataMatches(row.copy(location = expected.contentLocation), index, expected))) throw MmsProviderConflict()
        }
    }
    private fun readPart(row: PartRow, max: Int): ByteArray {
        if (row.mime in setOf("text/plain", "application/smil")) return row.text.orEmpty().toByteArray(Charsets.UTF_8)
        return resolver.openInputStream(Uri.parse("content://mms/part/${row.id}"))?.use { input ->
            val output = java.io.ByteArrayOutputStream(minOf(max, 8192)); val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count < 0) break; if (output.size().toLong() + count > max) throw MmsProviderConflict(); output.write(buffer, 0, count) }
            output.toByteArray()
        } ?: error("mms part read unavailable")
    }
    private fun prepareParts(id: Long, expected: List<MmsPayloadPart>, ensureOwner: () -> Unit) {
        val existing = parts(id)
        if (existing.map { it.cid }.distinct().size != existing.size || existing.any { row -> expected.none { it.contentId == row.cid } }) throw MmsProviderConflict()
        expected.forEachIndexed { index, part ->
            ensureOwner()
            val row = existing.singleOrNull { it.cid == part.contentId }
            val values = ContentValues().apply {
                put("seq", seq(index, part)); put("ct", part.mimeType); put("cid", part.contentId)
                put("cl", part.contentLocation); put("chset", part.charset)
                if (inline(part)) put("text", part.bytes.toString(Charsets.UTF_8))
            }
            val partUri = if (row == null) {
                // 不允许 Provider 将不可信名称用作磁盘路径。
                val insertValues = ContentValues(values).apply { if (!inline(part)) remove("cl") }
                resolver.insert(Uri.parse("content://mms/$id/part"), insertValues) ?: error("mms part insert unavailable")
            } else {
                // 插入binary part后尚未写回cl是本功能可恢复的中间态。
                if (!metadataMatches(row, index, part) && !(row.location.isEmpty() && metadataMatches(row.copy(location = part.contentLocation), index, part))) throw MmsProviderConflict()
                assertPartOwner(id, row.id, index, part)
                val bytes = readPart(row, part.bytes.size)
                if (bytes.size == part.bytes.size && !MessageDigest.isEqual(bytes, part.bytes)) throw MmsProviderConflict()
                if (MessageDigest.isEqual(bytes, part.bytes) && metadataMatches(row, index, part)) return@forEachIndexed
                Uri.parse("content://mms/part/${row.id}")
            }
            ensureOwner()
            val partId = partUri.lastPathSegment?.toLongOrNull() ?: throw MmsProviderConflict()
            assertPartOwner(id, partId, index, part)
            if (!inline(part)) resolver.openOutputStream(partUri, "w")?.use { it.write(part.bytes) } ?: error("mms part write unavailable")
            ensureOwner()
            if (resolver.update(partUri, values, "mid = ? AND cid = ?", arrayOf(id.toString(), part.contentId)) != 1) throw MmsProviderConflict()
        }
    }
    fun verify(message: OutgoingMmsMessage, composed: ComposedMms) {
        val id = message.sourceId ?: throw MmsProviderConflict()
        val found = header(id) ?: throw MmsProviderDeleted()
        checkHeader(found, message, composed.contentType)
        assertUniqueTransaction(message, id)
        val addresses = addressRows(id)
        if (addresses.size != 2 || addresses.toSet() != setOf(PduHeaders.FROM to "insert-address-token", PduHeaders.TO to message.snapshot.recipientAddress)) throw MmsProviderConflict()
        val rows = parts(id)
        if (rows.size != composed.parts.size) throw MmsProviderConflict()
        composed.parts.forEachIndexed { index, part ->
            val row = rows.singleOrNull { it.cid == part.contentId } ?: throw MmsProviderConflict()
            assertPartOwner(id, row.id, index, part)
            if (!metadataMatches(row, index, part) || !MessageDigest.isEqual(readPart(row, part.bytes.size), part.bytes)) throw MmsProviderConflict()
        }
    }
    private fun verifyPartial(message: OutgoingMmsMessage, composed: ComposedMms) {
        val id = message.sourceId ?: throw MmsProviderConflict()
        checkHeader(header(id) ?: throw MmsProviderDeleted(), message, composed.contentType)
        assertUniqueTransaction(message, id)
        val addresses = addressRows(id)
        val expectedAddresses = setOf(PduHeaders.FROM to "insert-address-token", PduHeaders.TO to message.snapshot.recipientAddress)
        if (addresses.distinct().size != addresses.size || addresses.any { it !in expectedAddresses }) throw MmsProviderConflict()
        val existing = parts(id)
        if (existing.map { it.cid }.distinct().size != existing.size) throw MmsProviderConflict()
        existing.forEach { row ->
            val index = composed.parts.indexOfFirst { it.contentId == row.cid }
            if (index < 0) throw MmsProviderConflict()
            val expected = composed.parts[index]
            if (!metadataMatches(row, index, expected) && !(row.location.isEmpty() && metadataMatches(row.copy(location = expected.contentLocation), index, expected))) throw MmsProviderConflict()
            assertPartOwner(id, row.id, index, expected)
            val bytes = readPart(row, expected.bytes.size)
            if (!bytes.contentEquals(expected.bytes.copyOf(bytes.size))) throw MmsProviderConflict()
        }
    }
    fun complete(message: OutgoingMmsMessage, composed: ComposedMms) {
        if (!message.providerReady && message.submittedAt == null && message.state in setOf(MmsSendState.FAILED, MmsSendState.CANCELLED)) verifyPartial(message, composed)
        else verify(message, composed)
        val box = when (message.state) {
            MmsSendState.SENT -> Telephony.Mms.MESSAGE_BOX_SENT
            MmsSendState.FAILED, MmsSendState.UNKNOWN, MmsSendState.CANCELLED -> Telephony.Mms.MESSAGE_BOX_FAILED
            else -> return
        }
        val changes = ContentValues().apply {
            put("msg_box", box)
            message.responseStatus?.let { put("resp_st", it) }
            message.messageId?.let { put("m_id", it) }
            if (message.state == MmsSendState.SENT) put("date_sent", (message.submittedAt ?: message.snapshot.createdAt) / 1000)
        }
        if (resolver.update(uri(message.sourceId!!), changes, "tr_id = ? AND sub_id = ? AND m_type = ?",
                arrayOf(message.transactionId, message.snapshot.subscriptionId.toString(), PduHeaders.MESSAGE_TYPE_SEND_REQ.toString())) != 1) throw MmsProviderConflict()
    }
}
