package vip.mystery0.pixel.text.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import vip.mystery0.pixel.text.R
import vip.mystery0.pixel.text.data.resource.HubResourceStore
import vip.mystery0.pixel.text.data.source.PixelTextHubClient
import vip.mystery0.pixel.text.domain.hub.HubOperationResult
import vip.mystery0.pixel.text.domain.hub.HubResourceManifest
import vip.mystery0.pixel.text.domain.hub.ResourceUpdateAvailability
import vip.mystery0.pixel.text.domain.hub.ResourceUpdateDetail
import vip.mystery0.pixel.text.domain.parser.MessageParser
import vip.mystery0.pixel.text.domain.settings.AppSettingsKeys
import vip.mystery0.pixel.text.domain.settings.AppSettingsRepository
import vip.mystery0.pixel.text.util.AppStrings
import vip.mystery0.pixel.text.worker.VerificationCodeIndexScheduler
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import vip.mystery0.pixel.text.data.resource.HubModelBundle

class HubResourceRepository(
    private val client: PixelTextHubClient,
    private val store: HubResourceStore,
    private val settings: AppSettingsRepository,
    private val messageParser: MessageParser,
    private val verificationCodeIndexScheduler: VerificationCodeIndexScheduler,
    private val initializationGuard: vip.mystery0.pixel.text.data.repository.initialization.DataInitializationGuard,
    private val activateBundle: suspend (HubModelBundle, suspend () -> Unit) -> Boolean = { _, _ -> false },
) {
    private val updateMutex = Mutex()

    suspend fun checkManifest(): HubResourceManifest = client.fetchManifest().also { manifest ->
        manifest.spamModel?.let(store::requireCompatible)
    }

    suspend fun checkResourceUpdateAvailability(): ResourceUpdateAvailability {
        val manifest = checkManifest()
        if (manifest.rules == null && manifest.spamModel == null) {
            return ResourceUpdateAvailability.NoUpdate(
                AppStrings.get(R.string.domain_hub_no_compatible_resources),
            )
        }
        val currentRuleVersion = settings.getRuleResourceVersion()
        val currentModelVersion = store.activeModelBundle()?.version ?: AppSettingsKeys.DEFAULT_RESOURCE_VERSION
        val remoteRuleVersion = manifest.rules?.version ?: currentRuleVersion
        val remoteModelVersion =
            manifest.spamModel?.version ?: currentModelVersion
        return if (
            remoteRuleVersion == currentRuleVersion &&
            remoteModelVersion == currentModelVersion
        ) {
            ResourceUpdateAvailability.NoUpdate(
                AppStrings.get(R.string.domain_hub_already_latest),
            )
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
        return withContext(Dispatchers.IO) {
            updateMutex.withLock {
                runCatching {
                    manifest.spamModel?.let(store::requireCompatible)
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
                        expectedSize: Long,
                    ) {
                        require(expectedSize > 0) { "resource size missing" }
                        var reportedBytes = 0L
                        reportProgress(message)
                        client.downloadTo(url, target, maxSizeBytes = expectedSize) { currentBytes ->
                            val delta = (currentBytes - reportedBytes).coerceAtLeast(0L)
                            reportedBytes = currentBytes
                            completedBytes += delta
                            reportProgress(message)
                        }
                    }

                    onProgress(AppStrings.get(R.string.domain_hub_progress_preparing), 0f)
                    manifest.rules?.let { rules ->
                        val temp = store.tempFile("rules-${safeVersion(rules.version)}.json")
                        downloadTracked(
                            AppStrings.get(R.string.domain_hub_progress_downloading_rules),
                            rules.downloadUrl,
                            temp,
                            rules.sizeBytes,
                        )
                        store.verifySize(temp, rules.sizeBytes)
                        store.verifySha256(temp, rules.sha256)
                        onProgress(
                            AppStrings.get(R.string.domain_hub_progress_verifying_rules),
                            completedBytes.toProgress(totalBytes),
                        )
                        val rulesJson = temp.readText(Charsets.UTF_8)
                        initializationGuard.withInputMutation {
                            messageParser.replaceRules(rulesJson) {
                                store.activateRules(temp)
                                settings.setRuleResourceVersion(rules.version)
                            }
                            verificationCodeIndexScheduler.scheduleFullRebuild()
                        }
                    }

                    manifest.spamModel?.let { spamModel ->
                        val safeVersion = "${safeVersion(spamModel.version)}-${UUID.randomUUID()}"
                        val modelTemp = store.tempFile("spam-model-$safeVersion.tflite")
                        val vocabTemp = store.tempFile("vocab-$safeVersion.txt")
                        val contractTemp = store.tempFile("contract-$safeVersion.json")
                        downloadTracked(
                            AppStrings.get(R.string.domain_hub_progress_downloading_model),
                            spamModel.model.downloadUrl,
                            modelTemp,
                            spamModel.model.sizeBytes,
                        )
                        downloadTracked(
                            AppStrings.get(R.string.domain_hub_progress_downloading_vocab),
                            spamModel.vocab.downloadUrl,
                            vocabTemp,
                            spamModel.vocab.sizeBytes,
                        )
                        downloadTracked(
                            AppStrings.get(R.string.domain_hub_progress_downloading_contract),
                            spamModel.contract.downloadUrl,
                            contractTemp,
                            spamModel.contract.sizeBytes,
                        )
                        val candidate = store.stageModelBundle(modelTemp, vocabTemp, contractTemp, spamModel)
                        modelTemp.delete()
                        vocabTemp.delete()
                        contractTemp.delete()
                        onProgress(
                            AppStrings.get(R.string.domain_hub_progress_verifying_model),
                            completedBytes.toProgress(totalBytes),
                        )
                        // 与初始化扫描保持相同锁序：输入变更锁先于学习决策锁。
                        initializationGuard.withInputMutation {
                            check(activateBundle(candidate) {
                                store.requireCompatible(spamModel)
                                store.activateModelBundle(candidate)
                                settings.setSpamModelResourceVersion(spamModel.version)
                                settings.setVocabResourceVersion(spamModel.version)
                            }) { AppStrings.get(R.string.domain_hub_error_model_update_incomplete) }
                        }
                    }

                    settings.setResourceUpdatedAt(System.currentTimeMillis())
                    onProgress(AppStrings.get(R.string.domain_hub_progress_complete), 1f)
                    HubOperationResult.Success
                }.getOrElse { error ->
                    if (error is CancellationException) throw error
                    HubOperationResult.Failure(error.message ?: "update failed")
                }
            }
        }
    }

    suspend fun deleteDownloadedRules(): HubOperationResult = withContext(Dispatchers.IO) {
        runCatching {
            initializationGuard.withInputMutation {
                messageParser.useBundledRules {
                    store.deleteActiveRules()
                    settings.setRuleResourceVersion(AppSettingsKeys.DEFAULT_RESOURCE_VERSION)
                    settings.setResourceUpdatedAt(System.currentTimeMillis())
                }
                verificationCodeIndexScheduler.scheduleFullRebuild()
            }
            HubOperationResult.Success
        }.getOrElse { error ->
            HubOperationResult.Failure(error.message ?: "delete rules failed")
        }
    }

    suspend fun deleteDownloadedModel(): HubOperationResult = withContext(Dispatchers.IO) {
        updateMutex.withLock {
            runCatching {
                val candidate = store.builtInModelBundle()
                initializationGuard.withInputMutation {
                    check(activateBundle(candidate) {
                        store.activateModelBundle(candidate)
                        settings.setSpamModelResourceVersion(AppSettingsKeys.DEFAULT_RESOURCE_VERSION)
                        settings.setVocabResourceVersion(AppSettingsKeys.DEFAULT_RESOURCE_VERSION)
                        settings.setResourceUpdatedAt(System.currentTimeMillis())
                    }) { AppStrings.get(R.string.domain_hub_error_model_switch_incomplete) }
                }
                HubOperationResult.Success
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                HubOperationResult.Failure(error.message ?: "delete model failed")
            }
        }
    }

    private fun safeVersion(version: String): String {
        return version.replace(unsafeFileNameChars, "_")
    }

    private fun HubResourceManifest.toResourceUpdateDetail(): ResourceUpdateDetail {
        val notes = listOfNotNull(
            spamModel?.releaseNotes
                ?.takeIf { it.isNotBlank() }
                ?.let { AppStrings.get(R.string.domain_hub_release_note_model, it) },
            rules?.releaseNotes
                ?.takeIf { it.isNotBlank() }
                ?.let { AppStrings.get(R.string.domain_hub_release_note_rules, it) }
        ).joinToString(separator = "\n")

        val notProvided = AppStrings.get(R.string.domain_hub_version_not_provided)
        return ResourceUpdateDetail(
            modelVersion = spamModel?.version ?: notProvided,
            modelSizeBytes = spamModel?.model?.sizeBytes,
            ruleVersion = rules?.version ?: notProvided,
            ruleSizeBytes = rules?.sizeBytes,
            releaseNotes = notes.ifBlank { AppStrings.get(R.string.domain_hub_no_release_notes) }
        )
    }

    private fun HubResourceManifest.totalDownloadBytes(): Long {
        return listOfNotNull(
            rules?.sizeBytes,
            spamModel?.model?.sizeBytes,
            spamModel?.vocab?.sizeBytes,
            spamModel?.contract?.sizeBytes
        ).sum()
    }

    private fun Long.toProgress(totalBytes: Long): Float {
        return (toDouble() / totalBytes.coerceAtLeast(1L).toDouble())
            .toFloat()
            .coerceIn(0f, 1f)
    }

    private companion object {
        private val unsafeFileNameChars = Regex("[^A-Za-z0-9._-]")
    }
}
