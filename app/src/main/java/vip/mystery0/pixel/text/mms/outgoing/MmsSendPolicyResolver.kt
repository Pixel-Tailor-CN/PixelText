package vip.mystery0.pixel.text.mms.outgoing

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.telephony.CarrierConfigManager
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import vip.mystery0.pixel.text.R
import java.security.MessageDigest

/** 每次从明确订阅读取有效政策，不回退默认卡，也不虚构运营商字节限额。 */
class MmsSendPolicyResolver(private val context: Context) {
    // Android 12、13 的运营商识别配置仍需兼容旧入口。
    @Suppress("DEPRECATION")
    fun resolve(subId: Int): MmsSendPolicy {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            throw MmsSendException(context.getString(R.string.sys_mms_policy_grant_sim_permission))
        }
        val feature = if (Build.VERSION.SDK_INT >= 33) PackageManager.FEATURE_TELEPHONY_MESSAGING else PackageManager.FEATURE_TELEPHONY
        if (!context.packageManager.hasSystemFeature(feature)) throw MmsSendException(context.getString(R.string.sys_mms_policy_device_unsupported))
        if (!SubscriptionManager.isValidSubscriptionId(subId)) throw MmsSendException(context.getString(R.string.sys_mms_policy_select_sim))
        try {
            val subscriptions = context.getSystemService(SubscriptionManager::class.java)
                ?: throw MmsSendException(context.getString(R.string.sys_mms_policy_cannot_read_sim))
            val subscription = subscriptions.activeSubscriptionInfoList?.singleOrNull { it.subscriptionId == subId }
                ?: throw MmsSendException(context.getString(R.string.sys_mms_policy_sim_unavailable))
            val carrier = context.getSystemService(CarrierConfigManager::class.java)?.getConfigForSubId(subId)
                ?: throw MmsSendException(context.getString(R.string.sys_mms_policy_carrier_config_unavailable))
            if (!CarrierConfigManager.isConfigForIdentifiedCarrier(carrier)) {
                throw MmsSendException(context.getString(R.string.sys_mms_policy_carrier_config_not_loaded))
            }
            val manager = context.getSystemService(SmsManager::class.java)?.createForSubscriptionId(subId)
                ?: throw MmsSendException(context.getString(R.string.sys_mms_policy_device_cannot_read_config))
            val values = manager.carrierConfigValues
            if (!values.containsKey(SmsManager.MMS_CONFIG_MMS_ENABLED)) throw missingPolicy()
            if (!values.getBoolean(SmsManager.MMS_CONFIG_MMS_ENABLED)) throw MmsSendException(context.getString(R.string.sys_mms_policy_mms_disabled_by_carrier))
            val maxBytes = requiredPositive(values, SmsManager.MMS_CONFIG_MAX_MESSAGE_SIZE)
            val width = requiredPositive(values, SmsManager.MMS_CONFIG_MAX_IMAGE_WIDTH)
            val height = requiredPositive(values, SmsManager.MMS_CONFIG_MAX_IMAGE_HEIGHT)
            val subject = requiredPositive(values, SmsManager.MMS_CONFIG_SUBJECT_MAX_LENGTH)
            // -1 是平台配置表示无限制的值；仍受完整 PDU 工程上限约束。
            if (!values.containsKey(SmsManager.MMS_CONFIG_MESSAGE_TEXT_MAX_SIZE)) throw missingPolicy()
            val text = values.getInt(SmsManager.MMS_CONFIG_MESSAGE_TEXT_MAX_SIZE)
            if (text == 0 || text < -1) throw missingPolicy()
            val boundedBytes = minOf(maxBytes, MmsPayloadStore.MAX_PDU_BYTES)
            val fingerprintSource = "$subId:${subscription.carrierId}:${subscription.cardId}:${subscription.simSlotIndex}:$maxBytes:$width:$height:$subject:$text"
            val fingerprint = MessageDigest.getInstance("SHA-256").digest(fingerprintSource.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            return MmsSendPolicy(subId, boundedBytes, width, height, subject,
                if (text == -1) boundedBytes else minOf(text, boundedBytes), fingerprint)
        } catch (_: SecurityException) {
            throw MmsSendException(context.getString(R.string.sys_mms_policy_sim_permission_changed))
        } catch (_: UnsupportedOperationException) {
            throw MmsSendException(context.getString(R.string.sys_mms_policy_device_no_config_read))
        }
    }

    private fun requiredPositive(values: Bundle, key: String): Int {
        if (!values.containsKey(key)) throw missingPolicy()
        return values.getInt(key).takeIf { it > 0 } ?: throw missingPolicy()
    }

    private fun missingPolicy() = MmsSendException(context.getString(R.string.sys_mms_policy_missing_or_invalid))
}
