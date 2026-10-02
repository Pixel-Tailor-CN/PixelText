package vip.mystery0.pixel.text.mms.outgoing

import org.json.JSONArray
import org.json.JSONObject

/** 显式字段的私有格式；不反射枚举，不依赖 R8 保留 Kotlin 构造参数。 */
object MmsSnapshotCodec {
    private fun attachment(value: MmsAttachment) = JSONObject().apply {
        put("id", value.id); put("name", value.displayName); put("mime", value.originalMime)
        put("path", value.originalPath); put("size", value.originalSize)
        put("preparedPath", value.preparedPath); put("preparedMime", value.preparedMime)
        put("preparedSize", value.preparedSize); put("sha256", value.preparedSha256)
        put("note", value.preparationNote); put("error", value.error)
    }
    private fun JSONObject.nullable(key: String): String? = if (isNull(key)) null else getString(key)
    private fun attachment(value: JSONObject) = MmsAttachment(
        value.getString("id"), value.getString("name"), value.getString("mime"), value.getString("path"), value.getLong("size"),
        value.nullable("preparedPath"), value.nullable("preparedMime"),
        if (value.isNull("preparedSize")) null else value.getLong("preparedSize"),
        value.nullable("sha256"), value.nullable("note"), value.nullable("error"),
    )
    private fun attachments(value: List<MmsAttachment>) = JSONArray().apply { value.forEach { put(attachment(it)) } }
    private fun attachments(value: JSONArray) = (0 until value.length()).map { attachment(value.getJSONObject(it)) }
    fun encodeDraft(value: MmsDraft): String = JSONObject().apply {
        put("recipient", value.recipientAddress); put("body", value.body); put("subject", value.subject)
        put("subId", value.subscriptionId); put("attachments", attachments(value.attachments)); put("possibleDuplicateOf", value.possibleDuplicateOf); put("importError", value.importError); put("originRequestId", value.originRequestId)
    }.toString()
    fun decodeDraft(id: String, revision: Long, updatedAt: Long, value: String): MmsDraft {
        val json = JSONObject(value)
        return MmsDraft(id, revision, json.getString("recipient"), json.getString("body"), json.getString("subject"),
            json.getInt("subId"), attachments(json.getJSONArray("attachments")), updatedAt, json.nullable("possibleDuplicateOf"), json.nullable("importError"), json.nullable("originRequestId"))
    }
    fun encode(value: MmsSendSnapshot): String = JSONObject().apply {
        put("recipient", value.recipientAddress); put("body", value.body); put("subject", value.subject)
        put("subId", value.subscriptionId); put("createdAt", value.createdAt); put("attachments", attachments(value.attachments))
        put("policy", JSONObject().apply {
            put("subId", value.policy.subscriptionId); put("maxBytes", value.policy.maxMessageBytes)
            put("width", value.policy.maxImageWidth); put("height", value.policy.maxImageHeight)
            put("subject", value.policy.maxSubjectLength); put("text", value.policy.maxTextBytes)
            put("fingerprint", value.policy.fingerprint)
        })
    }.toString()
    fun decode(value: String): MmsSendSnapshot {
        val json = JSONObject(value); val policy = json.getJSONObject("policy")
        return MmsSendSnapshot(json.getString("recipient"), json.getString("body"), json.getString("subject"), json.getInt("subId"),
            MmsSendPolicy(policy.getInt("subId"), policy.getInt("maxBytes"), policy.getInt("width"), policy.getInt("height"),
                policy.getInt("subject"), policy.getInt("text"), policy.getString("fingerprint")),
            attachments(json.getJSONArray("attachments")), json.getLong("createdAt"))
    }
}
