package vip.mystery0.pixel.text.ui.screen.mock

import vip.mystery0.pixel.text.R
import vip.mystery0.pixel.text.domain.model.MessageModel
import vip.mystery0.pixel.text.domain.model.ParsedResult
import vip.mystery0.pixel.text.domain.parser.MessageParser
import vip.mystery0.pixel.text.util.AppStrings

data class MockMessageSpec(
    val content: String,
    val isReceived: Boolean = true,
    val simName: String? = null,
    /**
     * When non-null, used as [MessageModel.parsedResult] instead of running [MessageParser].
     * Preview samples set this to [ParsedResult.None] so original bubbles stay stable even if
     * downloaded active rules would otherwise match the sample wording.
     */
    val parsedResultOverride: ParsedResult? = null,
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
            val sender = messageParser.extractSignature(spec.content)
                ?: AppStrings.get(R.string.conv_mock_sender_default)
            MessageModel(
                id = index.toLong() + 1,
                sender = sender,
                content = spec.content,
                timestamp = now - index * MESSAGE_INTERVAL_MILLIS,
                simName = spec.simName ?: AppStrings.get(R.string.conv_mock_sim_1),
                isReceived = spec.isReceived,
                parsedResult = spec.parsedResultOverride
                    ?: messageParser.parse(sender, spec.content),
            )
        }
    }

    private companion object {
        const val MESSAGE_INTERVAL_MILLIS = 5 * 60 * 1000L
    }
}
