package vip.mystery0.pixel.text.data.repository

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import vip.mystery0.pixel.text.data.resource.HubResourceStore
import vip.mystery0.pixel.text.data.source.PixelTextHubClient
import vip.mystery0.pixel.text.domain.hub.HubOperationResult
import vip.mystery0.pixel.text.domain.hub.HubResourceManifest
import vip.mystery0.pixel.text.domain.hub.ResourceUpdateAvailability
import vip.mystery0.pixel.text.domain.hub.ResourceUpdateDetail
import vip.mystery0.pixel.text.domain.parser.MessageParser
import vip.mystery0.pixel.text.domain.settings.AppSettingsKeys
import vip.mystery0.pixel.text.domain.settings.AppSettingsRepository
import java.io.File

class HubResourceRepository(
    private val client: PixelTextHubClient,
    private val store: HubResourceStore,
    private val settings: AppSettingsRepository,
    private val messageParser: MessageParser,
) {
    suspend fun checkManifest(): HubResourceManifest = client.fetchManifest()

    suspend fun checkResourceUpdateAvailability(): ResourceUpdateAvailability {
        val manifest = checkManifest()
        val remoteRuleVersion = manifest.rules?.version ?: AppSettingsKeys.DEFAULT_RESOURCE_VERSION
        val remoteModelVersion =
            manifest.spamModel?.version ?: AppSettingsKeys.DEFAULT_RESOURCE_VERSION
        val currentRuleVersion = settings.getRuleResourceVersion()
        val currentModelVersion = settings.getSpamModelResourceVersion()
        return if (
            remoteRuleVersion == currentRuleVersion &&
            remoteModelVersion == currentModelVersion
        ) {
            ResourceUpdateAvailability.NoUpdate("当前规则和模型已经是最新版本")
        } else {
            ResourceUpdateAvailability.Available(
                manifest = manifest,
                detail = manifest.toResourceUpdateDetail()
            )
        }
    }

    suspend fun updateAll(
        manifest: HubResourceManifest,
        onProgress: (message: String, progress: Float) -> Unit = { _, _ -> },
    ): HubOperationResult {
        return runCatching {
            val totalBytes = manifest.totalDownloadBytes().coerceAtLeast(1L)
            var completedBytes = 0L

            fun reportProgress(message: String) {
                onProgress(
                    message,
                    (completedBytes.toDouble() / totalBytes.toDouble())
                        .toFloat()
                        .coerceIn(0f, 1f)
                )
            }

            suspend fun downloadTracked(
                message: String,
                url: String,
                target: File,
            ) {
                var reportedBytes = 0L
                reportProgress(message)
                client.downloadTo(url, target) { currentBytes ->
                    val delta = (currentBytes - reportedBytes).coerceAtLeast(0L)
                    reportedBytes = currentBytes
                    completedBytes += delta
                    reportProgress(message)
                }
            }

            onProgress("正在准备下载资源", 0f)
            manifest.rules?.let { rules ->
                val temp = store.tempFile("rules-${safeVersion(rules.version)}.json")
                downloadTracked("正在下载智能卡片规则", rules.downloadUrl, temp)
                store.verifySize(temp, rules.sizeBytes)
                store.verifySha256(temp, rules.sha256)
                onProgress("正在校验智能卡片规则", completedBytes.toProgress(totalBytes))
                verifyRulesJson(temp.readText(Charsets.UTF_8))
                store.activateRules(temp)
                settings.setRuleResourceVersion(rules.version)
                messageParser.reloadRules()
            }

            manifest.spamModel?.let { spamModel ->
                val safeVersion = safeVersion(spamModel.version)
                val modelTemp = store.tempFile("spam-model-$safeVersion.tflite")
                val vocabTemp = store.tempFile("vocab-$safeVersion.txt")
                downloadTracked("正在下载离线模型", spamModel.model.downloadUrl, modelTemp)
                downloadTracked("正在下载模型词表", spamModel.vocab.downloadUrl, vocabTemp)
                store.verifySize(modelTemp, spamModel.model.sizeBytes)
                store.verifySize(vocabTemp, spamModel.vocab.sizeBytes)
                store.verifySha256(modelTemp, spamModel.model.sha256)
                store.verifySha256(vocabTemp, spamModel.vocab.sha256)
                onProgress("正在校验并启用离线模型", completedBytes.toProgress(totalBytes))
                store.activateModelAndVocab(modelTemp, vocabTemp)
                settings.setSpamModelResourceVersion(spamModel.version)
                settings.setVocabResourceVersion(spamModel.version)
            }

            settings.setResourceUpdatedAt(System.currentTimeMillis())
            onProgress("资源更新完成", 1f)
            HubOperationResult.Success
        }.getOrElse { error ->
            HubOperationResult.Failure(error.message ?: "update failed")
        }
    }

    suspend fun deleteDownloadedRules(): HubOperationResult = withContext(Dispatchers.IO) {
        runCatching {
            store.deleteActiveRules()
            settings.setRuleResourceVersion(AppSettingsKeys.DEFAULT_RESOURCE_VERSION)
            settings.setResourceUpdatedAt(System.currentTimeMillis())
            messageParser.reloadRules()
            HubOperationResult.Success
        }.getOrElse { error ->
            HubOperationResult.Failure(error.message ?: "delete rules failed")
        }
    }

    suspend fun deleteDownloadedModel(): HubOperationResult = withContext(Dispatchers.IO) {
        runCatching {
            store.deleteActiveModelAndVocab()
            settings.setSpamModelResourceVersion(AppSettingsKeys.DEFAULT_RESOURCE_VERSION)
            settings.setVocabResourceVersion(AppSettingsKeys.DEFAULT_RESOURCE_VERSION)
            settings.setResourceUpdatedAt(System.currentTimeMillis())
            HubOperationResult.Success
        }.getOrElse { error ->
            HubOperationResult.Failure(error.message ?: "delete model failed")
        }
    }

    private fun safeVersion(version: String): String {
        return version.replace(unsafeFileNameChars, "_")
    }

    private fun verifyRulesJson(json: String) {
        rulesFileAdapter.fromJson(json) ?: throw IllegalStateException("rules file empty")
    }

    private fun HubResourceManifest.toResourceUpdateDetail(): ResourceUpdateDetail {
        val notes = listOfNotNull(
            spamModel?.releaseNotes
                ?.takeIf { it.isNotBlank() }
                ?.let { "模型：$it" },
            rules?.releaseNotes
                ?.takeIf { it.isNotBlank() }
                ?.let { "规则：$it" }
        ).joinToString(separator = "\n")

        return ResourceUpdateDetail(
            modelVersion = spamModel?.version ?: "未提供",
            modelSizeBytes = spamModel?.model?.sizeBytes,
            ruleVersion = rules?.version ?: "未提供",
            ruleSizeBytes = rules?.sizeBytes,
            releaseNotes = notes.ifBlank { "暂无版本说明" }
        )
    }

    private fun HubResourceManifest.totalDownloadBytes(): Long {
        return listOfNotNull(
            rules?.sizeBytes,
            spamModel?.model?.sizeBytes,
            spamModel?.vocab?.sizeBytes
        ).sum()
    }

    private fun Long.toProgress(totalBytes: Long): Float {
        return (toDouble() / totalBytes.coerceAtLeast(1L).toDouble())
            .toFloat()
            .coerceIn(0f, 1f)
    }

    private companion object {
        private val unsafeFileNameChars = Regex("[^A-Za-z0-9._-]")
        private val rulesFileAdapter = Moshi.Builder()
            .build()
            .adapter(HubRulesFile::class.java)
    }
}

@JsonClass(generateAdapter = true)
internal data class HubRulesFile(
    val rules: List<HubRule>,
)

@JsonClass(generateAdapter = true)
internal data class HubRule(
    val id: String,
    @Json(name = "target_card")
    val targetCard: String,
    val conditions: HubRuleConditions,
)

@JsonClass(generateAdapter = true)
internal data class HubRuleConditions(
    @Json(name = "content_regex")
    val contentRegex: String,
)
