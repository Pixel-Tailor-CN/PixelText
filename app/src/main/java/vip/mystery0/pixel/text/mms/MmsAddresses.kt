package vip.mystery0.pixel.text.mms

import android.content.Context
import android.telephony.PhoneNumberUtils
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import java.util.Locale

internal object MmsAddresses {
    fun clean(value: String): String = value.replace(Regex("/TYPE=[^/]+$", RegexOption.IGNORE_CASE), "").trim()
    fun normalized(value: String): String {
        val address = clean(value)
        if ('@' in address) return address.lowercase(Locale.ROOT)
        if (!address.matches(Regex("\\+?[0-9() .-]+"))) return address
        return address.filter { it.isDigit() || it == '+' }
    }
    data class LocalIdentity(val numbers: Set<String>, val countryIso: String)

    fun equivalent(first: String, second: String, countryIso: String): Boolean {
        val left = normalized(first)
        val right = normalized(second)
        if (left.isBlank() || right.isBlank()) return false
        if (left == right) return true
        // 邮件地址只做规范化等值比较；号码不能用末尾若干位相同来猜测。
        if (!left.matches(Regex("\\+?[0-9]+")) || !right.matches(Regex("\\+?[0-9]+"))) return false
        if (left.removePrefix("+") == right.removePrefix("+")) return true
        return PhoneNumberUtils.areSamePhoneNumber(left, right, countryIso)
    }

    @Suppress("DEPRECATION")
    fun local(context: Context, subscriptionId: Int): LocalIdentity = try {
        val manager = context.getSystemService(SubscriptionManager::class.java)
        val info = manager.activeSubscriptionInfoList.orEmpty().firstOrNull { it.subscriptionId == subscriptionId }
        val telephony = context.getSystemService(TelephonyManager::class.java).createForSubscriptionId(subscriptionId)
        // 使用接收订阅的号码与 SIM 国家，避免另一张卡或漫游网络改变号码归属。
        val country = info?.countryIso.orEmpty().ifBlank { telephony.simCountryIso }
            .ifBlank { telephony.networkCountryIso }
        val numbers = listOfNotNull(info?.number, telephony.line1Number)
            .map(::normalized).filter { it.isNotBlank() }.toSet()
        LocalIdentity(numbers, country)
    } catch (_: SecurityException) { LocalIdentity(emptySet(), "") }
}
