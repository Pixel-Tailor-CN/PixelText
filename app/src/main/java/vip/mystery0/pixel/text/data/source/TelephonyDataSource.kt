package vip.mystery0.pixel.text.data.source

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

private const val TELEPHONY_TAG = "TelephonyDataSource"

data class SpamScanMessageRow(
    val date: Long,
    val messageId: Long,
    val threadId: Long,
    val address: String,
    val content: String
)

data class SmartspacerSmsRow(
    val id: Long,
    val threadId: Long,
    val address: String,
    val body: String,
    val date: Long,
    val read: Boolean
)

data class SmartspacerUnreadSmsRow(
    val id: Long,
    val threadId: Long
)

data class SmsIndexSummaryRow(
    val id: Long,
    val threadId: Long,
    val address: String,
    val date: Long,
    val dateSent: Long,
    val type: Int,
)

private data class IncomingMessageRef(
    val id: Long,
    val timestamp: Long,
    val isMms: Boolean
)

class TelephonyDataSource(
    private val context: Context,
    private val contentResolver: ContentResolver
) {
    private val simNameCache = mutableMapOf<Int, String>()

    /** 总数仅供进度估算；读取失败不影响索引，取消仍传播给调用方。 */
    suspend fun getSmsIndexCount(): Long? = messageCount(Telephony.Sms.CONTENT_URI)

    suspend fun getMmsCount(): Long? = messageCount(Telephony.Mms.CONTENT_URI)

    private suspend fun messageCount(uri: Uri): Long? = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            try {
                val count = contentResolver.query(
                    uri,
                    arrayOf(Telephony.Sms._ID),
                    null,
                    null,
                    null,
                    signal,
                )?.use { it.count.toLong() }
                if (continuation.isActive) continuation.resumeWith(Result.success(count))
            } catch (error: Exception) {
                if (error is CancellationException || !continuation.isActive) {
                    continuation.cancel(error)
                } else {
                    continuation.resumeWith(Result.success(null))
                }
            }
        }
    }

    fun getSmsIndexSummaries(
        beforeMessageId: Long? = null,
        limit: Int = DEFAULT_INDEX_BATCH_SIZE,
    ): List<SmsIndexSummaryRow> {
        val rows = mutableListOf<SmsIndexSummaryRow>()
        val selection = beforeMessageId?.let { "${Telephony.Sms._ID} < ?" }
        val selectionArgs = beforeMessageId?.let { arrayOf(it.toString()) }
        val cursor = contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.THREAD_ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.DATE,
                Telephony.Sms.DATE_SENT,
                Telephony.Sms.TYPE,
            ),
            selection,
            selectionArgs,
            "${Telephony.Sms._ID} DESC LIMIT $limit",
        ) ?: throw IllegalStateException("sms index query returned null")
        cursor.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
            val threadIdIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
            val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val dateSentIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE_SENT)
            val typeIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
            while (cursor.moveToNext()) {
                rows += SmsIndexSummaryRow(
                    id = cursor.getLong(idIndex),
                    threadId = cursor.getLong(threadIdIndex),
                    address = cursor.getString(addressIndex).orEmpty(),
                    date = cursor.getLong(dateIndex),
                    dateSent = cursor.getLong(dateSentIndex),
                    type = cursor.getInt(typeIndex),
                )
            }
        }
        return rows
    }


    /** 批量读取权威 Provider 正文，缺失的 ID 不加入结果。 */
    fun getSmsBodies(messageIds: Collection<Long>): Map<Long, String> {
        if (messageIds.isEmpty()) return emptyMap()
        return buildMap {
            messageIds.distinct().chunked(MAX_QUERY_ARGS).forEach { chunk ->
                val selection = "${Telephony.Sms._ID} IN (${chunk.joinToString(",") { "?" }})"
                val cursor = contentResolver.query(
                    Telephony.Sms.CONTENT_URI,
                    arrayOf(Telephony.Sms._ID, Telephony.Sms.BODY),
                    selection,
                    chunk.map(Long::toString).toTypedArray(),
                    null,
                ) ?: throw IllegalStateException("sms body query returned null")
                cursor.use {
                    val idIndex = it.getColumnIndexOrThrow(Telephony.Sms._ID)
                    val bodyIndex = it.getColumnIndexOrThrow(Telephony.Sms.BODY)
                    while (it.moveToNext()) {
                        put(it.getLong(idIndex), it.getString(bodyIndex).orEmpty())
                    }
                }
            }
        }
    }

    fun existingSmsMessageIds(messageIds: Collection<Long>): Set<Long> =
        queryExistingSmsIds(Telephony.Sms._ID, messageIds)

    fun existingSmsThreadIds(threadIds: Collection<Long>): Set<Long> =
        queryExistingSmsIds(Telephony.Sms.THREAD_ID, threadIds)

    private fun queryExistingSmsIds(column: String, values: Collection<Long>): Set<Long> {
        if (values.isEmpty()) return emptySet()
        val result = mutableSetOf<Long>()
        values.chunked(MAX_QUERY_ARGS).forEach { chunk ->
            val (selection, selectionArgs) = buildThreadSelection(column, chunk)
            val cursor = contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(column),
                selection,
                selectionArgs,
                null,
            ) ?: throw IllegalStateException("sms existence query returned null column=$column")
            cursor.use {
                val valueIndex = cursor.getColumnIndexOrThrow(column)
                while (cursor.moveToNext()) result += cursor.getLong(valueIndex)
            }
        }
        return result
    }

    fun deleteThreads(threadIds: Set<Long>) {
        if (threadIds.isEmpty()) return

        val placeholders = threadIds.joinToString(",") { "?" }
        val selectionArgs = threadIds.map { it.toString() }.toTypedArray()

        contentResolver.delete(
            Telephony.Sms.CONTENT_URI,
            "${Telephony.Sms.THREAD_ID} IN ($placeholders)",
            selectionArgs
        )

        try {
            contentResolver.delete(
                Telephony.Mms.CONTENT_URI,
                "${Telephony.Mms.THREAD_ID} IN ($placeholders)",
                selectionArgs
            )
        } catch (e: Exception) {
            Log.e(TELEPHONY_TAG, "failed to delete mms threads", e)
        }
    }

    fun deleteMessages(messageIds: Set<Long>): Int {
        if (messageIds.isEmpty()) return 0

        var deletedCount = 0
        val smsIds = messageIds.filter { it > 0 }
        val mmsIds = messageIds.filter { it < 0 }.map { -it }

        smsIds.chunked(MAX_QUERY_ARGS).forEach { chunk ->
            val (selection, selectionArgs) = buildThreadSelection(Telephony.Sms._ID, chunk)
            deletedCount += contentResolver.delete(
                Telephony.Sms.CONTENT_URI,
                selection,
                selectionArgs
            )
        }

        mmsIds.chunked(MAX_QUERY_ARGS).forEach { chunk ->
            try {
                val (selection, selectionArgs) = buildThreadSelection(Telephony.Mms._ID, chunk)
                deletedCount += contentResolver.delete(
                    Telephony.Mms.CONTENT_URI,
                    selection,
                    selectionArgs
                )
            } catch (e: Exception) {
                Log.e(TELEPHONY_TAG, "failed to delete mms messages", e)
            }
        }

        return deletedCount
    }

    fun markMessagesAsRead(messageIds: Set<Long>): Int {
        if (messageIds.isEmpty()) return 0

        val values = ContentValues().apply {
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
        }
        var updatedCount = 0
        val smsIds = messageIds.filter { it > 0 }
        val mmsIds = messageIds.filter { it < 0 }.map { -it }

        smsIds.chunked(MAX_QUERY_ARGS).forEach { chunk ->
            val (selection, selectionArgs) = buildThreadSelection(Telephony.Sms._ID, chunk)
            try {
                updatedCount += contentResolver.update(
                    Telephony.Sms.CONTENT_URI,
                    values,
                    "$selection AND (${Telephony.Sms.READ} = 0 OR ${Telephony.Sms.SEEN} = 0)",
                    selectionArgs
                )
            } catch (e: Exception) {
                Log.e(TELEPHONY_TAG, "failed to update sms read status", e)
            }
        }

        mmsIds.chunked(MAX_QUERY_ARGS).forEach { chunk ->
            val (selection, selectionArgs) = buildThreadSelection(Telephony.Mms._ID, chunk)
            try {
                updatedCount += contentResolver.update(
                    Telephony.Mms.CONTENT_URI,
                    values,
                    "$selection AND (${Telephony.Mms.READ} = 0 OR ${Telephony.Mms.SEEN} = 0)",
                    selectionArgs
                )
            } catch (e: Exception) {
                Log.e(TELEPHONY_TAG, "failed to update mms read status", e)
            }
        }

        return updatedCount
    }

    fun getThreadIdsForMessages(messageIds: Set<Long>): Set<Long> {
        if (messageIds.isEmpty()) return emptySet()

        val threadIds = linkedSetOf<Long>()
        val smsIds = messageIds.filter { it > 0 }
        val mmsIds = messageIds.filter { it < 0 }.map { -it }

        smsIds.chunked(MAX_QUERY_ARGS).forEach { chunk ->
            val (selection, selectionArgs) = buildThreadSelection(Telephony.Sms._ID, chunk)
            contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.THREAD_ID),
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                val threadIdIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
                while (cursor.moveToNext()) {
                    threadIds += cursor.getLong(threadIdIndex)
                }
            }
        }

        mmsIds.chunked(MAX_QUERY_ARGS).forEach { chunk ->
            try {
                val (selection, selectionArgs) = buildThreadSelection(Telephony.Mms._ID, chunk)
                contentResolver.query(
                    Telephony.Mms.CONTENT_URI,
                    arrayOf(Telephony.Mms.THREAD_ID),
                    selection,
                    selectionArgs,
                    null
                )?.use { cursor ->
                    val threadIdIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.THREAD_ID)
                    while (cursor.moveToNext()) {
                        threadIds += cursor.getLong(threadIdIndex)
                    }
                }
            } catch (e: Exception) {
                Log.e(TELEPHONY_TAG, "failed to query mms message thread ids", e)
            }
        }

        return threadIds
    }

    fun getConversationMessageIdsByThread(threadIds: List<Long>): Map<Long, List<Long>> {
        if (threadIds.isEmpty()) return emptyMap()

        val result = linkedMapOf<Long, MutableList<Long>>()
        threadIds.forEach { result[it] = mutableListOf() }

        threadIds.chunked(MAX_QUERY_ARGS).forEach { chunk ->
            val (selection, selectionArgs) = buildThreadSelection(Telephony.Sms.THREAD_ID, chunk)
            contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID, Telephony.Sms.THREAD_ID),
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
                val threadIdIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
                while (cursor.moveToNext()) {
                    val threadId = cursor.getLong(threadIdIndex)
                    result.getOrPut(threadId) { mutableListOf() }
                        .add(cursor.getLong(idIndex))
                }
            }
        }

        threadIds.chunked(MAX_QUERY_ARGS).forEach { chunk ->
            try {
                val (selection, selectionArgs) =
                    buildThreadSelection(Telephony.Mms.THREAD_ID, chunk)
                contentResolver.query(
                    Telephony.Mms.CONTENT_URI,
                    arrayOf(Telephony.Mms._ID, Telephony.Mms.THREAD_ID),
                    selection,
                    selectionArgs,
                    null
                )?.use { cursor ->
                    val idIndex = cursor.getColumnIndexOrThrow(Telephony.Mms._ID)
                    val threadIdIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.THREAD_ID)
                    while (cursor.moveToNext()) {
                        val threadId = cursor.getLong(threadIdIndex)
                        result.getOrPut(threadId) { mutableListOf() }
                            .add(-cursor.getLong(idIndex))
                    }
                }
            } catch (e: Exception) {
                Log.e(TELEPHONY_TAG, "failed to query mms message ids", e)
            }
        }

        return result
    }

    fun getSmsMessagesForSpamScan(): List<SpamScanMessageRow> {
        val rows = mutableListOf<SpamScanMessageRow>()
        contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.THREAD_ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE
            ),
            null,
            null,
            "${Telephony.Sms.DATE} DESC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
            val threadIdIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
            val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            while (cursor.moveToNext()) {
                rows += SpamScanMessageRow(
                    date = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)),
                    messageId = cursor.getLong(idIndex),
                    threadId = cursor.getLong(threadIdIndex),
                    address = cursor.getString(addressIndex).orEmpty(),
                    content = cursor.getString(bodyIndex).orEmpty()
                )
            }
        }
        return rows
    }

    fun getRecentSmsMessagesForSmartspacer(limit: Int): List<SmartspacerSmsRow> {
        val rows = mutableListOf<SmartspacerSmsRow>()
        contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.THREAD_ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.READ
            ),
            "${Telephony.Sms.TYPE} = ?",
            arrayOf(Telephony.Sms.MESSAGE_TYPE_INBOX.toString()),
            "${Telephony.Sms.DATE} DESC LIMIT $limit"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
            val threadIdIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
            val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val readIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.READ)
            while (cursor.moveToNext()) {
                rows += SmartspacerSmsRow(
                    id = cursor.getLong(idIndex),
                    threadId = cursor.getLong(threadIdIndex),
                    address = cursor.getString(addressIndex).orEmpty(),
                    body = cursor.getString(bodyIndex).orEmpty(),
                    date = cursor.getLong(dateIndex),
                    read = cursor.getInt(readIndex) == 1
                )
            }
        }
        return rows
    }

    fun getUnreadSmsMessagesForSmartspacer(): List<SmartspacerUnreadSmsRow> {
        val rows = mutableListOf<SmartspacerUnreadSmsRow>()
        contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID, Telephony.Sms.THREAD_ID),
            "${Telephony.Sms.READ} = 0 AND ${Telephony.Sms.TYPE} = ?",
            arrayOf(Telephony.Sms.MESSAGE_TYPE_INBOX.toString()),
            null
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
            val threadIdIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
            while (cursor.moveToNext()) {
                rows += SmartspacerUnreadSmsRow(
                    id = cursor.getLong(idIndex),
                    threadId = cursor.getLong(threadIdIndex)
                )
            }
        }
        return rows
    }

    fun insertOutboxPlaceholder(
        address: String,
        message: String,
        threadId: Long,
        subId: Int,
    ): Uri? {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, message)
            put(Telephony.Sms.DATE, now)
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_OUTBOX)
            if (threadId != -1L) {
                put(Telephony.Sms.THREAD_ID, threadId)
            }
            if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                put(Telephony.Sms.SUBSCRIPTION_ID, subId)
            }
        }
        return contentResolver.insert(Telephony.Sms.CONTENT_URI, values)
    }

    fun queryThreadIdFromUri(uri: Uri?): Long? {
        if (uri == null) return null
        contentResolver.query(
            uri,
            arrayOf(Telephony.Sms.THREAD_ID),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getLong(0)
            }
        }
        return null
    }

    fun updateSmsSendResult(uri: Uri?, resultCode: Int, success: Boolean) {
        if (uri == null) return
        val values = ContentValues().apply {
            if (success) {
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
                put(Telephony.Sms.DATE_SENT, System.currentTimeMillis())
            } else {
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_FAILED)
                put(Telephony.Sms.ERROR_CODE, resultCode)
            }
        }
        contentResolver.update(uri, values, null, null)
    }

    fun markThreadsAsRead(threadIds: Set<Long>) {
        if (threadIds.isEmpty()) return

        val values = ContentValues().apply {
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
        }

        threadIds.chunked(MAX_QUERY_ARGS).forEach { chunk ->
            val (smsSelection, smsSelectionArgs) =
                buildThreadSelection(Telephony.Sms.THREAD_ID, chunk)
            try {
                contentResolver.update(
                    Telephony.Sms.CONTENT_URI,
                    values,
                    "$smsSelection AND (${Telephony.Sms.READ} = 0 OR ${Telephony.Sms.SEEN} = 0)",
                    smsSelectionArgs
                )
            } catch (e: Exception) {
                Log.e(TELEPHONY_TAG, "failed to update sms read status", e)
            }

            val (mmsSelection, mmsSelectionArgs) =
                buildThreadSelection(Telephony.Mms.THREAD_ID, chunk)
            try {
                contentResolver.update(
                    Telephony.Mms.CONTENT_URI,
                    values,
                    "$mmsSelection AND (${Telephony.Mms.READ} = 0 OR ${Telephony.Mms.SEEN} = 0)",
                    mmsSelectionArgs
                )
            } catch (e: Exception) {
                Log.e(TELEPHONY_TAG, "failed to update mms read status", e)
            }
        }
    }

    fun markLatestIncomingMessageAsUnread(threadId: Long): Int {
        val latestMessage = listOfNotNull(
            queryLatestIncomingSms(threadId),
            queryLatestIncomingMms(threadId)
        ).maxByOrNull { it.timestamp } ?: return 0

        val values = ContentValues().apply {
            if (latestMessage.isMms) {
                put(Telephony.Mms.READ, 0)
                put(Telephony.Mms.SEEN, 0)
            } else {
                put(Telephony.Sms.READ, 0)
                put(Telephony.Sms.SEEN, 0)
            }
        }

        return try {
            if (latestMessage.isMms) {
                contentResolver.update(
                    Telephony.Mms.CONTENT_URI,
                    values,
                    "${Telephony.Mms._ID} = ?",
                    arrayOf(latestMessage.id.toString())
                )
            } else {
                contentResolver.update(
                    Telephony.Sms.CONTENT_URI,
                    values,
                    "${Telephony.Sms._ID} = ?",
                    arrayOf(latestMessage.id.toString())
                )
            }
        } catch (e: Exception) {
            Log.e(TELEPHONY_TAG, "failed to mark message unread", e)
            0
        }
    }

    fun getSimName(subId: Int): String {
        if (subId <= 0) return "默认卡"
        simNameCache[subId]?.let { return it }

        try {
            val telephonyManager =
                context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val subTelephonyManager = telephonyManager.createForSubscriptionId(subId)

            val networkName = subTelephonyManager.networkOperatorName
            val simName = subTelephonyManager.simOperatorName

            val resolvedName = if (!simName.isNullOrBlank()) {
                simName
            } else if (!networkName.isNullOrBlank()) {
                networkName
            } else {
                null
            }

            if (resolvedName != null && resolvedName.length > 1) {
                simNameCache[subId] = resolvedName
                return resolvedName
            }
        } catch (e: Exception) {
            Log.e(TELEPHONY_TAG, "exception reading telephony manager for subId $subId", e)
        }

        try {
            val cursor = contentResolver.query(
                "content://telephony/siminfo".toUri(),
                null,
                "_id = ?",
                arrayOf(subId.toString()),
                null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val carrierNameIndex = it.getColumnIndex("carrier_name")
                    val carrierName =
                        if (carrierNameIndex >= 0) it.getString(carrierNameIndex) else null

                    val displayNameIndex = it.getColumnIndex("display_name")
                    val displayName =
                        if (displayNameIndex >= 0) it.getString(displayNameIndex) else null

                    val simIdIndex = it.getColumnIndex("sim_id")
                    val simId = if (simIdIndex >= 0) it.getInt(simIdIndex) else simNameCache.size

                    val name = if (!carrierName.isNullOrBlank() && carrierName != "null") {
                        carrierName
                    } else if (!displayName.isNullOrBlank() && displayName != "null") {
                        displayName
                    } else {
                        "卡${simId + 1}"
                    }
                    simNameCache[subId] = name
                    return name
                }
            }
        } catch (e: Exception) {
            Log.e(TELEPHONY_TAG, "exception while querying siminfo for subId $subId", e)
        }

        val fallbackName = "卡${simNameCache.size + 1}"
        simNameCache[subId] = fallbackName
        return fallbackName
    }

    private fun queryLatestIncomingSms(threadId: Long): IncomingMessageRef? {
        return try {
            contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(
                    Telephony.Sms._ID,
                    Telephony.Sms.DATE
                ),
                "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.TYPE} = ?",
                arrayOf(
                    threadId.toString(),
                    Telephony.Sms.MESSAGE_TYPE_INBOX.toString()
                ),
                "${Telephony.Sms.DATE} DESC LIMIT 1"
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    IncomingMessageRef(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms._ID)),
                        timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)),
                        isMms = false
                    )
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TELEPHONY_TAG, "failed to query latest incoming sms", e)
            null
        }
    }

    private fun queryLatestIncomingMms(threadId: Long): IncomingMessageRef? {
        return try {
            contentResolver.query(
                Telephony.Mms.CONTENT_URI,
                arrayOf(
                    Telephony.Mms._ID,
                    Telephony.Mms.DATE
                ),
                "${Telephony.Mms.THREAD_ID} = ? AND ${Telephony.Mms.MESSAGE_BOX} = ?",
                arrayOf(
                    threadId.toString(),
                    Telephony.Mms.MESSAGE_BOX_INBOX.toString()
                ),
                "${Telephony.Mms.DATE} DESC LIMIT 1"
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    IncomingMessageRef(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Mms._ID)),
                        timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Mms.DATE)) * 1000,
                        isMms = true
                    )
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TELEPHONY_TAG, "failed to query latest incoming mms", e)
            null
        }
    }

    private fun buildThreadSelection(
        column: String,
        threadIds: List<Long>
    ): Pair<String, Array<String>> {
        val placeholders = threadIds.joinToString(",") { "?" }
        return "$column IN ($placeholders)" to threadIds.map { it.toString() }.toTypedArray()
    }

    private companion object {
        const val MAX_QUERY_ARGS = 900
        const val DEFAULT_INDEX_BATCH_SIZE = 500
    }
}
