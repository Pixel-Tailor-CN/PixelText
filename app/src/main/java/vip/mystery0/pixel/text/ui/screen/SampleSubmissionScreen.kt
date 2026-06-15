package vip.mystery0.pixel.text.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.koin.androidx.compose.koinViewModel
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
                OutlinedTextField(
                    value = viewModel.content,
                    onValueChange = viewModel::updateContent,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("短信样本（提交前请先脱敏）") },
                    minLines = 8
                )
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
