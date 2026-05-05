package vip.mystery0.pixel.text.domain.model

sealed class ParsedResult {
    data class VerificationCode(val code: String) : ParsedResult()

    sealed class Ticket : ParsedResult() {
        data class HighSpeedRail(
            val trainNumber: String,
            val date: String,
            val departureStation: String,
            val departureTime: String,
            val arrivalStation: String?,
            val arrivalTime: String?,
            val seat: String?,
            val passenger: String?,
            val ticketGate: String?,
            val status: String?
        ) : Ticket()

        data class Flight(val placeholder: String = "") : Ticket()

        data class Bus(val placeholder: String = "") : Ticket()
    }
    
    data object None : ParsedResult()
}

data class MessageModel(
    val id: Long,
    val sender: String,
    val content: String,
    val timestamp: Long,
    val simName: String = "卡1",
    val parsedResult: ParsedResult = ParsedResult.None
)
