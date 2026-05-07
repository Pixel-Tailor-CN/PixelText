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
    fun CreateCard(content: String, parsedResult: ParsedResult, isSelected: Boolean = false) {
        when (parsedResult) {
            is ParsedResult.Ticket.TrainTicket -> TrainTicketCard(parsedResult, isSelected)
            is ParsedResult.Ticket.Flight -> FlightCard(parsedResult, isSelected)
            is ParsedResult.BankTransaction -> BankTransactionCard(parsedResult, isSelected)
            is ParsedResult.PhoneRecharge -> PhoneRechargeCard(parsedResult, isSelected)
            is ParsedResult.ExpressDelivery -> ExpressDeliveryCard(parsedResult, isSelected)
            is ParsedResult.VerificationCode -> VerificationCodeCard(content, parsedResult, isSelected)
            else -> NormalMessageCard(content, isSelected)
        }
    }
}
