package vip.mystery0.pixel.text.domain.parser

import android.content.Context
import android.util.Log
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import vip.mystery0.pixel.text.data.resource.HubResourceStore
import vip.mystery0.pixel.text.domain.model.ParsedResult

data class ParseRule(
    val id: String,
    val targetCard: String,
    val priority: Int,
    val fastFailSenderEquals: String?,
    val fastFailSignatureEquals: String?,
    val fastFailKeywords: List<String>?,
    val contentRegex: Regex
)

class MessageParser(
    private val context: Context,
    private val resourceStore: HubResourceStore,
) {
    private val rules = mutableListOf<ParseRule>()

    // L1 Index
    private val senderIndex = mutableMapOf<String, MutableList<ParseRule>>()

    // L2 Index
    private val signatureIndex = mutableMapOf<String, MutableList<ParseRule>>()

    // L3/L4 Index
    private val keywordRules = mutableListOf<ParseRule>()
    private val genericRules = mutableListOf<ParseRule>()

    init {
        loadRules()
    }

    private fun loadRules() {
        try {
            val jsonString = readRulesJson()
            val jsonRules = rulesFileAdapter.fromJson(jsonString)?.rules.orEmpty()

            val tempRules = mutableListOf<ParseRule>()

            for (ruleObj in jsonRules) {
                val fastFail = ruleObj.fastFail
                val senderEquals = fastFail?.senderEquals?.takeIf { it.isNotBlank() }
                val signatureEquals = fastFail?.signatureEquals?.takeIf { it.isNotBlank() }
                val keywords = fastFail?.keywords.orEmpty()
                val contentRegex = Regex(ruleObj.conditions.contentRegex)

                tempRules.add(
                    ParseRule(
                        id = ruleObj.id,
                        targetCard = ruleObj.targetCard,
                        priority = ruleObj.priority,
                        fastFailSenderEquals = senderEquals,
                        fastFailSignatureEquals = signatureEquals,
                        fastFailKeywords = keywords.takeIf { it.isNotEmpty() },
                        contentRegex = contentRegex
                    )
                )
            }

            tempRules.sortByDescending { it.priority }
            this.rules.addAll(tempRules)

            for (rule in this.rules) {
                if (rule.fastFailSenderEquals != null) {
                    senderIndex.getOrPut(rule.fastFailSenderEquals) { mutableListOf() }.add(rule)
                } else if (rule.fastFailSignatureEquals != null) {
                    signatureIndex.getOrPut(rule.fastFailSignatureEquals) { mutableListOf() }
                        .add(rule)
                } else if (rule.fastFailKeywords != null) {
                    keywordRules.add(rule)
                } else {
                    genericRules.add(rule)
                }
            }
        } catch (e: Exception) {
            Log.e("MessageParser", "failed to load rules.json", e)
        }
    }

    private fun readRulesJson(): String {
        val activeRules = resourceStore.activeRulesFile()
        if (activeRules.isFile) {
            return activeRules.readText(Charsets.UTF_8)
        }
        return context.assets.open("rules.json").bufferedReader(Charsets.UTF_8).use {
            it.readText()
        }
    }

    fun reloadRules() {
        rules.clear()
        senderIndex.clear()
        signatureIndex.clear()
        keywordRules.clear()
        genericRules.clear()
        loadRules()
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
        val pattern = java.util.regex.Pattern.compile(rule.contentRegex.pattern)
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
