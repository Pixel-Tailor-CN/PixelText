package vip.mystery0.pixel.text.data.repository

import android.content.Context
import android.provider.Settings
import vip.mystery0.pixel.text.BuildConfig
import vip.mystery0.pixel.text.data.source.PixelTextHubClient
import vip.mystery0.pixel.text.domain.hub.HubOperationResult
import vip.mystery0.pixel.text.domain.hub.SampleSubmissionRequest
import vip.mystery0.pixel.text.domain.settings.AppSettingsRepository

class SampleSubmissionRepository(
    private val context: Context,
    private val client: PixelTextHubClient,
    private val settings: AppSettingsRepository,
) {
    suspend fun submit(
        content: String,
        sender: String?,
        category: String,
    ): HubOperationResult {
        val trimmedContent = content.trim()
        if (trimmedContent.length < MIN_SAMPLE_LENGTH) {
            return HubOperationResult.Failure("样本文本太短")
        }
        val deviceId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ).orEmpty()
        if (deviceId.isBlank()) {
            return HubOperationResult.Failure("无法读取设备标识")
        }
        return runCatching {
            client.submitSample(
                SampleSubmissionRequest(
                    deviceId = deviceId,
                    content = trimmedContent,
                    sender = sender?.trim()?.takeIf { it.isNotBlank() },
                    category = category,
                    appVersion = BuildConfig.VERSION_NAME,
                    ruleVersion = settings.getRuleResourceVersion(),
                    modelVersion = settings.getSpamModelResourceVersion()
                )
            )
            HubOperationResult.Success
        }.getOrElse { error ->
            HubOperationResult.Failure(error.message ?: "提交失败")
        }
    }

    private companion object {
        private const val MIN_SAMPLE_LENGTH = 6
    }
}
