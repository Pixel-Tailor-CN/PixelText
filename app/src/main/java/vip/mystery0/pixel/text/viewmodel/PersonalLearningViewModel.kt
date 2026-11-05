package vip.mystery0.pixel.text.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vip.mystery0.pixel.text.data.db.SpamDatabase
import vip.mystery0.pixel.text.data.resource.HubResourceStore
import vip.mystery0.pixel.text.data.spam.learning.ModelContract
import vip.mystery0.pixel.text.domain.hub.LearningFeedback
import vip.mystery0.pixel.text.domain.model.MessageModel
import vip.mystery0.pixel.text.domain.sample.sampleCategory
import vip.mystery0.pixel.text.domain.settings.AppSettingsRepository
import vip.mystery0.pixel.text.domain.spam.SenderWhitelistRepository
import vip.mystery0.pixel.text.domain.spam.learning.*
import java.util.concurrent.ConcurrentHashMap

enum class LearningRuleConflict {
    WHITELIST,
    KEYWORD,
}

data class LearningInteraction(
    val message: MessageModel? = null,
    val selectedLabel: LearningLabel? = null,
    val existingLabel: LearningLabel? = null,
    val linkedMessageCount: Int = 0,
    val ruleConflict: LearningRuleConflict? = null,
    val needsEnable: Boolean = false,
    val contributeSample: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
)

data class LearningSavedEvent(
    val label: LearningLabel,
    val ruleConflict: LearningRuleConflict?,
    val contribution: LearningContributionDraft? = null,
)

data class LearningContributionDraft(
    val content: String,
    val sender: String,
    val category: String,
    val feedback: LearningFeedback,
)

class PersonalLearningViewModel(
    private val repository: PersonalLearningRepository,
    private val coordinator: PersonalLearningCoordinator,
    private val settingsRepository: AppSettingsRepository,
    private val resources: HubResourceStore,
    private val whitelist: SenderWhitelistRepository,
    private val spamDatabase: SpamDatabase,
) : ViewModel() {
    val settings = settingsRepository.settings
    val samples = repository.observeSamples().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val learningState = combine(coordinator.state, settings) { state, _ ->
        val activeForCurrentBundle = withContext(Dispatchers.IO) {
            try {
                val bundle = resources.currentModelBundle()
                val contract = ModelContract.parse(bundle.contractFile.readText(Charsets.UTF_8))
                val parameters = repository.currentParameters(contract.identity)
                parameters != null &&
                    parameters.payload.size == contract.parameterCount &&
                    parameters.payload.all(Float::isFinite)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
        }
        state.copy(hasActiveParameters = activeForCurrentBundle)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        coordinator.state.value.copy(hasActiveParameters = false),
    )

    private val mutableInteraction = MutableStateFlow(LearningInteraction())
    val interaction = mutableInteraction.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    private val mutableUpdatingSample = MutableStateFlow(false)
    val updatingSample = mutableUpdatingSample.asStateFlow()

    private val _savedEvents = Channel<LearningSavedEvent>(Channel.BUFFERED)
    val savedEvents = _savedEvents.receiveAsFlow()

    // 保存当次记录时的基准版本和前序代次，使更多菜单“贡献样本”也能携带准确反馈快照。
    private val recordedFeedbackByKey = ConcurrentHashMap<LearningMessageKey, LearningFeedback>()

    fun begin(message: MessageModel) {
        val needsEnable = !settings.value.personalLearningEnabled || !settings.value.spamDetectionEnabled
        mutableInteraction.value = LearningInteraction(
            message = message,
            selectedLabel = null,
            needsEnable = needsEnable,
            error = if (message.content.isBlank()) "这条消息没有可学习的文本，图片和附件不参与学习" else null,
        )
        if (message.content.isBlank() || message.isMms) return
        viewModelScope.launch {
            try {
                val (existing, conflict) = withContext(Dispatchers.IO) {
                    val normalized = ModelContract.normalize(message.content)
                    // 使用包含关联计数的样本查询，避免 snapshot() 默认 linkedMessageCount=0 掩盖真实关联数。
                    val matchedSample = repository.observeSamples().first().firstOrNull {
                        ModelContract.normalize(it.text) == normalized
                    }
                    val ruleConflict = when {
                        whitelist.isAllowed(message.id) -> LearningRuleConflict.WHITELIST
                        message.id in spamDatabase.spamResultDao().keywordIds(listOf(message.id)) -> LearningRuleConflict.KEYWORD
                        else -> null
                    }
                    matchedSample to ruleConflict
                }
                mutableInteraction.update { current ->
                    if (current.message?.id != message.id) current
                    else current.copy(
                        existingLabel = existing?.label,
                        linkedMessageCount = existing?.linkedMessageCount ?: 0,
                        ruleConflict = conflict,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableInteraction.update { current ->
                    if (current.message?.id != message.id) current
                    else current.copy(
                        error = current.error ?: "读取规则或关联信息失败，仍可继续保存在本机",
                    )
                }
            }
        }
    }

    fun selectLabel(label: LearningLabel) {
        if (interaction.value.busy) return
        mutableInteraction.update { it.copy(selectedLabel = label, error = null) }
    }

    fun dismiss() {
        if (!interaction.value.busy) mutableInteraction.value = LearningInteraction()
    }

    fun setContributeSample(checked: Boolean) {
        if (interaction.value.busy) return
        mutableInteraction.update { it.copy(contributeSample = checked) }
    }

    fun saveSelection() {
        val before = interaction.value
        val message = before.message ?: return
        val label = before.selectedLabel ?: return
        if (before.busy || message.isMms || message.content.isBlank()) return
        mutableInteraction.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                if (before.needsEnable || !settings.value.personalLearningEnabled || !settings.value.spamDetectionEnabled) {
                    if (!settings.value.spamDetectionEnabled) settingsRepository.setSpamDetectionEnabled(true)
                    if (!settings.value.personalLearningEnabled) coordinator.setEnabled(true)
                }
                val key = LearningMessageKey.create(
                    if (message.isMms) "MMS" else "SMS",
                    if (message.isMms) -message.id else message.id,
                    message.sender,
                    message.timestamp,
                    message.content,
                )
                // 同一决策快照读取贡献来源，不能用异步 UI 状态或旧版设置猜测模型代次。
                val (baseVersion, priorGeneration) = withContext(Dispatchers.IO) {
                    repository.withDecision {
                        val bundle = resources.currentModelBundle()
                        val contract = ModelContract.parse(bundle.contractFile.readText(Charsets.UTF_8))
                        val params = repository.currentParameters(contract.identity)
                            ?.takeIf { it.payload.size == contract.parameterCount && it.payload.all(Float::isFinite) }
                        bundle.version to params?.generation
                    }
                }
                coordinator.record(key, message.content, label)
                val feedback = LearningFeedback(
                    baseModelVersion = baseVersion,
                    personalGeneration = priorGeneration,
                    originalLabel = label.name.lowercase(),
                )
                recordedFeedbackByKey[key] = feedback
                mutableInteraction.value = LearningInteraction()
                // 仅准备贡献草稿，仍需用户检查内容并明确确认后才上传。
                val contribution = if (before.contributeSample) {
                    LearningContributionDraft(
                        content = message.content,
                        sender = message.sender,
                        category = if (label == LearningLabel.SPAM) "spam" else "normal",
                        feedback = feedback,
                    )
                } else null
                _savedEvents.trySend(
                    LearningSavedEvent(label, before.ruleConflict, contribution),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableInteraction.update { it.copy(busy = false, error = "未能保存学习样本，请重试") }
            }
        }
    }

    /** 从会话菜单发起贡献时，若该短信已有本机学习纠正，自动关联学习反馈快照。 */
    fun prepareContribution(
        message: MessageModel,
        fallbackSender: String,
        onReady: (content: String, sender: String, category: String, feedback: LearningFeedback?) -> Unit,
    ) {
        viewModelScope.launch {
            val sender = message.sender.ifBlank { fallbackSender }
            if (message.isMms || message.content.isBlank()) {
                onReady(message.content, sender, message.sampleCategory().value, null)
                return@launch
            }
            val key = LearningMessageKey.create("SMS", message.id, message.sender, message.timestamp, message.content)
            val (category, feedback) = withContext(Dispatchers.IO) {
                try {
                    repository.withDecision {
                        val correction = repository.correction(key) ?: return@withDecision null
                        val labelValue = if (correction == LearningLabel.SPAM) "spam" else "normal"
                        val cached = recordedFeedbackByKey[key]?.takeIf { it.originalLabel == labelValue }
                        val resolvedFeedback = cached ?: run {
                            val bundle = resources.currentModelBundle()
                            val contract = ModelContract.parse(bundle.contractFile.readText(Charsets.UTF_8))
                            val generation = repository.currentParameters(contract.identity)
                                ?.takeIf { it.payload.size == contract.parameterCount && it.payload.all(Float::isFinite) }
                                ?.generation
                            LearningFeedback(
                                baseModelVersion = bundle.version,
                                personalGeneration = generation,
                                originalLabel = labelValue,
                            )
                        }
                        labelValue to resolvedFeedback
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                } ?: (message.sampleCategory().value to null)
            }
            onReady(message.content, sender, category, feedback)
        }
    }

    fun setEnabled(enabled: Boolean) = operation {
        if (enabled) settingsRepository.setSpamDetectionEnabled(true)
        coordinator.setEnabled(enabled)
    }

    fun setAutoDesensitize(enabled: Boolean) = settingsRepository.setLearningAutoDesensitizeEnabled(enabled)
    fun prepareStoredSampleContribution(id: String, onReady: (String, String) -> Unit) {
        if (mutableUpdatingSample.value) return
        mutableUpdatingSample.value = true
        mutableError.value = null
        viewModelScope.launch {
            try {
                // 从持久化记录读取最新分类，避免保存后列表尚未刷新时带入旧标签。
                val sample = repository.observeSamples().first().firstOrNull { it.id == id }
                    ?: error("learning sample not found")
                onReady(sample.text, if (sample.label == LearningLabel.SPAM) "spam" else "normal")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableError.value = "无法准备贡献样本，请重试"
            } finally {
                mutableUpdatingSample.value = false
            }
        }
    }

    fun updateSampleLabel(id: String, label: LearningLabel) {
        if (mutableUpdatingSample.value) return
        mutableUpdatingSample.value = true
        mutableError.value = null
        viewModelScope.launch {
            try {
                check(coordinator.updateSampleLabel(id, label))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableError.value = "分类未能保存，请重试"
            } finally {
                mutableUpdatingSample.value = false
            }
        }
    }
    fun deleteSample(id: String) = operation { coordinator.deleteSample(id) }
    fun clear() = operation { coordinator.clear() }
    fun retry() = coordinator.requestLearning()

    private fun operation(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                mutableError.value = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableError.value = "操作未完成，请重试"
            }
        }
    }
}
