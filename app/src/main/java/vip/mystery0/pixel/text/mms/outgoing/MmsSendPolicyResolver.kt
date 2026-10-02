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
import java.security.MessageDigest

/** 每次从明确订阅读取有效政策，不回退默认卡，也不虚构运营商字节限额。 */
class MmsSendPolicyResolver(private val context: Context) {
    fun resolve(subId: Int): MmsSendPolicy {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            throw MmsSendException("请允许读取 SIM 信息后重试")
        }
        val feature = if (Build.VERSION.SDK_INT >= 33) PackageManager.FEATURE_TELEPHONY_MESSAGING else PackageManager.FEATURE_TELEPHONY
        if (!context.packageManager.hasSystemFeature(feature)) throw MmsSendException("此设备不支持彩信发送")
        if (!SubscriptionManager.isValidSubscriptionId(subId)) throw MmsSendException("请选择发送彩信的 SIM")
        try {
            val subscriptions = context.getSystemService(SubscriptionManager::class.java)
                ?: throw MmsSendException("无法读取 SIM 信息")
            val subscription = subscriptions.activeSubscriptionInfoList?.singleOrNull { it.subscriptionId == subId }
                ?: throw MmsSendException("所选 SIM 已不可用，请重新选择")
            val carrier = context.getSystemService(CarrierConfigManager::class.java)?.getConfigForSubId(subId)
                ?: throw MmsSendException("运营商彩信配置暂不可用，请稍后重新读取")
            if (!CarrierConfigManager.isConfigForIdentifiedCarrier(carrier)) {
                throw MmsSendException("运营商配置尚未载入，请稍后重新读取")
            }
            val manager = context.getSystemService(SmsManager::class.java)?.createForSubscriptionId(subId)
                ?: throw MmsSendException("此设备暂不能读取彩信配置")
            val values = manager.carrierConfigValues
            if (!values.containsKey(SmsManager.MMS_CONFIG_MMS_ENABLED)) throw missingPolicy()
            if (!values.getBoolean(SmsManager.MMS_CONFIG_MMS_ENABLED)) throw MmsSendException("所选 SIM 的运营商未启用彩信")
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
        } catch (exception: SecurityException) {
            throw MmsSendException("SIM 读取权限已变化，请重新授权后重试")
        } catch (exception: UnsupportedOperationException) {
            throw MmsSendException("此设备不支持读取彩信配置")
        }
    }

    private fun requiredPositive(values: Bundle, key: String): Int {
        if (!values.containsKey(key)) throw missingPolicy()
        return values.getInt(key).takeIf { it > 0 } ?: throw missingPolicy()
    }

    private fun missingPolicy() = MmsSendException("运营商彩信限制缺失或无效，请稍后重新读取")
}
