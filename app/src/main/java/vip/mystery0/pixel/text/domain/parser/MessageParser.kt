package vip.mystery0.pixel.text.domain.parser

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import vip.mystery0.pixel.text.domain.model.ParsedResult
import java.io.InputStreamReader

data class ParseRule(
    val id: String,
    val targetCard: String,
    val priority: Int,
    val fastFailSenderEquals: String?,
    val fastFailSignatureEquals: String?,
    val fastFailKeywords: List<String>?,
    val contentRegex: Regex
)

class MessageParser(private val context: Context) {
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
            val inputStream = context.assets.open("rules.json")
            val jsonString = InputStreamReader(inputStream).readText()
            val jsonObject = JSONObject(jsonString)
            val jsonRules = jsonObject.optJSONArray("rules") ?: JSONArray()

            val tempRules = mutableListOf<ParseRule>()

            for (i in 0 until jsonRules.length()) {
                val ruleObj = jsonRules.getJSONObject(i)
                val id = ruleObj.getString("id")
                val targetCard = ruleObj.getString("target_card")
                val priority = ruleObj.optInt("priority", 0)

                val fastFail = ruleObj.optJSONObject("fast_fail")
                val senderEquals =
                    fastFail?.optString("sender_equals", null)?.takeIf { it.isNotBlank() }
                val signatureEquals =
                    fastFail?.optString("signature_equals", null)?.takeIf { it.isNotBlank() }
                val keywordsArray = fastFail?.optJSONArray("keywords")
                val keywords = mutableListOf<String>()
                if (keywordsArray != null) {
                    for (j in 0 until keywordsArray.length()) {
                        keywords.add(keywordsArray.getString(j))
                    }
                }

                val conditions = ruleObj.getJSONObject("conditions")
                val contentRegexStr = conditions.getString("content_regex")
                val contentRegex = Regex(contentRegexStr)

                tempRules.add(
                    ParseRule(
                        id = id,
                        targetCard = targetCard,
                        priority = priority,
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

            Log.d("MessageParser", "Loaded ${rules.size} dynamic rules successfully.")
        } catch (e: Exception) {
            Log.e("MessageParser", "Failed to load rules.json", e)
        }
    }

    fun extractSignature(content: String): String? {
        val startRegex = Regex("^【(.*?)】|^\\[(.*?)\\]")
        val endRegex = Regex("【(.*?)】$|\\[(.*?)\\]$")

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
                return ParsedResult.Ticket.TrainTicket(
                    trainNumber = getGroupOrNull(matcher, "trainNo") ?: "--",
                    date = getGroupOrNull(matcher, "date") ?: "",
                    trainType = "高铁",
                    departureStation = getGroupOrNull(matcher, "departureStation") ?: "--",
                    departureTime = getGroupOrNull(matcher, "departureTime") ?: "--",
                    arrivalStation = getGroupOrNull(matcher, "arrivalStation") ?: "--",
                    arrivalTime = "--",
                    seat = getGroupOrNull(matcher, "seat") ?: "--",
                    passenger = getGroupOrNull(matcher, "passenger") ?: "--"
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

                return ParsedResult.BankTransaction(
                    type = getGroupOrNull(matcher, "type") ?: "交易",
                    amount = getGroupOrNull(matcher, "amount") ?: "0.00",
                    isSuccess = isSuccess,
                    errorMessage = reason,
                    details = details
                )
            }
            "ExpressDelivery" -> {
                return ParsedResult.ExpressDelivery(
                    company = getGroupOrNull(matcher, "company") ?: "--",
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
}
