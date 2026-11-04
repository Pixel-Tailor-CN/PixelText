package vip.mystery0.pixel.text.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import vip.mystery0.pixel.text.data.resource.HubResourceStore
import vip.mystery0.pixel.text.data.spam.learning.ModelContract
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import vip.mystery0.pixel.text.domain.hub.LearningFeedback
import vip.mystery0.pixel.text.domain.model.MessageModel
import vip.mystery0.pixel.text.domain.settings.AppSettingsRepository
import vip.mystery0.pixel.text.domain.spam.learning.*

data class LearningInteraction(
    val message: MessageModel? = null,
    val needsEnable: Boolean = false,
    val busy: Boolean = false,
    val recorded: Boolean = false,
    val label: LearningLabel? = null,
    val feedback: LearningFeedback? = null,
    val error: String? = null,
)

class PersonalLearningViewModel(
    private val repository: PersonalLearningRepository,
    private val coordinator: PersonalLearningCoordinator,
    private val settingsRepository: AppSettingsRepository,
    private val resources: HubResourceStore,
) : ViewModel() {
    val settings = settingsRepository.settings
    val samples = repository.observeSamples().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val learningState = coordinator.state
    private val mutableInteraction = MutableStateFlow(LearningInteraction())
    val interaction = mutableInteraction.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()

    fun begin(message: MessageModel) {
        mutableInteraction.value = LearningInteraction(message = message,
            needsEnable = !settings.value.personalLearningEnabled || !settings.value.spamDetectionEnabled,
            error = if (message.content.isBlank()) "这条消息没有可学习的文本，图片和附件不参与学习" else null)
    }

    fun dismiss() { if (!interaction.value.busy) mutableInteraction.value = LearningInteraction() }

    fun enableForMessage() = operation {
        settingsRepository.setSpamDetectionEnabled(true)
        coordinator.setEnabled(true)
        mutableInteraction.update { it.copy(needsEnable = false) }
    }

    fun learn(label: LearningLabel) {
        val before = interaction.value
        val message = before.message ?: return
        if (before.busy || before.recorded || message.isMms || message.content.isBlank() || before.needsEnable) return
        mutableInteraction.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val key = LearningMessageKey.create(if (message.isMms) "MMS" else "SMS",
                    if (message.isMms) -message.id else message.id, message.sender, message.timestamp, message.content)
                // 同一决策快照读取贡献来源，不能用异步 UI 状态或旧版设置猜测模型代次。
                val (baseVersion, priorGeneration) = withContext(Dispatchers.IO) {
                    repository.withDecision {
                        val bundle = resources.currentModelBundle()
                        val identity = ModelContract.parse(bundle.contractFile.readText(Charsets.UTF_8)).identity
                        bundle.version to repository.currentParameters(identity)?.generation
                    }
                }
                coordinator.record(key, message.content, label)
                mutableInteraction.update { it.copy(busy = false, recorded = true, label = label,
                    feedback = LearningFeedback(baseModelVersion = baseVersion, personalGeneration = priorGeneration,
                        originalLabel = label.name.lowercase())) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                mutableInteraction.update { it.copy(busy = false, error = "未能保存学习样本，请重试") }
            }
        }
    }

    fun setEnabled(enabled: Boolean) = operation {
        if (enabled) settingsRepository.setSpamDetectionEnabled(true)
        coordinator.setEnabled(enabled)
    }
    fun setAutoDesensitize(enabled: Boolean) = settingsRepository.setLearningAutoDesensitizeEnabled(enabled)
    fun deleteSample(id: String) = operation { coordinator.deleteSample(id) }
    fun clear() = operation { coordinator.clear() }
    fun retry() = coordinator.requestLearning()
    private fun operation(block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block(); mutableError.value = null
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { mutableError.value = "操作未完成，请重试" }
        }
    }
}
