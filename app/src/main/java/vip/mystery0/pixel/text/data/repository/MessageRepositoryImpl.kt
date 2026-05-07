package vip.mystery0.pixel.text.data.repository

import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.telephony.TelephonyManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import vip.mystery0.pixel.text.domain.model.ConversationModel
import vip.mystery0.pixel.text.domain.model.MessageModel
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

        // 2. 针对这批 thread_id，从短信表中获取最新的消息详情和未读数
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
                    // 因为是按 DATE DESC 排序，所以第一条遇到的就是该会话的最新的消息
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
                    // 后续遇到的同一 threadId 的消息，只累加未读数
                    if (!read) {
                        val existing = messagesMap[threadId]!!
                        messagesMap[threadId] =
                            existing.copy(unreadCount = existing.unreadCount + 1)
                    }
                }
            }
        }

        // 3. 按照 threadIds 的原始顺序（时间倒序）发射结果
        val sortedResult = threadIds.mapNotNull { messagesMap[it] }
        emit(sortedResult)
    }.flowOn(Dispatchers.IO)

    private val simNameCache = mutableMapOf<Int, String>()

    private fun getSimName(subId: Int): String {
        Log.d("SIM_INFO", "getSimName called with subId: $subId")
        if (subId <= 0) return "默认卡"
        if (simNameCache.containsKey(subId)) {
            Log.d("SIM_INFO", "Cache hit for subId $subId: ${simNameCache[subId]}")
            return simNameCache[subId]!!
        }

        try {
            val telephonyManager =
                context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val subTelephonyManager = telephonyManager.createForSubscriptionId(subId)

            val networkName = subTelephonyManager.networkOperatorName
            val simName = subTelephonyManager.simOperatorName

            Log.d(
                "SIM_INFO",
                "TelephonyManager API - networkName: $networkName, simName: $simName for subId: $subId"
            )

            val resolvedName = if (!simName.isNullOrBlank()) {
                simName
            } else if (!networkName.isNullOrBlank()) {
                networkName
            } else {
                null
            }

            if (resolvedName != null && resolvedName.length > 1) {
                Log.d("SIM_INFO", "Resolved name via TelephonyManager: $resolvedName")
                simNameCache[subId] = resolvedName
                return resolvedName
            }
        } catch (e: Exception) {
            Log.e("SIM_INFO", "Exception reading TelephonyManager for subId $subId", e)
        }

        try {
            Log.d("SIM_INFO", "Attempting to query content://telephony/siminfo for subId: $subId")
            val cursor = context.contentResolver.query(
                Uri.parse("content://telephony/siminfo"),
                null,
                "_id = ?",
                arrayOf(subId.toString()),
                null
            )
            cursor?.use {
                Log.d("SIM_INFO", "Cursor returned for subId $subId. Count: ${it.count}")
                if (it.moveToFirst()) {
                    val columns = it.columnNames
                    val columnData = columns.joinToString(", ") { col ->
                        val idx = it.getColumnIndex(col)
                        val value = if (idx >= 0) {
                            when (it.getType(idx)) {
                                android.database.Cursor.FIELD_TYPE_STRING -> it.getString(idx)
                                android.database.Cursor.FIELD_TYPE_INTEGER -> it.getInt(idx)
                                    .toString()

                                else -> "other_type"
                            }
                        } else "not_found"
                        "$col=$value"
                    }
                    Log.d("SIM_INFO", "Row data for subId $subId: $columnData")

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
                    Log.d("SIM_INFO", "Resolved name for subId $subId: $name")
                    simNameCache[subId] = name
                    return name
                }
            }
        } catch (e: Exception) {
            Log.e("SIM_INFO", "Exception while querying siminfo for subId $subId", e)
        }

        val fallbackName = "卡${simNameCache.size + 1}"
        Log.d("SIM_INFO", "Fallback triggered for subId $subId. Assigning: $fallbackName")
        simNameCache[subId] = fallbackName
        return fallbackName
    }

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
                val address = cursor.getString(addressIndex) ?: ""
                val body = cursor.getString(bodyIndex) ?: ""
                val date = cursor.getLong(dateIndex)

                messages.add(
                    MessageModel(
                        id = id,
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
}
