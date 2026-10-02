package vip.mystery0.pixel.text.mms.outgoing

import android.content.Intent
import android.net.Uri

/** 外部输入只生成编辑建议。任何不唯一收件信息都不能偷偷取第一项。 */
data class MmsExternalInput(val recipient: String, val body: String, val subject: String,
    val attachments: List<Uri>, val error: String?, val requestsMms: Boolean) {
    companion object {
        fun parse(intent: Intent): MmsExternalInput = try { parseChecked(intent) }
        catch (_: Exception) {
            // exported 入口的 Parcel 类型与内容都不可信，失败必须整体阻断而非丢掉坏项。
            MmsExternalInput("", "", "", emptyList(), "分享附件或收件信息无效，请重新选择并确认", true)
        }
        @Suppress("DEPRECATION")
        private fun parseChecked(intent: Intent): MmsExternalInput {
            val data = intent.data
            val scheme = data?.scheme?.lowercase()
            val allowedScheme = scheme in setOf("sms", "smsto", "mms", "mmsto")
            val query = if (allowedScheme) data?.encodedSchemeSpecificPart?.substringAfter('?', "").orEmpty() else ""
            val queryUri = Uri.parse("content://compose/?$query")
            val rawAddress = if (allowedScheme) Uri.decode(data?.encodedSchemeSpecificPart?.substringBefore('?').orEmpty()).removePrefix("//") else ""
            val candidates = buildList {
                if (rawAddress.isNotBlank()) add(rawAddress)
                listOf("to", "address").forEach { key -> addAll(runCatching { queryUri.getQueryParameters(key) }.getOrDefault(emptyList()).filter { it.isNotBlank() }) }
                intent.getStringExtra("address")?.takeIf { it.isNotBlank() }?.let(::add)
                val emailRecipients = intent.getStringArrayExtra(Intent.EXTRA_EMAIL)
                if (intent.hasExtra(Intent.EXTRA_EMAIL) && emailRecipients == null) throw IllegalArgumentException("invalid recipient extra")
                emailRecipients?.filter { it.isNotBlank() }?.let(::addAll)
            }
            val hasAdditionalRecipients = listOf("cc", "bcc").any { key ->
                runCatching { queryUri.getQueryParameters(key) }.getOrDefault(emptyList()).any { it.isNotBlank() }
            } || listOf(Intent.EXTRA_CC, Intent.EXTRA_BCC).any { key ->
                intent.hasExtra(key) && (intent.getStringArrayExtra(key)?.isNotEmpty() ?: true)
            }
            val normalized = candidates.map { MmsRecipient.normalize(it) }
            val unique = normalized.filterNotNull().distinct()
            var error: String? = if (hasAdditionalRecipients || normalized.any { it == null } || unique.size > 1) "此入口仅支持单人彩信，请重新确认一个电话号码；原收件人未被采用" else null
            val recipient = if (error == null) unique.singleOrNull().orEmpty() else ""
            val shares = intent.action in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)
            val declaredMime = intent.type?.substringBefore(';')?.trim()?.lowercase(java.util.Locale.ROOT).orEmpty()
            val attachmentExpected = shares && (intent.action == Intent.ACTION_SEND_MULTIPLE || intent.hasExtra(Intent.EXTRA_STREAM) ||
                data?.scheme in setOf("content", "file") || declaredMime.isNotEmpty() && declaredMime !in setOf("text/plain", "text/html"))
            val uris = if (shares) buildList<Uri> {
                if (intent.hasExtra(Intent.EXTRA_STREAM)) {
                    if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
                        val entries = intent.getParcelableArrayListExtra<android.os.Parcelable>(Intent.EXTRA_STREAM)
                            ?: throw IllegalArgumentException("invalid stream list")
                        if (entries.size > 10) throw IllegalArgumentException("too many streams")
                        entries.forEach { entry -> add(entry as? Uri ?: throw IllegalArgumentException("invalid stream")) }
                    } else {
                        val entry = intent.getParcelableExtra<android.os.Parcelable>(Intent.EXTRA_STREAM)
                        add(entry as? Uri ?: throw IllegalArgumentException("invalid stream"))
                    }
                }
                data?.takeIf { it.scheme in setOf("content", "file") }?.let(::add)
                intent.clipData?.let { clip ->
                    if (clip.itemCount > 10) throw IllegalArgumentException("too many streams")
                    for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let(::add)
                }
            }.distinct() else emptyList()
            if (attachmentExpected && uris.isEmpty()) error = "分享附件缺失，请重新选择文件，或明确移除未导入附件后继续"
            val validUris = if (uris.size > 10 || uris.any { it.scheme != "content" }) {
                error = "只接受最多 10 个已授权的内容附件，请重新选择文件"; emptyList()
            } else uris
            val body = (intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                ?: intent.getStringExtra("sms_body") ?: runCatching { queryUri.getQueryParameter("body") }.getOrNull()).orEmpty()
            val subject = (intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: runCatching { queryUri.getQueryParameter("subject") }.getOrNull()).orEmpty()
            if (body.length > 1024 * 1024 || subject.length > 4096) error = "分享文字过长，请在编辑器中重新填写"
            return MmsExternalInput(recipient, body.takeIf { it.length <= 1024 * 1024 }.orEmpty(), subject.takeIf { it.length <= 4096 }.orEmpty(), validUris, error,
                scheme in setOf("mms", "mmsto") || subject.isNotEmpty() || uris.isNotEmpty() || attachmentExpected)
        }
    }
}
