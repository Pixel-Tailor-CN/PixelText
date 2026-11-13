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
import vip.mystery0.pixel.text.R
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
                    context.getString(R.string.domain_mms_contact_export_select_system)
                }
                phase = "contact"
                val resolver = context.contentResolver
                val (contactId, displayName) = resolver.query(
                    contactUri,
                    arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
                    null, null, null,
                )?.use { cursor ->
                    check(cursor.moveToFirst()) { context.getString(R.string.domain_mms_contact_export_not_found) }
                    cursor.getLong(0) to cursor.getString(1).orEmpty()
                } ?: error(context.getString(R.string.domain_mms_contact_export_read_failed))
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
                } ?: error(context.getString(R.string.domain_mms_contact_export_phones_failed))
                check(numbers.isNotEmpty()) { context.getString(R.string.domain_mms_contact_export_no_phones) }
                val name = displayName.ifBlank { numbers.first() }
                phase = "encode"
                val card = VCard().apply {
                    setFormattedName(name)
                    structuredName = StructuredName()
                    numbers.forEach { addTelephoneNumber(it) }
                }
                val encoded = Ezvcard.write(card).version(VCardVersion.V3_0).prodId(false).go()
                phase = "write"
                val directory = File(context.cacheDir, "mms-share/contacts")
                check(directory.isDirectory || directory.mkdirs()) {
                    context.getString(R.string.domain_mms_contact_export_save_failed)
                }
                val file = File(directory, "${UUID.randomUUID()}.vcf")
                temporary = file
                file.writeText(encoded, Charsets.UTF_8)
                val filename = name.filter { !it.isISOControl() && it !in "\\/" }.take(60)
                    .ifBlank { context.getString(R.string.domain_mms_contact_export_default_filename) } + ".vcf"
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
