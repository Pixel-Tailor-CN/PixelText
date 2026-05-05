package vip.mystery0.pixel.text.domain.model

sealed class ParsedResult {
    data class VerificationCode(val code: String) : ParsedResult()
    data class TrainTicket(
        val trainNumber: String,
        val date: String,
        val departureStation: String,
        val departureTime: String,
        val arrivalStation: String?,
        val passenger: String?
    ) : ParsedResult()

    data object None : ParsedResult()
}

data class MessageModel(
    val id: Long,
    val sender: String,
    val content: String,
    val timestamp: Long,
    val parsedResult: ParsedResult = ParsedResult.None
)
