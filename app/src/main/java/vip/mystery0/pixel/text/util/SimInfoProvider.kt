package vip.mystery0.pixel.text.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

/**
 * 简化版的 SIM 卡信息描述，剥离系统对象方便在 UI 层使用。
 */
@Stable
@Immutable
data class SimInfo(
    val subscriptionId: Int,
    val slotIndex: Int,
    val displayName: String,
)

object SimInfoProvider {
    private const val TAG = "SimInfoProvider"

    /**
     * 读取当前激活的 SIM 卡列表。
     *
     * 没有 READ_PHONE_STATE 权限时返回空列表，调用方需要兜底处理。
     */
    fun getActiveSimList(context: Context): List<SimInfo> {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_PHONE_STATE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return emptyList()
        }
        val sm = context.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
        val list: List<SubscriptionInfo> = try {
            sm.activeSubscriptionInfoList ?: emptyList()
        } catch (e: SecurityException) {
            Log.w(TAG, "getActiveSimList: failed to get sim list", e)
            emptyList()
        }
        return list.map { info ->
            SimInfo(
                subscriptionId = info.subscriptionId,
                slotIndex = info.simSlotIndex,
                displayName = info.displayName?.toString()?.takeIf { it.isNotBlank() }
                    ?: info.carrierName?.toString()?.takeIf { it.isNotBlank() }
                    ?: "卡${info.simSlotIndex + 1}",
            )
        }.sortedBy { it.slotIndex }
    }

    /**
     * 当前用于发送 SMS 的默认 subId（用户在系统设置里配置的"默认短信卡"）。
     * 返回 [SubscriptionManager.INVALID_SUBSCRIPTION_ID] 表示没有任何可用 SIM。
     */
    fun getDefaultSmsSubscriptionId(): Int {
        return SmsManager.getDefaultSmsSubscriptionId()
    }
}
