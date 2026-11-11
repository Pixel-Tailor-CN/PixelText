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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.koin.androidx.compose.koinViewModel
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
        TopAppBar(title = { Text("备份与恢复") }, navigationIcon = {
            IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
        })
    }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
                .verticalScroll(scrollState).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "安全导出短信，或从备份文件合并恢复。现有短信不会被清空。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 所有反馈信息都用对话框呈现，页面只保留操作入口。
            if (!state.busy && (!state.restoreProtected || preview != null)) {
                if (preview == null) {
                    BackupTaskCard(title = "导出备份") {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .toggleable(value = encrypt, role = Role.Switch, onValueChange = { encrypt = it }),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("加密导出", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    if (encrypt) "使用密码保护备份文件" else "文件不加密，持有者可直接读取短信",
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
                                label = { Text("确认密码") },
                                supportingText = {
                                    Text(if (mismatch) "两次输入的密码不一致" else "建议至少 12 位，丢失后无法找回")
                                },
                                isError = mismatch,
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Text(
                            "不含彩信、附件及应用名称与图标。文件大小和条目名称仍可能可见，保存位置由你自己选择。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = { showExportContentDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("导出备份") }
                    }
                    BackupTaskCard(title = "恢复备份") {
                        Text(
                            "选择备份文件后会先显示预览，确认后才开始恢复。",
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
                                Text("加密文件", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    if (restoreEncrypted) "需要密码才能解密备份文件" else "文件未加密，将直接读取",
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
                                label = "文件密码",
                            )
                        }
                        Button(
                            onClick = { open.launch(arrayOf("*/*")) },
                            enabled = !restoreEncrypted || restorePassword.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("选择备份文件")
                        }
                    }
                } else {
                    BackupTaskCard(title = "恢复文件预览", cornerRadius = 28.dp) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("短信数量 ${preview.smsCount}", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "备份时间：${SimpleDateFormat("yyyy年MM月dd日 HH:mm:ss", locale).format(Date(preview.createdAt))}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text("选择恢复内容", style = MaterialTheme.typography.titleSmall)
                        BackupSectionSelection(
                            sections = BackupSection.entries.filter { it in preview.sections },
                            selection = selection,
                            onSelectionChange = { selection = it },
                        )
                        Text(
                            "短信去重合并，规则取并集；选中的设置由备份覆盖。旧 SIM 标识不迁移；出站消息转为失败且不会发送。会话归档可能同时影响该会话现有彩信的列表展示。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = { restoreConfirm = true }, enabled = selection.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("恢复所选内容") }
                        TextButton(onClick = viewModel::cancel) { Text("关闭预览") }
                    }
                }
            }
            if (state.busy) {
                Text("请保持应用在前台", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                BackupTaskCard(title = "正在处理") {
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
                        buildString {
                            append("已用时 ").append(formatDuration(elapsedSeconds))
                            remainingSeconds?.let { append(" · 预计还需 ").append(formatDuration(it)) }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(when (state.phase) {
                        BackupPhase.SYNCING_MIRROR -> "正在同步短信镜像…"
                        BackupPhase.SNAPSHOTTING -> "正在创建应用数据库快照…"
                        BackupPhase.EXPORTING -> "正在打包并保存备份…"
                        BackupPhase.VALIDATING -> "正在解密并校验全部备份内容…"
                        BackupPhase.REBUILDING -> "正在对账和重建索引…"
                        else -> "正在恢复… ${state.processed} / ${state.total ?: "—"}"
                    }, style = MaterialTheme.typography.bodyMedium)
                    // 导出与恢复的中断影响不同，提示和按钮按当前操作区分。
                    val exporting = state.phase in setOf(
                        BackupPhase.SYNCING_MIRROR, BackupPhase.SNAPSHOTTING, BackupPhase.EXPORTING,
                    )
                    if (state.restoreProtected || !exporting) {
                        Text(
                            "已写入的短信不会撤销；停止后可以重新导入，重复内容会自动跳过。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            "停止后已写入的文件可能不完整。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = viewModel::cancel, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            when (state.phase) {
                                BackupPhase.SYNCING_MIRROR, BackupPhase.SNAPSHOTTING,
                                BackupPhase.EXPORTING -> "停止导出"
                                BackupPhase.VALIDATING -> "取消"
                                else -> "停止恢复"
                            },
                        )
                    }
                }
            }
            // 中断态的入口：反馈在对话框里，这里只保留继续处理的按钮。
            if (state.restoreProtected && !state.busy && state.phase != BackupPhase.COMPLETED) {
                Button(onClick = { showInterruptedDialog = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("继续处理上次恢复")
                }
            }
        }
    }
    // 错误提示统一用对话框，关闭后只清除提示，不影响中断进度。
    state.errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::clearError,
            title = { Text(if (state.phase == BackupPhase.INTERRUPTED) "操作已停止" else "操作失败") },
            text = {
                Text(
                    if (state.authorizationRequired && !authorizationNeeded) {
                        "权限已恢复，请重新创建备份或重试恢复"
                    } else message,
                )
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (authorizationNeeded) {
                        TextButton(onClick = { viewModel.clearError(); authorize() }) { Text("授权") }
                    }
                    TextButton(onClick = viewModel::clearError) { Text("知道了") }
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
            title = { Text("上次恢复未完成") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("已写入的短信会保留，验证码自动清理暂缓。")
                    BackupResultDetails(state.summary)
                    if (preview == null) {
                        if (restoreEncrypted) {
                            BackupPasswordField(
                                password = restorePassword,
                                onPasswordChange = { restorePassword = it },
                                label = "文件密码",
                            )
                        }
                        TextButton(
                            onClick = { showInterruptedDialog = false; open.launch(arrayOf("*/*")) },
                            enabled = !restoreEncrypted || restorePassword.isNotEmpty(),
                        ) { Text("重新选择文件继续恢复") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showInterruptedDialog = false; finishConfirm = true }) {
                    Text("确认结果并结束保护")
                }
            },
            dismissButton = { TextButton(onClick = { showInterruptedDialog = false }) { Text("稍后处理") } },
        )
    }
    // 导出和恢复完成后都用对话框告知结果，关闭后页面回到初始状态。
    if (state.phase == BackupPhase.COMPLETED && !state.busy && !state.restoreProtected) {
        val restored = state.summary.completedSections.isNotEmpty()
        AlertDialog(
            onDismissRequest = viewModel::dismissResult,
            title = { Text(if (restored) "恢复完成" else "导出完成") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (restored) {
                        Text("短信已写入并完成同步，可以直接返回使用。")
                        BackupResultDetails(state.summary)
                    } else {
                        Text("备份文件已保存到你选择的位置，请妥善保管。")
                        if (encrypt) Text("文件使用你设置的密码加密，忘记密码将无法恢复。")
                    }
                }
            },
            confirmButton = { TextButton(onClick = viewModel::dismissResult) { Text("完成") } },
        )
    }
    if (showExportContentDialog) AlertDialog(
        onDismissRequest = { showExportContentDialog = false },
        title = { Text("选择备份内容") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("选择要导出的内容，确认后进入文件保存流程。")
                if (encrypt && (password.isEmpty() || password != confirmation)) {
                    Text(
                        "请先输入并确认备份密码。",
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
            ) { Text("继续导出") }
        },
        dismissButton = { TextButton(onClick = { showExportContentDialog = false }) { Text("取消") } },
    )
    if (warning) AlertDialog(onDismissRequest = { warning = false }, title = { Text("导出未加密备份？") },
        text = { Text("任何持有此文件的人都可以读取短信正文及设置，请妥善保管。") },
        confirmButton = { TextButton(onClick = { warning = false; createFile() }) { Text("继续") } },
        dismissButton = { TextButton(onClick = { warning = false }) { Text("取消") } })
    if (restoreConfirm && preview != null) AlertDialog(onDismissRequest = { restoreConfirm = false }, title = { Text("确认恢复") },
        text = { Text("不会清空现有短信。所选设置会被覆盖，中断时已写入的数据会保留。") },
        confirmButton = { TextButton(onClick = { restoreConfirm = false; viewModel.restore(preview.token, selection) }) { Text("开始恢复") } },
        dismissButton = { TextButton(onClick = { restoreConfirm = false }) { Text("取消") } })
    if (finishConfirm) AlertDialog(onDismissRequest = { finishConfirm = false }, title = { Text("结束恢复保护") },
        text = { Text("会先暂停验证码自动删除，避免清理刚恢复的历史验证码；需要时可在设置中重新开启。已写入的短信保留，重新导入会去重。") },
        confirmButton = { TextButton(onClick = { finishConfirm = false; viewModel.acknowledge() }) { Text("确认结束") } },
        dismissButton = { TextButton(onClick = { finishConfirm = false }) { Text("取消") } })
}

private fun formatDuration(seconds: Long): String {
    val minutes = seconds / 60
    val remaining = seconds % 60
    return "%02d:%02d".format(minutes, remaining)
}
