package vip.mystery0.pixel.text.data.source

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.net.toUri
import java.nio.charset.Charset

private const val TELEPHONY_TAG = "TelephonyDataSource"

data class SmsConversationRow(
    val threadId: Long,
    val address: String,
    val body: String,
    val date: Long,
    val read: Boolean
)

data class MmsConversationRow(
    val mmsId: Long,
    val threadId: Long,
    val date: Long,
    val read: Boolean,
    val subject: String?,
    val address: String,
    val textContent: String
)

data class SmsMessageRow(
    val id: Long,
    val threadId: Long,
    val address: String,
    val body: String,
    val date: Long,
    val subId: Int,
    val isReceived: Boolean
)

data class MmsMessageRow(
    val mmsId: Long,
    val threadId: Long,
    val date: Long,
    val subId: Int,
    val isReceived: Boolean,
    val subject: String?,
    val address: String,
    val textContent: String,
    val imageUris: List<String>
)

data class SpamScanMessageRow(
    val messageId: Long,
    val threadId: Long,
    val address: String,
    val content: String
)

class TelephonyDataSource(
    private val context: Context,
    private val contentResolver: ContentResolver
) {
    private val simNameCache = mutableMapOf<Int, String>()

    fun searchConversationThreadIds(query: String, maxResults: Int = 50): List<Long> {
        val threadIds = linkedSetOf<Long>()

        contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.THREAD_ID),
            "${Telephony.Sms.ADDRESS} LIKE ? OR ${Telephony.Sms.BODY} LIKE ?",
            arrayOf("%$query%", "%$query%"),
            "${Telephony.Sms.DATE} DESC"
        )?.use { cursor ->
            val threadIdIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
            while (cursor.moveToNext()) {
                threadIds.add(cursor.getLong(threadIdIndex))
                if (threadIds.size >= maxResults) break
            }
        }

        if (threadIds.size < maxResults) {
            try {
                contentResolver.query(
                    Telephony.Mms.CONTENT_URI,
                    arrayOf(Telephony.Mms.THREAD_ID),
                    "${Telephony.Mms.SUBJECT} LIKE ?",
                    arrayOf("%$query%"),
                    "${Telephony.Mms.DATE} DESC"
                )?.use { cursor ->
                    val threadIdIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.THREAD_ID)
                    while (cursor.moveToNext()) {
                        threadIds.add(cursor.getLong(threadIdIndex))
                        if (threadIds.size >= maxResults) break
                    }
                }
            } catch (e: Exception) {
                Log.e(TELEPHONY_TAG, "failed to search mms conversations", e)
            }
        }

        return threadIds.toList()
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

    fun queryConversationThreadIds(): List<Long> {
        val threadIds = mutableListOf<Long>()
        contentResolver.query(
            "content://mms-sms/conversations?simple=true".toUri(),
            arrayOf("_id"),
            null,
            null,
            "date DESC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow("_id")
            while (cursor.moveToNext()) threadIds.add(cursor.getLong(idIndex))
        }
        return threadIds
    }

    fun fetchConversationSmsRows(threadIds: List<Long>): List<SmsConversationRow> {
        if (threadIds.isEmpty()) return emptyList()

        val (selection, selectionArgs) = buildThreadSelection(Telephony.Sms.THREAD_ID, threadIds)
        val rows = mutableListOf<SmsConversationRow>()
        contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(
                Telephony.Sms.THREAD_ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.READ
            ),
            selection,
            selectionArgs,
            "${Telephony.Sms.DATE} DESC"
        )?.use { cursor ->
            val threadIdIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
            val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val readIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.READ)

            while (cursor.moveToNext()) {
                rows += SmsConversationRow(
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

    fun fetchConversationMmsRows(threadIds: List<Long>): List<MmsConversationRow> {
        if (threadIds.isEmpty()) return emptyList()

        return try {
            val (selection, selectionArgs) = buildThreadSelection(Telephony.Mms.THREAD_ID, threadIds)
            val rows = mutableListOf<MmsConversationRow>()
            contentResolver.query(
                Telephony.Mms.CONTENT_URI,
                arrayOf(
                    Telephony.Mms._ID,
                    Telephony.Mms.THREAD_ID,
                    Telephony.Mms.DATE,
                    Telephony.Mms.READ,
                    Telephony.Mms.SUBJECT,
                    Telephony.Mms.SUBJECT_CHARSET
                ),
                selection,
                selectionArgs,
                "${Telephony.Mms.DATE} DESC"
            )?.use { cursor ->
                val mmsIdIndex = cursor.getColumnIndexOrThrow(Telephony.Mms._ID)
                val threadIdIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.THREAD_ID)
                val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.DATE)
                val readIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.READ)
                val subjectIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.SUBJECT)
                val subjectCsIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.SUBJECT_CHARSET)

                while (cursor.moveToNext()) {
                    val mmsId = cursor.getLong(mmsIdIndex)
                    rows += MmsConversationRow(
                        mmsId = mmsId,
                        threadId = cursor.getLong(threadIdIndex),
                        date = cursor.getLong(dateIndex) * 1000,
                        read = cursor.getInt(readIndex) == 1,
                        subject = decodeMmsSubject(
                            cursor.getString(subjectIndex),
                            cursor.getInt(subjectCsIndex)
                        ),
                        address = getMmsAddress(mmsId),
                        textContent = getMmsTextContent(mmsId)
                    )
                }
            }
            rows
        } catch (e: Exception) {
            Log.e(TELEPHONY_TAG, "failed to fetch mms conversation details", e)
            emptyList()
        }
    }

    fun searchSmsMessages(query: String): List<SmsMessageRow> {
        val rows = mutableListOf<SmsMessageRow>()
        contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            smsMessageProjection,
            "${Telephony.Sms.BODY} LIKE ?",
            arrayOf("%$query%"),
            "${Telephony.Sms.DATE} DESC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                rows += cursor.toSmsMessageRow()
            }
        }
        return rows
    }

    fun searchMmsMessages(query: String): List<MmsMessageRow> {
        return try {
            val rows = mutableListOf<MmsMessageRow>()
            val matchedMmsIds = mutableSetOf<Long>()

            contentResolver.query(
                "content://mms/part".toUri(),
                arrayOf("mid", "text"),
                "ct = 'text/plain' AND text LIKE ?",
                arrayOf("%$query%"),
                null
            )?.use { cursor ->
                val midIndex = cursor.getColumnIndexOrThrow("mid")
                while (cursor.moveToNext()) {
                    val mmsId = cursor.getLong(midIndex)
                    if (!matchedMmsIds.add(mmsId)) continue
                    getMmsMessageRow(mmsId)?.let { rows += it }
                }
            }

            contentResolver.query(
                Telephony.Mms.CONTENT_URI,
                mmsMessageProjection,
                "${Telephony.Mms.SUBJECT} LIKE ?",
                arrayOf("%$query%"),
                "${Telephony.Mms.DATE} DESC"
            )?.use { cursor ->
                val mmsIdIndex = cursor.getColumnIndexOrThrow(Telephony.Mms._ID)
                while (cursor.moveToNext()) {
                    val mmsId = cursor.getLong(mmsIdIndex)
                    if (!matchedMmsIds.add(mmsId)) continue
                    rows += cursor.toMmsMessageRow(mmsId)
                }
            }
            rows
        } catch (e: Exception) {
            Log.e(TELEPHONY_TAG, "failed to search mms messages", e)
            emptyList()
        }
    }

    fun getSmsMessagesByThread(threadId: Long, totalNeeded: Int): List<SmsMessageRow> {
        val rows = mutableListOf<SmsMessageRow>()
        contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            smsMessageProjection,
            "${Telephony.Sms.THREAD_ID} = ?",
            arrayOf(threadId.toString()),
            "${Telephony.Sms.DATE} DESC LIMIT $totalNeeded"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                rows += cursor.toSmsMessageRow(threadIdOverride = threadId)
            }
        }
        return rows
    }

    fun getMmsMessagesByThread(threadId: Long, totalNeeded: Int): List<MmsMessageRow> {
        return try {
            val rows = mutableListOf<MmsMessageRow>()
            contentResolver.query(
                Telephony.Mms.CONTENT_URI,
                mmsMessageProjection,
                "${Telephony.Mms.THREAD_ID} = ?",
                arrayOf(threadId.toString()),
                "${Telephony.Mms.DATE} DESC LIMIT $totalNeeded"
            )?.use { cursor ->
                val mmsIdIndex = cursor.getColumnIndexOrThrow(Telephony.Mms._ID)
                while (cursor.moveToNext()) {
                    rows += cursor.toMmsMessageRow(cursor.getLong(mmsIdIndex))
                }
            }
            rows
        } catch (e: Exception) {
            Log.e(TELEPHONY_TAG, "failed to query mms messages, threadId=$threadId", e)
            emptyList()
        }
    }

    fun getAllSmsMessages(): List<SmsMessageRow> {
        val rows = mutableListOf<SmsMessageRow>()
        contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            smsMessageProjection,
            null,
            null,
            "${Telephony.Sms.DATE} DESC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                rows += cursor.toSmsMessageRow()
            }
        }
        return rows
    }

    fun getAllMmsMessages(): List<MmsMessageRow> {
        return try {
            val rows = mutableListOf<MmsMessageRow>()
            contentResolver.query(
                Telephony.Mms.CONTENT_URI,
                mmsMessageProjection,
                null,
                null,
                "${Telephony.Mms.DATE} DESC"
            )?.use { cursor ->
                val mmsIdIndex = cursor.getColumnIndexOrThrow(Telephony.Mms._ID)
                while (cursor.moveToNext()) {
                    rows += cursor.toMmsMessageRow(cursor.getLong(mmsIdIndex))
                }
            }
            rows
        } catch (e: Exception) {
            Log.e(TELEPHONY_TAG, "failed to query all mms messages", e)
            emptyList()
        }
    }

    fun getSmsMessagesForSpamScan(): List<SpamScanMessageRow> {
        val rows = mutableListOf<SpamScanMessageRow>()
        contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.THREAD_ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY
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
                    messageId = cursor.getLong(idIndex),
                    threadId = cursor.getLong(threadIdIndex),
                    address = cursor.getString(addressIndex).orEmpty(),
                    content = cursor.getString(bodyIndex).orEmpty()
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

    fun markThreadAsRead(threadId: Long) {
        val values = ContentValues().apply {
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
        }

        try {
            contentResolver.update(
                Telephony.Sms.CONTENT_URI,
                values,
                "${Telephony.Sms.THREAD_ID} = ? AND (${Telephony.Sms.READ} = 0 OR ${Telephony.Sms.SEEN} = 0)",
                arrayOf(threadId.toString())
            )
        } catch (e: Exception) {
            Log.e(TELEPHONY_TAG, "error updating sms read status", e)
        }

        try {
            contentResolver.update(
                Telephony.Mms.CONTENT_URI,
                values,
                "${Telephony.Mms.THREAD_ID} = ? AND (${Telephony.Mms.READ} = 0 OR ${Telephony.Mms.SEEN} = 0)",
                arrayOf(threadId.toString())
            )
        } catch (e: Exception) {
            Log.e(TELEPHONY_TAG, "error updating mms read status", e)
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

    private fun getMmsMessageRow(mmsId: Long): MmsMessageRow? {
        contentResolver.query(
            Telephony.Mms.CONTENT_URI,
            mmsMessageProjection,
            "${Telephony.Mms._ID} = ?",
            arrayOf(mmsId.toString()),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.toMmsMessageRow(mmsId)
            }
        }
        return null
    }

    private fun getMmsAddress(mmsId: Long): String {
        try {
            contentResolver.query(
                "content://mms/$mmsId/addr".toUri(),
                arrayOf(Telephony.Mms.Addr.ADDRESS),
                "${Telephony.Mms.Addr.TYPE} = ?",
                arrayOf("137"),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val address = cursor.getString(0)
                    if (!address.isNullOrBlank()) return address
                }
            }

            contentResolver.query(
                "content://mms/$mmsId/addr".toUri(),
                arrayOf(Telephony.Mms.Addr.ADDRESS),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val address = cursor.getString(0)
                    if (!address.isNullOrBlank()) return address
                }
            }
        } catch (e: Exception) {
            Log.e(TELEPHONY_TAG, "failed to get mms address, mmsId=$mmsId", e)
        }
        return ""
    }

    private fun getMmsTextContent(mmsId: Long): String {
        try {
            contentResolver.query(
                "content://mms/$mmsId/part".toUri(),
                arrayOf("text"),
                "ct = ?",
                arrayOf("text/plain"),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val text = cursor.getString(0)
                    if (!text.isNullOrBlank()) return text
                }
            }
        } catch (e: Exception) {
            Log.e(TELEPHONY_TAG, "failed to get mms text, mmsId=$mmsId", e)
        }
        return ""
    }

    private fun getMmsImageUris(mmsId: Long): List<String> {
        val uris = mutableListOf<String>()
        try {
            contentResolver.query(
                "content://mms/$mmsId/part".toUri(),
                arrayOf("_id", "ct"),
                null,
                null,
                null
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow("_id")
                val ctIndex = cursor.getColumnIndexOrThrow("ct")
                while (cursor.moveToNext()) {
                    val contentType = cursor.getString(ctIndex) ?: continue
                    if (contentType.startsWith("image/")) {
                        val partId = cursor.getLong(idIndex)
                        uris.add("content://mms/part/$partId")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TELEPHONY_TAG, "failed to get mms images, mmsId=$mmsId", e)
        }
        return uris
    }

    private fun Cursor.toSmsMessageRow(threadIdOverride: Long? = null): SmsMessageRow {
        val type = getInt(getColumnIndexOrThrow(Telephony.Sms.TYPE))
        return SmsMessageRow(
            id = getLong(getColumnIndexOrThrow(Telephony.Sms._ID)),
            threadId = threadIdOverride ?: getLong(getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)),
            address = getString(getColumnIndexOrThrow(Telephony.Sms.ADDRESS)).orEmpty(),
            body = getString(getColumnIndexOrThrow(Telephony.Sms.BODY)).orEmpty(),
            date = getLong(getColumnIndexOrThrow(Telephony.Sms.DATE)),
            subId = getInt(getColumnIndexOrThrow(Telephony.Sms.SUBSCRIPTION_ID)),
            isReceived = type == Telephony.Sms.MESSAGE_TYPE_INBOX
        )
    }

    private fun Cursor.toMmsMessageRow(mmsId: Long): MmsMessageRow {
        val messageBox = getInt(getColumnIndexOrThrow(Telephony.Mms.MESSAGE_BOX))
        val subject = decodeMmsSubject(
            getString(getColumnIndexOrThrow(Telephony.Mms.SUBJECT)),
            getInt(getColumnIndexOrThrow(Telephony.Mms.SUBJECT_CHARSET))
        )
        return MmsMessageRow(
            mmsId = mmsId,
            threadId = getLong(getColumnIndexOrThrow(Telephony.Mms.THREAD_ID)),
            date = getLong(getColumnIndexOrThrow(Telephony.Mms.DATE)) * 1000,
            subId = getInt(getColumnIndexOrThrow(Telephony.Mms.SUBSCRIPTION_ID)),
            isReceived = messageBox == Telephony.Mms.MESSAGE_BOX_INBOX,
            subject = subject,
            address = getMmsAddress(mmsId),
            textContent = getMmsTextContent(mmsId),
            imageUris = getMmsImageUris(mmsId),
        )
    }

    private fun buildThreadSelection(
        column: String,
        threadIds: List<Long>
    ): Pair<String, Array<String>> {
        val placeholders = threadIds.joinToString(",") { "?" }
        return "$column IN ($placeholders)" to threadIds.map { it.toString() }.toTypedArray()
    }

    private fun decodeMmsSubject(raw: String?, charsetCode: Int): String? {
        if (raw.isNullOrBlank()) return null
        return try {
            val charsetName = when (charsetCode) {
                106 -> "UTF-8"
                4 -> "ISO-8859-1"
                else -> "UTF-8"
            }
            String(raw.toByteArray(Charsets.ISO_8859_1), Charset.forName(charsetName))
        } catch (e: Exception) {
            Log.w(TELEPHONY_TAG, "failed to decode mms subject", e)
            raw
        }
    }

    private companion object {
        const val MAX_QUERY_ARGS = 900

        val smsMessageProjection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.SUBSCRIPTION_ID,
            Telephony.Sms.TYPE
        )

        val mmsMessageProjection = arrayOf(
            Telephony.Mms._ID,
            Telephony.Mms.THREAD_ID,
            Telephony.Mms.DATE,
            Telephony.Mms.SUBSCRIPTION_ID,
            Telephony.Mms.MESSAGE_BOX,
            Telephony.Mms.SUBJECT,
            Telephony.Mms.SUBJECT_CHARSET
        )
    }
}
