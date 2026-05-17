package vip.mystery0.pixel.text.data.source

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.util.Log

private const val CONTACT_TAG = "ContactDataSource"

class ContactDataSource(
    private val context: Context,
    private val contentResolver: ContentResolver
) {
    private val contactNameCache = mutableMapOf<String, String?>()
    private var contactNameCacheLoaded = false

    fun getDisplayName(address: String): String? {
        if (address.isBlank()) return null
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return null
        }

        ensureContactNameCacheLoaded()

        for (key in contactLookupKeys(address)) {
            if (contactNameCache.containsKey(key)) return contactNameCache[key]
        }
        return null
    }

    private fun ensureContactNameCacheLoaded() {
        if (contactNameCacheLoaded) return

        try {
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                ),
                null,
                null,
                null
            )?.use { cursor ->
                val numberIndex =
                    cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val nameIndex =
                    cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)

                while (cursor.moveToNext()) {
                    val number = cursor.getString(numberIndex).orEmpty()
                    val displayName = cursor.getString(nameIndex)?.takeIf { it.isNotBlank() }
                    if (displayName == null) continue

                    contactLookupKeys(number).forEach { key ->
                        contactNameCache.putIfAbsent(key, displayName)
                    }
                }
            }
            contactNameCacheLoaded = true
        } catch (e: SecurityException) {
            Log.w(CONTACT_TAG, "missing contacts permission while loading contact names", e)
        } catch (e: Exception) {
            Log.e(CONTACT_TAG, "failed to load contact names", e)
        }
    }

    private fun contactLookupKeys(number: String): Set<String> {
        val normalized = PhoneNumberUtils.normalizeNumber(number)
        val digits = number.filter { it.isDigit() }
        val withoutChinaCountryCode = when {
            digits.startsWith("0086") -> digits.removePrefix("0086")
            digits.startsWith("86") && digits.length > 11 -> digits.removePrefix("86")
            else -> digits
        }

        return buildSet {
            if (number.isNotBlank()) add(number)
            if (normalized.isNotBlank()) add(normalized)
            if (digits.isNotBlank()) add(digits)
            if (withoutChinaCountryCode.isNotBlank()) add(withoutChinaCountryCode)
        }
    }
}
