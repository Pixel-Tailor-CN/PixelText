package vip.mystery0.pixel.text.mms

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.provider.Telephony
import androidx.core.net.toUri
import vip.mystery0.pixel.text.mms.vendor.pdu.CharacterSets
import vip.mystery0.pixel.text.mms.vendor.pdu.EncodedStringValue
import vip.mystery0.pixel.text.mms.vendor.pdu.PduHeaders
import vip.mystery0.pixel.text.mms.vendor.pdu.PduPart
import vip.mystery0.pixel.text.mms.vendor.pdu.RetrieveConf
import java.nio.charset.Charset
import vip.mystery0.pixel.text.domain.parser.mms.MmsTextDecoder

/** 覆盖本次占位的内容；失败保留占位，重试重新写入，不创建第二条彩信。 */
class MmsProviderWriter(private val resolver: ContentResolver) {
    fun exists(mmsId: Long): Boolean = resolver.query(
        "content://mms/$mmsId".toUri(), arrayOf("_id"), null, null, null,
    )?.use { it.moveToFirst() } ?: error("mms existence query unavailable")

    fun persist(
        mmsId: Long, message: RetrieveConf,
        previousAddresses: List<Long>, saveOriginalAddresses: (List<Long>) -> Unit,
        threadId: Long? = null,
    ) {
        check(exists(mmsId)) { "mms deleted" }
        val uri = "content://mms/$mmsId".toUri()
        val partsUri = "$uri/part".toUri()
        val addressesUri = "$uri/addr".toUri()
        // 仅处理仍为通知占位的记录。完成后再次回调不重写已接收的内容。
        val type = resolver.query(uri, arrayOf("m_type"), null, null, null)
            ?.use { if (it.moveToFirst()) it.getInt(0) else null }
            ?: error("mms missing")
        if (type == PduHeaders.MESSAGE_TYPE_RETRIEVE_CONF) {
            previousAddresses.forEach { resolver.delete(addressesUri, "_id = ?", arrayOf(it.toString())) }
            return
        }
        check(type == PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND) { "unexpected mms type" }
        resolver.delete(partsUri, null, null)
        // 占位 FROM 保留至全部内容成功。新地址失败只回滚新地址，不损坏通知。
        val originalAddresses = resolver.query(addressesUri, arrayOf("_id"), null, null, null)
            ?.use { cursor -> buildList<Long> { while (cursor.moveToNext()) add(cursor.getLong(0)) } }
            ?: error("mms addresses unavailable")
        // 先落日志；完成标记后的崩溃也能接续清理原占位地址。
        saveOriginalAddresses(originalAddresses)
        val createdParts = mutableListOf<Uri>()
        val createdAddressIds = mutableListOf<Long>()
        var committed = false
        try {
            fun address(value: EncodedStringValue?, addressType: Int) {
                val encoded = value ?: return
                check(exists(mmsId)) { "mms deleted" }
                val values = ContentValues().apply {
                    put("address", MmsAddresses.clean(encoded.string))
                    put("charset", encoded.characterSet)
                    put("type", addressType)
                }
                val inserted = resolver.insert(addressesUri, values) ?: error("mms address insert failed")
                createdAddressIds += inserted.lastPathSegment?.toLongOrNull()
                    ?: error("mms address identity unavailable")
            }
            address(message.from, PduHeaders.FROM)
            message.to?.forEach { address(it, PduHeaders.TO) }
            message.cc?.forEach { address(it, PduHeaders.CC) }
            message.pduHeaders.getEncodedStringValues(PduHeaders.BCC)?.forEach { address(it, PduHeaders.BCC) }
            val body = message.body
            // Provider 没有父子列：先保存容器原件，再按深度优先顺序保存实际子项。
            // 展示层从容器原件和 CID/Content-Location 派生关系，不改写原始标识。
            val flattened = buildList {
                fun append(part: PduPart) {
                    add(part)
                    part.children?.let { children ->
                        for (child in 0 until children.partsNum) append(children.getPart(child))
                    }
                }
                for (index in 0 until body.partsNum) append(body.getPart(index))
            }
            for ((index, part) in flattened.withIndex()) {
                check(exists(mmsId)) { "mms deleted" }
                val contentType = part.contentType?.toString(Charsets.ISO_8859_1)
                    ?: "application/octet-stream"
                val bytes = part.data ?: byteArrayOf()
                val inlineText = contentType in setOf("text/plain", "application/smil")
                val values = ContentValues().apply {
                    put("seq", if (contentType == "application/smil") -1 else index)
                    put("ct", contentType)
                    put("chset", part.charset)
                    part.name?.let { put("name", it.toString(Charsets.ISO_8859_1)) }
                    part.filename?.let { put("fn", it.toString(Charsets.ISO_8859_1)) }
                    part.contentId?.let { put("cid", it.toString(Charsets.ISO_8859_1)) }
                    part.contentLocation?.let { put("cl", it.toString(Charsets.ISO_8859_1)) }
                    part.contentDisposition?.let { put("cd", it.toString(Charsets.ISO_8859_1)) }
                    if (inlineText) {
                        val decoded = MmsTextDecoder.decode(bytes, contentType, part.charset)
                        // 合法 BOM 与声明按共用规则解码；损坏输入沿用 Provider 的替换字符兼容行为。
                        val text = decoded.text ?: bytes.toString(runCatching {
                            Charset.forName(CharacterSets.getMimeName(part.charset))
                        }.getOrDefault(Charsets.UTF_8))
                        put("text", text)
                    }
                }
                // 系统 Provider 会用 name/cl 生成磁盘文件名。先让它生成安全存储名，
                // 再写回完整原标识，避免长名称或路径影响存储，也不破坏 SMIL/CID 匹配。
                val insertValues = ContentValues(values).apply {
                    if (!inlineText) {
                        remove("name")
                        remove("fn")
                        remove("cl")
                    }
                }
                val partUri = resolver.insert(partsUri, insertValues) ?: error("mms part insert failed")
                createdParts += partUri
                if (!inlineText) {
                    val output = resolver.openOutputStream(partUri) ?: error("mms part stream unavailable")
                    output.use { it.write(bytes) }
                    check(resolver.update(partUri, values, null, null) == 1) { "mms part metadata update failed" }
                }
            }
            check(exists(mmsId)) { "mms deleted" }
            val values = ContentValues().apply {
                threadId?.let { put("thread_id", it) }
                put("m_type", PduHeaders.MESSAGE_TYPE_RETRIEVE_CONF)
                put("v", message.mmsVersion)
                put("st", 0)
                put("retr_st", message.retrieveStatus)
                val headers = message.pduHeaders
                mapOf("pri" to PduHeaders.PRIORITY, "d_rpt" to PduHeaders.DELIVERY_REPORT,
                    "rr" to PduHeaders.READ_REPORT).forEach { (column, field) ->
                    headers.getOctet(field).takeIf { it != 0 }?.let { put(column, it) }
                }
                mapOf("m_size" to PduHeaders.MESSAGE_SIZE, "exp" to PduHeaders.EXPIRY)
                    .forEach { (column, field) -> headers.getLongInteger(field).takeIf { it >= 0 }?.let { put(column, it) } }
                message.messageClass?.let { put("m_cls", it.toString(Charsets.ISO_8859_1)) }
                message.retrieveText?.let {
                    put("retr_txt", it.textString.toString(Charsets.ISO_8859_1))
                    put("retr_txt_cs", it.characterSet)
                }
                put("ct_t", message.contentType?.toString(Charsets.ISO_8859_1))
                message.messageId?.let { put("m_id", it.toString(Charsets.ISO_8859_1)) }
                message.transactionId?.let { put("tr_id", it.toString(Charsets.ISO_8859_1)) }
                if (message.date > 0) put("date_sent", message.date)
                message.subject?.let {
                    put("sub", it.textString.toString(Charsets.ISO_8859_1))
                    put("sub_cs", it.characterSet)
                }
            }
            check(resolver.update(uri, values, null, null) == 1) { "mms completion update failed" }
            committed = true
            originalAddresses.forEach { originalId ->
                // 完成提交后的清理失败不能回滚已经成功的正文和附件。
                resolver.delete(addressesUri, "_id = ?", arrayOf(originalId.toString()))
            }
        } catch (error: Exception) {
            // 只清理本请求确实插入的子记录；不删除其他消息。
            if (!committed) {
                createdParts.asReversed().forEach { child -> runCatching { resolver.delete(child, null, null) } }
                // addr 插入返回的 /mms/addr/<id> 不是可删除单地址路由，必须使用消息父路由。
                createdAddressIds.asReversed().forEach { id ->
                    runCatching { resolver.delete(addressesUri, "_id = ?", arrayOf(id.toString())) }
                }
            }
            throw error
        }
    }
}
