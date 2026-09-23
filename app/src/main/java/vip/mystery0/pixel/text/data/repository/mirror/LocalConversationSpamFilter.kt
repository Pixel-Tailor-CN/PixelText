package vip.mystery0.pixel.text.data.repository.mirror

import vip.mystery0.pixel.text.data.db.SpamDatabase
import vip.mystery0.pixel.text.data.source.whitelistMessageFingerprint
import vip.mystery0.pixel.text.domain.model.mirror.MessageTransport
import vip.mystery0.pixel.text.domain.model.mirror.MirrorMessageModel
import vip.mystery0.pixel.text.domain.spam.SenderWhitelistMatcher
import vip.mystery0.pixel.text.domain.spam.WhitelistRuleType

/** 列表只读筛选：身份来自镜像，不查询 Provider，也不顺便持久化白名单。 */
class LocalConversationSpamFilter(private val database: SpamDatabase) {
    suspend fun spamIds(messages: List<MirrorMessageModel>, threshold: Float): Set<Long> {
        val whitelist = database.senderWhitelistDao()
        val matchers = whitelist.getRules().map {
            SenderWhitelistMatcher(WhitelistRuleType.valueOf(it.type), it.value)
        }
        val result = mutableSetOf<Long>()
        for (batch in messages.chunked(500)) {
            fun id(message: MirrorMessageModel) =
                if (message.key.transport == MessageTransport.MMS) -message.key.sourceId else message.key.sourceId

            val ids = batch.map(::id)
            val candidates = database.spamResultDao().getSpamMessageIds(ids, threshold).toSet()
            val allowed = whitelist.getAllowed(ids).associateBy { it.messageId }
            for (message in batch) {
                val messageId = id(message)
                if (messageId !in candidates) continue
                val addresses = message.addresses.filter {
                    it.type in setOf(
                        137,
                        151,
                        130,
                        129
                    ) && !it.address.isNullOrBlank() && it.address != "insert-address-token"
                }
                val sender =
                    if (message.key.transport == MessageTransport.SMS) message.address.orEmpty()
                    else (addresses.firstOrNull { it.type == 137 }
                        ?: addresses.firstOrNull())?.address.orEmpty()
                val date =
                    message.rawValues.firstOrNull { it.column == "date" }?.value?.toLongOrNull()
                        ?: 0
                val sent =
                    message.rawValues.firstOrNull { it.column == "date_sent" }?.value?.toLongOrNull()
                        ?: 0
                val fingerprint = whitelistMessageFingerprint(messageId, sender, date, sent)
                if (allowed[messageId]?.fingerprint == fingerprint || matchers.any {
                        it.matches(
                            sender
                        )
                    }) continue
                result += messageId
            }
        }
        return result
    }
}
