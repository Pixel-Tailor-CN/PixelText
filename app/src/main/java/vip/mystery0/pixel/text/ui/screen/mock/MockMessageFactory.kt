package vip.mystery0.pixel.text.ui.screen.mock

import vip.mystery0.pixel.text.domain.model.MessageModel
import vip.mystery0.pixel.text.domain.parser.MessageParser

class MockMessageFactory(
    private val messageParser: MessageParser,
) {
    fun create(
        messages: List<String>,
        now: Long = System.currentTimeMillis(),
    ): List<MessageModel> {
        return messages.mapIndexed { index, content ->
            val sender = messageParser.extractSignature(content) ?: "模拟短信"
            MessageModel(
                id = index.toLong() + 1,
                sender = sender,
                content = content,
                timestamp = now - index * MESSAGE_INTERVAL_MILLIS,
                simName = "卡1",
                parsedResult = messageParser.parse(sender, content),
            )
        }
    }

    private companion object {
        const val MESSAGE_INTERVAL_MILLIS = 5 * 60 * 1000L
    }
}
