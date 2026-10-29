package vip.mystery0.pixel.text.ui.screen

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.koin.androidx.compose.koinViewModel
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
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var categoryMenuExpanded by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    var showLeaveDialog by remember { mutableStateOf(false) }
    val seconds = sampleReportRetrySeconds(state.retryAt)
    val contentError = when {
        state.content.trim().codePointCount(0, state.content.trim().length) < 6 -> "样本文本至少需要 6 个字符"
        state.content.trim().toByteArray(Charsets.UTF_8).size > 64 * 1024 -> "样本文本不能超过 64 KiB，请减少内容"
        else -> null
    }
    val senderTooLong = state.sender.codePointCount(0, state.sender.length) > 256
    val canSubmit = !state.loading && !state.localError && !state.submitting && !state.saving && state.draftChoice == null && seconds == 0L &&
        (state.pending || (state.agreed && state.validationError == null))
    val requestBack: () -> Unit = {
        // 按下提交后可能尚未重绘，返回保护必须读取 ViewModel 的即时状态。
        val current = viewModel.state.value
        if (current.preparing || current.saving || current.loading) viewModel.showPreparingHint()
        else if (current.dirty && !current.pending) showLeaveDialog = true
        else onNavigateBack()
    }
    BackHandler(enabled = !state.desensitizationState.visible && state.draftChoice == null, onBack = requestBack)
    LaunchedEffect(initialContent, initialSender, initialCategory) {
        viewModel.initialize(initialContent, initialSender, initialCategory)
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
    Scaffold(topBar = { SampleReportTopBar("上报样本", requestBack) },
        containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.loading) item { CircularProgressIndicator() }
            if (state.preparing) item {
                Text("正在保存本次提交，请稍候。保存完成后即可返回，网络等待不会阻止退出。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.pending) item {
                Text("暂未确认提交结果", style = MaterialTheme.typography.titleMedium)
                Text("本次内容和请求编号已保留。重试将使用原来确认的全部信息，不能修改后作为同一次请求发送。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.resultMessage?.let { message -> item {
                Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            } }
            if (state.localError) item {
                TextButton(onClick = viewModel::retryLocalRead, enabled = !state.loading && !state.submitting && !state.saving) {
                    Text(if (state.loading) "正在重新读取…" else "重新读取本机数据")
                }
            }
            item {
                OutlinedTextField(value = state.content, onValueChange = viewModel::updateContent,
                    modifier = Modifier.fillMaxWidth(), enabled = !state.locked,
                    label = { Text("脱敏正文") }, minLines = 8,
                    isError = state.content.isNotEmpty() && contentError != null,
                    supportingText = {
                        Text(contentError ?: "提交前请删除或替换所有敏感信息；最多 64 KiB")
                    })
                TextButton(onClick = viewModel::openDesensitizationAssistant,
                    enabled = !state.locked && state.content.isNotBlank()) {
                    Icon(Icons.Rounded.Edit, null)
                    Text("辅助脱敏", Modifier.padding(start = 8.dp))
                }
                HorizontalDivider()
            }
            item {
                Text("提交分类", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(Modifier.fillMaxWidth()) {
                    TextButton(onClick = { categoryMenuExpanded = true }, enabled = !state.locked,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(sampleReportCategory(state.category), Modifier.weight(1f))
                        Icon(Icons.Rounded.ArrowDropDown, "选择分类")
                    }
                    DropdownMenu(expanded = categoryMenuExpanded, onDismissRequest = { categoryMenuExpanded = false }) {
                        SampleCategory.entries.forEach { category ->
                            DropdownMenuItem(text = { Text(category.label) }, onClick = {
                                viewModel.updateCategory(category.value)
                                categoryMenuExpanded = false
                            })
                        }
                    }
                }
                HorizontalDivider()
            }
            item {
                OutlinedTextField(value = state.sender, onValueChange = viewModel::updateSender,
                    modifier = Modifier.fillMaxWidth(), enabled = !state.locked,
                    label = { Text("发送方（可选）") },
                    isError = senderTooLong,
                    supportingText = { Text(if (senderTooLong) "发送方不能超过 256 个字符" else "最多 256 个字符，请一并脱敏") })
            }
            if (!state.pending) item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Checkbox(checked = state.agreed, onCheckedChange = viewModel::updateAgreed, enabled = !state.locked)
                    Text("我确认已删除或替换姓名、手机号、地址、订单号、银行卡号等敏感信息，并同意主动提交此样本、发送方、分类、版本、Android 设备标识及本次安装身份，用于规则改进、模型优化、反滥用风控和查询本人记录。",
                        Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
            item {
                Button(onClick = viewModel::submit, enabled = canSubmit, modifier = Modifier.fillMaxWidth()) {
                    if (state.submitting) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(20.dp), strokeWidth = 2.dp)
                    Text(when {
                        state.preparing -> "正在保存…"
                        state.submitting -> "正在提交…"
                        seconds > 0 -> "${seconds} 秒后可重试"
                        state.pending -> "重试本次提交"
                        else -> "上报样本"
                    })
                }
            }
            if (state.pending) item {
                TextButton(onClick = onViewReports, enabled = !state.submitting, modifier = Modifier.fillMaxWidth()) { Text("查看我的上报") }
                TextButton(onClick = requestBack, modifier = Modifier.fillMaxWidth()) { Text("保留并返回") }
            }
        }
    }
    if (state.draftChoice != null) AlertDialog(onDismissRequest = onNavigateBack,
        title = { Text("选择要编辑的内容") },
        text = { Text("本机已有一份保留草稿。请选择使用当前短信，或继续编辑保留草稿。选择当前短信不会删除或覆盖原草稿。") },
        confirmButton = { TextButton(onClick = viewModel::chooseCurrentMessage) { Text("使用当前短信") } },
        dismissButton = {
            Column {
                TextButton(onClick = viewModel::chooseRetainedDraft) { Text("继续保留草稿") }
                TextButton(onClick = onNavigateBack) { Text("取消") }
            }
        })
    if (showLeaveDialog) AlertDialog(onDismissRequest = { showLeaveDialog = false }, title = { Text("保留这份草稿？") },
        text = { Text("脱敏修改尚未提交。保留后仅存于本次安装的本机私有目录，不参与备份，也不会自动上传。" +
            if (state.hasRetainedDraft) "保留会覆盖先前保留的草稿；选择放弃草稿会删除先前的保留草稿。" else "") },
        confirmButton = { TextButton(onClick = { showLeaveDialog = false; viewModel.saveAndLeave(onNavigateBack) }) { Text("保留并返回") } },
        dismissButton = {
            Row {
                TextButton(onClick = { showLeaveDialog = false; viewModel.discardAndLeave(onNavigateBack) }) { Text("放弃草稿") }
                TextButton(onClick = { showLeaveDialog = false }) { Text("继续编辑") }
            }
        })

    if (state.desensitizationState.visible) {
        val latestDesensitizationState = rememberUpdatedState(state.desensitizationState)
        val requestDiscardDialog = rememberUpdatedState {
            showDiscardDialog = true
        }
        val sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(
                SheetValue.Hidden,
                SheetValue.Expanded
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
            }
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
                }
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
            }
        )
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
            bottom = 24.dp
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                "辅助脱敏",
                style = MaterialTheme.typography.titleLarge
            )
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "选择敏感片段",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    state.tokens.forEach { token ->
                        TokenChip(
                            token = token,
                            selected = state.isTokenSelected(token),
                            onClick = {
                                if (token.selectable) {
                                    onSelectRange(token.start, token.end)
                                }
                            }
                        )
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "敏感类型",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SensitiveType.entries.forEach { type ->
                        FilterChip(
                            selected = state.selectedType == type,
                            onClick = { onTypeSelected(type) },
                            label = { Text(type.label) }
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
                minLines = 4
            )
        }
        item {
            Button(
                onClick = onReplace,
                enabled = state.canReplace,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("替换")
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismissRequest,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("取消")
                }
                Button(
                    onClick = onApply,
                    enabled = state.draft.isNotBlank(),
                    modifier = Modifier.weight(1f)
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
            label = { Text(token.displayText()) }
        )
    } else {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small
        ) {
            Text(
                text = token.displayText(),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun categoryLabel(value: String): String {
    return SampleCategory.fromValue(value)?.label ?: value
}

private fun SampleTextToken.displayText(): String {
    return when {
        text == "\n" -> "换行"
        text == "\t" -> "制表"
        text.isBlank() -> "空格"
        else -> text
    }
}
