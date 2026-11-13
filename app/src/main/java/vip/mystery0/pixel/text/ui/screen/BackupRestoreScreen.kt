package vip.mystery0.pixel.text.ui.screen

import android.Manifest
import android.os.SystemClock
import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri
import vip.mystery0.pixel.text.data.source.SmsAccessState
import vip.mystery0.pixel.text.data.source.smsAccessState
import androidx.lifecycle.compose.LifecycleResumeEffect
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.koin.androidx.compose.koinViewModel
import vip.mystery0.pixel.text.R
import vip.mystery0.pixel.text.domain.backup.*
import vip.mystery0.pixel.text.viewmodel.BackupViewModel
import vip.mystery0.pixel.text.ui.isDefaultSmsApp
import vip.mystery0.pixel.text.ui.createDefaultSmsAppRequestIntent
import java.text.SimpleDateFormat
import java.util.Date
import kotlinx.coroutines.delay

@Composable
fun BackupRestoreScreen(onNavigateBack: () -> Unit, viewModel: BackupViewModel = koinViewModel()) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val state by viewModel.state.collectAsState()
    val scrollState = rememberScrollState()
    var selection by rememberSaveable(stateSaver = listSaver(
        save = { it.map { section -> section.name } }, restore = { it.map(BackupSection::valueOf).toSet() },
    )) { mutableStateOf(BackupSection.entries.toSet()) }
    var selectionToken by rememberSaveable { mutableStateOf<String?>(null) }
    var encrypt by rememberSaveable { mutableStateOf(true) }
    var restoreEncrypted by rememberSaveable { mutableStateOf(true) }
    // 密码禁止使用 rememberSaveable，配置变化时由用户重新输入。
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var restorePassword by remember { mutableStateOf("") }
    var warning by remember { mutableStateOf(false) }
    var showExportContentDialog by remember { mutableStateOf(false) }
    var showInterruptedDialog by remember { mutableStateOf(false) }
    var restoreConfirm by remember { mutableStateOf(false) }
    var finishConfirm by remember { mutableStateOf(false) }
    var elapsedSeconds by remember { mutableLongStateOf(0L) }
    var permissionVersion by remember { mutableIntStateOf(0) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionVersion++ }
    val role = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        permissionVersion++
        if (context.isDefaultSmsApp() && context.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            permissions.launch(Manifest.permission.READ_SMS)
        }
    }
    LifecycleResumeEffect(Unit) {
        permissionVersion++
        onPauseOrDispose { }
    }
    val authorizationNeeded = remember(permissionVersion, state.authorizationRequired) {
        state.authorizationRequired && context.smsAccessState() != SmsAccessState.READY
    }
    fun authorize() {
        if (!context.isDefaultSmsApp()) role.launch(context.createDefaultSmsAppRequestIntent())
        else if (context.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            permissions.launch(Manifest.permission.READ_SMS)
        } else {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
        }
    }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) viewModel.exportTo(uri.toString(), selection, if (encrypt) password.toCharArray() else null)
        password = ""; confirmation = ""
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            viewModel.inspect(
                uri.toString(),
                restorePassword.takeIf { restoreEncrypted && it.isNotEmpty() }?.toCharArray(),
            )
        }
        // 校验失败时保留密码方便重试，成功预览后再清除。
    }
    LaunchedEffect(state.restoreProtected, state.busy, state.phase) {
        // 中断后立即用对话框告知，用户选择“稍后处理”后不再自动弹出。
        if (state.restoreProtected && !state.busy && state.phase != BackupPhase.COMPLETED) {
            showInterruptedDialog = true
        }
    }
    LaunchedEffect(state.errorMessage, scrollState) {
        // 错误反馈位于页首，失败后无需用户在长表单或恢复结果中查找。
        if (!state.errorMessage.isNullOrEmpty()) scrollState.scrollTo(0)
    }
    LaunchedEffect(state.busy) {
        if (!state.busy) {
            elapsedSeconds = 0L
            return@LaunchedEffect
        }
        val startedAt = SystemClock.elapsedRealtime()
        while (true) {
            elapsedSeconds = (SystemClock.elapsedRealtime() - startedAt) / 1_000L
            delay(1_000L)
        }
    }
    LaunchedEffect(state.preview?.token) {
        if (selectionToken != state.preview?.token) {
            state.preview?.let { selection = it.sections }
            selectionToken = state.preview?.token
            // 预览生成说明密码已经验证，不再保留明文密码。
            restorePassword = ""
        }
    }
    val preview = state.preview
    fun createFile() { create.launch("PixelText-${System.currentTimeMillis()}.ptbackup") }

    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.settings_backup_restore_title)) }, navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.settings_action_back))
            }
        })
    }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
                .verticalScroll(scrollState).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.settings_backup_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 所有反馈信息都用对话框呈现，页面只保留操作入口。
            if (!state.busy && (!state.restoreProtected || preview != null)) {
                if (preview == null) {
                    BackupTaskCard(title = stringResource(R.string.settings_backup_export_title)) {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .toggleable(value = encrypt, role = Role.Switch, onValueChange = { encrypt = it }),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(stringResource(R.string.settings_backup_encrypt_title), style = MaterialTheme.typography.titleSmall)
                                Text(
                                    stringResource(
                                        if (encrypt) R.string.settings_backup_encrypt_on
                                        else R.string.settings_backup_encrypt_off,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = encrypt, onCheckedChange = null)
                        }
                        if (encrypt) {
                            BackupPasswordField(password = password, onPasswordChange = { password = it })
                            val mismatch = confirmation.isNotEmpty() && password != confirmation
                            OutlinedTextField(
                                value = confirmation,
                                onValueChange = { confirmation = it },
                                label = { Text(stringResource(R.string.settings_backup_confirm_password)) },
                                supportingText = {
                                    Text(
                                        stringResource(
                                            if (mismatch) R.string.settings_backup_password_mismatch
                                            else R.string.settings_backup_password_hint,
                                        ),
                                    )
                                },
                                isError = mismatch,
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Text(
                            stringResource(R.string.settings_backup_export_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = { showExportContentDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.settings_backup_export_title)) }
                    }
                    BackupTaskCard(title = stringResource(R.string.settings_backup_restore_card_title)) {
                        Text(
                            stringResource(R.string.settings_backup_restore_intro),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .toggleable(
                                    value = restoreEncrypted,
                                    role = Role.Switch,
                                    onValueChange = { restoreEncrypted = it },
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(stringResource(R.string.settings_backup_encrypted_file), style = MaterialTheme.typography.titleSmall)
                                Text(
                                    stringResource(
                                        if (restoreEncrypted) R.string.settings_backup_encrypted_file_on
                                        else R.string.settings_backup_encrypted_file_off,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = restoreEncrypted, onCheckedChange = null)
                        }
                        if (restoreEncrypted) {
                            BackupPasswordField(
                                password = restorePassword,
                                onPasswordChange = { restorePassword = it },
                                label = stringResource(R.string.settings_backup_file_password),
                            )
                        }
                        Button(
                            onClick = { open.launch(arrayOf("*/*")) },
                            enabled = !restoreEncrypted || restorePassword.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.settings_backup_select_file))
                        }
                    }
                } else {
                    BackupTaskCard(title = stringResource(R.string.settings_backup_preview_title), cornerRadius = 28.dp) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                stringResource(
                                    if (preview.smsCount == 1L) R.string.settings_backup_preview_sms_count_single
                                    else R.string.settings_backup_preview_sms_count_plural,
                                    preview.smsCount,
                                ),
                                style = MaterialTheme.typography.titleLarge,
                            )
                            val timePattern = stringResource(R.string.settings_backup_preview_time_pattern)
                            Text(
                                stringResource(
                                    R.string.settings_backup_preview_time,
                                    SimpleDateFormat(timePattern, locale).format(Date(preview.createdAt)),
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(stringResource(R.string.settings_backup_preview_select_content), style = MaterialTheme.typography.titleSmall)
                        BackupSectionSelection(
                            sections = BackupSection.entries.filter { it in preview.sections },
                            selection = selection,
                            onSelectionChange = { selection = it },
                        )
                        Text(
                            stringResource(R.string.settings_backup_preview_note),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = { restoreConfirm = true }, enabled = selection.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.settings_backup_restore_selected)) }
                        TextButton(onClick = viewModel::cancel) { Text(stringResource(R.string.settings_backup_close_preview)) }
                    }
                }
            }
            if (state.busy) {
                Text(stringResource(R.string.settings_backup_keep_foreground), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                BackupTaskCard(title = stringResource(R.string.settings_backup_processing)) {
                    val progress = state.total?.takeIf { it > 0 }?.let {
                        (state.processed.toFloat() / it).coerceIn(0f, 1f)
                    }
                    if (progress != null) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "${state.processed} / ${state.total}",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    val remainingSeconds = state.total?.takeIf { state.processed > 0 && elapsedSeconds > 0 }?.let {
                        (elapsedSeconds * (it - state.processed).coerceAtLeast(0) / state.processed).coerceAtLeast(0)
                    }
                    Text(
                        if (remainingSeconds != null) {
                            stringResource(
                                R.string.settings_backup_elapsed_remaining,
                                formatDuration(elapsedSeconds),
                                formatDuration(remainingSeconds),
                            )
                        } else {
                            stringResource(
                                R.string.settings_backup_elapsed,
                                formatDuration(elapsedSeconds),
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(when (state.phase) {
                        BackupPhase.SYNCING_MIRROR -> stringResource(R.string.settings_backup_phase_syncing)
                        BackupPhase.SNAPSHOTTING -> stringResource(R.string.settings_backup_phase_snapshotting)
                        BackupPhase.EXPORTING -> stringResource(R.string.settings_backup_phase_exporting)
                        BackupPhase.VALIDATING -> stringResource(R.string.settings_backup_phase_validating)
                        BackupPhase.REBUILDING -> stringResource(R.string.settings_backup_phase_rebuilding)
                        else -> stringResource(
                            R.string.settings_backup_phase_restoring,
                            state.processed,
                            state.total?.toString() ?: "—",
                        )
                    }, style = MaterialTheme.typography.bodyMedium)
                    // 导出与恢复的中断影响不同，提示和按钮按当前操作区分。
                    val exporting = state.phase in setOf(
                        BackupPhase.SYNCING_MIRROR, BackupPhase.SNAPSHOTTING, BackupPhase.EXPORTING,
                    )
                    if (state.restoreProtected || !exporting) {
                        Text(
                            stringResource(R.string.settings_backup_interrupt_restore_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            stringResource(R.string.settings_backup_interrupt_export_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = viewModel::cancel, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            when (state.phase) {
                                BackupPhase.SYNCING_MIRROR, BackupPhase.SNAPSHOTTING,
                                BackupPhase.EXPORTING -> stringResource(R.string.settings_backup_stop_export)
                                BackupPhase.VALIDATING -> stringResource(R.string.settings_action_cancel)
                                else -> stringResource(R.string.settings_backup_stop_restore)
                            },
                        )
                    }
                }
            }
            // 中断态的入口：反馈在对话框里，这里只保留继续处理的按钮。
            if (state.restoreProtected && !state.busy && state.phase != BackupPhase.COMPLETED) {
                Button(onClick = { showInterruptedDialog = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_backup_resume_button))
                }
            }
        }
    }
    // 错误提示统一用对话框，关闭后只清除提示，不影响中断进度。
    state.errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::clearError,
            title = {
                Text(
                    stringResource(
                        if (state.phase == BackupPhase.INTERRUPTED) R.string.settings_backup_error_stopped
                        else R.string.settings_backup_error_failed,
                    ),
                )
            },
            text = {
                Text(
                    if (state.authorizationRequired && !authorizationNeeded) {
                        stringResource(R.string.settings_backup_auth_restored)
                    } else message,
                )
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (authorizationNeeded) {
                        TextButton(onClick = { viewModel.clearError(); authorize() }) { Text(stringResource(R.string.settings_action_grant)) }
                    }
                    TextButton(onClick = viewModel::clearError) { Text(stringResource(R.string.settings_action_ok)) }
                }
            },
        )
    }
    // 恢复中断的进度与处理入口也放在对话框里。
    if (showInterruptedDialog && state.restoreProtected && !state.busy &&
        state.phase != BackupPhase.COMPLETED
    ) {
        AlertDialog(
            onDismissRequest = { showInterruptedDialog = false },
            title = { Text(stringResource(R.string.settings_backup_interrupted_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.settings_backup_interrupted_body))
                    BackupResultDetails(state.summary)
                    if (preview == null) {
                        if (restoreEncrypted) {
                            BackupPasswordField(
                                password = restorePassword,
                                onPasswordChange = { restorePassword = it },
                                label = stringResource(R.string.settings_backup_file_password),
                            )
                        }
                        TextButton(
                            onClick = { showInterruptedDialog = false; open.launch(arrayOf("*/*")) },
                            enabled = !restoreEncrypted || restorePassword.isNotEmpty(),
                        ) { Text(stringResource(R.string.settings_backup_reselect_file)) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showInterruptedDialog = false; finishConfirm = true }) {
                    Text(stringResource(R.string.settings_backup_confirm_end_protection))
                }
            },
            dismissButton = { TextButton(onClick = { showInterruptedDialog = false }) { Text(stringResource(R.string.settings_action_later)) } },
        )
    }
    // 导出和恢复完成后都用对话框告知结果，关闭后页面回到初始状态。
    if (state.phase == BackupPhase.COMPLETED && !state.busy && !state.restoreProtected) {
        val restored = state.summary.completedSections.isNotEmpty()
        AlertDialog(
            onDismissRequest = viewModel::dismissResult,
            title = {
                Text(
                    stringResource(
                        if (restored) R.string.settings_backup_completed_restore_title
                        else R.string.settings_backup_completed_export_title,
                    ),
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (restored) {
                        Text(stringResource(R.string.settings_backup_completed_restore_body))
                        BackupResultDetails(state.summary)
                    } else {
                        Text(stringResource(R.string.settings_backup_completed_export_body))
                        if (encrypt) Text(stringResource(R.string.settings_backup_completed_export_encrypted))
                    }
                }
            },
            confirmButton = { TextButton(onClick = viewModel::dismissResult) { Text(stringResource(R.string.settings_action_done)) } },
        )
    }
    if (showExportContentDialog) AlertDialog(
        onDismissRequest = { showExportContentDialog = false },
        title = { Text(stringResource(R.string.settings_backup_export_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_backup_export_dialog_body))
                if (encrypt && (password.isEmpty() || password != confirmation)) {
                    Text(
                        stringResource(R.string.settings_backup_export_dialog_pw_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                BackupSectionSelection(
                    sections = BackupSection.entries,
                    selection = selection,
                    onSelectionChange = { selection = it },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    showExportContentDialog = false
                    if (encrypt) createFile() else warning = true
                },
                enabled = selection.isNotEmpty() &&
                    (!encrypt || (password.isNotEmpty() && password == confirmation)),
            ) { Text(stringResource(R.string.settings_backup_export_dialog_continue)) }
        },
        dismissButton = { TextButton(onClick = { showExportContentDialog = false }) { Text(stringResource(R.string.settings_action_cancel)) } },
    )
    if (warning) AlertDialog(
        onDismissRequest = { warning = false },
        title = { Text(stringResource(R.string.settings_backup_unencrypted_title)) },
        text = { Text(stringResource(R.string.settings_backup_unencrypted_body)) },
        confirmButton = { TextButton(onClick = { warning = false; createFile() }) { Text(stringResource(R.string.settings_backup_unencrypted_confirm)) } },
        dismissButton = { TextButton(onClick = { warning = false }) { Text(stringResource(R.string.settings_action_cancel)) } },
    )
    if (restoreConfirm && preview != null) AlertDialog(
        onDismissRequest = { restoreConfirm = false },
        title = { Text(stringResource(R.string.settings_backup_restore_confirm_title)) },
        text = { Text(stringResource(R.string.settings_backup_restore_confirm_body)) },
        confirmButton = { TextButton(onClick = { restoreConfirm = false; viewModel.restore(preview.token, selection) }) { Text(stringResource(R.string.settings_backup_restore_confirm_button)) } },
        dismissButton = { TextButton(onClick = { restoreConfirm = false }) { Text(stringResource(R.string.settings_action_cancel)) } },
    )
    if (finishConfirm) AlertDialog(
        onDismissRequest = { finishConfirm = false },
        title = { Text(stringResource(R.string.settings_backup_finish_confirm_title)) },
        text = { Text(stringResource(R.string.settings_backup_finish_confirm_body)) },
        confirmButton = { TextButton(onClick = { finishConfirm = false; viewModel.acknowledge() }) { Text(stringResource(R.string.settings_action_confirm)) } },
        dismissButton = { TextButton(onClick = { finishConfirm = false }) { Text(stringResource(R.string.settings_action_cancel)) } },
    )
}

private fun formatDuration(seconds: Long): String {
    val minutes = seconds / 60
    val remaining = seconds % 60
    return "%02d:%02d".format(minutes, remaining)
}
