package vip.mystery0.pixel.text.data.source.mms

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.FileProvider
import ezvcard.Ezvcard
import ezvcard.VCard
import ezvcard.VCardVersion
import ezvcard.property.StructuredName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** 只查询选中联系人的姓名和电话号码，不读取或复制系统完整名片。 */
class MmsContactExporter(private val context: Context) {
    data class Attachment(val uri: Uri, val file: File)

    suspend fun export(contactUri: Uri): Attachment {
        var temporary: File? = null
        var phase = "validate"
        try {
            return withContext(Dispatchers.IO) {
                // 系统选择器可能返回带 Android 用户前缀的 authority，例如 0@com.android.contacts。
                require(contactUri.scheme == "content" && contactUri.host == ContactsContract.AUTHORITY &&
                    contactUri.port == -1 && (contactUri.userInfo == null || contactUri.userInfo?.toIntOrNull() != null)) {
                    "请选择系统联系人"
                }
                phase = "contact"
                val resolver = context.contentResolver
                val (contactId, displayName) = resolver.query(
                    contactUri,
                    arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
                    null, null, null,
                )?.use { cursor ->
                    check(cursor.moveToFirst()) { "联系人已不存在，请重新选择" }
                    cursor.getLong(0) to cursor.getString(1).orEmpty()
                } ?: error("无法读取联系人，请重新选择")
                phase = "phones"
                val numbers = resolver.query(
                    // 保持选中 URI 的用户范围，不能用工作资料的联系人 ID 查询个人通讯录。
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI.buildUpon().encodedAuthority(contactUri.encodedAuthority).build(),
                    arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                    "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                    arrayOf(contactId.toString()),
                    "${ContactsContract.CommonDataKinds.Phone._ID} ASC",
                )?.use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) cursor.getString(0)?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
                    }.distinct()
                } ?: error("无法读取联系人电话，请重新选择")
                check(numbers.isNotEmpty()) { "该联系人没有电话号码，请选择其他联系人" }
                val name = displayName.ifBlank { numbers.first() }
                phase = "encode"
                val card = VCard().apply {
                    setFormattedName(name)
                    setStructuredName(StructuredName())
                    numbers.forEach { addTelephoneNumber(it) }
                }
                val encoded = Ezvcard.write(card).version(VCardVersion.V3_0).prodId(false).go()
                phase = "write"
                val directory = File(context.cacheDir, "mms-share/contacts")
                check(directory.isDirectory || directory.mkdirs()) { "无法保存联系人附件，请检查存储空间" }
                val file = File(directory, "${UUID.randomUUID()}.vcf")
                temporary = file
                file.writeText(encoded, Charsets.UTF_8)
                val filename = name.filter { !it.isISOControl() && it !in "\\/" }.take(60).ifBlank { "联系人" } + ".vcf"
                Attachment(FileProvider.getUriForFile(context, "${context.packageName}.mms.attachment", file, filename), file)
            }
        } catch (error: Exception) {
            // withContext 交接时取消也会走这里，避免留下无人接管的名片。
            temporary?.delete()
            if (error !is kotlinx.coroutines.CancellationException) android.util.Log.w("MmsContactExporter",
                "contact export failed phase=$phase type=${error.javaClass.simpleName}")
            throw error
        }
    }
}
