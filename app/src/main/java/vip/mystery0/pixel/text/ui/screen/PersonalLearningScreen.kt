package vip.mystery0.pixel.text.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.koin.androidx.compose.koinViewModel
import vip.mystery0.pixel.text.domain.hub.LearningFeedback
import vip.mystery0.pixel.text.domain.spam.learning.*
import vip.mystery0.pixel.text.viewmodel.PersonalLearningViewModel

private const val LEARNING_NOTICE = "学习样本独立保存在本机，删除原短信不会忘记学习。可在此删除单条样本或清空；样本和学习成果不参与备份及设备迁移，卸载或清除数据后会丢失。关闭学习会保留样本和人工纠正，并停止个人训练与预测。"

@Composable
fun PersonalLearningScreen(onNavigateBack: () -> Unit, viewModel: PersonalLearningViewModel = koinViewModel()) {
    val settings by viewModel.settings.collectAsState()
    val samples by viewModel.samples.collectAsState()
    val state by viewModel.learningState.collectAsState()
    val error by viewModel.error.collectAsState()
    var enableConfirmation by rememberSaveable { mutableStateOf(false) }
    var deletionId by rememberSaveable { mutableStateOf<String?>(null) }
    var clearConfirmation by rememberSaveable { mutableStateOf(false) }
    Scaffold(topBar = { SampleReportTopBar("个人学习", onNavigateBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("个人学习", style = MaterialTheme.typography.titleMedium)
                        Text(if (settings.personalLearningEnabled) "已开启" else "已关闭，样本与人工纠正保留")
                    }
                    Switch(settings.personalLearningEnabled, onCheckedChange = {
                        if (it) enableConfirmation = true else viewModel.setEnabled(false)
                    })
                }
                Text(LEARNING_NOTICE, style = MaterialTheme.typography.bodyMedium)
            }
            item {
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("贡献草稿自动脱敏")
                        Text("仅处理上传草稿，请人工检查，确认前不会上传", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(settings.learningAutoDesensitizeEnabled, viewModel::setAutoDesensitize)
                }
            }
            item {
                Text("${samples.size} 条学习样本 · ${when (state.status) {
                    LearningStatus.IDLE -> "等待学习"
                    LearningStatus.QUEUED -> "已排队"
                    LearningStatus.RUNNING -> "学习中"
                    LearningStatus.APPLIED -> "已生效"
                    LearningStatus.FAILED -> "学习失败，可重试"
                }}")
                if (state.status == LearningStatus.FAILED) TextButton(onClick = viewModel::retry,
                    enabled = settings.personalLearningEnabled) { Text("重试学习") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { clearConfirmation = true }, enabled = samples.isNotEmpty()) { Text("清空学习数据") }
            }
            items(samples, key = { it.id }) { sample ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(if (sample.label == LearningLabel.SPAM) "骚扰短信" else "正常短信", style = MaterialTheme.typography.titleSmall)
                        Text(sample.text)
                        Text("关联 ${sample.linkedMessageCount} 条消息", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { deletionId = sample.id }) { Text("删除样本") }
                    }
                }
            }
        }
    }
    if (enableConfirmation) AlertDialog(onDismissRequest = { enableConfirmation = false },
        title = { Text("开启个人学习") }, text = { Text(LEARNING_NOTICE +
            if (!settings.spamDetectionEnabled) "\n同时开启本地模型识别，以使用个人学习结果。" else "") },
        confirmButton = { TextButton(onClick = { viewModel.setEnabled(true); enableConfirmation = false }) { Text("开启") } },
        dismissButton = { TextButton(onClick = { enableConfirmation = false }) { Text("取消") } })
    samples.firstOrNull { it.id == deletionId }?.let { sample ->
        AlertDialog(onDismissRequest = { deletionId = null }, title = { Text("删除学习样本") },
            text = { Text("将撤销 ${sample.linkedMessageCount} 条关联消息的人工纠正。个人参数立即失效，重建期间使用基础识别；白名单和关键词保留。") },
            confirmButton = { TextButton(onClick = { viewModel.deleteSample(sample.id); deletionId = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deletionId = null }) { Text("取消") } })
    }
    if (clearConfirmation) AlertDialog(onDismissRequest = { clearConfirmation = false }, title = { Text("清空学习数据") },
        text = { Text("删除全部学习样本、关联纠正和个人参数，保留短信、白名单与关键词。此操作无法撤销。") },
        confirmButton = { TextButton(onClick = { viewModel.clear(); clearConfirmation = false }) { Text("清空") } },
        dismissButton = { TextButton(onClick = { clearConfirmation = false }) { Text("取消") } })
}

@Composable
internal fun PersonalLearningDialog(viewModel: PersonalLearningViewModel,
    onContribute: (String, String, String, LearningFeedback?) -> Unit) {
    val interaction by viewModel.interaction.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val operationError by viewModel.error.collectAsState()
    val message = interaction.message ?: return
    AlertDialog(onDismissRequest = viewModel::dismiss,
        title = { Text(if (interaction.recorded) "学习已记录" else "让 AI 学习") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(when {
                interaction.recorded -> "已保存本地标签并请求学习。白名单、持久放行及关键词规则优先，命中这些规则时当前分类可能不变。贡献样本是独立操作，取消不会影响学习。"
                interaction.needsEnable -> LEARNING_NOTICE + if (!settings.spamDetectionEnabled) "\n继续将同时开启本地模型识别。" else ""
                else -> "请选择这条消息应有的标签。白名单、持久放行和关键词规则仍优先于学习结果；不会自动把号码加入白名单。"
            })
            interaction.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            operationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (interaction.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        } },
        confirmButton = {
            when {
                message.content.isBlank() -> TextButton(onClick = viewModel::dismiss) { Text("知道了") }
                interaction.recorded -> TextButton(onClick = {
                    onContribute(message.content, message.sender,
                        if (interaction.label == LearningLabel.SPAM) "spam" else "normal", interaction.feedback)
                    viewModel.dismiss()
                }) { Text("编辑贡献草稿") }
                interaction.needsEnable -> TextButton(onClick = viewModel::enableForMessage) { Text("开启并继续") }
                else -> Column {
                    TextButton(onClick = { viewModel.learn(LearningLabel.SPAM) }, enabled = !interaction.busy) { Text("骚扰短信") }
                    TextButton(onClick = { viewModel.learn(LearningLabel.NORMAL) }, enabled = !interaction.busy) { Text("正常短信") }
                }
            }
        },
        dismissButton = { TextButton(onClick = viewModel::dismiss, enabled = !interaction.busy) {
            Text(if (interaction.recorded) "仅本地学习" else "取消")
        } })
}
