package vip.mystery0.pixel.text.mms.outgoing

/** 编辑身份与系统消息身份分开；一个快照永远只有一个收件号码。 */
data class MmsDraft(
    val id: String,
    val revision: Long = 0,
    val recipientAddress: String = "",
    val body: String = "",
    val subject: String = "",
    val subscriptionId: Int = -1,
    val attachments: List<MmsAttachment> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
    val possibleDuplicateOf: String? = null,
    val importError: String? = null,
    val originRequestId: String? = null,
)

data class MmsAttachment(
    val id: String,
    val displayName: String,
    val originalMime: String,
    val originalPath: String,
    val originalSize: Long,
    val preparedPath: String? = null,
    val preparedMime: String? = null,
    val preparedSize: Long? = null,
    val preparedSha256: String? = null,
    val preparationNote: String? = null,
    val error: String? = null,
)

data class MmsSendPolicy(
    val subscriptionId: Int,
    val maxMessageBytes: Int,
    val maxImageWidth: Int,
    val maxImageHeight: Int,
    val maxSubjectLength: Int,
    val maxTextBytes: Int,
    val fingerprint: String,
)

data class MmsSendSnapshot(
    val recipientAddress: String,
    val body: String,
    val subject: String,
    val subscriptionId: Int,
    val policy: MmsSendPolicy,
    val attachments: List<MmsAttachment>,
    val createdAt: Long = System.currentTimeMillis(),
)

data class MmsPayloadPart(
    val contentId: String,
    val contentLocation: String,
    val mimeType: String,
    val bytes: ByteArray,
    val charset: Int = 0,
)

data class ComposedMms(
    val bytes: ByteArray,
    val parts: List<MmsPayloadPart>,
    val contentType: String,
)

enum class MmsSendState { PREPARING, READY, DISPATCHING, AWAITING_RESULT, SENT, FAILED, UNKNOWN, CANCELLED }

data class OutgoingMmsMessage(
    val requestId: String,
    val draftId: String,
    val draftRevision: Long,
    val snapshot: MmsSendSnapshot,
    val attemptToken: String,
    val transactionId: String,
    val state: MmsSendState,
    val sourceId: Long? = null,
    val providerReady: Boolean = false,
    val pduPath: String? = null,
    val pduSha256: String? = null,
    val submittedAt: Long? = null,
    val resultCode: Int? = null,
    val httpStatus: Int? = null,
    val responseStatus: Int? = null,
    val messageId: String? = null,
    val confirmation: String? = null,
    val error: String? = null,
    val providerSyncPending: Boolean = true,
    val deleted: Boolean = false,
    val possibleDuplicateOf: String? = null,
)

/** 只显示经过审核的类别，不把运营商响应、号码或文件路径泄漏给日志。 */
class MmsSendException(val userMessage: String) : Exception(userMessage)

object MmsRecipient {
    fun normalize(value: String): String? {
        val trimmed = value.trim()
        if (!trimmed.matches(Regex("\\+?[0-9][0-9 ()-]{1,30}"))) return null
        val normalized = trimmed.filter { it.isDigit() || it == '+' }
        return normalized.takeIf { it.removePrefix("+").length in 3..20 }
    }
}
