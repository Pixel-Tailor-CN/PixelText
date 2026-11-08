package vip.mystery0.pixel.text.data.source

import com.squareup.moshi.Moshi
import vip.mystery0.pixel.text.domain.model.DeveloperSupportConfig
import vip.mystery0.pixel.text.domain.model.SupportChannel
import vip.mystery0.pixel.text.domain.model.SupportChannelId
import java.io.IOException
import java.net.URI

/** 远程配置仅决定渠道资源，不接受远程包名、任意 Intent 或界面文案。 */
class SupportConfigParser {
    private val adapter = Moshi.Builder().build().adapter(Any::class.java)

    fun parse(json: String): DeveloperSupportConfig {
        if (json.isBlank()) throw IOException("empty support config")
        val root = adapter.fromJson(json) as? Map<*, *>
            ?: throw IOException("invalid support config root")
        if (root["schemaVersion"] != 1.0) throw IOException("unsupported support config version")
        val enabled = root["enabled"] as? Boolean ?: throw IOException("missing support enabled")
        if (!enabled) return DeveloperSupportConfig(false, emptyList())
        val items = root["channels"] as? List<*> ?: throw IOException("missing support channels")
        // 渠道数量受限，单个无效项不能让其他有效渠道不可用；重复渠道只取首个有效项。
        val channels = items.take(20).mapNotNull { item ->
            val fields = item as? Map<*, *> ?: return@mapNotNull null
            if (fields["enabled"] != true) return@mapNotNull null
            val id = SupportChannelId.entries.firstOrNull { it.value == fields["id"] }
                ?: return@mapNotNull null
            val qr = (fields["qrCodeUrl"] as? String)?.takeIf(::isTrustedQrUrl)
                ?: return@mapNotNull null
            val payment = (fields["paymentUrl"] as? String)?.takeIf { isTrustedPaymentUrl(id, it) }
            SupportChannel(id, qr, payment)
        }.distinctBy { it.id }
        if (channels.isEmpty()) throw IOException("no valid support channels")
        return DeveloperSupportConfig(true, channels)
    }

    companion object {
        fun isTrustedQrUrl(value: String): Boolean {
            val uri = safeHttpsUri(value) ?: return false
            return uri.host == "pixel.mystery0.app" && uri.rawPath.startsWith("/support/") &&
                !uri.rawPath.contains('%') &&
                uri.normalize().rawPath == uri.rawPath && uri.rawQuery == null
        }

        fun isTrustedPaymentUrl(id: SupportChannelId, value: String): Boolean {
            val uri = safeHttpsUri(value) ?: return false
            return uri.host == when (id) {
                SupportChannelId.ALIPAY -> "qr.alipay.com"
                SupportChannelId.WECHAT -> "payapp.weixin.qq.com"
            }
        }

        private fun safeHttpsUri(value: String): URI? = try {
            if (value.length > 2048) null else URI(value).takeIf {
                it.scheme == "https" && !it.host.isNullOrBlank() &&
                    it.rawUserInfo == null && it.port == -1 && it.rawFragment == null
            }
        } catch (_: Exception) {
            null
        }
    }
}
