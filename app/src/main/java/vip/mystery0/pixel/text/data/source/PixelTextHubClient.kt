package vip.mystery0.pixel.text.data.source

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Streaming
import retrofit2.http.Url
import vip.mystery0.pixel.text.domain.hub.HubArtifact
import vip.mystery0.pixel.text.domain.hub.HubFileArtifact
import vip.mystery0.pixel.text.domain.hub.HubResourceManifest
import vip.mystery0.pixel.text.domain.hub.HubSpamModelArtifact
import vip.mystery0.pixel.text.domain.hub.SampleSubmissionRequest
import vip.mystery0.pixel.text.domain.model.SenderProfileManifest
import java.io.File
import java.util.concurrent.TimeUnit

class PixelTextHubClient(
    private val baseUrl: String,
) {
    private val service: PixelTextHubService by lazy {
        Retrofit.Builder()
            .baseUrl(normalizeBaseUrl(baseUrl))
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(PixelTextHubService::class.java)
    }

    suspend fun fetchManifest(): HubResourceManifest = withContext(Dispatchers.IO) {
        bodyOrThrow(service.fetchManifest(), "request failed").toDomain()
    }

    suspend fun submitSample(request: SampleSubmissionRequest) = withContext(Dispatchers.IO) {
        ensureSuccessful(service.submitSample(request.toBody()), "request failed")
    }

    suspend fun fetchSenderProfileManifest(): SenderProfileManifest? =
        withContext(Dispatchers.IO) {
            val response = service.fetchSenderProfileManifest()
            response.errorBody()?.close()
            when {
                response.code() == 204 -> null
                !response.isSuccessful ->
                    throw IllegalStateException("sender profile manifest failed status=${response.code()}")
                else -> response.body()?.toDomain()
                    ?: throw IllegalStateException("sender profile manifest empty body")
            }
        }

    suspend fun downloadSenderProfileTo(
        url: String,
        target: File,
        expectedSizeBytes: Long,
        maxSizeBytes: Long,
        onProgress: (Long) -> Unit = {},
    ): Long = withContext(Dispatchers.IO) {
        if (!url.startsWith("https://")) {
            throw IllegalStateException("sender profile download url must use https")
        }
        val response = service.download(url)
        if (!response.isSuccessful) {
            response.errorBody()?.close()
            throw SenderProfileDownloadException(response.code())
        }
        val body = response.body() ?: throw IllegalStateException("sender profile download empty body")
        val contentLength = body.contentLength()
        if (contentLength >= 0 && contentLength != expectedSizeBytes) {
            body.close()
            throw IllegalStateException(
                "sender profile content length mismatch expected=$expectedSizeBytes actual=$contentLength"
            )
        }
        target.parentFile?.mkdirs()
        runCatching {
            body.use {
                it.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var totalBytes = 0L
                        while (true) {
                            val readBytes = input.read(buffer)
                            if (readBytes < 0) break
                            totalBytes += readBytes
                            if (totalBytes > maxSizeBytes || totalBytes > expectedSizeBytes) {
                                throw IllegalStateException("sender profile download size exceeded")
                            }
                            output.write(buffer, 0, readBytes)
                            onProgress(totalBytes)
                        }
                    }
                }
            }
        }.onFailure { target.delete() }.getOrThrow()
        if (target.length() != expectedSizeBytes) {
            target.delete()
            throw IllegalStateException(
                "sender profile download size mismatch expected=$expectedSizeBytes actual=${target.length()}"
            )
        }
        target.length()
    }

    suspend fun downloadTo(
        url: String,
        target: File,
        onProgress: (Long) -> Unit = {},
    ): Long = withContext(Dispatchers.IO) {
        val response = service.download(url)
        if (!response.isSuccessful) {
            throw IllegalStateException("download failed status=${response.code()}")
        }
        val body = response.body() ?: throw IllegalStateException("download failed empty body")
        target.parentFile?.mkdirs()
        body.use {
            it.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var totalBytes = 0L
                    while (true) {
                        val readBytes = input.read(buffer)
                        if (readBytes < 0) break
                        output.write(buffer, 0, readBytes)
                        totalBytes += readBytes
                        onProgress(totalBytes)
                    }
                }
            }
        }
        target.length()
    }

    private fun ensureSuccessful(response: Response<ResponseBody>, prefix: String) {
        response.body()?.close()
        response.errorBody()?.close()
        if (!response.isSuccessful) {
            throw IllegalStateException("$prefix status=${response.code()}")
        }
    }

    private fun <T : Any> bodyOrThrow(response: Response<T>, prefix: String): T {
        response.errorBody()?.close()
        if (!response.isSuccessful) {
            throw IllegalStateException("$prefix status=${response.code()}")
        }
        return response.body() ?: throw IllegalStateException("$prefix empty body")
    }

    private fun normalizeBaseUrl(url: String): String {
        return if (url.endsWith('/')) url else "$url/"
    }

    private companion object {
        private val moshi = Moshi.Builder().build()
        private val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

private interface PixelTextHubService {
    @GET("api/v1/resources/manifest")
    suspend fun fetchManifest(): Response<HubResourceManifestResponse>

    @GET("api/v1/sender-profiles/manifest")
    suspend fun fetchSenderProfileManifest(): Response<SenderProfileManifestResponse>

    @POST("api/v1/samples")
    suspend fun submitSample(@Body body: SampleSubmissionBody): Response<ResponseBody>

    @Streaming
    @GET
    suspend fun download(@Url url: String): Response<ResponseBody>
}

@JsonClass(generateAdapter = true)
internal data class HubResourceManifestResponse(
    val rules: HubArtifactResponse?,
    val spamModel: HubSpamModelArtifactResponse?,
) {
    fun toDomain(): HubResourceManifest = HubResourceManifest(
        rules = rules?.toDomain(),
        spamModel = spamModel?.toDomain()
    )
}

@JsonClass(generateAdapter = true)
internal data class HubArtifactResponse(
    val version: String,
    val sha256: String,
    val sizeBytes: Long,
    val downloadUrl: String,
    val releaseNotes: String = "",
) {
    fun toDomain(): HubArtifact = HubArtifact(
        version = version,
        sha256 = sha256,
        sizeBytes = sizeBytes,
        downloadUrl = downloadUrl,
        releaseNotes = releaseNotes
    )
}

@JsonClass(generateAdapter = true)
internal data class HubFileArtifactResponse(
    val sha256: String,
    val sizeBytes: Long,
    val downloadUrl: String,
) {
    fun toDomain(): HubFileArtifact = HubFileArtifact(
        sha256 = sha256,
        sizeBytes = sizeBytes,
        downloadUrl = downloadUrl
    )
}

@JsonClass(generateAdapter = true)
internal data class HubSpamModelArtifactResponse(
    val version: String,
    val model: HubFileArtifactResponse,
    val vocab: HubFileArtifactResponse,
    val releaseNotes: String = "",
) {
    fun toDomain(): HubSpamModelArtifact = HubSpamModelArtifact(
        version = version,
        model = model.toDomain(),
        vocab = vocab.toDomain(),
        releaseNotes = releaseNotes
    )
}

@JsonClass(generateAdapter = true)
internal data class SenderProfileManifestResponse(
    val version: String,
    val schemaVersion: Int,
    val sha256: String,
    val sizeBytes: Long,
    val downloadUrl: String,
    val releaseNotes: String = "",
    val minAppVersionCode: Int,
    val publishedAt: String? = null,
) {
    fun toDomain() = SenderProfileManifest(
        version = version,
        schemaVersion = schemaVersion,
        sha256 = sha256,
        sizeBytes = sizeBytes,
        downloadUrl = downloadUrl,
        releaseNotes = releaseNotes,
        minAppVersionCode = minAppVersionCode,
        publishedAt = publishedAt,
    )
}

class SenderProfileDownloadException(val statusCode: Int) :
    IllegalStateException("sender profile download failed status=$statusCode")

@JsonClass(generateAdapter = true)
internal data class SampleSubmissionBody(
    val deviceId: String,
    val content: String,
    val sender: String?,
    val category: String,
    val appVersion: String,
    val ruleVersion: String,
    val modelVersion: String,
)

private fun SampleSubmissionRequest.toBody(): SampleSubmissionBody = SampleSubmissionBody(
    deviceId = deviceId,
    content = content,
    sender = sender,
    category = category,
    appVersion = appVersion,
    ruleVersion = ruleVersion,
    modelVersion = modelVersion
)
