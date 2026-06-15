package vip.mystery0.pixel.text.data.repository

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import vip.mystery0.pixel.text.BuildConfig
import vip.mystery0.pixel.text.data.resource.HubResourceStore
import vip.mystery0.pixel.text.data.source.PixelTextHubClient
import vip.mystery0.pixel.text.domain.hub.HubOperationResult
import vip.mystery0.pixel.text.domain.hub.HubResourceManifest
import vip.mystery0.pixel.text.domain.parser.MessageParser
import vip.mystery0.pixel.text.domain.settings.AppSettingsKeys
import vip.mystery0.pixel.text.domain.settings.AppSettingsRepository

class HubResourceRepository(
    private val client: PixelTextHubClient,
    private val store: HubResourceStore,
    private val settings: AppSettingsRepository,
    private val messageParser: MessageParser,
) {
    suspend fun checkManifest(): HubResourceManifest = client.fetchManifest()

    suspend fun updateAll(manifest: HubResourceManifest): HubOperationResult {
        return runCatching {
            manifest.rules?.let { rules ->
                if (rules.minAppVersionCode <= BuildConfig.VERSION_CODE) {
                    val temp = store.tempFile("rules-${safeVersion(rules.version)}.json")
                    client.downloadTo(rules.downloadUrl, temp)
                    store.verifySize(temp, rules.sizeBytes)
                    store.verifySha256(temp, rules.sha256)
                    verifyRulesJson(temp.readText(Charsets.UTF_8))
                    store.activateRules(temp)
                    settings.setRuleResourceVersion(rules.version)
                    messageParser.reloadRules()
                }
            }

            manifest.spamModel?.let { spamModel ->
                if (spamModel.minAppVersionCode <= BuildConfig.VERSION_CODE) {
                    val safeVersion = safeVersion(spamModel.version)
                    val modelTemp = store.tempFile("spam-model-$safeVersion.tflite")
                    val vocabTemp = store.tempFile("vocab-$safeVersion.txt")
                    client.downloadTo(spamModel.model.downloadUrl, modelTemp)
                    client.downloadTo(spamModel.vocab.downloadUrl, vocabTemp)
                    store.verifySize(modelTemp, spamModel.model.sizeBytes)
                    store.verifySize(vocabTemp, spamModel.vocab.sizeBytes)
                    store.verifySha256(modelTemp, spamModel.model.sha256)
                    store.verifySha256(vocabTemp, spamModel.vocab.sha256)
                    store.activateModelAndVocab(modelTemp, vocabTemp)
                    settings.setSpamModelResourceVersion(spamModel.version)
                    settings.setVocabResourceVersion(spamModel.version)
                }
            }

            settings.setResourceUpdatedAt(System.currentTimeMillis())
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
