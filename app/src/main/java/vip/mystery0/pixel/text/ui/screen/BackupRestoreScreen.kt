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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.koin.androidx.compose.koinViewModel
import vip.mystery0.pixel.text.domain.backup.*
import vip.mystery0.pixel.text.viewmodel.BackupViewModel
import vip.mystery0.pixel.text.ui.isDefaultSmsApp
import vip.mystery0.pixel.text.ui.createDefaultSmsAppRequestIntent
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay

@Composable
fun BackupRestoreScreen(onNavigateBack: () -> Unit, viewModel: BackupViewModel = koinViewModel()) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val scrollState = rememberScrollState()
    var selection by rememberSaveable(stateSaver = listSaver(
        save = { it.map { section -> section.name } }, restore = { it.map(BackupSection::valueOf).toSet() },
    )) { mutableStateOf(BackupSection.entries.toSet()) }
    var selectionToken by rememberSaveable { mutableStateOf<String?>(null) }
    var encrypt by rememberSaveable { mutableStateOf(true) }
    // 密码禁止使用 rememberSaveable，配置变化时由用户重新输入。
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var restorePassword by remember { mutableStateOf("") }
    var warning by remember { mutableStateOf(false) }
    var restoreConfirm by remember { mutableStateOf(false) }
    var finishConfirm by remember { mutableStateOf(false) }
    var disableCleanup by remember { mutableStateOf(false) }
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
        if (uri != null) viewModel.inspect(uri.toString(), restorePassword.takeIf { it.isNotEmpty() }?.toCharArray())
        restorePassword = ""
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
            state.errorMessage?.let {
                BackupFeedbackCard(title = "操作失败", description = if (state.authorizationRequired && !authorizationNeeded) {
                    "权限已恢复，请重新创建备份或重试恢复"
                } else it, isError = true)
                if (authorizationNeeded) {
                    FilledTonalButton(onClick = ::authorize, enabled = !state.busy) { Text("授权") }
                }
            }
            if (state.restoreProtected) {
                BackupFeedbackCard(
                    title = "恢复保护已开启",
                    description = "自动验证码清理暂缓。已写入的短信不会因取消而撤销；确认结果后才会恢复常规清理。",
                )
            }

            if (!state.busy && (!state.restoreProtected || preview != null)) {
                if (preview == null) {
                    BackupTaskCard(title = "备份内容") {
                        Text("不含彩信、附件及应用名称与图标。", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        BackupSectionSelection(
                            sections = BackupSection.entries,
                            selection = selection,
                            onSelectionChange = { selection = it },
                        )
                    }
                    BackupTaskCard(title = "导出与安全") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("加密导出", style = MaterialTheme.typography.titleSmall)
                            }
                            Switch(checked = encrypt, onCheckedChange = { encrypt = it })
                        }
                        if (encrypt) {
                            BackupPasswordField(password = password, onPasswordChange = { password = it })
                            val mismatch = confirmation.isNotEmpty() && password != confirmation
                            OutlinedTextField(
                                value = confirmation,
                                onValueChange = { confirmation = it },
                                label = { Text("确认密码") },
                                supportingText = if (mismatch) ({ Text("两次输入的密码不一致") }) else null,
                                isError = mismatch,
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Text(
                            "建议使用至少 12 位强密码，丢失无法找回。文件大小和条目名称等元信息仍可能可见。系统文件选择器可能包含云盘，请自行选择保存位置。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = { if (encrypt) createFile() else warning = true },
                            enabled = selection.isNotEmpty() &&
                                (!encrypt || (password.isNotEmpty() && password == confirmation)),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("创建备份") }
                    }
                    BackupTaskCard(title = "从文件恢复") {
                        BackupPasswordField(
                            password = restorePassword,
                            onPasswordChange = { restorePassword = it },
                            label = "文件密码（未加密可留空）",
                        )
                        Text(
                            "备份文件通常位于 Downloads 文件夹。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(onClick = { open.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                            Text("选择备份文件")
                        }
                    }
                } else {
                    BackupTaskCard(title = "恢复文件预览", cornerRadius = 28.dp) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("短信数量 ${preview.smsCount}", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "备份时间：${DateFormat.getDateTimeInstance().format(Date(preview.createdAt))}",
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
                    Text(
                        "已写入的短信不会撤销；停止后可以重新导入，重复内容会自动跳过。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = viewModel::cancel, modifier = Modifier.fillMaxWidth()) { Text("停止恢复") }
                }
            }
            if (state.phase == BackupPhase.COMPLETED) {
                Text("操作完成", style = MaterialTheme.typography.titleMedium)
            }
            if (state.restoreProtected || state.summary.inserted + state.summary.existing > 0) {
                BackupTaskCard(title = "恢复结果", cornerRadius = 28.dp) {
                    BackupResultDetails(state.summary)
                    if (!state.busy) {
                        Button(onClick = { finishConfirm = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("确认结果并结束恢复保护")
                        }
                        if (preview == null) {
                            BackupPasswordField(
                                password = restorePassword,
                                onPasswordChange = { restorePassword = it },
                                label = "文件密码（未加密可留空）",
                            )
                            OutlinedButton(onClick = { open.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                                Text("重新选择文件并预览恢复")
                            }
                        }
                    }
                }
            }
        }
    }
    if (warning) AlertDialog(onDismissRequest = { warning = false }, title = { Text("导出未加密备份？") },
        text = { Text("任何持有此文件的人都可以读取短信正文及设置，请妥善保管。") },
        confirmButton = { TextButton(onClick = { warning = false; createFile() }) { Text("继续") } },
        dismissButton = { TextButton(onClick = { warning = false }) { Text("取消") } })
    if (restoreConfirm && preview != null) AlertDialog(onDismissRequest = { restoreConfirm = false }, title = { Text("确认恢复") },
        text = { Text("不会清空现有短信。所选设置会被覆盖，中断时已写入的数据会保留。") },
        confirmButton = { TextButton(onClick = { restoreConfirm = false; viewModel.restore(preview.token, selection) }) { Text("开始恢复") } },
        dismissButton = { TextButton(onClick = { restoreConfirm = false }) { Text("取消") } })
    if (finishConfirm) AlertDialog(onDismissRequest = { finishConfirm = false }, title = { Text("结束恢复保护") },
        text = { Column {
            Text("恢复保护结束后，已启用的常规验证码清理可能删除刚恢复的过期验证码。中断后的计数以最后持久化进度为准，重新导入会再次去重。")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = disableCleanup, onCheckedChange = { disableCleanup = it })
                Text("暂停验证码自动删除")
            }
            Text(
                "勾选后，本次恢复结束时不会恢复验证码自动清理；之后可在设置中手动开启。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } }, confirmButton = { TextButton(onClick = { finishConfirm = false; viewModel.acknowledge(disableCleanup) }) { Text("确认结束") } },
        dismissButton = { TextButton(onClick = { finishConfirm = false }) { Text("取消") } })
}

private fun formatDuration(seconds: Long): String {
    val minutes = seconds / 60
    val remaining = seconds % 60
    return "%02d:%02d".format(minutes, remaining)
}
