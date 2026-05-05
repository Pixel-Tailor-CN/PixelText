package vip.mystery0.pixel.text.domain.parser

import vip.mystery0.pixel.text.domain.model.ParsedResult

object MessageParser {
    // 验证码通常为4-6位数字，并且短信内容包含“验证码”、“动态码”等关键字
    private val verificationCodeRegex =
        Regex("(?i)(?:验证码|动态码|校验码).*?([0-9]{4,6})|([0-9]{4,6}).*?(?:验证码|动态码|校验码)")

    // 12306 购票短信，提取关键信息
    // 示例：【铁路客服】订单E123456789，邓易林您已购4月30日C6921次10车11B号广州南09:02开。
    // 示例：【12306】邓易林购票成功，4月30日C6921次，广州南站09:02开
    private val trainTicketRegex =
        Regex("(?<=购|买)(\\d+月\\d+日)([a-zA-Z0-9]+)次.*?([^\\d]+?站|[^\\d]+?)(\\d{2}:\\d{2})开")

    fun parse(content: String): ParsedResult {
        // 先尝试匹配 12306 行程信息
        if (content.contains("12306") || content.contains("铁路")) {
            val match = trainTicketRegex.find(content)
            if (match != null) {
                return ParsedResult.TrainTicket(
                    date = match.groupValues[1],
                    trainNumber = match.groupValues[2],
                    departureStation = match.groupValues[3],
                    departureTime = match.groupValues[4],
                    arrivalStation = null, // 有的短信不包含到达站
                    passenger = null // 暂不提取乘车人
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
                // 优先取第1个捕获组，如果没有则取第2个捕获组
                val code = match.groupValues[1].takeIf { it.isNotEmpty() } ?: match.groupValues[2]
                if (code.isNotEmpty()) {
                    return ParsedResult.VerificationCode(code)
                }
            }
            // 更泛用的验证码正则，单独4-6个数字且周围有特殊字符/空格等
            val simpleRegex = Regex("(?<!\\d)\\d{4,6}(?!\\d)")
            val simpleMatch = simpleRegex.find(content)
            if (simpleMatch != null) {
                return ParsedResult.VerificationCode(simpleMatch.value)
            }
        }

        return ParsedResult.None
    }
}
