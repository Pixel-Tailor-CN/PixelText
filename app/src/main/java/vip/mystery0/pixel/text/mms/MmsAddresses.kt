package vip.mystery0.pixel.text.mms

import android.content.Context
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
    @Suppress("DEPRECATION")
    fun local(context: Context): Set<String> = try {
        val manager = context.getSystemService(SubscriptionManager::class.java)
        val telephony = context.getSystemService(TelephonyManager::class.java)
        manager.activeSubscriptionInfoList.orEmpty().flatMap { info ->
            listOfNotNull(info.number, telephony.createForSubscriptionId(info.subscriptionId).line1Number)
        }.map(::normalized).filter { it.isNotBlank() }.toSet()
    } catch (_: SecurityException) { emptySet() }
}
