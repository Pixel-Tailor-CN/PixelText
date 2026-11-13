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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import vip.mystery0.pixel.text.R
import vip.mystery0.pixel.text.domain.hub.LearningFeedback
import vip.mystery0.pixel.text.domain.sample.DesensitizationAssistantState
import vip.mystery0.pixel.text.domain.sample.SampleCategory
import vip.mystery0.pixel.text.domain.sample.SampleTextToken
import vip.mystery0.pixel.text.domain.sample.SensitiveType
import vip.mystery0.pixel.text.viewmodel.SampleSubmissionViewModel

internal const val SAMPLE_SUBMISSION_DRAFT_CONTENT = "sample_submission_draft_content"
internal const val SAMPLE_SUBMISSION_DRAFT_SENDER = "sample_submission_draft_sender"
internal const val SAMPLE_SUBMISSION_DRAFT_CATEGORY = "sample_submission_draft_category"

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
        state.content.trim().codePointCount(0, state.content.trim().length) < 6 ->
            stringResource(R.string.conv_sample_error_too_short)
        state.content.trim().toByteArray(Charsets.UTF_8).size > 64 * 1024 ->
            stringResource(R.string.conv_sample_error_too_large)
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
        topBar = { SampleReportTopBar(stringResource(R.string.conv_menu_contribute_sample), handleBack) },
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
                        stringResource(R.string.conv_sample_saving_submission),
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
                            Text(stringResource(R.string.conv_sample_pending_title), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(R.string.conv_sample_pending_desc),
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
                        Text(
                            stringResource(
                                if (state.loading) R.string.conv_sample_reloading_local
                                else R.string.conv_sample_reload_local
                            )
                        )
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
                            label = { Text(stringResource(R.string.conv_sample_body_label)) },
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
                            Text(stringResource(R.string.conv_sample_desensitize_assistant), Modifier.padding(start = 8.dp))
                        }
                    }
                }

                item(key = "step1_category") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            stringResource(R.string.conv_sample_submitted_category),
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
                        label = { Text(stringResource(R.string.conv_sample_sender_optional)) },
                        isError = senderTooLong,
                        supportingText = if (senderTooLong) ({ Text(stringResource(R.string.conv_sample_sender_too_long)) }) else null,
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
                        Text(stringResource(R.string.conv_sample_next_confirm))
                    }
                }
            } else {
                item(key = "step2_title") {
                    Text(stringResource(R.string.conv_sample_confirm_upload_title), style = MaterialTheme.typography.titleLarge)
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
                                stringResource(R.string.conv_sample_body_label),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            SelectionContainer {
                                Text(
                                    text = state.content.ifBlank { stringResource(R.string.conv_sample_empty_placeholder) },
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
                                stringResource(R.string.conv_sample_category_label),
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
                                stringResource(R.string.conv_sample_sender),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                state.sender.ifBlank { stringResource(R.string.conv_sample_not_provided) },
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
                                Text(stringResource(R.string.conv_sample_back_to_edit))
                            }
                        }
                    }
                }

                item(key = "step2_attached_info") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.conv_sample_also_included), style = MaterialTheme.typography.titleMedium)
                        val feedback = state.learningFeedback
                        Text(
                            text = if (feedback != null) {
                                stringResource(
                                    R.string.conv_sample_included_learning_feedback,
                                    feedback.baseModelVersion.ifBlank { stringResource(R.string.conv_sample_builtin) },
                                    feedback.personalGeneration?.toString() ?: stringResource(R.string.conv_sample_none),
                                    stringResource(
                                        if (feedback.originalLabel == "spam") R.string.conv_sample_label_spam
                                        else R.string.conv_sample_label_normal
                                    ),
                                )
                            } else {
                                stringResource(R.string.conv_sample_included_versions)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(R.string.conv_sample_included_device_id),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(R.string.conv_sample_included_purpose),
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
                                text = stringResource(R.string.conv_sample_consent_text),
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
                                    state.preparing -> stringResource(R.string.conv_sample_btn_saving)
                                    state.submitting -> stringResource(R.string.conv_sample_btn_submitting)
                                    seconds > 0 -> stringResource(R.string.conv_sample_retry_in_seconds, seconds)
                                    state.pending -> stringResource(R.string.conv_sample_btn_retry_submit)
                                    else -> stringResource(R.string.conv_sample_btn_confirm_submit)
                                }
                            )
                        }
                        if (state.pending) {
                            TextButton(
                                onClick = onViewReports,
                                enabled = !state.submitting,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.conv_sample_view_my_reports)) }
                            TextButton(
                                onClick = requestExit,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.conv_sample_keep_and_back)) }
                        }
                    }
                }
            }
        }
    }

    if (state.draftChoice != null) {
        AlertDialog(
            onDismissRequest = onNavigateBack,
            title = { Text(stringResource(R.string.conv_sample_draft_choice_title)) },
            text = { Text(stringResource(R.string.conv_sample_draft_choice_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmStep = false
                    viewModel.chooseCurrentMessage()
                }) { Text(stringResource(R.string.conv_sample_use_current_message)) }
            },
            dismissButton = {
                Column {
                    TextButton(onClick = {
                        confirmStep = false
                        viewModel.chooseRetainedDraft()
                    }) { Text(stringResource(R.string.conv_sample_continue_draft)) }
                    TextButton(onClick = onNavigateBack) { Text(stringResource(R.string.conv_action_cancel)) }
                }
            },
        )
    }

    if (showLeaveDialog) {
        AlertDialog(
            onDismissRequest = { showLeaveDialog = false },
            title = { Text(stringResource(R.string.conv_sample_keep_draft_title)) },
            text = {
                Text(
                    stringResource(
                        if (state.hasRetainedDraft) R.string.conv_sample_keep_draft_desc_overwrite
                        else R.string.conv_sample_keep_draft_desc
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showLeaveDialog = false
                    viewModel.saveAndLeave(onNavigateBack)
                }) { Text(stringResource(R.string.conv_sample_keep_and_back)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        showLeaveDialog = false
                        viewModel.discardAndLeave(onNavigateBack)
                    }) { Text(stringResource(R.string.conv_sample_discard_draft)) }
                    TextButton(onClick = { showLeaveDialog = false }) { Text(stringResource(R.string.conv_sample_continue_editing)) }
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
            title = { Text(stringResource(R.string.conv_sample_discard_edits_title)) },
            text = { Text(stringResource(R.string.conv_sample_discard_edits_desc)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardDialog = false
                        viewModel.discardDesensitizedDraft()
                    }
                ) {
                    Text(stringResource(R.string.conv_sample_discard_edits))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text(stringResource(R.string.conv_sample_continue_editing))
                }
            },
        )
    }
}

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
                stringResource(R.string.conv_sample_step_review),
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
                stringResource(R.string.conv_sample_step_confirm),
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
                stringResource(R.string.conv_sample_desensitize_assistant),
                style = MaterialTheme.typography.titleLarge,
            )
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.conv_sample_select_sensitive),
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
                    stringResource(R.string.conv_sample_sensitive_type),
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
                label = { Text(stringResource(R.string.conv_sample_preview)) },
                minLines = 4,
            )
        }
        item {
            FilledTonalButton(
                onClick = onReplace,
                enabled = state.canReplace,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.conv_sample_replace))
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
                    Text(stringResource(R.string.conv_action_cancel))
                }
                Button(
                    onClick = onApply,
                    enabled = state.draft.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.conv_sample_apply_to_sample))
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

@Composable
private fun SampleTextToken.displayText(): String {
    return when {
        text == "\n" -> stringResource(R.string.conv_token_newline)
        text == "\t" -> stringResource(R.string.conv_token_tab)
        text.isBlank() -> stringResource(R.string.conv_token_space)
        else -> text
    }
}
