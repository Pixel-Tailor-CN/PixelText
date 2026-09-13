package vip.mystery0.pixel.text.data.source.mirror

import androidx.core.net.toUri

import android.content.Context
import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import android.os.Bundle
import vip.mystery0.pixel.text.domain.model.search.SearchPhoneNumbers
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import vip.mystery0.pixel.text.data.db.mirror.*
import vip.mystery0.pixel.text.domain.model.mirror.*
import java.nio.charset.Charset

sealed interface SourceRead<out T> {
    data class Success<T>(val value: T, val columns: Set<String> = emptySet()) : SourceRead<T>
    data class Failure(val category: String) : SourceRead<Nothing>
}

data class MmsChildren(val addresses: List<ProviderRowSnapshot>, val parts: List<ProviderRowSnapshot>)
data class ThreadSources(
    val threads: SourceRead<List<ProviderRowSnapshot>>,
    val canonicalAddresses: SourceRead<List<ProviderRowSnapshot>>,
)

/** 只读取授权的 Telephony Provider；任何失败都不会伪装成空集合。 */
class TelephonyMirrorSource(context: Context) {
    private val resolver = context.applicationContext.contentResolver

    suspend fun readSmsPage(afterId: Long?, limit: Int = 200): SourceRead<List<ProviderRowSnapshot>> =
        readPage("sms", afterId, limit)

    suspend fun readMmsPage(afterId: Long?, limit: Int = 200): SourceRead<List<ProviderRowSnapshot>> =
        readPage("mms", afterId, limit)

    private suspend fun readPage(authority: String, afterId: Long?, limit: Int): SourceRead<List<ProviderRowSnapshot>> =
        query("content://$authority".toUri(), afterId?.let { "_id > ?" },
            afterId?.let { arrayOf(it.toString()) }, "_id ASC", limit.coerceIn(1, 500))

    suspend fun readMessage(key: SourceMessageKey): SourceRead<MirrorStructure?> {
        val result = query("content://${key.transport.name.lowercase()}/${key.sourceId}".toUri())
        return when (result) {
            is SourceRead.Failure -> result
            is SourceRead.Success -> {
                if (result.value.size > 1) SourceRead.Failure("unexpected_row_count")
                else result.value.firstOrNull()?.let {
                    try {
                        SourceRead.Success(toStructure(key.transport, it), result.columns)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        SourceRead.Failure(error.javaClass.simpleName)
                    }
                } ?: SourceRead.Success(null, result.columns)
            }
        }
    }

    suspend fun readMmsChildren(sourceId: Long): SourceRead<MmsChildren> {
        val addresses = query("content://mms/$sourceId/addr".toUri())
        if (addresses is SourceRead.Failure) return addresses
        val parts = query("content://mms/$sourceId/part".toUri())
        if (parts is SourceRead.Failure) return parts
        addresses as SourceRead.Success
        parts as SourceRead.Success
        val partIds = parts.value.map { it.long("_id") }
        if (partIds.any { it == null } || partIds.distinct().size != partIds.size) {
            return SourceRead.Failure("invalid_part_identity")
        }
        return SourceRead.Success(MmsChildren(
            addresses.value.sortedWith(compareBy<ProviderRowSnapshot> { it.long("_id") }.thenBy { it.int("type") }),
            parts.value.sortedWith(compareBy<ProviderRowSnapshot> { it.int("seq") }.thenBy { it.long("_id") }),
        ), addresses.columns.map { "addr.$it" }.toSet() + parts.columns.map { "part.$it" })
    }

    suspend fun readThreadSources(): ThreadSources = ThreadSources(
        query("content://mms-sms/conversations?simple=true".toUri()),
        query("content://mms-sms/canonical-addresses".toUri()),
    )

    suspend fun messageKeyForPart(partId: Long): SourceRead<SourceMessageKey?> =
        when (val result = query("content://mms/part/$partId".toUri())) {
            is SourceRead.Failure -> result
            is SourceRead.Success -> SourceRead.Success(result.value.firstOrNull()?.long("mid")?.let {
                SourceMessageKey(MessageTransport.MMS, it)
            }, result.columns)
        }

    suspend fun toStructure(transport: MessageTransport, row: ProviderRowSnapshot, generation: Long = 0): MirrorStructure {
        currentCoroutineContext().ensureActive()
        val sourceId = requireNotNull(row.long("_id")) { "missing_source_id" }
        val isMms = transport == MessageTransport.MMS
        val children = if (isMms) readMmsChildren(sourceId) else SourceRead.Success(MmsChildren(emptyList(), emptyList()))
        val complete = children is SourceRead.Success
        val childRows = (children as? SourceRead.Success)?.value
        val originalDate = row.long("date")
        val message = MirrorMessageEntity(
            transport = transport.name, sourceId = sourceId, threadId = row.long("thread_id"),
            timestamp = originalDate?.let { if (isMms) Math.multiplyExact(it, 1000L) else it },
            originalDate = originalDate, dateUnit = if (isMms) "SECONDS" else "MILLISECONDS",
            subscriptionId = row.int("sub_id"), boxType = row.int(if (isMms) "msg_box" else "type"),
            read = row.int("read"), seen = row.int("seen"), structureComplete = complete, generation = generation,
        )
        val sms = if (isMms) null else MirrorSmsEntity(
            0, row.encode(), row.string("body"), row.string("address"), row.string("subject"),
            row.long("date_sent"), row.int("status"), row.int("error_code"), row.int("locked"),
            row.int("protocol"), row.int("reply_path_present"), row.string("service_center"), row.string("creator"),
            normalizedAddress = SearchPhoneNumbers.digits(row.string("address")),
        )
        val mms = if (!isMms) null else MirrorMmsEntity(
            0, row.encode(), row.string("sub"), row.int("sub_cs"), decodeSubject(row.string("sub"), row.int("sub_cs")),
            row.int("m_type"), row.int("st"), row.string("tr_id"), row.string("ct_l"), row.long("exp"), row.long("m_size"),
        )
        val addresses = childRows?.addresses.orEmpty().mapIndexed { index, addr ->
            MirrorAddressEntity(0, index, addr.long("_id"), addr.int("type"), addr.int("charset"),
                addr.string("address"), SearchPhoneNumbers.digits(addr.string("address")), addr.encode())
        }
        val parts = childRows?.parts.orEmpty().map { part ->
            MirrorPartEntity(0, requireNotNull(part.long("_id")) { "missing_part_id" }, part.int("seq"),
                part.string("ct"), part.int("chset"), part.string("name"), part.string("fn"), part.string("cid"),
                part.string("cl"), part.string("text"), part.encode())
        }
        val attachments = parts.map { part ->
            // 文本也尝试复制 Provider 流；没有流且已有文本时由复制器保留文本快照。
            MirrorAttachmentEntity(0, part.sourceId, 1, "content://mms/part/${part.sourceId}",
                state = MirrorAttachmentState.SOURCE_PRESENT.name)
        }
        return MirrorStructure(message, sms, mms, addresses, parts, attachments, complete,
            (children as? SourceRead.Success)?.columns.orEmpty())
    }

    private suspend fun query(
        uri: Uri, selection: String? = null, args: Array<String>? = null, sortOrder: String? = null,
        maxRows: Int = Int.MAX_VALUE,
    ): SourceRead<List<ProviderRowSnapshot>> = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            try {
                val queryArgs = Bundle().apply {
                    selection?.let { putString(ContentResolver.QUERY_ARG_SQL_SELECTION, it) }
                    args?.let { putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, it) }
                    sortOrder?.let { putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, it) }
                    if (maxRows != Int.MAX_VALUE) putInt(ContentResolver.QUERY_ARG_LIMIT, maxRows)
                }
                val cursor = resolver.query(uri, null, queryArgs, signal)
                    ?: throw IllegalStateException("null_cursor")
                cursor.use {
                    val columns = it.columnNames.toSet()
                    val rows = mutableListOf<ProviderRowSnapshot>()
                    // 部分 Provider 忽略 QUERY_ARG_LIMIT，仍限制每批读取行数。
                    while (rows.size < maxRows && it.moveToNext()) {
                        if (!continuation.isActive) throw CancellationException()
                        rows += ProviderRowSnapshot.fromCursor(it)
                    }
                    if (continuation.isActive) continuation.resumeWith(Result.success(SourceRead.Success(rows, columns)))
                }
            } catch (error: Exception) {
                if (error is CancellationException || !continuation.isActive) {
                    continuation.cancel(error)
                } else {
                    val category = when (error) {
                        is SecurityException -> "permission_denied"
                        is IllegalStateException -> if (error.message == "null_cursor") "null_cursor" else "provider_state"
                        else -> error.javaClass.simpleName
                    }
                    Log.w("TelephonyMirrorSource", "source query failed category=$category")
                    continuation.resumeWith(Result.success(SourceRead.Failure(category)))
                }
            }
        }
    }

    private fun decodeSubject(value: String?, charset: Int?): String? {
        if (value == null) return null
        if (value.any { it.code > 255 }) return value
        val name = when (charset) { 3 -> "US-ASCII"; 4 -> "ISO-8859-1"; 106 -> "UTF-8"; 2026 -> "Big5"; 2025 -> "GB2312"; else -> return value }
        return runCatching { String(value.toByteArray(Charsets.ISO_8859_1), Charset.forName(name)) }.getOrDefault(value)
    }
}
