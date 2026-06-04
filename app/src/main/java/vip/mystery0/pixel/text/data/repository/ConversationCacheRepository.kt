package vip.mystery0.pixel.text.data.repository

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vip.mystery0.pixel.text.data.db.CachedConversationDao
import vip.mystery0.pixel.text.data.db.toCachedConversationEntity
import vip.mystery0.pixel.text.data.db.toConversationModel
import vip.mystery0.pixel.text.data.source.TelephonyDataSource
import vip.mystery0.pixel.text.domain.model.ConversationModel

private const val TAG = "ConversationCacheRepo"

class ConversationCacheRepository(
    private val context: Context,
    private val dao: CachedConversationDao,
    private val telephonyDataSource: TelephonyDataSource,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            scope.launch { syncChangedThreads(uri) }
        }
    }

    fun startObserving() {
        context.contentResolver.registerContentObserver(
            Telephony.Sms.CONTENT_URI, true, observer
        )
        context.contentResolver.registerContentObserver(
            Telephony.Mms.CONTENT_URI, true, observer
        )
    }

    fun stopObserving() {
        context.contentResolver.unregisterContentObserver(observer)
    }

    suspend fun isCacheReady(): Boolean = withContext(Dispatchers.IO) {
        dao.count() > 0
    }

    suspend fun fullSync(archivedThreadIds: Set<Long>) = withContext(Dispatchers.IO) {
        Log.d(TAG, "starting full sync")
        val allThreadIds = telephonyDataSource.queryConversationThreadIds()
            .distinct()
            .filter { it !in archivedThreadIds }

        if (allThreadIds.isEmpty()) {
            dao.deleteAll()
            return@withContext
        }

        val conversations = fetchAndBuildConversations(allThreadIds)
        val cachedThreadIds = dao.getAllThreadIds().toSet()
        val deletedIds = cachedThreadIds - allThreadIds.toSet()

        dao.upsert(conversations.map { it.toCachedConversationEntity() })
        if (deletedIds.isNotEmpty()) dao.delete(deletedIds)
        Log.d(TAG, "full sync done: upserted=${conversations.size} deleted=${deletedIds.size}")
    }

    private suspend fun syncChangedThreads(uri: Uri?) {
        // 从 URI 提取受影响的 threadId（如有），否则做局部刷新
        val threadId = uri?.lastPathSegment?.toLongOrNull()
        if (threadId != null) {
            syncThreads(listOf(threadId))
        } else {
            // URI 不含具体 ID，刷新最近变化的少量会话（取前50个）
            val recentThreadIds = telephonyDataSource.queryConversationThreadIds()
                .take(50)
            if (recentThreadIds.isEmpty()) return
            syncThreads(recentThreadIds)
        }
    }

    private suspend fun syncThreads(threadIds: List<Long>) {
        val conversations = fetchAndBuildConversations(threadIds)
        if (conversations.isEmpty()) {
            dao.delete(threadIds.toSet())
        } else {
            dao.upsert(conversations.map { it.toCachedConversationEntity() })
            val fetchedIds = conversations.map { it.threadId }.toSet()
            val missingIds = threadIds.toSet() - fetchedIds
            if (missingIds.isNotEmpty()) dao.delete(missingIds)
        }
    }

    private fun fetchAndBuildConversations(threadIds: List<Long>): List<ConversationModel> {
        val map = mutableMapOf<Long, ConversationModel>()

        telephonyDataSource.fetchConversationSmsRows(threadIds).forEach { row ->
            val existing = map[row.threadId]
            if (existing == null) {
                map[row.threadId] = ConversationModel(
                    threadId = row.threadId,
                    address = row.address,
                    displayName = null,
                    snippet = row.body,
                    timestamp = row.date,
                    unreadCount = if (row.read) 0 else 1
                )
            } else if (!row.read) {
                map[row.threadId] = existing.copy(unreadCount = existing.unreadCount + 1)
            }
        }

        telephonyDataSource.fetchConversationMmsRows(threadIds).forEach { row ->
            val existing = map[row.threadId]
            if (existing == null) {
                map[row.threadId] = ConversationModel(
                    threadId = row.threadId,
                    address = row.address,
                    displayName = null,
                    snippet = row.subject ?: row.textContent,
                    timestamp = row.date,
                    unreadCount = if (row.read) 0 else 1,
                    isMms = true,
                    hasMms = true
                )
            } else if (row.date > existing.timestamp) {
                val address = if (existing.address.isNotBlank()) existing.address else row.address
                map[row.threadId] = existing.copy(
                    snippet = row.subject ?: row.textContent,
                    timestamp = row.date,
                    address = address,
                    unreadCount = existing.unreadCount + if (row.read) 0 else 1,
                    isMms = true,
                    hasMms = true
                )
            } else {
                map[row.threadId] = existing.copy(
                    unreadCount = existing.unreadCount + if (row.read) 0 else 1,
                    hasMms = true
                )
            }
        }

        return threadIds.mapNotNull { map[it] }
    }

    suspend fun getAllConversations(
        archivedThreadIds: Set<Long>,
        hiddenThreadIds: Set<Long> = emptySet()
    ): List<ConversationModel> = withContext(Dispatchers.IO) {
        dao.getAllConversations()
            .filter { it.threadId !in archivedThreadIds && it.threadId !in hiddenThreadIds }
            .map { it.toConversationModel() }
    }
}
