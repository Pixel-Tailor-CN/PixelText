package vip.mystery0.pixel.text.domain.model.mirror

enum class MessageTransport { SMS, MMS }

data class SourceMessageKey(val transport: MessageTransport, val sourceId: Long)
