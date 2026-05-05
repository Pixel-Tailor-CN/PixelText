package vip.mystery0.pixel.text.ui.message.factory

import androidx.compose.runtime.Composable
import vip.mystery0.pixel.text.domain.model.ParsedResult
import vip.mystery0.pixel.text.domain.parser.MessageParser
import vip.mystery0.pixel.text.ui.message.cards.NormalMessageCard
import vip.mystery0.pixel.text.ui.message.cards.TrainTicketCard
import vip.mystery0.pixel.text.ui.message.cards.VerificationCodeCard

object MessageCardFactory {

    @Composable
    fun CreateCard(content: String) {
        val parsedResult = MessageParser.parse(content)
        when (parsedResult) {
            is ParsedResult.TrainTicket -> TrainTicketCard(content, parsedResult)
            is ParsedResult.VerificationCode -> VerificationCodeCard(content, parsedResult)
            is ParsedResult.None -> NormalMessageCard(content)
        }
    }
}
