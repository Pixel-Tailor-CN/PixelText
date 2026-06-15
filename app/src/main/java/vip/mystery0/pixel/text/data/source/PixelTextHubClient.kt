package vip.mystery0.pixel.text.data.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import vip.mystery0.pixel.text.domain.hub.HubArtifact
import vip.mystery0.pixel.text.domain.hub.HubFileArtifact
import vip.mystery0.pixel.text.domain.hub.HubResourceManifest
import vip.mystery0.pixel.text.domain.hub.HubSpamModelArtifact
import vip.mystery0.pixel.text.domain.hub.SampleSubmissionRequest
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

class PixelTextHubClient(
    private val baseUrl: String,
) {
    suspend fun fetchManifest(): HubResourceManifest = withContext(Dispatchers.IO) {
        val json = getJson("$baseUrl/api/v1/resources/manifest")
        HubResourceManifest(
            rules = json.optJSONObject("rules")?.toRulesArtifact(),
            spamModel = json.optJSONObject("spamModel")?.toSpamModelArtifact()
        )
    }

    suspend fun submitSample(request: SampleSubmissionRequest) = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("deviceId", request.deviceId)
            put("content", request.content)
            put("sender", request.sender)
            put("category", request.category)
            put("appVersion", request.appVersion)
            put("ruleVersion", request.ruleVersion)
            put("modelVersion", request.modelVersion)
        }.toString()
        postJson("$baseUrl/api/v1/samples", body)
    }

    suspend fun downloadTo(url: String, target: File): Long = withContext(Dispatchers.IO) {
        val connection = openConnection(url)
        try {
            connection.requestMethod = METHOD_GET
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("download failed status=${connection.responseCode}")
            }
            target.parentFile?.mkdirs()
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            target.length()
        } finally {
            connection.disconnect()
        }
    }

    private fun getJson(url: String): JSONObject {
        val connection = openConnection(url)
        return try {
            connection.requestMethod = METHOD_GET
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("request failed status=${connection.responseCode}")
            }
            JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private fun postJson(url: String, body: String) {
        val connection = openConnection(url)
        try {
            connection.requestMethod = METHOD_POST
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("request failed status=${connection.responseCode}")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String): HttpURLConnection {
        return URI(url).toURL().openConnection() as HttpURLConnection
    }

    private fun JSONObject.toRulesArtifact(): HubArtifact = HubArtifact(
        version = getString("version"),
        sha256 = getString("sha256"),
        sizeBytes = getLong("sizeBytes"),
        downloadUrl = getString("downloadUrl"),
        minAppVersionCode = getInt("minAppVersionCode"),
        releaseNotes = optString("releaseNotes")
    )

    private fun JSONObject.toFileArtifact(): HubFileArtifact = HubFileArtifact(
        sha256 = getString("sha256"),
        sizeBytes = getLong("sizeBytes"),
        downloadUrl = getString("downloadUrl")
    )

    private fun JSONObject.toSpamModelArtifact(): HubSpamModelArtifact = HubSpamModelArtifact(
        version = getString("version"),
        model = getJSONObject("model").toFileArtifact(),
        vocab = getJSONObject("vocab").toFileArtifact(),
        minAppVersionCode = getInt("minAppVersionCode"),
        releaseNotes = optString("releaseNotes")
    )

    private companion object {
        private const val METHOD_GET = "GET"
        private const val METHOD_POST = "POST"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 30_000
    }
}
