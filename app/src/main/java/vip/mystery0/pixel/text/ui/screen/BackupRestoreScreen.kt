package vip.mystery0.pixel.text.ui.screen

import android.Manifest
import android.app.role.RoleManager
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.koin.androidx.compose.koinViewModel
import vip.mystery0.pixel.text.domain.backup.*
import vip.mystery0.pixel.text.viewmodel.BackupViewModel
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupRestoreScreen(onNavigateBack: () -> Unit, viewModel: BackupViewModel = koinViewModel()) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    var selection by rememberSaveable(stateSaver = listSaver<Set<BackupSection>, String>(
        save = { it.map { section -> section.name } }, restore = { it.map(BackupSection::valueOf).toSet() },
    )) { mutableStateOf(BackupSection.entries.toSet()) }
    var selectionToken by rememberSaveable { mutableStateOf<String?>(null) }
    var encrypt by rememberSaveable { mutableStateOf(true) }
    // 密码禁止使用 rememberSaveable，配置变化时由用户重新输入。
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var warning by remember { mutableStateOf(false) }
    var restoreConfirm by remember { mutableStateOf(false) }
    var finishConfirm by remember { mutableStateOf(false) }
    var disableCleanup by remember { mutableStateOf(true) }
    var permissionVersion by remember { mutableIntStateOf(0) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionVersion++ }
    val role = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { permissionVersion++ }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) viewModel.exportTo(uri.toString(), selection, if (encrypt) password.toCharArray() else null)
        password = ""; confirmation = ""
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.inspect(uri.toString(), password.takeIf { it.isNotEmpty() }?.toCharArray())
        password = ""; confirmation = ""
    }
    LaunchedEffect(state.preview?.token) {
        if (selectionToken != state.preview?.token) {
            state.preview?.let { selection = it.sections }
            selectionToken = state.preview?.token
        }
    }
    val readable = remember(permissionVersion) { context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED }
    val defaultSms = remember(permissionVersion) { context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_SMS) }
    val preview = state.preview
    fun createFile() { create.launch("PixelText-${System.currentTimeMillis()}.ptbackup") }

    Scaffold(topBar = {
        TopAppBar(title = { Text("备份与恢复") }, navigationIcon = {
            IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("备份 Pixel Text 的设置、规则和短信镜像。首版不包含彩信及附件，请在操作期间保持应用前台。")
            if (state.restoreProtected) {
                Text("恢复保护已开启：自动验证码清理暂缓。已写入的短信不会因取消而撤销。", color = MaterialTheme.colorScheme.error)
            }
            if (!readable) OutlinedButton(onClick = { permissions.launch(Manifest.permission.READ_SMS) }, enabled = !state.busy) { Text("授予读取短信权限") }
            if (!defaultSms) OutlinedButton(onClick = {
                role.launch(context.getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_SMS))
            }, enabled = !state.busy) { Text("设为默认短信应用（恢复短信需要）") }

            if (!state.busy && (!state.restoreProtected || preview != null)) {
                Text(if (preview == null) "选择备份内容" else "选择恢复内容", style = MaterialTheme.typography.titleMedium)
                BackupSection.entries.filter { preview == null || it in preview.sections }.forEach { section ->
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(checked = section in selection, onCheckedChange = { selected -> selection = if (selected) selection + section else selection - section })
                        Text(section.label)
                    }
                }
                if (preview == null) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Switch(checked = encrypt, onCheckedChange = { encrypt = it })
                        Spacer(Modifier.width(8.dp)); Text("加密导出")
                    }
                    OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("密码（恢复加密文件时也在此输入）") },
                        visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (encrypt) OutlinedTextField(value = confirmation, onValueChange = { confirmation = it }, label = { Text("导出密码确认") },
                        visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("建议使用至少 12 位强密码，丢失无法找回。文件大小和条目名称等元信息仍可能可见。系统文件选择器可能包含云盘，请自行选择保存位置。", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { if (encrypt) createFile() else warning = true }, enabled = selection.isNotEmpty() &&
                        !state.restoreProtected && (BackupSection.SMS !in selection || readable) && (!encrypt || (password.isNotEmpty() && password == confirmation))) { Text("创建备份") }
                    OutlinedButton(onClick = { open.launch(arrayOf("*/*")) }) { Text("选择文件并预览恢复") }
                } else {
                    Text("备份时间：${DateFormat.getDateTimeInstance().format(Date(preview.createdAt))}\n短信数量：${preview.smsCount}")
                    Text("短信去重合并，规则取并集；选中的设置由备份覆盖。旧 SIM 标识不迁移；出站消息转为失败且不会发送。会话归档可能同时影响该会话现有彩信的列表展示。")
                    Button(onClick = { restoreConfirm = true }, enabled = selection.isNotEmpty() &&
                        (BackupSection.SMS !in selection || (readable && defaultSms))) { Text("恢复所选内容") }
                    TextButton(onClick = viewModel::cancel) { Text("关闭预览") }
                }
            }
            if (state.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(when (state.phase) {
                    BackupPhase.SYNCING_MIRROR -> "正在同步短信镜像…"
                    BackupPhase.SNAPSHOTTING -> "正在创建应用数据库快照…"
                    BackupPhase.EXPORTING -> "正在打包并保存备份…"
                    BackupPhase.VALIDATING -> "正在解密并校验全部备份内容…"
                    BackupPhase.REBUILDING -> "正在对账和重建索引…"
                    else -> "正在恢复… ${state.processed} / ${state.total ?: "—"}"
                })
                OutlinedButton(onClick = viewModel::cancel) { Text("停止后续处理") }
            }
            state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.phase == BackupPhase.COMPLETED) Text("操作完成", style = MaterialTheme.typography.titleMedium)
            if (state.restoreProtected || state.summary.inserted + state.summary.existing > 0) {
                val result = state.summary
                Text("新增 ${result.inserted}，已存在 ${result.existing}，失败 ${result.failed}，未处理 ${result.remaining}\n出站转失败 ${result.convertedOutgoing}，关联跳过 ${result.skippedAssociations}")
                if (result.completedSections.isNotEmpty()) Text("已完成：${result.completedSections.joinToString { it.label }}")
                if (!state.busy) {
                    Button(onClick = { finishConfirm = true }) { Text("确认结果并结束恢复保护") }
                    if (preview == null) {
                        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("密码（恢复加密文件时也在此输入）") },
                            visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedButton(onClick = { open.launch(arrayOf("*/*")) }) { Text("重新选择文件并预览恢复") }
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
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked = disableCleanup, onCheckedChange = { disableCleanup = it }); Text("关闭验证码自动删除")
            }
        } }, confirmButton = { TextButton(onClick = { finishConfirm = false; viewModel.acknowledge(disableCleanup) }) { Text("确认结束") } },
        dismissButton = { TextButton(onClick = { finishConfirm = false }) { Text("取消") } })
}
