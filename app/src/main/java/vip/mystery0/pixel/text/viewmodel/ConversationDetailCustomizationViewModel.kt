package vip.mystery0.pixel.text.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import vip.mystery0.pixel.text.domain.theme.ConversationDetailAppearance
import vip.mystery0.pixel.text.domain.theme.DEFAULT_CONVERSATION_DETAIL_TEXT_SCALE
import vip.mystery0.pixel.text.domain.theme.MAX_CONVERSATION_DETAIL_TEXT_SCALE
import vip.mystery0.pixel.text.domain.theme.MIN_CONVERSATION_DETAIL_TEXT_SCALE
import vip.mystery0.pixel.text.domain.theme.ThemeAssetRepository
import vip.mystery0.pixel.text.domain.theme.ThemeColorReference
import vip.mystery0.pixel.text.domain.theme.ThemeConfiguration
import vip.mystery0.pixel.text.domain.theme.ThemeConfigurationRepository
import vip.mystery0.pixel.text.domain.theme.ThemeImageDraft
import vip.mystery0.pixel.text.domain.theme.ThemeImageReference
import vip.mystery0.pixel.text.domain.theme.ThemeMode
import vip.mystery0.pixel.text.domain.theme.appearance
import vip.mystery0.pixel.text.domain.theme.normalized
import vip.mystery0.pixel.text.domain.theme.withAppearance
import vip.mystery0.pixel.text.ui.theme.HighTextContrastMonitor

data class ConversationDetailCustomizationUiState(
    val persistedTheme: ThemeConfiguration = ThemeConfiguration(),
    val draftTheme: ThemeConfiguration = ThemeConfiguration(),
    val previewMode: ThemeMode = ThemeMode.LIGHT,
    val highTextContrastEnabled: Boolean = false,
    val isSaving: Boolean = false,
    val hasUnsavedChanges: Boolean = false,
)

sealed interface ThemeCustomizationEvent {
    data class Message(val text: String) : ThemeCustomizationEvent
    data object Saved : ThemeCustomizationEvent
    data object SavedAndExit : ThemeCustomizationEvent
}

class ConversationDetailCustomizationViewModel(
    private val themeRepository: ThemeConfigurationRepository,
    private val themeAssetRepository: ThemeAssetRepository,
    private val highTextContrastMonitor: HighTextContrastMonitor,
) : ViewModel() {
    private val imageDrafts = mutableMapOf<ThemeMode, ThemeImageDraft>()
    private val selectionJobs = mutableMapOf<ThemeMode, Job>()

    private val initialConfiguration = themeRepository.configuration.value
    // Snapshot the draft originated from; used for three-way merge on save.
    private var baseConfiguration: ThemeConfiguration = initialConfiguration
    private val _uiState = MutableStateFlow(
        ConversationDetailCustomizationUiState(
            persistedTheme = initialConfiguration,
            draftTheme = initialConfiguration,
            highTextContrastEnabled = highTextContrastMonitor.enabled.value,
            hasUnsavedChanges = false,
        ),
    )
    val uiState: StateFlow<ConversationDetailCustomizationUiState> = _uiState.asStateFlow()

    private val _events = Channel<ThemeCustomizationEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            themeAssetRepository.cleanStaleDrafts()
        }
        viewModelScope.launch {
            combine(
                themeRepository.configuration,
                highTextContrastMonitor.enabled,
            ) { configuration, highTextContrastEnabled ->
                configuration to highTextContrastEnabled
            }.collect { (configuration, highTextContrastEnabled) ->
                // Rebase / dirty handling is centralized in publish (including stale-clean).
                publish(
                    persistedTheme = configuration,
                    highTextContrastEnabled = highTextContrastEnabled,
                )
            }
        }
    }

    fun setPreviewMode(mode: ThemeMode) {
        if (_uiState.value.previewMode == mode) {
            return
        }
        publish(previewMode = mode)
    }

    fun setReceivedTextColor(reference: ThemeColorReference?) {
        updateCurrentAppearance { it.copy(receivedTextColor = reference) }
    }

    fun setSentTextColor(reference: ThemeColorReference?) {
        updateCurrentAppearance { it.copy(sentTextColor = reference) }
    }

    fun setReceivedBubbleColor(reference: ThemeColorReference?) {
        updateCurrentAppearance { it.copy(receivedBubbleColor = reference) }
    }

    fun setSentBubbleColor(reference: ThemeColorReference?) {
        updateCurrentAppearance { it.copy(sentBubbleColor = reference) }
    }

    fun setInputBackgroundColor(reference: ThemeColorReference?) {
        updateCurrentAppearance { it.copy(inputBackgroundColor = reference) }
    }

    fun setShowSimInfo(show: Boolean) {
        val current = _uiState.value
        if (current.draftTheme.conversationDetail.showSimInfo == show) {
            return
        }
        publish(
            draftTheme = current.draftTheme.copy(
                conversationDetail = current.draftTheme.conversationDetail.copy(
                    showSimInfo = show,
                ),
            ),
        )
    }

    fun setInputPlaceholder(value: String) {
        val current = _uiState.value
        if (current.draftTheme.conversationDetail.inputPlaceholder == value) {
            return
        }
        publish(
            draftTheme = current.draftTheme.copy(
                conversationDetail = current.draftTheme.conversationDetail.copy(
                    inputPlaceholder = value,
                ),
            ),
        )
    }

    fun setTextScale(value: Float) {
        val scale = value
            .takeIf { it.isFinite() }
            ?.coerceIn(
                MIN_CONVERSATION_DETAIL_TEXT_SCALE,
                MAX_CONVERSATION_DETAIL_TEXT_SCALE,
            )
            ?: DEFAULT_CONVERSATION_DETAIL_TEXT_SCALE
        val current = _uiState.value
        if (current.draftTheme.conversationDetail.textScale == scale) {
            return
        }
        publish(
            draftTheme = current.draftTheme.copy(
                conversationDetail = current.draftTheme.conversationDetail.copy(
                    textScale = scale,
                ),
            ),
        )
    }

    fun removeBackground(mode: ThemeMode) {
        cancelSelection(mode)
        imageDrafts.remove(mode)?.let { draft ->
            themeAssetRepository.discardDraft(draft)
        }
        val current = _uiState.value
        val module = current.draftTheme.conversationDetail
        val appearance = module.appearance(mode)
        if (appearance.backgroundImage == null) {
            // Still publish when a draft file was discarded so preview recomposes.
            publish(draftTheme = current.draftTheme)
            return
        }
        publish(
            draftTheme = current.draftTheme.copy(
                conversationDetail = module.withAppearance(
                    mode,
                    appearance.copy(backgroundImage = null),
                ),
            ),
        )
    }

    fun resetCurrentMode() {
        val current = _uiState.value
        val mode = current.previewMode
        cancelSelection(mode)
        imageDrafts.remove(mode)?.let { draft ->
            themeAssetRepository.discardDraft(draft)
        }
        val module = current.draftTheme.conversationDetail
        publish(
            draftTheme = current.draftTheme.copy(
                conversationDetail = module.withAppearance(
                    mode,
                    ConversationDetailAppearance(),
                ),
            ),
        )
    }

    fun resetAll() {
        cancelAllSelections()
        discardAllImageDrafts()
        publish(draftTheme = ThemeConfiguration())
    }

    fun selectBackground(sourceUri: String) {
        if (_uiState.value.isSaving) {
            return
        }
        // Capture target mode before launch so previewMode switches cannot retarget the request.
        val mode = _uiState.value.previewMode
        cancelSelection(mode)
        val job = viewModelScope.launch {
            try {
                val result = themeAssetRepository.createDraftBackground(mode, sourceUri)
                if (!isActive) {
                    result.getOrNull()?.let(themeAssetRepository::discardDraft)
                    return@launch
                }
                result
                    .onSuccess { newDraft ->
                        // Main-thread continuation: bail before mutating maps if superseded/saving.
                        if (!isActive || _uiState.value.isSaving) {
                            themeAssetRepository.discardDraft(newDraft)
                            return@onSuccess
                        }
                        imageDrafts.put(mode, newDraft)?.let { previous ->
                            themeAssetRepository.discardDraft(previous)
                        }
                        val current = _uiState.value
                        val module = current.draftTheme.conversationDetail
                        val appearance = module.appearance(mode).copy(
                            backgroundImage = draftImageReference(newDraft),
                        )
                        publish(
                            draftTheme = current.draftTheme.copy(
                                conversationDetail = module.withAppearance(mode, appearance),
                            ),
                        )
                    }
                    .onFailure { error ->
                        if (error is CancellationException || !isActive) {
                            return@onFailure
                        }
                        Log.w(
                            TAG,
                            "theme background draft failed mode=$mode error=${error.message}",
                        )
                        _events.trySend(
                            ThemeCustomizationEvent.Message("图片处理失败，请重试"),
                        )
                    }
            } finally {
                val currentJob = coroutineContext[Job]
                if (currentJob != null && selectionJobs[mode] == currentJob) {
                    selectionJobs.remove(mode)
                }
            }
        }
        selectionJobs[mode] = job
    }

    fun resolvePreviewBackground(mode: ThemeMode): File? {
        imageDrafts[mode]?.let { draft ->
            themeAssetRepository.resolve(draft)?.let { return it }
        }
        val reference = _uiState.value.draftTheme.conversationDetail
            .appearance(mode)
            .backgroundImage
            ?: return null
        parseDraftId(reference)?.let { draftId ->
            return themeAssetRepository.resolve(
                ThemeImageDraft(draftId = draftId, mode = mode),
            )
        }
        return themeAssetRepository.resolve(reference)
    }

    /**
     * True when the draft references a background for [mode] but the image file cannot be resolved.
     * Used to prompt re-selection without snackbar spam.
     */
    fun isBackgroundMissing(mode: ThemeMode): Boolean {
        val reference = _uiState.value.draftTheme.conversationDetail
            .appearance(mode)
            .backgroundImage
            ?: return false
        return resolvePreviewBackground(mode) == null
    }

    fun save(exitAfterSave: Boolean = false) {
        val current = _uiState.value
        if (current.isSaving) {
            return
        }
        // Drop in-flight picks so they cannot mutate draft after the save snapshot/event.
        cancelAllSelections()
        if (!current.hasUnsavedChanges) {
            if (exitAfterSave) {
                viewModelScope.launch {
                    _events.send(ThemeCustomizationEvent.SavedAndExit)
                }
            }
            return
        }
        // Claim save synchronously before launching to prevent duplicate concurrent saves.
        val draftSnapshot = current.draftTheme
        val baseSnapshot = baseConfiguration
        publish(isSaving = true)
        viewModelScope.launch {
            val newlyCommitted = mutableListOf<ThemeImageReference>()
            var jsonSaved = false
            try {
                // Commit draft image files first so the mutex-protected merge only writes formal refs.
                val draftWithFormalBackgrounds = commitDraftBackgrounds(
                    draft = draftSnapshot,
                    newlyCommitted = newlyCommitted,
                )
                // Capture latest-before / merged-after inside the repository mutex transform.
                var latestBefore: ThemeConfiguration? = null
                var mergedAfter: ThemeConfiguration? = null
                val saveResult = themeRepository.update { latest ->
                    latestBefore = latest
                    val merged = mergeThemeConfigurations(
                        base = baseSnapshot,
                        draft = draftWithFormalBackgrounds,
                        latest = latest,
                    ).normalized()
                    mergedAfter = merged
                    merged
                }
                if (saveResult.isFailure) {
                    newlyCommitted.forEach(themeAssetRepository::deleteAsset)
                    Log.w(
                        TAG,
                        "theme config save failed error=${saveResult.exceptionOrNull()?.message}",
                    )
                    _events.send(
                        ThemeCustomizationEvent.Message("保存失败，请重试"),
                    )
                    return@launch
                }
                jsonSaved = true

                val persistedLatest = latestBefore
                    ?: error("theme save missing latest-before capture")
                val finalConfiguration = mergedAfter
                    ?: error("theme save missing merged-after capture")

                // Delete assets present on the actual pre-merge snapshot but no longer referenced.
                val oldReferences = persistedLatest.backgroundReferences()
                val newReferences = finalConfiguration.backgroundReferences()
                (oldReferences - newReferences).forEach(themeAssetRepository::deleteAsset)

                val draftsToDiscard = imageDrafts.values.toList()
                imageDrafts.clear()
                draftsToDiscard.forEach(themeAssetRepository::discardDraft)

                baseConfiguration = finalConfiguration
                publish(
                    persistedTheme = finalConfiguration,
                    draftTheme = finalConfiguration,
                    isSaving = false,
                )
                _events.send(
                    if (exitAfterSave) {
                        ThemeCustomizationEvent.SavedAndExit
                    } else {
                        ThemeCustomizationEvent.Saved
                    },
                )
            } catch (error: CancellationException) {
                if (!jsonSaved) {
                    newlyCommitted.forEach(themeAssetRepository::deleteAsset)
                }
                throw error
            } catch (error: Exception) {
                if (!jsonSaved) {
                    newlyCommitted.forEach(themeAssetRepository::deleteAsset)
                }
                Log.w(TAG, "theme config save failed error=${error.message}")
                _events.send(
                    ThemeCustomizationEvent.Message("保存失败，请重试"),
                )
            } finally {
                if (_uiState.value.isSaving) {
                    publish(isSaving = false)
                }
            }
        }
    }

    fun discardChanges() {
        if (_uiState.value.isSaving) {
            return
        }
        // Cancel in-flight picks before restoring persisted state so late completions cannot
        // recreate dirty draft/image entries after discard.
        cancelAllSelections()
        discardAllImageDrafts()
        val persisted = _uiState.value.persistedTheme
        baseConfiguration = persisted
        publish(draftTheme = persisted)
    }

    override fun onCleared() {
        // viewModelScope is already cancelling; clear job tracking and discard private drafts
        // synchronously without launching new scoped work.
        selectionJobs.clear()
        imageDrafts.values.forEach(themeAssetRepository::discardDraft)
        imageDrafts.clear()
    }

    private fun cancelSelection(mode: ThemeMode) {
        selectionJobs.remove(mode)?.cancel()
    }

    private fun cancelAllSelections() {
        if (selectionJobs.isEmpty()) {
            return
        }
        selectionJobs.values.forEach { job -> job.cancel() }
        selectionJobs.clear()
    }

    private fun updateCurrentAppearance(
        transform: (ConversationDetailAppearance) -> ConversationDetailAppearance,
    ) {
        val current = _uiState.value
        val mode = current.previewMode
        val module = current.draftTheme.conversationDetail
        val updatedAppearance = transform(module.appearance(mode))
        if (updatedAppearance == module.appearance(mode)) {
            return
        }
        publish(
            draftTheme = current.draftTheme.copy(
                conversationDetail = module.withAppearance(mode, updatedAppearance),
            ),
        )
    }

    /**
     * Three-way merge: apply only fields the user changed (draft vs base) onto the latest
     * persisted configuration so concurrent repository updates (e.g. pinch text-scale) are kept.
     */
    private fun mergeThemeConfigurations(
        base: ThemeConfiguration,
        draft: ThemeConfiguration,
        latest: ThemeConfiguration,
    ): ThemeConfiguration {
        val baseModule = base.conversationDetail
        val draftModule = draft.conversationDetail
        val latestModule = latest.conversationDetail
        return latest.copy(
            conversationDetail = latestModule.copy(
                light = mergeAppearance(baseModule.light, draftModule.light, latestModule.light),
                dark = mergeAppearance(baseModule.dark, draftModule.dark, latestModule.dark),
                showSimInfo = if (draftModule.showSimInfo != baseModule.showSimInfo) {
                    draftModule.showSimInfo
                } else {
                    latestModule.showSimInfo
                },
                inputPlaceholder = if (draftModule.inputPlaceholder != baseModule.inputPlaceholder) {
                    draftModule.inputPlaceholder
                } else {
                    latestModule.inputPlaceholder
                },
                textScale = if (draftModule.textScale != baseModule.textScale) {
                    draftModule.textScale
                } else {
                    latestModule.textScale
                },
            ),
        )
    }

    private fun mergeAppearance(
        base: ConversationDetailAppearance,
        draft: ConversationDetailAppearance,
        latest: ConversationDetailAppearance,
    ): ConversationDetailAppearance {
        return latest.copy(
            receivedTextColor = if (draft.receivedTextColor != base.receivedTextColor) {
                draft.receivedTextColor
            } else {
                latest.receivedTextColor
            },
            sentTextColor = if (draft.sentTextColor != base.sentTextColor) {
                draft.sentTextColor
            } else {
                latest.sentTextColor
            },
            receivedBubbleColor = if (draft.receivedBubbleColor != base.receivedBubbleColor) {
                draft.receivedBubbleColor
            } else {
                latest.receivedBubbleColor
            },
            sentBubbleColor = if (draft.sentBubbleColor != base.sentBubbleColor) {
                draft.sentBubbleColor
            } else {
                latest.sentBubbleColor
            },
            inputBackgroundColor = if (draft.inputBackgroundColor != base.inputBackgroundColor) {
                draft.inputBackgroundColor
            } else {
                latest.inputBackgroundColor
            },
            backgroundImage = if (draft.backgroundImage != base.backgroundImage) {
                draft.backgroundImage
            } else {
                latest.backgroundImage
            },
        )
    }

    private suspend fun commitDraftBackgrounds(
        draft: ThemeConfiguration,
        newlyCommitted: MutableList<ThemeImageReference>,
    ): ThemeConfiguration {
        val module = draft.conversationDetail
        val light = commitAppearanceBackground(
            appearance = module.light,
            mode = ThemeMode.LIGHT,
            newlyCommitted = newlyCommitted,
        )
        val dark = commitAppearanceBackground(
            appearance = module.dark,
            mode = ThemeMode.DARK,
            newlyCommitted = newlyCommitted,
        )
        return draft.copy(
            conversationDetail = module.copy(
                light = light,
                dark = dark,
            ),
        )
    }

    private suspend fun commitAppearanceBackground(
        appearance: ConversationDetailAppearance,
        mode: ThemeMode,
        newlyCommitted: MutableList<ThemeImageReference>,
    ): ConversationDetailAppearance {
        val reference = appearance.backgroundImage ?: return appearance
        val draftId = parseDraftId(reference) ?: return appearance
        val imageDraft = imageDrafts[mode]
            ?: error("missing theme image draft mode=$mode draft_id=$draftId")
        if (imageDraft.draftId != draftId) {
            error(
                "theme image draft mismatch mode=$mode expected=$draftId actual=${imageDraft.draftId}",
            )
        }
        val formal = themeAssetRepository.commitDraft(imageDraft).getOrElse { error ->
            throw error
        }
        newlyCommitted += formal
        return appearance.copy(backgroundImage = formal)
    }

    private fun discardAllImageDrafts() {
        if (imageDrafts.isEmpty()) {
            return
        }
        imageDrafts.values.forEach(themeAssetRepository::discardDraft)
        imageDrafts.clear()
    }

    private fun publish(
        persistedTheme: ThemeConfiguration = _uiState.value.persistedTheme,
        draftTheme: ThemeConfiguration = _uiState.value.draftTheme,
        previewMode: ThemeMode = _uiState.value.previewMode,
        highTextContrastEnabled: Boolean = _uiState.value.highTextContrastEnabled,
        isSaving: Boolean = _uiState.value.isSaving,
    ) {
        var nextBase = baseConfiguration
        var nextDraft = draftTheme
        val nextPersisted = persistedTheme

        // Stale-clean rebase: draft matches the old base while persisted moved on (external
        // update while dirty, then user undid back to base). Do not treat stale base as clean.
        // Skip while saving so in-flight save publication is not disrupted.
        if (!isSaving && nextDraft == nextBase && nextBase != nextPersisted) {
            if (imageDrafts.isNotEmpty()) {
                discardAllImageDrafts()
            }
            nextBase = nextPersisted
            nextDraft = nextPersisted
            baseConfiguration = nextBase
        }

        _uiState.value = ConversationDetailCustomizationUiState(
            persistedTheme = nextPersisted,
            draftTheme = nextDraft,
            previewMode = previewMode,
            highTextContrastEnabled = highTextContrastEnabled,
            isSaving = isSaving,
            hasUnsavedChanges = nextDraft != baseConfiguration,
        )
    }

    private fun ThemeConfiguration.backgroundReferences(): Set<ThemeImageReference> {
        return buildSet {
            conversationDetail.light.backgroundImage
                ?.takeUnless { parseDraftId(it) != null }
                ?.let(::add)
            conversationDetail.dark.backgroundImage
                ?.takeUnless { parseDraftId(it) != null }
                ?.let(::add)
        }
    }

    private companion object {
        private const val TAG = "ThemeCustomizationVM"
        private const val DRAFT_ASSET_PREFIX = "draft:"

        private fun draftImageReference(draft: ThemeImageDraft): ThemeImageReference {
            return ThemeImageReference(assetId = "$DRAFT_ASSET_PREFIX${draft.draftId}")
        }

        private fun parseDraftId(reference: ThemeImageReference): String? {
            val assetId = reference.assetId
            if (!assetId.startsWith(DRAFT_ASSET_PREFIX)) {
                return null
            }
            val draftId = assetId.removePrefix(DRAFT_ASSET_PREFIX)
            return draftId.takeIf { it.isNotEmpty() }
        }
    }
}
