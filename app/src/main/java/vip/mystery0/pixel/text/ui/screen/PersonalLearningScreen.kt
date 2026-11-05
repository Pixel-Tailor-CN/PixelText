package vip.mystery0.pixel.text.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.koin.androidx.compose.koinViewModel
import vip.mystery0.pixel.text.domain.spam.learning.*
import vip.mystery0.pixel.text.viewmodel.LearningRuleConflict
import vip.mystery0.pixel.text.viewmodel.PersonalLearningViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private const val LEARNING_NOTICE =
    "学习样本独立保存在本机，删除原短信不会忘记学习。样本和学习成果不参与备份及设备迁移，卸载或清除数据后会丢失。关闭学习会保留样本和人工纠正，并停止个人训练与预测。白名单和关键词规则始终优先于个人学习结果。"

@Composable
fun PersonalLearningScreen(
    onNavigateBack: () -> Unit,
    onContributeSample: (content: String, category: String) -> Unit = { _, _ -> },
    viewModel: PersonalLearningViewModel = koinViewModel(),
) {
    val settings by viewModel.settings.collectAsState()
    val samples by viewModel.samples.collectAsState()
    val state by viewModel.learningState.collectAsState()
    val error by viewModel.error.collectAsState()
    val updatingSample by viewModel.updatingSample.collectAsState()
    var enableConfirmation by rememberSaveable { mutableStateOf(false) }
    var detailSampleId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletionId by rememberSaveable { mutableStateOf<String?>(null) }
    var clearConfirmation by rememberSaveable { mutableStateOf(false) }
    var showAboutDialog by rememberSaveable { mutableStateOf(false) }
    var showContributionSettings by rememberSaveable { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("个人学习") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showContributionSettings = true }) {
                        Icon(Icons.Rounded.Settings, contentDescription = "样本贡献设置")
                    }
                    Box {
                        IconButton(onClick = { showMoreMenu = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "更多选项")
                        }
                        DropdownMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("清空学习数据") },
                                enabled = samples.isNotEmpty(),
                                onClick = {
                                    showMoreMenu = false
                                    clearConfirmation = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("关于个人学习") },
                                onClick = {
                                    showMoreMenu = false
                                    showAboutDialog = true
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            item(key = "master_switch") {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = if (settings.personalLearningEnabled) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .toggleable(
                            value = settings.personalLearningEnabled,
                            role = Role.Switch,
                            onValueChange = { enabled ->
                                if (enabled) enableConfirmation = true else viewModel.setEnabled(false)
                            },
                        ),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("使用个人学习", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = when {
                                    settings.personalLearningEnabled -> "让识别更符合你的习惯。"
                                    samples.isNotEmpty() -> "已暂停，保留样本和人工纠正。"
                                    else -> "仅保存在本机，不参与备份。"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = settings.personalLearningEnabled,
                            onCheckedChange = null,
                            modifier = Modifier.clearAndSetSemantics {},
                        )
                    }
                }
            }

            item(key = "status") {
                Spacer(Modifier.height(20.dp))
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PersonalLearningStatusSection(
                        enabled = settings.personalLearningEnabled,
                        spamDetectionEnabled = settings.spamDetectionEnabled,
                        sampleCount = samples.size,
                        state = state,
                        onRetry = viewModel::retry,
                    )
                    error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }

            item(key = "records_header") {
                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("学习记录", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${samples.size} 条",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (samples.isEmpty()) {
                item(key = "empty_records") {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("还没有学习记录", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "你调整过分类的短信会显示在这里。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                items(samples, key = { it.id }) { sample ->
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { detailSampleId = sample.id }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = sample.text,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    text = buildString {
                                        append(if (sample.label == LearningLabel.SPAM) "骚扰短信" else "正常短信")
                                        append(" · ")
                                        append(formatLearningSampleDate(sample.updatedAt))
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                contentDescription = "查看详情",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }

    samples.firstOrNull { it.id == detailSampleId }?.let { sample ->
        LearningSampleDetailSheet(
            sample = sample,
            busy = updatingSample,
            error = error,
            onDismiss = { detailSampleId = null },
            onUpdateLabel = { newLabel ->
                viewModel.updateSampleLabel(sample.id, newLabel)
            },
            onContribute = {
                viewModel.prepareStoredSampleContribution(sample.id) { content, category ->
                    detailSampleId = null
                    onContributeSample(content, category)
                }
            },
            onRequestDelete = {
                detailSampleId = null
                deletionId = sample.id
            },
        )
    }

    if (showContributionSettings) {
        ContributionSettingsSheet(
            autoDesensitizeEnabled = settings.learningAutoDesensitizeEnabled,
            onAutoDesensitizeChange = viewModel::setAutoDesensitize,
            onDismiss = { showContributionSettings = false },
        )
    }

    if (enableConfirmation) {
        AlertDialog(
            onDismissRequest = { enableConfirmation = false },
            title = { Text("开启个人学习") },
            text = {
                Text(
                    LEARNING_NOTICE +
                        if (!settings.spamDetectionEnabled) "\n\n同时开启本地模型识别，以使用个人学习结果。" else ""
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setEnabled(true)
                    enableConfirmation = false
                }) { Text("开启") }
            },
            dismissButton = {
                TextButton(onClick = { enableConfirmation = false }) { Text("取消") }
            },
        )
    }

    samples.firstOrNull { it.id == deletionId }?.let { sample ->
        AlertDialog(
            onDismissRequest = { deletionId = null },
            title = { Text("删除学习样本") },
            text = {
                Text("删除后将撤销此样本的分类纠正，并重新学习。原短信会保留。" +
                    if (sample.linkedMessageCount > 1) "这会影响 ${sample.linkedMessageCount} 条相同内容的短信。" else "")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSample(sample.id)
                    deletionId = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deletionId = null }) { Text("取消") }
            },
        )
    }

    if (clearConfirmation) {
        AlertDialog(
            onDismissRequest = { clearConfirmation = false },
            title = { Text("清空学习数据") },
            text = { Text("删除全部学习样本、关联纠正和个人参数，保留短信、白名单与关键词。此操作无法撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clear()
                    clearConfirmation = false
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { clearConfirmation = false }) { Text("取消") }
            },
        )
    }

    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = { Text("关于个人学习") },
            text = { Text(LEARNING_NOTICE) },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) { Text("知道了") }
            },
        )
    }
}

@Composable
private fun PersonalLearningStatusSection(
    enabled: Boolean,
    spamDetectionEnabled: Boolean,
    sampleCount: Int,
    state: LearningState,
    onRetry: () -> Unit,
) {
    val title: String
    val subtitle: String
    val isActive = enabled && spamDetectionEnabled && sampleCount > 0
    val isFailed = isActive && state.status == LearningStatus.FAILED
    val isRunning = isActive && state.status == LearningStatus.RUNNING
    val isApplied = isActive && state.status == LearningStatus.APPLIED && state.hasActiveParameters
    val isMissingParameters = isActive && state.status == LearningStatus.APPLIED && !state.hasActiveParameters
    val canRetry = isFailed || isMissingParameters

    when {
        !enabled && sampleCount == 0 -> {
            title = "尚未开启"
            subtitle = ""
        }
        !enabled -> {
            title = "已暂停"
            subtitle = "已停止个人训练与预测，保留 $sampleCount 条学习样本与人工纠正。"
        }
        !spamDetectionEnabled -> {
            title = "本地模型识别已关闭"
            subtitle = "学习记录与人工纠正已保留；需在骚扰识别设置中开启本地模型识别后才会参与预测。"
        }
        sampleCount == 0 -> {
            title = "等待学习"
            subtitle = ""
        }
        state.status == LearningStatus.APPLIED && state.hasActiveParameters -> {
            title = "已生效"
            subtitle = ""
        }
        state.status == LearningStatus.APPLIED -> {
            title = "个人学习结果未就绪，暂时使用基础识别"
            subtitle = "当前模型尚无有效个人参数，可点击重试学习。"
        }
        state.status == LearningStatus.RUNNING -> {
            title = "正在更新个人学习"
            subtitle = "正在本机更新学习结果，请稍候。"
        }
        state.status == LearningStatus.FAILED -> {
            title = if (state.hasActiveParameters) {
                "本次更新失败，仍使用上次学习结果"
            } else {
                "本次更新失败，暂时使用基础识别"
            }
            subtitle = state.error ?: "本地学习未完成，可点击重试。"
        }
        else -> {
            title = "等待更新"
            subtitle = "纠正已保存，正在排队更新个人学习。"
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(modifier = Modifier.padding(top = 2.dp)) {
            when {
                isRunning -> CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.5.dp,
                )
                isApplied -> Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                isFailed -> Icon(
                    imageVector = Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                else -> Icon(
                    imageVector = Icons.Rounded.Schedule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = when {
                    isFailed -> MaterialTheme.colorScheme.error
                    isApplied -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (canRetry) {
                TextButton(
                    onClick = onRetry,
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                ) {
                    Text("重试学习")
                }
            }
        }
    }
}

@Composable
private fun LearningSampleDetailSheet(
    sample: LearningSample,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onUpdateLabel: (LearningLabel) -> Unit,
    onContribute: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    var showMore by remember { mutableStateOf(false) }
    var editingLabel by rememberSaveable(sample.id) { mutableStateOf(false) }
    var selectedLabel by rememberSaveable(sample.id, sample.label) { mutableStateOf(sample.label) }
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("学习记录", style = MaterialTheme.typography.titleLarge)
                    Text(
                        formatLearningSampleDetailTime(sample.updatedAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    IconButton(onClick = { showMore = true }, enabled = !busy) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "记录选项")
                    }
                    DropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                        DropdownMenuItem(
                            text = { Text("删除记录", color = MaterialTheme.colorScheme.error) },
                            onClick = { showMore = false; onRequestDelete() },
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = "关闭")
                }
            }
            Column(
                modifier = Modifier.weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            ) {
                SelectionContainer(Modifier.padding(vertical = 24.dp)) {
                    Text(sample.text, style = MaterialTheme.typography.bodyLarge)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ListItem(
                    headlineContent = { Text("短信分类") },
                    supportingContent = {
                        Text(if (sample.label == LearningLabel.SPAM) "骚扰短信" else "正常短信")
                    },
                    trailingContent = {
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
                    },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    modifier = Modifier.clickable(enabled = !busy) {
                        selectedLabel = sample.label
                        editingLabel = true
                    },
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Button(
                onClick = onContribute,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)
                    .heightIn(min = 48.dp),
            ) { Text("贡献样本") }
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
    if (editingLabel) {
        AlertDialog(
            onDismissRequest = { editingLabel = false },
            title = { Text("短信分类") },
            text = {
                Column {
                    LearningLabelRadioGroup(
                        selectedLabel = selectedLabel,
                        enabled = true,
                        onSelect = { selectedLabel = it },
                    )
                    if (sample.linkedMessageCount > 1) {
                        Text(
                            "将同时更新 ${sample.linkedMessageCount} 条相同内容短信的分类。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { onUpdateLabel(selectedLabel); editingLabel = false },
                    enabled = selectedLabel != sample.label,
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editingLabel = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ContributionSettingsSheet(
    autoDesensitizeEnabled: Boolean,
    onAutoDesensitizeChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("样本贡献设置", style = MaterialTheme.typography.titleLarge)

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .toggleable(
                        value = autoDesensitizeEnabled,
                        role = Role.Switch,
                        onValueChange = onAutoDesensitizeChange,
                    ),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("贡献草稿自动脱敏", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "仅处理上传草稿，不修改本机学习样本。自动脱敏不能保证去除全部敏感信息，每次提交前仍需人工检查并确认。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = autoDesensitizeEnabled,
                        onCheckedChange = null,
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                }
            }

            Text(
                "贡献样本前会单独确认上传内容，本地学习不会自动上传短信。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

@Composable
internal fun PersonalLearningDialog(
    viewModel: PersonalLearningViewModel,
    onOpenWhitelist: () -> Unit = {},
    onOpenKeywords: () -> Unit = {},
) {
    val interaction by viewModel.interaction.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val operationError by viewModel.error.collectAsState()
    val message = interaction.message ?: return
    var summaryExpanded by rememberSaveable(message.id) { mutableStateOf(false) }
    val latestBusy = rememberUpdatedState(interaction.busy)
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        confirmValueChange = remember {
            { targetValue ->
                targetValue != SheetValue.Hidden || !latestBusy.value
            }
        },
    )

    ModalBottomSheet(
        onDismissRequest = viewModel::dismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("纠正识别", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "选择这条短信应有的分类。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(enabled = message.content.isNotBlank()) {
                        summaryExpanded = !summaryExpanded
                    },
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = message.content.ifBlank { "这条消息没有可学习的文本" },
                        maxLines = if (summaryExpanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (message.content.length > 56 || message.content.count { it == '\n' } >= 2) {
                        Text(
                            text = if (summaryExpanded) "收起全文" else "展开全文",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (interaction.linkedMessageCount > 1) {
                        Text(
                            text = "相同内容已关联 ${interaction.linkedMessageCount} 条短信，保存将一并更新。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (message.content.isNotBlank()) {
                LearningLabelRadioGroup(
                    selectedLabel = interaction.selectedLabel,
                    enabled = !interaction.busy,
                    onSelect = viewModel::selectLabel,
                )
            }

            if (interaction.needsEnable && message.content.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("首次开启个人学习", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "学习样本独立保存在本机，删除原短信不会忘记学习，不参与备份或设备迁移。" +
                                if (!settings.spamDetectionEnabled) {
                                    "\n继续将同时开启本地模型识别，以使用个人学习结果。"
                                } else {
                                    ""
                                },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            interaction.ruleConflict?.let { conflict ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = when (conflict) {
                                LearningRuleConflict.WHITELIST -> "此短信已命中白名单或历史放行记录，放行优先于个人学习。"
                                LearningRuleConflict.KEYWORD -> "该短信命中拦截关键词，当前仍按关键词规则处理。"
                            },
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        TextButton(
                            onClick = {
                                viewModel.dismiss()
                                when (conflict) {
                                    LearningRuleConflict.WHITELIST -> onOpenWhitelist()
                                    LearningRuleConflict.KEYWORD -> onOpenKeywords()
                                }
                            },
                            enabled = !interaction.busy,
                        ) {
                            Text(
                                when (conflict) {
                                    LearningRuleConflict.WHITELIST -> "查看白名单"
                                    LearningRuleConflict.KEYWORD -> "查看规则"
                                }
                            )
                        }
                    }
                }
            }

            if (message.content.isNotBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .toggleable(
                            value = interaction.contributeSample,
                            enabled = !interaction.busy,
                            role = Role.Checkbox,
                            onValueChange = viewModel::setContributeSample,
                        )
                        .heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = interaction.contributeSample,
                        onCheckedChange = null,
                        enabled = !interaction.busy,
                        modifier = Modifier.padding(12.dp),
                    )
                    Text("同时贡献此样本", style = MaterialTheme.typography.bodyLarge)
                }
            }
            Text(
                text = if (interaction.contributeSample) {
                    "保存后可检查待上传内容，确认后才会上传样本。"
                } else {
                    "仅在本机学习，不会上传短信。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            interaction.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            operationError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (interaction.busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (message.content.isBlank()) {
                Button(
                    onClick = viewModel::dismiss,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("知道了")
                }
            } else {
                Button(
                    onClick = viewModel::saveSelection,
                    enabled = interaction.selectedLabel != null && !interaction.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(if (interaction.needsEnable) "开启个人学习并保存" else "保存纠正")
                }
                TextButton(
                    onClick = viewModel::dismiss,
                    enabled = !interaction.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("取消")
                }
            }

            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

@Composable
private fun LearningLabelRadioGroup(
    selectedLabel: LearningLabel?,
    enabled: Boolean,
    onSelect: (LearningLabel) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LearningLabelRadioRow(
            title = "正常短信",
            subtitle = "日常通知、验证码或个人消息",
            selected = selectedLabel == LearningLabel.NORMAL,
            enabled = enabled,
            onClick = { onSelect(LearningLabel.NORMAL) },
        )
        LearningLabelRadioRow(
            title = "骚扰短信",
            subtitle = "广告推广、诈骗或其他骚扰",
            selected = selectedLabel == LearningLabel.SPAM,
            enabled = enabled,
            onClick = { onSelect(LearningLabel.SPAM) },
        )
    }
}

@Composable
private fun LearningLabelRadioRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .clip(RoundedCornerShape(16.dp))
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatLearningSampleDate(timestamp: Long): String {
    val now = Calendar.getInstance()
    val target = Calendar.getInstance().apply { timeInMillis = timestamp }
    val sameYear = now.get(Calendar.YEAR) == target.get(Calendar.YEAR)
    val nowDay = now.get(Calendar.DAY_OF_YEAR)
    val targetDay = target.get(Calendar.DAY_OF_YEAR)
    return when {
        sameYear && nowDay == targetDay -> "今天"
        sameYear && nowDay - targetDay == 1 -> "昨天"
        sameYear -> SimpleDateFormat("M月d日", Locale.getDefault()).format(Date(timestamp))
        else -> SimpleDateFormat("yyyy年M月d日", Locale.getDefault()).format(Date(timestamp))
    }
}

private fun formatLearningSampleDetailTime(timestamp: Long): String {
    return SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.getDefault()).format(Date(timestamp))
}
