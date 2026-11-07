package vip.mystery0.pixel.text.ui.screen

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import vip.mystery0.pixel.text.domain.hub.LearningFeedback
import vip.mystery0.pixel.text.domain.sample.DesensitizationAssistantState
import vip.mystery0.pixel.text.domain.sample.SampleCategory
import vip.mystery0.pixel.text.domain.sample.SampleTextToken
import vip.mystery0.pixel.text.domain.sample.SensitiveType
import vip.mystery0.pixel.text.viewmodel.SampleSubmissionViewModel

internal const val SAMPLE_SUBMISSION_DRAFT_CONTENT = "sample_submission_draft_content"
internal const val SAMPLE_SUBMISSION_DRAFT_SENDER = "sample_submission_draft_sender"
internal const val SAMPLE_SUBMISSION_DRAFT_CATEGORY = "sample_submission_draft_category"

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SampleSubmissionScreen(
    viewModel: SampleSubmissionViewModel = koinViewModel(),
    onNavigateBack: () -> Unit,
    onSubmitted: (String) -> Unit,
    onViewReports: () -> Unit,
    initialContent: String = "",
    initialSender: String = "",
    initialCategory: String = SampleCategory.NORMAL.value,
    initialLearningFeedback: LearningFeedback? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    var confirmStep by rememberSaveable { mutableStateOf(false) }
    var contentEdited by rememberSaveable { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    var showLeaveDialog by remember { mutableStateOf(false) }
    val seconds = sampleReportRetrySeconds(state.retryAt)
    val contentError = when {
        state.content.trim().codePointCount(0, state.content.trim().length) < 6 -> "样本文本至少需要 6 个字符"
        state.content.trim().toByteArray(Charsets.UTF_8).size > 64 * 1024 -> "样本文本不能超过 64 KiB，请减少内容"
        else -> null
    }
    val senderTooLong = state.sender.codePointCount(0, state.sender.length) > 256
    val visibleContentError = contentError.takeIf { contentEdited || state.content.isNotEmpty() }
    val canProceedToConfirm = !state.locked && state.validationError == null && contentError == null && !senderTooLong
    val canSubmit = !state.loading && !state.localError && !state.submitting && !state.saving &&
        state.draftChoice == null && seconds == 0L &&
        (state.pending || (state.agreed && state.validationError == null))
    val onConfirmStep = confirmStep || state.pending

    val dismissKeyboard: () -> Unit = {
        focusManager.clearFocus()
        keyboardController?.hide()
    }
    val requestExit: () -> Unit = {
        dismissKeyboard()
        // 按下提交后可能尚未重绘，返回保护必须读取 ViewModel 的即时状态。
        val current = viewModel.state.value
        if (current.preparing || current.saving || current.loading) {
            viewModel.showPreparingHint()
        } else if (current.dirty && !current.pending) {
            showLeaveDialog = true
        } else {
            onNavigateBack()
        }
    }
    val handleBack: () -> Unit = {
        val current = viewModel.state.value
        if (current.preparing || current.saving || current.loading) {
            viewModel.showPreparingHint()
        } else if (confirmStep && !current.pending && !current.submitting) {
            dismissKeyboard()
            viewModel.updateAgreed(false)
            confirmStep = false
        } else {
            requestExit()
        }
    }

    BackHandler(enabled = !state.desensitizationState.visible && state.draftChoice == null, onBack = handleBack)
    LaunchedEffect(initialContent, initialSender, initialCategory) {
        viewModel.initialize(initialContent, initialSender, initialCategory, initialLearningFeedback)
    }
    LaunchedEffect(state.pending) {
        if (state.pending) confirmStep = true
    }
    // 切换检查/确认步骤或恢复待确认请求时，收起软键盘并将列表重置到顶部，确保从头核对待上传内容。
    LaunchedEffect(onConfirmStep, state.pending) {
        dismissKeyboard()
        listState.scrollToItem(0)
    }
    LaunchedEffect(state.successReportId) {
        state.successReportId?.let { id ->
            viewModel.consumeSuccess()
            onSubmitted(id)
        }
    }
    state.desensitizationHintMessage?.let { message ->
        LaunchedEffect(message) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.clearDesensitizationHintMessage()
        }
    }

    Scaffold(
        topBar = { SampleReportTopBar("贡献样本", handleBack) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "stepper") {
                SubmissionStepHeader(confirmStep = onConfirmStep)
            }

            if (state.loading) {
                item(key = "loading") { CircularProgressIndicator() }
            }

            if (state.preparing) {
                item(key = "preparing") {
                    Text(
                        "正在保存提交内容…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (state.pending) {
                item(key = "pending_notice") {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("暂未确认提交结果", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "重试将提交已确认的内容。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
            }

            state.resultMessage?.let { message ->
                item(key = "result_message") {
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            if (state.localError) {
                item(key = "local_error") {
                    TextButton(
                        onClick = viewModel::retryLocalRead,
                        enabled = !state.loading && !state.submitting && !state.saving,
                    ) {
                        Text(if (state.loading) "正在重新读取…" else "重新读取本机数据")
                    }
                }
            }

            if (!onConfirmStep) {
                item(key = "step1_content") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = state.content,
                            onValueChange = {
                                contentEdited = true
                                viewModel.updateContent(it)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.locked,
                            label = { Text("短信正文") },
                            minLines = 4,
                            isError = visibleContentError != null,
                            supportingText = visibleContentError?.let { { Text(it) } },
                        )
                        TextButton(
                            onClick = {
                                dismissKeyboard()
                                viewModel.openDesensitizationAssistant()
                            },
                            enabled = !state.locked && state.content.isNotBlank(),
                        ) {
                            Icon(Icons.Rounded.Edit, null)
                            Text("辅助脱敏", Modifier.padding(start = 8.dp))
                        }
                    }
                }

                item(key = "step1_category") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "提交分类",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(0.dp),
                        ) {
                            SampleCategory.entries.forEach { category ->
                                FilterChip(
                                    selected = state.category == category.value,
                                    enabled = !state.locked,
                                    onClick = { viewModel.updateCategory(category.value) },
                                    label = { Text(category.label) },
                                )
                            }
                        }
                    }
                }

                item(key = "step1_sender") {
                    OutlinedTextField(
                        value = state.sender,
                        onValueChange = viewModel::updateSender,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.locked,
                        singleLine = true,
                        label = { Text("发送方（可选）") },
                        isError = senderTooLong,
                        supportingText = if (senderTooLong) ({ Text("发送方不能超过 256 个字符") }) else null,
                    )
                }

                item(key = "step1_next") {
                    Button(
                        onClick = {
                            dismissKeyboard()
                            viewModel.updateAgreed(false)
                            confirmStep = true
                        },
                        enabled = canProceedToConfirm,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text("下一步：确认提交")
                    }
                }
            } else {
                item(key = "step2_title") {
                    Text("确认本次上传内容", style = MaterialTheme.typography.titleLarge)
                }

                item(key = "step2_preview") {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "短信正文",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            SelectionContainer {
                                Text(
                                    text = state.content.ifBlank { "（空）" },
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                        }
                    }
                }

                item(key = "step2_meta") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "分类",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                sampleReportCategory(state.category),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "发送方",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                state.sender.ifBlank { "未填写" },
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        if (!state.pending) {
                            TextButton(
                                onClick = {
                                    dismissKeyboard()
                                    viewModel.updateAgreed(false)
                                    confirmStep = false
                                },
                                enabled = !state.locked,
                                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                            ) {
                                Text("返回修改")
                            }
                        }
                    }
                }

                item(key = "step2_attached_info") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("同时提交", style = MaterialTheme.typography.titleMedium)
                        val feedback = state.learningFeedback
                        Text(
                            text = if (feedback != null) {
                                "1. 应用与资源版本、学习反馈（来源：本机学习；基础版本：${feedback.baseModelVersion.ifBlank { "内置" }}；" +
                                    "代次：${feedback.personalGeneration?.toString() ?: "无"}；" +
                                    "标签：${if (feedback.originalLabel == "spam") "骚扰短信" else "正常短信"}；" +
                                    "不上传个人参数或学习样本库）；"
                            } else {
                                "1. 应用版本、当前规则版本与离线模型版本（不上传个人参数或本机学习样本库）；"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "2. Android 设备标识与本次安装身份。",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "用于规则改进、模型优化、反滥用风控和查询本人记录。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (!state.pending) {
                    item(key = "step2_consent") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .toggleable(
                                    value = state.agreed,
                                    enabled = !state.locked,
                                    role = Role.Checkbox,
                                    onValueChange = viewModel::updateAgreed,
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Checkbox(
                                checked = state.agreed,
                                onCheckedChange = null,
                                enabled = !state.locked,
                                modifier = Modifier.clearAndSetSemantics {},
                            )
                            Text(
                                text = "我已检查脱敏结果并移除敏感信息，同意上传以上内容。",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }

                item(key = "step2_submit") {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                dismissKeyboard()
                                viewModel.submit()
                            },
                            enabled = canSubmit,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            if (state.submitting) {
                                CircularProgressIndicator(
                                    Modifier.padding(end = 8.dp).size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                            Text(
                                when {
                                    state.preparing -> "正在保存…"
                                    state.submitting -> "正在提交…"
                                    seconds > 0 -> "${seconds} 秒后可重试"
                                    state.pending -> "重试本次提交"
                                    else -> "确认并提交"
                                }
                            )
                        }
                        if (state.pending) {
                            TextButton(
                                onClick = onViewReports,
                                enabled = !state.submitting,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("查看我的上报") }
                            TextButton(
                                onClick = requestExit,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("保留并返回") }
                        }
                    }
                }
            }
        }
    }

    if (state.draftChoice != null) {
        AlertDialog(
            onDismissRequest = onNavigateBack,
            title = { Text("选择要编辑的内容") },
            text = { Text("本机已有一份保留草稿。请选择使用当前短信，或继续编辑保留草稿。选择当前短信不会删除或覆盖原草稿。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmStep = false
                    viewModel.chooseCurrentMessage()
                }) { Text("使用当前短信") }
            },
            dismissButton = {
                Column {
                    TextButton(onClick = {
                        confirmStep = false
                        viewModel.chooseRetainedDraft()
                    }) { Text("继续保留草稿") }
                    TextButton(onClick = onNavigateBack) { Text("取消") }
                }
            },
        )
    }

    if (showLeaveDialog) {
        AlertDialog(
            onDismissRequest = { showLeaveDialog = false },
            title = { Text("保留这份草稿？") },
            text = {
                Text(
                    "脱敏修改尚未提交。保留后仅存于本次安装的本机私有目录，不参与备份，也不会自动上传。" +
                        if (state.hasRetainedDraft) "保留会覆盖先前保留的草稿；选择放弃草稿会删除先前的保留草稿。" else ""
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showLeaveDialog = false
                    viewModel.saveAndLeave(onNavigateBack)
                }) { Text("保留并返回") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        showLeaveDialog = false
                        viewModel.discardAndLeave(onNavigateBack)
                    }) { Text("放弃草稿") }
                    TextButton(onClick = { showLeaveDialog = false }) { Text("继续编辑") }
                }
            },
        )
    }

    if (state.desensitizationState.visible) {
        val latestDesensitizationState = rememberUpdatedState(state.desensitizationState)
        val requestDiscardDialog = rememberUpdatedState {
            showDiscardDialog = true
        }
        val sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(
                SheetValue.Hidden,
                SheetValue.Expanded,
            ),
            confirmValueChange = remember {
                { targetValue ->
                    if (
                        targetValue == SheetValue.Hidden &&
                        latestDesensitizationState.value.dirty
                    ) {
                        requestDiscardDialog.value()
                        false
                    } else {
                        true
                    }
                }
            },
        )
        ModalBottomSheet(
            onDismissRequest = {
                if (!viewModel.requestCloseDesensitizationAssistant()) {
                    showDiscardDialog = true
                }
            },
            sheetState = sheetState,
        ) {
            DesensitizationAssistantSheet(
                state = state.desensitizationState,
                onSelectRange = viewModel::selectSensitiveRange,
                onTypeSelected = viewModel::updateSensitiveType,
                onReplace = viewModel::replaceSelectedSensitiveText,
                onApply = viewModel::applyDesensitizedDraft,
                onDismissRequest = {
                    if (!viewModel.requestCloseDesensitizationAssistant()) {
                        showDiscardDialog = true
                    }
                },
            )
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("放弃修改？") },
            text = { Text("辅助脱敏中的修改还没有应用到样本。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardDialog = false
                        viewModel.discardDesensitizedDraft()
                    }
                ) {
                    Text("放弃修改")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text("继续编辑")
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubmissionStepHeader(confirmStep: Boolean) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(
                        color = if (confirmStep) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (confirmStep) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                } else {
                    Text(
                        "1",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
            Text(
                "检查内容",
                style = MaterialTheme.typography.bodyMedium,
                color = if (!confirmStep) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(
                        color = if (confirmStep) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "2",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (confirmStep) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Text(
                "确认提交",
                style = MaterialTheme.typography.bodyMedium,
                color = if (confirmStep) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.DesensitizationAssistantSheet(
    state: DesensitizationAssistantState,
    onSelectRange: (Int, Int) -> Unit,
    onTypeSelected: (SensitiveType) -> Unit,
    onReplace: () -> Unit,
    onApply: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            bottom = 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                "辅助脱敏",
                style = MaterialTheme.typography.titleLarge,
            )
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "选择敏感片段",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                        state.tokens.forEach { token ->
                            TokenChip(
                                token = token,
                                selected = state.isTokenSelected(token),
                                onClick = {
                                    if (token.selectable) {
                                        onSelectRange(token.start, token.end)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "敏感类型",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    SensitiveType.entries.forEach { type ->
                        FilterChip(
                            selected = state.selectedType == type,
                            onClick = { onTypeSelected(type) },
                            label = { Text(type.label) },
                        )
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                value = state.draft,
                onValueChange = {},
                modifier = Modifier.fillMaxWidth(),
                readOnly = true,
                label = { Text("预览") },
                minLines = 4,
            )
        }
        item {
            FilledTonalButton(
                onClick = onReplace,
                enabled = state.canReplace,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("替换")
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onDismissRequest,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("取消")
                }
                Button(
                    onClick = onApply,
                    enabled = state.draft.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("应用到样本")
                }
            }
        }
        item {
            Spacer(
                modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars)
            )
        }
    }
}

@Composable
private fun TokenChip(
    token: SampleTextToken,
    selected: Boolean,
    onClick: () -> Unit,
) {
    if (token.selectable) {
        FilterChip(
            selected = selected,
            onClick = onClick,
            label = { Text(token.displayText()) },
        )
    } else {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.small,
        ) {
            Text(
                text = token.displayText(),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun SampleTextToken.displayText(): String {
    return when {
        text == "\n" -> "换行"
        text == "\t" -> "制表"
        text.isBlank() -> "空格"
        else -> text
    }
}
