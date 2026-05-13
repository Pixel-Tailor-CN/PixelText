package vip.mystery0.pixel.text.data.repository

import android.content.ContentValues
import android.content.Context
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
import java.nio.charset.Charset

class MessageRepositoryImpl(
    private val context: Context,
    private val messageParser: MessageParser
) : MessageRepository {

    override fun getConversations(limit: Int, offset: Int): Flow<List<ConversationModel>> = flow {
        val threadIds = mutableListOf<Long>()
        context.contentResolver.query(
            "content://mms-sms/conversations?simple=true".toUri(),
            arrayOf("_id"),
            null, null,
            "date DESC LIMIT $limit OFFSET $offset"
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow("_id")
            while (cursor.moveToNext()) threadIds.add(cursor.getLong(idIdx))
        }
        if (threadIds.isEmpty()) {
            emit(emptyList()); return@flow
        }
        emit(fetchConversationDetails(threadIds).sortedByDescending { it.timestamp })
    }.flowOn(Dispatchers.IO)

    override fun searchConversations(query: String): Flow<List<ConversationModel>> = flow {
        val threadIds = mutableSetOf<Long>()

        // 搜索 SMS 地址和内容
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
                if (threadIds.size > 50) break
            }
        }

        // 搜索 MMS 主题
        if (threadIds.size <= 50) {
            try {
                context.contentResolver.query(
                    Telephony.Mms.CONTENT_URI,
                    arrayOf(Telephony.Mms.THREAD_ID),
                    "${Telephony.Mms.SUBJECT} LIKE ?",
                    arrayOf("%$query%"),
                    "${Telephony.Mms.DATE} DESC"
                )?.use { cursor ->
                    val threadIdIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.THREAD_ID)
                    while (cursor.moveToNext()) {
                        threadIds.add(cursor.getLong(threadIdIndex))
                        if (threadIds.size > 50) break
                    }
                }
            } catch (e: Exception) {
                Log.e("MessageRepository", "failed to search MMS conversations", e)
            }
        }

        if (threadIds.isEmpty()) {
            emit(emptyList())
            return@flow
        }

        val sorted = fetchConversationDetails(threadIds.toList())
            .sortedByDescending { it.timestamp }
        emit(sorted)
    }.flowOn(Dispatchers.IO)

    private fun fetchConversationDetails(threadIds: List<Long>): List<ConversationModel> {
        val messagesMap = mutableMapOf<Long, ConversationModel>()
        val idString = threadIds.joinToString(",")

        // 查询 SMS
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

        // 查询 MMS
        try {
            context.contentResolver.query(
                Telephony.Mms.CONTENT_URI,
                arrayOf(
                    Telephony.Mms._ID,
                    Telephony.Mms.THREAD_ID,
                    Telephony.Mms.DATE,
                    Telephony.Mms.READ,
                    Telephony.Mms.SUBJECT,
                    Telephony.Mms.SUBJECT_CHARSET
                ),
                "${Telephony.Mms.THREAD_ID} IN ($idString)",
                null,
                "${Telephony.Mms.DATE} DESC"
            )?.use { cursor ->
                val mmsIdIndex = cursor.getColumnIndexOrThrow(Telephony.Mms._ID)
                val threadIdIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.THREAD_ID)
                val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.DATE)
                val readIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.READ)
                val subjectIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.SUBJECT)
                val subjectCsIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.SUBJECT_CHARSET)

                while (cursor.moveToNext()) {
                    val threadId = cursor.getLong(threadIdIndex)
                    val mmsId = cursor.getLong(mmsIdIndex)
                    val mmsDate = cursor.getLong(dateIndex) * 1000 // 秒→毫秒
                    val read = cursor.getInt(readIndex) == 1
                    val subject = decodeMmsSubject(
                        cursor.getString(subjectIndex),
                        cursor.getInt(subjectCsIndex)
                    )

                    val existing = messagesMap[threadId]
                    if (existing == null) {
                        // MMS-only 会话
                        val address = getMmsAddress(mmsId)
                        val snippet = subject ?: getMmsTextContent(mmsId) ?: ""
                        messagesMap[threadId] = ConversationModel(
                            threadId = threadId,
                            address = address,
                            snippet = snippet,
                            timestamp = mmsDate,
                            unreadCount = if (read) 0 else 1,
                            isMms = true,
                            hasMms = true
                        )
                    } else {
                        if (mmsDate > existing.timestamp) {
                            // MMS 比现有 SMS 更新，更新 snippet 和时间
                            val snippet = subject ?: getMmsTextContent(mmsId) ?: ""
                            val address =
                                if (existing.address.isNotBlank()) existing.address else getMmsAddress(
                                    mmsId
                                )
                            messagesMap[threadId] = existing.copy(
                                snippet = snippet,
                                timestamp = mmsDate,
                                address = address,
                                unreadCount = existing.unreadCount + if (read) 0 else 1,
                                isMms = true,
                                hasMms = true
                            )
                        } else {
                            messagesMap[threadId] = existing.copy(
                                unreadCount = existing.unreadCount + if (read) 0 else 1,
                                hasMms = true
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MessageRepository", "failed to fetch MMS conversation details", e)
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
            Log.e("SIM_INFO", "exception reading TelephonyManager for subId $subId", e)
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
            Log.e("SIM_INFO", "exception while querying siminfo for subId $subId", e)
        }

        val fallbackName = "卡${simNameCache.size + 1}"
        simNameCache[subId] = fallbackName
        return fallbackName
    }

    override fun searchMessages(query: String): Flow<List<MessageModel>> = flow {
        val smsMessages = mutableListOf<MessageModel>()
        val mmsMessages = mutableListOf<MessageModel>()

        // 搜索 SMS
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

                smsMessages.add(
                    MessageModel(
                        id = id,
                        threadId = threadId,
                        sender = address,
                        content = body,
                        timestamp = date,
                        simName = getSimName(subId),
                        isReceived = type == Telephony.Sms.MESSAGE_TYPE_INBOX,
                        parsedResult = ParsedResult.None
                    )
                )
            }
        }

        // 搜索 MMS 主题
        try {
            val matchedMmsIds = mutableSetOf<Long>()

            // 搜索 MMS 正文 (part 表，编码正确，优先搜索)
            context.contentResolver.query(
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
                    addMmsMessageModel(mmsId, mmsMessages)
                }
            }

            // 搜索 MMS 主题（subject 是原始字节，LIKE 匹配乱码字符串，命中率低但作为补充）
            context.contentResolver.query(
                Telephony.Mms.CONTENT_URI,
                arrayOf(
                    Telephony.Mms._ID,
                    Telephony.Mms.THREAD_ID,
                    Telephony.Mms.DATE,
                    Telephony.Mms.SUBSCRIPTION_ID,
                    Telephony.Mms.MESSAGE_BOX,
                    Telephony.Mms.SUBJECT,
                    Telephony.Mms.SUBJECT_CHARSET
                ),
                "${Telephony.Mms.SUBJECT} LIKE ?",
                arrayOf("%$query%"),
                "${Telephony.Mms.DATE} DESC"
            )?.use { cursor ->
                val mmsIdIndex = cursor.getColumnIndexOrThrow(Telephony.Mms._ID)
                val threadIdIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.THREAD_ID)
                val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.DATE)
                val subIdIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.SUBSCRIPTION_ID)
                val messageBoxIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.MESSAGE_BOX)
                val subjectIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.SUBJECT)
                val subjectCsIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.SUBJECT_CHARSET)

                while (cursor.moveToNext()) {
                    val mmsId = cursor.getLong(mmsIdIndex)
                    if (!matchedMmsIds.add(mmsId)) continue
                    val threadId = cursor.getLong(threadIdIndex)
                    val date = cursor.getLong(dateIndex) * 1000
                    val subId = cursor.getInt(subIdIndex)
                    val messageBox = cursor.getInt(messageBoxIndex)
                    val subject = decodeMmsSubject(
                        cursor.getString(subjectIndex),
                        cursor.getInt(subjectCsIndex)
                    )
                    val address = getMmsAddress(mmsId)
                    val textContent = getMmsTextContent(mmsId)

                    mmsMessages.add(
                        MessageModel(
                            id = -mmsId,
                            threadId = threadId,
                            sender = address,
                            content = textContent ?: "",
                            timestamp = date,
                            simName = getSimName(subId),
                            isReceived = messageBox == Telephony.Mms.MESSAGE_BOX_INBOX,
                            parsedResult = ParsedResult.None,
                            imageUris = getMmsImageUris(mmsId),
                            mmsSubject = subject
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("MessageRepository", "failed to search MMS messages", e)
        }

        emit((smsMessages + mmsMessages).sortedByDescending { it.timestamp })
    }.flowOn(Dispatchers.IO)

    override fun getMessagesByThread(
        threadId: Long,
        limit: Int,
        offset: Int
    ): Flow<List<MessageModel>> = flow {
        val totalNeeded = limit + offset
        val smsMessages = mutableListOf<MessageModel>()
        val mmsMessages = mutableListOf<MessageModel>()

        // 查询 SMS
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
            "${Telephony.Sms.DATE} DESC LIMIT $totalNeeded"
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

                smsMessages.add(
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

        // 查询 MMS
        try {
            context.contentResolver.query(
                Telephony.Mms.CONTENT_URI,
                arrayOf(
                    Telephony.Mms._ID,
                    Telephony.Mms.DATE,
                    Telephony.Mms.MESSAGE_BOX,
                    Telephony.Mms.SUBSCRIPTION_ID,
                    Telephony.Mms.SUBJECT,
                    Telephony.Mms.SUBJECT_CHARSET
                ),
                "${Telephony.Mms.THREAD_ID} = ?",
                arrayOf(threadId.toString()),
                "${Telephony.Mms.DATE} DESC LIMIT $totalNeeded"
            )?.use { cursor ->
                val mmsIdIndex = cursor.getColumnIndexOrThrow(Telephony.Mms._ID)
                val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.DATE)
                val messageBoxIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.MESSAGE_BOX)
                val subIdIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.SUBSCRIPTION_ID)
                val subjectIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.SUBJECT)
                val subjectCsIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.SUBJECT_CHARSET)

                while (cursor.moveToNext()) {
                    val mmsId = cursor.getLong(mmsIdIndex)
                    val date = cursor.getLong(dateIndex) * 1000
                    val messageBox = cursor.getInt(messageBoxIndex)
                    val subId = cursor.getInt(subIdIndex)
                    val subject = decodeMmsSubject(
                        cursor.getString(subjectIndex),
                        cursor.getInt(subjectCsIndex)
                    )
                    val address = getMmsAddress(mmsId)
                    val textContent = getMmsTextContent(mmsId)

                    mmsMessages.add(
                        MessageModel(
                            id = -mmsId, // 负数避免与 SMS ID 冲突
                            threadId = threadId,
                            sender = address,
                            content = textContent ?: "",
                            timestamp = date,
                            simName = getSimName(subId),
                            isReceived = messageBox == Telephony.Mms.MESSAGE_BOX_INBOX,
                            parsedResult = messageParser.parse(address, textContent ?: ""),
                            imageUris = getMmsImageUris(mmsId),
                            mmsSubject = subject
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("MessageRepository", "failed to query MMS messages, threadId=$threadId", e)
        }

        // 合并排序并分页
        val merged = (smsMessages + mmsMessages)
            .sortedByDescending { it.timestamp }
            .drop(offset)
            .take(limit)
        emit(merged)
    }.flowOn(Dispatchers.IO)

    override fun getMessages(): Flow<List<MessageModel>> = flow {
        val smsMessages = mutableListOf<MessageModel>()
        val mmsMessages = mutableListOf<MessageModel>()

        // 查询 SMS
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.THREAD_ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.SUBSCRIPTION_ID
            ),
            null,
            null,
            "${Telephony.Sms.DATE} DESC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
            val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val subIdIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.SUBSCRIPTION_ID)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex)
                val threadId = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID))
                val address = cursor.getString(addressIndex) ?: ""
                val body = cursor.getString(bodyIndex) ?: ""
                val date = cursor.getLong(dateIndex)
                val subId = cursor.getInt(subIdIndex)

                smsMessages.add(
                    MessageModel(
                        id = id,
                        threadId = threadId,
                        sender = address,
                        content = body,
                        timestamp = date,
                        simName = getSimName(subId),
                        parsedResult = messageParser.parse(address, body)
                    )
                )
            }
        }

        // 查询 MMS
        try {
            context.contentResolver.query(
                Telephony.Mms.CONTENT_URI,
                arrayOf(
                    Telephony.Mms._ID,
                    Telephony.Mms.THREAD_ID,
                    Telephony.Mms.DATE,
                    Telephony.Mms.SUBSCRIPTION_ID,
                    Telephony.Mms.MESSAGE_BOX,
                    Telephony.Mms.SUBJECT,
                    Telephony.Mms.SUBJECT_CHARSET
                ),
                null,
                null,
                "${Telephony.Mms.DATE} DESC"
            )?.use { cursor ->
                val mmsIdIndex = cursor.getColumnIndexOrThrow(Telephony.Mms._ID)
                val threadIdIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.THREAD_ID)
                val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.DATE)
                val subIdIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.SUBSCRIPTION_ID)
                val messageBoxIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.MESSAGE_BOX)
                val subjectIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.SUBJECT)
                val subjectCsIndex = cursor.getColumnIndexOrThrow(Telephony.Mms.SUBJECT_CHARSET)

                while (cursor.moveToNext()) {
                    val mmsId = cursor.getLong(mmsIdIndex)
                    val threadId = cursor.getLong(threadIdIndex)
                    val date = cursor.getLong(dateIndex) * 1000
                    val subId = cursor.getInt(subIdIndex)
                    val messageBox = cursor.getInt(messageBoxIndex)
                    val subject = decodeMmsSubject(
                        cursor.getString(subjectIndex),
                        cursor.getInt(subjectCsIndex)
                    )
                    val address = getMmsAddress(mmsId)
                    val textContent = getMmsTextContent(mmsId)

                    mmsMessages.add(
                        MessageModel(
                            id = -mmsId,
                            threadId = threadId,
                            sender = address,
                            content = textContent ?: "",
                            timestamp = date,
                            simName = getSimName(subId),
                            isReceived = messageBox == Telephony.Mms.MESSAGE_BOX_INBOX,
                            parsedResult = messageParser.parse(address, textContent ?: ""),
                            imageUris = getMmsImageUris(mmsId),
                            mmsSubject = subject
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("MessageRepository", "failed to query all MMS messages", e)
        }

        emit((smsMessages + mmsMessages).sortedByDescending { it.timestamp })
    }.flowOn(Dispatchers.IO)

    private fun getMmsAddress(mmsId: Long): String {
        try {
            context.contentResolver.query(
                "content://mms/$mmsId/addr".toUri(),
                arrayOf(Telephony.Mms.Addr.ADDRESS),
                "${Telephony.Mms.Addr.TYPE} = ?",
                arrayOf("137"), // PduHeaders.FROM
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val address = cursor.getString(0)
                    if (!address.isNullOrBlank()) return address
                }
            }
            // FROM 没查到，尝试不限 type
            context.contentResolver.query(
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
            Log.e("MessageRepository", "failed to get MMS address, mmsId=$mmsId", e)
        }
        return ""
    }

    private fun getMmsTextContent(mmsId: Long): String {
        try {
            context.contentResolver.query(
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
            Log.e("MessageRepository", "failed to get MMS text, mmsId=$mmsId", e)
        }
        return ""
    }

    private fun getMmsImageUris(mmsId: Long): List<String> {
        val uris = mutableListOf<String>()
        try {
            context.contentResolver.query(
                "content://mms/$mmsId/part".toUri(),
                arrayOf("_id", "ct"),
                null,
                null,
                null
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow("_id")
                val ctIndex = cursor.getColumnIndexOrThrow("ct")
                while (cursor.moveToNext()) {
                    val ct = cursor.getString(ctIndex) ?: continue
                    if (ct.startsWith("image/")) {
                        val partId = cursor.getLong(idIndex)
                        uris.add("content://mms/part/$partId")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MessageRepository", "failed to get MMS images, mmsId=$mmsId", e)
        }
        return uris
    }

    private fun addMmsMessageModel(mmsId: Long, target: MutableList<MessageModel>) {
        context.contentResolver.query(
            Telephony.Mms.CONTENT_URI,
            arrayOf(
                Telephony.Mms._ID,
                Telephony.Mms.THREAD_ID,
                Telephony.Mms.DATE,
                Telephony.Mms.SUBSCRIPTION_ID,
                Telephony.Mms.MESSAGE_BOX,
                Telephony.Mms.SUBJECT,
                Telephony.Mms.SUBJECT_CHARSET
            ),
            "${Telephony.Mms._ID} = ?",
            arrayOf(mmsId.toString()),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val threadId = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Mms.THREAD_ID))
                val date = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Mms.DATE)) * 1000
                val subId =
                    cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Mms.SUBSCRIPTION_ID))
                val messageBox =
                    cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Mms.MESSAGE_BOX))
                val subject = decodeMmsSubject(
                    cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Mms.SUBJECT)),
                    cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Mms.SUBJECT_CHARSET))
                )
                val address = getMmsAddress(mmsId)
                val textContent = getMmsTextContent(mmsId)

                target.add(
                    MessageModel(
                        id = -mmsId,
                        threadId = threadId,
                        sender = address,
                        content = textContent ?: "",
                        timestamp = date,
                        simName = getSimName(subId),
                        isReceived = messageBox == Telephony.Mms.MESSAGE_BOX_INBOX,
                        parsedResult = ParsedResult.None,
                        imageUris = getMmsImageUris(mmsId),
                        mmsSubject = subject
                    )
                )
            }
        }
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
        } catch (_: Exception) {
            raw
        }
    }

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
                Log.e("MessageRepository", "error updating SMS read status", e)
            }

            try {
                context.contentResolver.update(
                    Telephony.Mms.CONTENT_URI,
                    values,
                    "${Telephony.Mms.THREAD_ID} = ? AND (${Telephony.Mms.READ} = 0 OR ${Telephony.Mms.SEEN} = 0)",
                    arrayOf(threadId.toString())
                )
            } catch (e: Exception) {
                Log.e("MessageRepository", "error updating MMS read status", e)
            }
        }
    }
}
