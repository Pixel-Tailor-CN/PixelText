package vip.mystery0.pixel.text.ui.message.mms

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.provider.ContactsContract
import vip.mystery0.pixel.text.domain.model.mms.MmsCalendarModel
import vip.mystery0.pixel.text.domain.model.mms.MmsContactModel

/** 用户点击后只打开系统编辑页，由用户决定是否保存。 */
internal fun contactInsertIntent(contact: MmsContactModel): Intent {
    val rows = arrayListOf<ContentValues>()
    fun row(mime: String, value: String, column: String, label: String = "", typeColumn: String? = null, labelColumn: String? = null) {
        rows += ContentValues().apply {
            put(ContactsContract.Data.MIMETYPE, mime)
            put(column, value)
            if (label.isNotBlank() && typeColumn != null && labelColumn != null) {
                put(typeColumn, 0)
                put(labelColumn, label)
            }
        }
    }
    contact.phones.forEach { row(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, it.value,
        ContactsContract.CommonDataKinds.Phone.NUMBER, it.label, ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.LABEL) }
    contact.emails.forEach { row(ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE, it.value,
        ContactsContract.CommonDataKinds.Email.ADDRESS, it.label, ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.LABEL) }
    contact.addresses.forEach { row(ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE, it.value,
        ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS, it.label, ContactsContract.CommonDataKinds.StructuredPostal.TYPE, ContactsContract.CommonDataKinds.StructuredPostal.LABEL) }
    if (contact.organization != null || contact.title != null) rows += ContentValues().apply {
        put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE)
        put(ContactsContract.CommonDataKinds.Organization.COMPANY, contact.organization)
        put(ContactsContract.CommonDataKinds.Organization.TITLE, contact.title)
    }
    contact.notes?.let { row(ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE, it, ContactsContract.CommonDataKinds.Note.NOTE) }
    return Intent(Intent.ACTION_INSERT).setType(ContactsContract.Contacts.CONTENT_TYPE)
        .putExtra(ContactsContract.Intents.Insert.NAME, contact.name)
        .putParcelableArrayListExtra(ContactsContract.Intents.Insert.DATA, rows)
}

internal fun calendarInsertIntent(event: MmsCalendarModel): Intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).apply {
    require(event.canImport)
    putExtra(CalendarContract.Events.TITLE, event.title ?: "未命名事件")
    putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, requireNotNull(event.start).toEpochMilli())
    putExtra(CalendarContract.EXTRA_EVENT_END_TIME, requireNotNull(event.end).toEpochMilli())
    putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, event.allDay)
    putExtra(CalendarContract.Events.EVENT_TIMEZONE, event.zoneId)
    putExtra(CalendarContract.Events.EVENT_END_TIMEZONE, event.zoneId)
    putExtra(CalendarContract.Events.EVENT_LOCATION, event.location)
    putExtra(CalendarContract.Events.DESCRIPTION, event.description)
}

internal fun launchMmsStructuredIntent(context: Context, intent: Intent, feedback: (String) -> Unit) =
    launchMmsStructuredIntent(context, feedback) { intent }

/** 把构造和启动放在同一保护范围，防止派生字段无法转换成系统参数。 */
internal fun launchMmsStructuredIntent(context: Context, feedback: (String) -> Unit, createIntent: () -> Intent) {
    try {
        context.startActivity(createIntent())
    } catch (_: ActivityNotFoundException) {
        feedback("未找到可处理此操作的应用，可保存或打开原件")
    } catch (_: SecurityException) {
        feedback("系统不允许打开此操作，可保存或打开原件")
    } catch (_: IllegalArgumentException) {
        feedback("此字段无法交给系统应用，请核对原件")
    } catch (_: ArithmeticException) {
        feedback("日期或时长超出系统支持范围，请打开原件")
    }
}
