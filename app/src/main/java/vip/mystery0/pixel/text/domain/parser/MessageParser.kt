package vip.mystery0.pixel.text.domain.parser

import vip.mystery0.pixel.text.domain.model.ParsedResult

object MessageParser {
    private val verificationCodeRegex =
        Regex("(?i)(?:验证码|动态码|校验码).*?([0-9]{4,6})|([0-9]{4,6}).*?(?:验证码|动态码|校验码)")
    
    fun parse(content: String): ParsedResult {
        // 先尝试匹配 12306 高铁票信息
        if (content.contains("12306") || content.contains("铁路")) {
            val dateMatch =
                Regex("(\\d{4}-\\d{2}-\\d{2}|\\d{4}年\\d{2}月\\d{2}日|\\d+月\\d+日)").find(content)
            val trainMatch = Regex("([a-zA-Z0-9]+)次").find(content)
            val seatMatch = Regex("(\\d+车厢[^，。、]+座|\\d+车[^，。、]+号)").find(content)
            val depMatch = Regex("([^，。、0-9]+)(\\d{2}:\\d{2})开往").find(content)
                ?: Regex("([^，。、0-9]+)(\\d{2}:\\d{2})开").find(content)
            val arrMatch = Regex("开往([^，。、]+)").find(content)
            val passengerMatch = Regex("，?([\\u4e00-\\u9fa5]+)您已购").find(content)
            val gateMatch = Regex("检票口([a-zA-Z0-9]+)").find(content)

            if (trainMatch != null && depMatch != null) {
                return ParsedResult.Ticket.HighSpeedRail(
                    trainNumber = trainMatch.groupValues[1],
                    date = dateMatch?.value ?: "",
                    departureStation = depMatch.groupValues[1].replace("座", ""), // 简单清理
                    departureTime = depMatch.groupValues[2],
                    arrivalStation = arrMatch?.groupValues?.get(1),
                    arrivalTime = null, // 通常短信不包含到达时间
                    seat = seatMatch?.groupValues?.get(1),
                    passenger = passengerMatch?.groupValues?.get(1),
                    ticketGate = gateMatch?.groupValues?.get(1),
                    status = if (content.contains("已购") || content.contains("成功")) "购票成功" else null
                )
            }
        }

        // 再尝试匹配验证码
        if (content.contains("验证码") || content.contains("动态码") || content.contains("校验码") || content.contains(
                "code",
                ignoreCase = true
            )
        ) {
            val match = verificationCodeRegex.find(content)
            if (match != null) {
                val code = match.groupValues[1].takeIf { it.isNotEmpty() } ?: match.groupValues[2]
                if (code.isNotEmpty()) {
                    return ParsedResult.VerificationCode(code)
                }
            }
            val simpleRegex = Regex("(?<!\\d)\\d{4,6}(?!\\d)")
            val simpleMatch = simpleRegex.find(content)
            if (simpleMatch != null) {
                return ParsedResult.VerificationCode(simpleMatch.value)
            }
        }

        return ParsedResult.None
    }
}
