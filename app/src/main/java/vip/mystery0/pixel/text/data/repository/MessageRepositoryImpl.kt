package vip.mystery0.pixel.text.data.repository

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import vip.mystery0.pixel.text.domain.model.ConversationModel
import vip.mystery0.pixel.text.domain.model.MessageModel
import vip.mystery0.pixel.text.domain.model.ParsedResult
import vip.mystery0.pixel.text.domain.parser.MessageParser
import vip.mystery0.pixel.text.domain.repository.MessageRepository

class MessageRepositoryImpl(
    private val context: Context,
    private val messageParser: MessageParser
) : MessageRepository {

    override fun getConversations(limit: Int, offset: Int): Flow<List<ConversationModel>> = flow {
        val threadIds = mutableListOf<Long>()

        // 1. 先从 conversations 视图获取 thread_id 列表，这非常快且支持分页
        context.contentResolver.query(
            Uri.parse("content://sms/conversations"),
            arrayOf(Telephony.Sms.THREAD_ID),
            null,
            null,
            "${Telephony.Sms.DATE} DESC LIMIT $limit OFFSET $offset"
        )?.use { cursor ->
            val threadIdIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
            while (cursor.moveToNext()) {
                threadIds.add(cursor.getLong(threadIdIndex))
            }
        }

        if (threadIds.isEmpty()) {
            emit(emptyList())
            return@flow
        }

        emit(fetchConversationDetails(threadIds))
    }.flowOn(Dispatchers.IO)

    override fun searchConversations(query: String): Flow<List<ConversationModel>> = flow {
        val threadIds = mutableSetOf<Long>()

        // 同时搜索地址和内容
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.THREAD_ID),
            "${Telephony.Sms.ADDRESS} LIKE ? OR ${Telephony.Sms.BODY} LIKE ?",
            arrayOf("%$query%", "%$query%"),
            "${Telephony.Sms.DATE} DESC"
        )?.use { cursor ->
            val threadIdIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
            while (cursor.moveToNext()) {
                threadIds.add(cursor.getLong(threadIdIndex))
                if (threadIds.size > 50) break // 搜索结果限制前 50 条
            }
        }

        if (threadIds.isEmpty()) {
            emit(emptyList())
            return@flow
        }

        emit(fetchConversationDetails(threadIds.toList()))
    }.flowOn(Dispatchers.IO)

    private fun fetchConversationDetails(threadIds: List<Long>): List<ConversationModel> {
        val messagesMap = mutableMapOf<Long, ConversationModel>()
        val idString = threadIds.joinToString(",")

        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(
                Telephony.Sms.THREAD_ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.READ
            ),
            "${Telephony.Sms.THREAD_ID} IN ($idString)",
            null,
            "${Telephony.Sms.DATE} DESC"
        )?.use { cursor ->
            val threadIdIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
            val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val readIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.READ)

            while (cursor.moveToNext()) {
                val threadId = cursor.getLong(threadIdIndex)
                val read = cursor.getInt(readIndex) == 1

                if (!messagesMap.containsKey(threadId)) {
                    val address = cursor.getString(addressIndex) ?: ""
                    val body = cursor.getString(bodyIndex) ?: ""
                    val date = cursor.getLong(dateIndex)
                    messagesMap[threadId] = ConversationModel(
                        threadId = threadId,
                        address = address,
                        snippet = body,
                        timestamp = date,
                        unreadCount = if (read) 0 else 1
                    )
                } else {
                    if (!read) {
                        val existing = messagesMap[threadId]!!
                        messagesMap[threadId] =
                            existing.copy(unreadCount = existing.unreadCount + 1)
                    }
                }
            }
        }
        return threadIds.mapNotNull { messagesMap[it] }
    }

    private val simNameCache = mutableMapOf<Int, String>()

    private fun getSimName(subId: Int): String {
        if (subId <= 0) return "默认卡"
        if (simNameCache.containsKey(subId)) {
            return simNameCache[subId]!!
        }

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
            Log.e("SIM_INFO", "Exception reading TelephonyManager for subId $subId", e)
        }

        try {
            val cursor = context.contentResolver.query(
                "content://telephony/siminfo".toUri(),
                null,
                "_id = ?",
                arrayOf(subId.toString()),
                null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val carrierNameIdx = it.getColumnIndex("carrier_name")
                    val carrierName =
                        if (carrierNameIdx >= 0) it.getString(carrierNameIdx) else null

                    val displayNameIdx = it.getColumnIndex("display_name")
                    val displayName =
                        if (displayNameIdx >= 0) it.getString(displayNameIdx) else null

                    val simIdIdx = it.getColumnIndex("sim_id")
                    val simId = if (simIdIdx >= 0) it.getInt(simIdIdx) else simNameCache.size

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
            Log.e("SIM_INFO", "Exception while querying siminfo for subId $subId", e)
        }

        val fallbackName = "卡${simNameCache.size + 1}"
        simNameCache[subId] = fallbackName
        return fallbackName
    }

    override fun searchMessages(query: String): Flow<List<MessageModel>> = flow {
        val messages = mutableListOf<MessageModel>()
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.THREAD_ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.SUBSCRIPTION_ID,
                Telephony.Sms.TYPE
            ),
            "${Telephony.Sms.BODY} LIKE ?",
            arrayOf("%$query%"),
            "${Telephony.Sms.DATE} DESC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
            val threadIdIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
            val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val subIdIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.SUBSCRIPTION_ID)
            val typeIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex)
                val threadId = cursor.getLong(threadIdIndex)
                val address = cursor.getString(addressIndex) ?: ""
                val body = cursor.getString(bodyIndex) ?: ""
                val date = cursor.getLong(dateIndex)
                val subId = cursor.getInt(subIdIndex)
                val type = cursor.getInt(typeIndex)

                messages.add(
                    MessageModel(
                        id = id,
                        threadId = threadId,
                        sender = address,
                        content = body,
                        timestamp = date,
                        simName = getSimName(subId),
                        isReceived = type == Telephony.Sms.MESSAGE_TYPE_INBOX,
                        parsedResult = ParsedResult.None // 搜索模式暂不解析
                    )
                )
            }
        }
        emit(messages)
    }.flowOn(Dispatchers.IO)

    override fun getMessagesByThread(
        threadId: Long,
        limit: Int,
        offset: Int
    ): Flow<List<MessageModel>> = flow {
        val messages = mutableListOf<MessageModel>()
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.SUBSCRIPTION_ID,
                Telephony.Sms.TYPE
            ),
            "${Telephony.Sms.THREAD_ID} = ?",
            arrayOf(threadId.toString()),
            "${Telephony.Sms.DATE} DESC LIMIT $limit OFFSET $offset"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
            val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val subIdIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.SUBSCRIPTION_ID)
            val typeIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
            
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex)
                val address = cursor.getString(addressIndex) ?: ""
                val body = cursor.getString(bodyIndex) ?: ""
                val date = cursor.getLong(dateIndex)
                val subId = cursor.getInt(subIdIndex)
                val type = cursor.getInt(typeIndex)
                
                messages.add(
                    MessageModel(
                        id = id,
                        threadId = threadId,
                        sender = address,
                        content = body,
                        timestamp = date,
                        simName = getSimName(subId),
                        isReceived = type == Telephony.Sms.MESSAGE_TYPE_INBOX,
                        parsedResult = messageParser.parse(address, body)
                    )
                )
            }
        }
        emit(messages)
    }.flowOn(Dispatchers.IO)

    override fun getMessages(): Flow<List<MessageModel>> = flow {
        val messages = mutableListOf<MessageModel>()
        val uri = Telephony.Sms.CONTENT_URI
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.SUBSCRIPTION_ID
        )

        context.contentResolver.query(
            uri,
            projection,
            null,
            null,
            "${Telephony.Sms.DATE} DESC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
            val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            // Sim slot is more complex to get via SubscriptionManager, we fallback to default logic for now

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex)
                val threadId = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID))
                val address = cursor.getString(addressIndex) ?: ""
                val body = cursor.getString(bodyIndex) ?: ""
                val date = cursor.getLong(dateIndex)

                messages.add(
                    MessageModel(
                        id = id,
                        threadId = threadId,
                        sender = address,
                        content = body,
                        timestamp = date,
                        simName = "默认卡",
                        parsedResult = messageParser.parse(address, body)
                    )
                )
            }
        }
        emit(messages)
    }.flowOn(Dispatchers.IO)

    override suspend fun markThreadAsRead(threadId: Long) {
        withContext(Dispatchers.IO) {
            val values = ContentValues().apply {
                put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.SEEN, 1)
            }

            try {
                context.contentResolver.update(
                    Telephony.Sms.CONTENT_URI,
                    values,
                    "${Telephony.Sms.THREAD_ID} = ? AND (${Telephony.Sms.READ} = 0 OR ${Telephony.Sms.SEEN} = 0)",
                    arrayOf(threadId.toString())
                )
            } catch (e: Exception) {
                Log.e("MessageRepository", "Error updating SMS read status", e)
            }

            try {
                context.contentResolver.update(
                    Telephony.Mms.CONTENT_URI,
                    values,
                    "${Telephony.Mms.THREAD_ID} = ? AND (${Telephony.Mms.READ} = 0 OR ${Telephony.Mms.SEEN} = 0)",
                    arrayOf(threadId.toString())
                )
            } catch (e: Exception) {
                Log.e("MessageRepository", "Error updating MMS read status", e)
            }
        }
    }
}
