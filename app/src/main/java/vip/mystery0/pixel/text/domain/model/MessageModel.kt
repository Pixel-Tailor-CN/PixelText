package vip.mystery0.pixel.text.domain.model

sealed class ParsedResult {
    data class VerificationCode(val code: String, val signature: String? = null) : ParsedResult()

    sealed class Ticket : ParsedResult() {
        data class TrainTicket(
            val date: String,
            val trainNumber: String,
            val trainType: String,
            val departureTime: String,
            val departureStation: String,
            val arrivalTime: String,
            val arrivalStation: String,
            val passenger: String,
            val seat: String
        ) : Ticket()

        data class Flight(
            val date: String,
            val flightNumber: String,
            val departureCode: String,
            val departureCity: String,
            val departureTime: String,
            val flightType: String,
            val arrivalCode: String,
            val arrivalCity: String,
            val arrivalTime: String,
            val terminal: String,
            val boardingTime: String
        ) : Ticket()
    }

    data class BankTransaction(
        val type: String,
        val amount: String,
        val isSuccess: Boolean = true,
        val errorMessage: String? = null,
        val details: Map<String, String>
    ) : ParsedResult()

    data class ExpressDelivery(
        val company: String,
        val code: String,
        val location: String,
        val time: String? = null
    ) : ParsedResult()

    data class PhoneRecharge(
        val amount: String,
        val details: Map<String, String>
    ) : ParsedResult()

    data class Dynamic(
        val cardType: String,
        val fields: Map<String, String>
    ) : ParsedResult()
    
    data object None : ParsedResult()
}

data class MessageModel(
    val id: Long,
    val threadId: Long = -1,
    val sender: String,
    val content: String,
    val timestamp: Long,
    val simName: String = "卡1",
    val isReceived: Boolean = true,
    val parsedResult: ParsedResult = ParsedResult.None
)
