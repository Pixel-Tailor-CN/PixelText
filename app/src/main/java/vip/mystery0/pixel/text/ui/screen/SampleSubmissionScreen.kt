package vip.mystery0.pixel.text.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.unit.dp
import org.koin.androidx.compose.koinViewModel
import vip.mystery0.pixel.text.domain.sample.DesensitizationAssistantState
import vip.mystery0.pixel.text.domain.sample.SampleTextToken
import vip.mystery0.pixel.text.domain.sample.SensitiveType
import vip.mystery0.pixel.text.viewmodel.SampleSubmissionViewModel

internal const val SAMPLE_SUBMISSION_DRAFT_CONTENT = "sample_submission_draft_content"
internal const val SAMPLE_SUBMISSION_DRAFT_SENDER = "sample_submission_draft_sender"

@Composable
fun SampleSubmissionScreen(
    viewModel: SampleSubmissionViewModel = koinViewModel(),
    onNavigateBack: () -> Unit,
    initialContent: String = "",
    initialSender: String = "",
) {
    var categoryMenuExpanded by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    val canSubmit = viewModel.agreed && viewModel.content.isNotBlank() && !viewModel.submitting

    LaunchedEffect(initialContent, initialSender) {
        viewModel.applyDraft(initialContent, initialSender)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                title = {
                    Text(
                        "上报脱敏样本",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0.dp),
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(paddingValues),
            contentPadding = PaddingValues(
                start = 20.dp,
                top = paddingValues.calculateTopPadding() + 16.dp,
                end = 20.dp,
                bottom = 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                OutlinedTextField(
                    value = viewModel.sender,
                    onValueChange = viewModel::updateSender,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("发件人（可选）") },
                    singleLine = true
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "样本类别",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { categoryMenuExpanded = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                categoryLabel(viewModel.category),
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                Icons.Rounded.ArrowDropDown,
                                contentDescription = "Expand"
                            )
                        }
                        DropdownMenu(
                            expanded = categoryMenuExpanded,
                            onDismissRequest = { categoryMenuExpanded = false }
                        ) {
                            sampleCategories.forEach { category ->
                                DropdownMenuItem(
                                    text = { Text(category.label) },
                                    onClick = {
                                        viewModel.updateCategory(category.value)
                                        categoryMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = viewModel.content,
                        onValueChange = viewModel::updateContent,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("短信样本（提交前请先脱敏）") },
                        minLines = 8
                    )
                    OutlinedButton(
                        onClick = viewModel::openDesensitizationAssistant,
                        enabled = viewModel.content.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.Edit, contentDescription = null)
                        Text(
                            "辅助脱敏",
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {
                    Checkbox(
                        checked = viewModel.agreed,
                        onCheckedChange = viewModel::updateAgreed
                    )
                    Text(
                        text = "我确认已删除或替换姓名、手机号、地址、订单号、银行卡号等敏感信息，并同意提交该样本及 Android 设备标识用于规则改进、模型优化和反滥用风控。",
                        modifier = Modifier
                            .weight(1f)
                            .padding(top = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            item {
                Button(
                    onClick = viewModel::submit,
                    enabled = canSubmit,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null)
                    Text(
                        if (viewModel.submitting) "上报中..." else "上报样本",
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
            item {
                Spacer(
                    modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars)
                )
            }
        }
    }

    viewModel.resultMessage?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::clearResult,
            title = { Text("上报结果") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = viewModel::clearResult) {
                    Text("知道了")
                }
            }
        )
    }

    if (viewModel.desensitizationState.visible) {
        val latestDesensitizationState = rememberUpdatedState(viewModel.desensitizationState)
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
                state = viewModel.desensitizationState,
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

private data class SampleCategory(
    val value: String,
    val label: String,
)

private val sampleCategories = listOf(
    SampleCategory("verification_code", "验证码"),
    SampleCategory("bank_transaction", "银行动账"),
    SampleCategory("express_delivery", "快递通知"),
    SampleCategory("ticket", "票务出行"),
    SampleCategory("spam", "垃圾短信"),
    SampleCategory("normal", "普通短信"),
)

private fun categoryLabel(value: String): String {
    return sampleCategories.firstOrNull { it.value == value }?.label ?: value
}

private fun SampleTextToken.displayText(): String {
    return when {
        text == "\n" -> "换行"
        text == "\t" -> "制表"
        text.isBlank() -> "空格"
        else -> text
    }
}
