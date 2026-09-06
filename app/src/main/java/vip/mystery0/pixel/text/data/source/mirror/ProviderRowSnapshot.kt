package vip.mystery0.pixel.text.data.source.mirror

import android.database.Cursor
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import vip.mystery0.pixel.text.domain.model.mirror.RawProviderValue

/** 对每个真实列记录 Cursor 类型；缺列与 SQL NULL 保持不同。 */
data class ProviderRowSnapshot(val values: List<RawProviderValue>) {
    fun string(column: String): String? = values.firstOrNull { it.column == column }?.value
    fun long(column: String): Long? = string(column)?.toLongOrNull()
    fun int(column: String): Int? = long(column)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
    fun has(column: String): Boolean = values.any { it.column == column }
    fun encode(): String = JSONArray().apply {
        values.forEach { value -> put(JSONObject().apply {
            put("column", value.column)
            put("type", value.type)
            put("value", value.value ?: JSONObject.NULL)
        }) }
    }.toString()

    companion object {
        fun fromCursor(cursor: Cursor): ProviderRowSnapshot = ProviderRowSnapshot(
            cursor.columnNames.mapIndexed { index, column ->
                val type = cursor.getType(index)
                val value = when (type) {
                    Cursor.FIELD_TYPE_NULL -> null
                    Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index).toString()
                    Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index).toString()
                    Cursor.FIELD_TYPE_BLOB -> Base64.encodeToString(cursor.getBlob(index), Base64.NO_WRAP)
                    else -> cursor.getString(index)
                }
                RawProviderValue(column, type, value)
            },
        )

        fun decode(encoded: String): ProviderRowSnapshot {
            val array = JSONArray(encoded)
            return ProviderRowSnapshot((0 until array.length()).map { index ->
                val value = array.getJSONObject(index)
                RawProviderValue(value.getString("column"), value.getInt("type"),
                    if (value.isNull("value")) null else value.getString("value"))
            })
        }
    }
}
