package vip.mystery0.pixel.text.data.source

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PickedPhone(
    val number: String,
    val displayName: String?,
)

interface PickedPhoneSource {
    suspend fun resolvePickedPhone(uri: Uri): PickedPhone?
}

class PickedPhoneSourceImpl(
    private val context: Context,
) : PickedPhoneSource {
    override suspend fun resolvePickedPhone(uri: Uri): PickedPhone? = withContext(Dispatchers.IO) {
        try {
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            )
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val number = if (numberIndex != -1) cursor.getString(numberIndex)?.trim() else null
                    val name = if (nameIndex != -1) cursor.getString(nameIndex)?.trim()?.takeIf { it.isNotBlank() } else null
                    if (!number.isNullOrBlank()) {
                        PickedPhone(number = number, displayName = name)
                    } else {
                        null
                    }
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}
