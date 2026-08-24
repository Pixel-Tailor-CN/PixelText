package vip.mystery0.pixel.text.domain.theme

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.lang.reflect.Type

/**
 * Lenient adapter for nullable theme color references.
 *
 * Unknown or malformed `type`/`value` payloads are consumed and decoded as null so a single
 * bad color field cannot fail the entire [ThemeConfiguration] document.
 */
class ThemeColorReferenceAdapter : JsonAdapter<ThemeColorReference>() {
    override fun fromJson(reader: JsonReader): ThemeColorReference? {
        return when (reader.peek()) {
            JsonReader.Token.NULL -> {
                reader.nextNull<Unit>()
                null
            }
            JsonReader.Token.BEGIN_OBJECT -> readObject(reader)
            else -> {
                reader.skipValue()
                null
            }
        }
    }

    override fun toJson(writer: JsonWriter, value: ThemeColorReference?) {
        if (value == null) {
            writer.nullValue()
            return
        }
        writer.beginObject()
        writer.name(KEY_TYPE)
        writer.value(value.type.name)
        writer.name(KEY_VALUE)
        writer.value(value.value)
        writer.endObject()
    }

    private fun readObject(reader: JsonReader): ThemeColorReference? {
        var typeName: String? = null
        var rawValue: String? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                KEY_TYPE -> typeName = readOptionalString(reader)
                KEY_VALUE -> rawValue = readOptionalString(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        val type = typeName
            ?.let { name -> ThemeColorType.entries.firstOrNull { it.name == name } }
            ?: return null
        if (rawValue == null) {
            return null
        }
        return ThemeColorReference(type = type, value = rawValue)
    }

    private fun readOptionalString(reader: JsonReader): String? {
        return when (reader.peek()) {
            JsonReader.Token.NULL -> {
                reader.nextNull<Unit>()
                null
            }
            JsonReader.Token.STRING -> reader.nextString()
            else -> {
                reader.skipValue()
                null
            }
        }
    }

    companion object {
        private const val KEY_TYPE = "type"
        private const val KEY_VALUE = "value"

        val FACTORY = object : Factory {
            override fun create(
                type: Type,
                annotations: MutableSet<out Annotation>,
                moshi: Moshi,
            ): JsonAdapter<*>? {
                if (annotations.isNotEmpty()) {
                    return null
                }
                val rawType = Types.getRawType(type)
                if (rawType != ThemeColorReference::class.java) {
                    return null
                }
                return ThemeColorReferenceAdapter().nullSafe()
            }
        }
    }
}
