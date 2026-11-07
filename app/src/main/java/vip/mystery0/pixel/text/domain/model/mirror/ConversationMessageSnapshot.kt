package vip.mystery0.pixel.text.domain.model.mirror

import vip.mystery0.pixel.text.domain.model.MessageModel

/** 会话聚合与白名单判断所需的轻量快照，不携带短信原始字段 JSON。 */
data class ConversationMessageSnapshot(
    val localId: Long,
    val message: MessageModel,
    val whitelistSender: String,
    val originalDate: Long,
    val dateSent: Long,
    val revision: Long,
)
