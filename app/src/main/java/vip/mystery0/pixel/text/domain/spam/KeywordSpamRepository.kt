package vip.mystery0.pixel.text.domain.spam

import kotlinx.coroutines.flow.Flow
import vip.mystery0.pixel.text.domain.model.BlockedKeyword
import vip.mystery0.pixel.text.domain.model.KeywordSaveResult
import vip.mystery0.pixel.text.domain.model.KeywordSpamMessage

interface KeywordSpamRepository {
    fun observeKeywords(): Flow<List<BlockedKeyword>>
    suspend fun save(id: Long?, keyword: String): KeywordSaveResult
    suspend fun delete(id: Long): Boolean
    suspend fun updateMessageMatch(messageId: Long, threadId: Long, content: String): Boolean
    suspend fun rebuildMatches(messages: List<KeywordSpamMessage>): Int
}
