package vip.mystery0.pixel.text.ui.message.factory

import androidx.compose.runtime.Composable
import vip.mystery0.pixel.text.domain.model.ParsedResult
import vip.mystery0.pixel.text.ui.message.cards.ExpressDeliveryCard
import vip.mystery0.pixel.text.ui.message.cards.NormalMessageCard
import vip.mystery0.pixel.text.ui.message.cards.VerificationCodeCard
import vip.mystery0.pixel.text.ui.message.cards.finance.BankTransactionCard
import vip.mystery0.pixel.text.ui.message.cards.finance.PhoneRechargeCard
import vip.mystery0.pixel.text.ui.message.cards.ticket.FlightCard
import vip.mystery0.pixel.text.ui.message.cards.ticket.TrainTicketCard

object MessageCardFactory {

    @Composable
    fun CreateCard(content: String, parsedResult: ParsedResult) {
        when (parsedResult) {
            is ParsedResult.Ticket.TrainTicket -> TrainTicketCard(parsedResult)
            is ParsedResult.Ticket.Flight -> FlightCard(parsedResult)
            is ParsedResult.BankTransaction -> BankTransactionCard(parsedResult)
            is ParsedResult.PhoneRecharge -> PhoneRechargeCard(parsedResult)
            is ParsedResult.ExpressDelivery -> ExpressDeliveryCard(parsedResult)
            is ParsedResult.VerificationCode -> VerificationCodeCard(content, parsedResult)
            else -> NormalMessageCard(content)
        }
    }
}
