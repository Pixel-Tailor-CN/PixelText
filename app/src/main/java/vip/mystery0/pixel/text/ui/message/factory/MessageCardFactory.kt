package vip.mystery0.pixel.text.ui.message.factory

import androidx.compose.runtime.Composable
import vip.mystery0.pixel.text.domain.model.ParsedResult
import vip.mystery0.pixel.text.ui.message.cards.NormalMessageCard
import vip.mystery0.pixel.text.ui.message.cards.VerificationCodeCard
import vip.mystery0.pixel.text.ui.message.cards.ticket.HighSpeedRailCard

object MessageCardFactory {

    @Composable
    fun CreateCard(content: String, parsedResult: ParsedResult) {
        when (parsedResult) {
            is ParsedResult.Ticket.HighSpeedRail -> HighSpeedRailCard(parsedResult)
            is ParsedResult.Ticket.Flight -> NormalMessageCard(content) // Not implemented yet
            is ParsedResult.Ticket.Bus -> NormalMessageCard(content) // Not implemented yet
            is ParsedResult.VerificationCode -> VerificationCodeCard(content, parsedResult)
            is ParsedResult.None -> NormalMessageCard(content)
        }
    }
}
