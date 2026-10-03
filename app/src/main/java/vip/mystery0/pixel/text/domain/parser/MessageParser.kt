package vip.mystery0.pixel.text.domain.parser

import android.content.Context
import android.util.Log
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import vip.mystery0.pixel.text.data.resource.HubResourceStore
import vip.mystery0.pixel.text.domain.model.DataUsageStatus
import vip.mystery0.pixel.text.domain.model.ParsedResult

data class ParseRule(
    val id: String,
    val targetCard: String,
    val priority: Int,
    val fastFailSenderEquals: String?,
    val fastFailSignatureEquals: String?,
    val fastFailKeywords: List<String>?,
    val dataUsageStatus: DataUsageStatus?,
    val contentRegex: Regex
)

class MessageParser(
    private val context: Context,
    private val resourceStore: HubResourceStore,
) {
    private data class RuleSnapshot(
        val senderIndex: Map<String, List<ParseRule>>,
        val signatureIndex: Map<String, List<ParseRule>>,
        val keywordRules: List<ParseRule>,
        val genericRules: List<ParseRule>,
    )

    // 每次解析仅使用一个完整快照；更新失败不能清空正在使用的规则。
    @Volatile private var snapshot = runCatching { compileRules(readRulesJson()) }
        .getOrElse {
            Log.w("MessageParser", "active rules unavailable, using bundled rules")
            compileRules(readBundledRules())
        }

    private fun compileRules(json: String): RuleSnapshot {
        val source = requireNotNull(rulesFileAdapter.fromJson(json)) { "rules file empty" }
        require(source.rules.isNotEmpty()) { "rules file empty" }
        val rules = source.rules.map { rule ->
            ParseRule(
                id = rule.id, targetCard = rule.targetCard, priority = rule.priority,
                fastFailSenderEquals = rule.fastFail?.senderEquals?.takeIf(String::isNotBlank),
                fastFailSignatureEquals = rule.fastFail?.signatureEquals?.takeIf(String::isNotBlank),
                fastFailKeywords = rule.fastFail?.keywords?.takeIf { it.isNotEmpty() },
                dataUsageStatus = rule.dataUsageStatus?.let(DataUsageStatus::valueOf),
                contentRegex = Regex(rule.conditions.contentRegex),
            )
        }.sortedByDescending { it.priority }
        return RuleSnapshot(
            rules.filter { it.fastFailSenderEquals != null }.groupBy { requireNotNull(it.fastFailSenderEquals) },
            rules.filter { it.fastFailSenderEquals == null && it.fastFailSignatureEquals != null }
                .groupBy { requireNotNull(it.fastFailSignatureEquals) },
            rules.filter { it.fastFailSenderEquals == null && it.fastFailSignatureEquals == null && it.fastFailKeywords != null },
            rules.filter { it.fastFailSenderEquals == null && it.fastFailSignatureEquals == null && it.fastFailKeywords == null },
        )
    }

    /** 完整编译成功后才允许持久化；持久化失败时继续使用旧快照。 */
    @Synchronized fun replaceRules(json: String, persist: () -> Unit) {
        val prepared = compileRules(json)
        persist()
        snapshot = prepared
    }

    fun useBundledRules(persist: () -> Unit) = replaceRules(readBundledRules(), persist)

    private fun readBundledRules(): String =
        context.assets.open("rules.json").bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun readRulesJson(): String {
        val activeRules = resourceStore.activeRulesFile()
        if (activeRules.isFile) {
            return activeRules.readText(Charsets.UTF_8)
        }
        return context.assets.open("rules.json").bufferedReader(Charsets.UTF_8).use {
            it.readText()
        }
    }

    @Synchronized fun reloadRules() {
        snapshot = compileRules(readRulesJson())
    }

    fun extractSignature(content: String): String? {
        val startRegex = Regex("^【(.*?)】|^\\[(.*?)]")
        val endRegex = Regex("【(.*?)】$|\\[(.*?)]$")

        startRegex.find(content)?.let { return it.groupValues[1].ifEmpty { it.groupValues[2] } }
        endRegex.find(content)?.let { return it.groupValues[1].ifEmpty { it.groupValues[2] } }
        return null
    }

    fun parse(sender: String, content: String): ParsedResult {
        val signature = extractSignature(content)
        val (senderIndex, signatureIndex, keywordRules, genericRules) = snapshot

        // Level 1: Sender Filter
        senderIndex[sender]?.let { rules ->
            for (rule in rules) {
                val result = executeRule(rule, content, signature)
                if (result != null) return result
            }
        }

        // Level 2: Signature Filter
        if (signature != null) {
            signatureIndex[signature]?.let { rules ->
                for (rule in rules) {
                    val result = executeRule(rule, content, signature)
                    if (result != null) return result
                }
            }
        }

        // Level 3: Keyword Fast-Fail
        for (rule in keywordRules) {
            val containsKeyword =
                rule.fastFailKeywords?.any { content.contains(it, ignoreCase = true) } == true
            if (containsKeyword) {
                val result = executeRule(rule, content, signature)
                if (result != null) return result
            }
        }

        // Level 4: Generic Rules (No Fast-Fail)
        for (rule in genericRules) {
            val result = executeRule(rule, content, signature)
            if (result != null) return result
        }

        // Level 5: On-Device AI Placeholder
        // TODO: (L5 Fallback) 
        // Execute quantized NLP Model (TFLite) or ML Kit Entity Extraction 
        // to dynamically pull fields from unknown formats when all L1~L4 rules fail.

        return ParsedResult.None
    }

    private fun executeRule(rule: ParseRule, content: String, signature: String?): ParsedResult? {
        val pattern = rule.contentRegex.toPattern()
        val matcher = pattern.matcher(content)
        if (!matcher.find()) return null

        when (rule.targetCard) {
            "TrainTicket" -> {
                val details = mutableMapOf<String, String>()
                getGroupOrNull(matcher, "passenger")?.let { details["乘车人"] = it }
                getGroupOrNull(matcher, "seatClass")?.let { details["席别"] = it }
                getGroupOrNull(matcher, "seat")?.let { details["座位"] = it }
                getGroupOrNull(matcher, "gate")?.let { details["检票口"] = it }
                getGroupOrNull(matcher, "orderNo")?.let { details["订单号"] = it }

                return ParsedResult.Ticket.TrainTicket(
                    trainNumber = getGroupOrNull(matcher, "trainNo") ?: "--",
                    date = getGroupOrNull(matcher, "date") ?: "",
                    trainType = "高铁",
                    departureStation = getGroupOrNull(matcher, "departureStation") ?: "--",
                    departureTime = getGroupOrNull(matcher, "departureTime") ?: "--",
                    arrivalStation = getGroupOrNull(matcher, "arrivalStation") ?: "--",
                    arrivalTime = "--",
                    details = details
                )
            }
            "Flight" -> {
                return ParsedResult.Ticket.Flight(
                    date = getGroupOrNull(matcher, "date") ?: "",
                    flightNumber = getGroupOrNull(matcher, "flightNo") ?: "--",
                    departureCode = getGroupOrNull(matcher, "departureCode") ?: "--",
                    departureCity = getGroupOrNull(matcher, "departureCity") ?: "--",
                    departureTime = getGroupOrNull(matcher, "departureTime") ?: "--",
                    flightType = getGroupOrNull(matcher, "flightType") ?: "直飞",
                    arrivalCode = getGroupOrNull(matcher, "arrivalCode") ?: "--",
                    arrivalCity = getGroupOrNull(matcher, "arrivalCity") ?: "--",
                    arrivalTime = getGroupOrNull(matcher, "arrivalTime") ?: "--",
                    terminal = getGroupOrNull(matcher, "terminal") ?: "--",
                    boardingTime = getGroupOrNull(matcher, "boardingTime") ?: "--"
                )
            }
            "BankTransaction" -> {
                val details = mutableMapOf<String, String>()
                getGroupOrNull(matcher, "account")?.let { details["交易账户"] = it }
                getGroupOrNull(matcher, "date")?.let { details["交易时间"] = it }
                getGroupOrNull(matcher, "details")?.let {
                    details["交易备注"] = it.trim('（', '）', '(', ')')
                }

                val isSuccess = getGroupOrNull(matcher, "status") != "失败"
                val reason = getGroupOrNull(matcher, "reason")

                val type = getGroupOrNull(matcher, "type") ?: "交易"
                val rawAmount = getGroupOrNull(matcher, "amount") ?: "0.00"

                val incomeKeywords = listOf("入账", "收入", "存入", "退款", "退回")
                val expenseKeywords = listOf("扣款", "消费", "支出", "支付", "转出", "代收")

                val sign = when {
                    incomeKeywords.any { type.contains(it) } -> "+"
                    expenseKeywords.any { type.contains(it) } -> "-"
                    else -> ""
                }

                return ParsedResult.BankTransaction(
                    type = type,
                    amount = "$sign$rawAmount",
                    isSuccess = isSuccess,
                    errorMessage = reason,
                    details = details
                )
            }
            "ExpressDelivery" -> {
                return ParsedResult.ExpressDelivery(
                    company = getGroupOrNull(matcher, "company") ?: signature ?: "--",
                    code = getGroupOrNull(matcher, "code") ?: "--",
                    location = getGroupOrNull(matcher, "location") ?: "--",
                    time = getGroupOrNull(matcher, "time")
                )
            }

            "PhoneRecharge" -> {
                val details = mutableMapOf<String, String>()
                getGroupOrNull(matcher, "date")?.let { details["充值时间"] = it }
                getGroupOrNull(matcher, "balance")?.let { details["当前余额"] = "${it}元" }

                return ParsedResult.PhoneRecharge(
                    amount = getGroupOrNull(matcher, "amount") ?: "0.00",
                    details = details
                )
            }
            "MissedCall" -> {
                val phoneNumber = getGroupOrNull(matcher, "phoneNumber")?.trim() ?: return null
                val digitCount = phoneNumber.count(Char::isDigit)
                if (digitCount !in 7..15) return null
                return ParsedResult.MissedCall(
                    phoneNumber = phoneNumber,
                    time = getGroupOrNull(matcher, "time"),
                    location = getGroupOrNull(matcher, "location"),
                )
            }
            "DataUsage" -> {
                val status = rule.dataUsageStatus ?: DataUsageStatus.NORMAL
                val remainingData = getGroupOrNull(matcher, "remainingData")
                val usedData = getGroupOrNull(matcher, "usedData")
                val primaryData = when (status) {
                    DataUsageStatus.EXHAUSTED -> null
                    DataUsageStatus.NORMAL,
                    DataUsageStatus.LOW -> remainingData ?: usedData ?: return null
                }
                val rawLabel = getGroupOrNull(matcher, "dataLabel") ?: "手机上网流量"
                val dataLabel = when {
                    status == DataUsageStatus.EXHAUSTED -> rawLabel
                    remainingData != null -> "$rawLabel · 剩余"
                    else -> "$rawLabel · 已使用"
                }
                val details = mutableMapOf<String, String>()
                getGroupOrNull(matcher, "totalData")?.let { details["套餐总量"] = it }
                if (remainingData != null) {
                    usedData?.let { details["已使用"] = it }
                }
                getGroupOrNull(matcher, "throttledUsedData")?.let {
                    details["达量限速流量"] = "已使用$it"
                }
                getGroupOrNull(matcher, "throttleThreshold")?.let {
                    details["限速阈值"] = it
                }
                getGroupOrNull(matcher, "planName")?.let { details["流量类型"] = it }

                return ParsedResult.DataUsage(
                    status = status,
                    primaryData = primaryData,
                    dataLabel = dataLabel,
                    cutoffTime = getGroupOrNull(matcher, "cutoffTime"),
                    details = details,
                )
            }
            "VerificationCode" -> {
                val code = getGroupOrNull(matcher, "code")
                if (code != null) {
                    return ParsedResult.VerificationCode(code, signature)
                }
            }
        }

        return null
    }

    private fun getGroupOrNull(matcher: java.util.regex.Matcher, name: String): String? {
        return try {
            matcher.group(name)?.takeIf { it.isNotBlank() }
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private companion object {
        private val rulesFileAdapter = Moshi.Builder()
            .build()
            .adapter(MessageRulesFile::class.java)
    }
}

@JsonClass(generateAdapter = true)
internal data class MessageRulesFile(
    val rules: List<MessageRuleJson> = emptyList(),
)

@JsonClass(generateAdapter = true)
internal data class MessageRuleJson(
    val id: String,
    @Json(name = "target_card")
    val targetCard: String,
    val priority: Int = 0,
    @Json(name = "fast_fail")
    val fastFail: MessageRuleFastFail? = null,
    @Json(name = "data_usage_status")
    val dataUsageStatus: String? = null,
    val conditions: MessageRuleConditions,
)

@JsonClass(generateAdapter = true)
internal data class MessageRuleFastFail(
    @Json(name = "sender_equals")
    val senderEquals: String? = null,
    @Json(name = "signature_equals")
    val signatureEquals: String? = null,
    val keywords: List<String> = emptyList(),
)

@JsonClass(generateAdapter = true)
internal data class MessageRuleConditions(
    @Json(name = "content_regex")
    val contentRegex: String,
)
