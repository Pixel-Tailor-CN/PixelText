package vip.mystery0.pixel.text.domain.repository

import kotlinx.coroutines.flow.StateFlow
import vip.mystery0.pixel.text.domain.model.EngagementPromptState

interface EngagementPromptRepository {
    val state: StateFlow<EngagementPromptState>
    suspend fun awaitLoaded()
    suspend fun noteForegroundStarted(nowMillis: Long)
    suspend fun recordQualifiedSession(nowMillis: Long, epochDay: Long)
    suspend fun markShown(nowMillis: Long)
    suspend fun dismiss()
}
