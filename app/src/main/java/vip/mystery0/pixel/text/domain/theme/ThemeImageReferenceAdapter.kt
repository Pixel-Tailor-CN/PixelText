package vip.mystery0.pixel.text.domain.theme

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.lang.reflect.Type

/**
 * Lenient adapter for nullable theme image references.
 *
 * Unknown or malformed `assetId` payloads are consumed and decoded as null so a single
 * bad background field cannot fail the entire [ThemeConfiguration] document.
 */
class ThemeImageReferenceAdapter : JsonAdapter<ThemeImageReference>() {
    override fun fromJson(reader: JsonReader): ThemeImageReference? {
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

    override fun toJson(writer: JsonWriter, value: ThemeImageReference?) {
        if (value == null) {
            writer.nullValue()
            return
        }
        writer.beginObject()
        writer.name(KEY_ASSET_ID)
        writer.value(value.assetId)
        writer.endObject()
    }

    private fun readObject(reader: JsonReader): ThemeImageReference? {
        var assetId: String? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                KEY_ASSET_ID -> assetId = readOptionalString(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        val normalized = assetId?.trim().orEmpty()
        if (normalized.isEmpty()) {
            return null
        }
        return ThemeImageReference(assetId = normalized)
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
        private const val KEY_ASSET_ID = "assetId"

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
                if (rawType != ThemeImageReference::class.java) {
                    return null
                }
                return ThemeImageReferenceAdapter().nullSafe()
            }
        }
    }
}
