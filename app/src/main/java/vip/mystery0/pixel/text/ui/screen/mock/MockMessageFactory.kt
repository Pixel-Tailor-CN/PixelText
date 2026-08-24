package vip.mystery0.pixel.text.ui.screen.mock

import vip.mystery0.pixel.text.domain.model.MessageModel
import vip.mystery0.pixel.text.domain.parser.MessageParser

data class MockMessageSpec(
    val content: String,
    val isReceived: Boolean = true,
    val simName: String = "卡1",
)

class MockMessageFactory(
    private val messageParser: MessageParser,
) {
    fun create(
        messages: List<String>,
        now: Long = System.currentTimeMillis(),
    ): List<MessageModel> {
        return createSpecs(
            specs = messages.map { content -> MockMessageSpec(content = content) },
            now = now,
        )
    }

    fun createSpecs(
        specs: List<MockMessageSpec>,
        now: Long = System.currentTimeMillis(),
    ): List<MessageModel> {
        return specs.mapIndexed { index, spec ->
            val sender = messageParser.extractSignature(spec.content) ?: "模拟短信"
            MessageModel(
                id = index.toLong() + 1,
                sender = sender,
                content = spec.content,
                timestamp = now - index * MESSAGE_INTERVAL_MILLIS,
                simName = spec.simName,
                isReceived = spec.isReceived,
                parsedResult = messageParser.parse(sender, spec.content),
            )
        }
    }

    private companion object {
        const val MESSAGE_INTERVAL_MILLIS = 5 * 60 * 1000L
    }
}
