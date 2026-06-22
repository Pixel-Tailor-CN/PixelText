package vip.mystery0.pixel.text.data.resource

import android.content.Context
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi

class BundledResourceVersionProvider(context: Context) {
    private val assets = context.applicationContext.assets

    fun read(): BundledResourceVersions {
        return runCatching {
            assets.open(BUNDLED_RESOURCE_VERSIONS_FILE)
                .bufferedReader(Charsets.UTF_8)
                .use { reader ->
                    bundledResourceVersionsAdapter.fromJson(reader.readText())
                }
                ?: BundledResourceVersions()
        }.getOrDefault(BundledResourceVersions())
    }

    private companion object {
        private const val BUNDLED_RESOURCE_VERSIONS_FILE = "bundled_resource_versions.json"
        private val bundledResourceVersionsAdapter = Moshi.Builder()
            .build()
            .adapter(BundledResourceVersions::class.java)
    }
}

@JsonClass(generateAdapter = true)
data class BundledResourceVersions(
    val rulesVersion: String? = null,
    val spamModelVersion: String? = null,
)
