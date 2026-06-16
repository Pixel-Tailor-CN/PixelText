package vip.mystery0.pixel.text.ui.screen

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Rule
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DashboardCustomize
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import me.zhanghai.compose.preference.Preference
import me.zhanghai.compose.preference.ProvidePreferenceTheme
import me.zhanghai.compose.preference.SwitchPreference
import me.zhanghai.compose.preference.preferenceCategory
import org.koin.androidx.compose.koinViewModel
import vip.mystery0.pixel.text.BuildConfig
import vip.mystery0.pixel.text.R
import vip.mystery0.pixel.text.domain.settings.SpamAutoAction
import vip.mystery0.pixel.text.ui.createDefaultSmsAppRequestIntent
import vip.mystery0.pixel.text.ui.isDefaultSmsApp
import vip.mystery0.pixel.text.util.enableDebugMode
import vip.mystery0.pixel.text.util.isDebugModeEnabled
import vip.mystery0.pixel.text.viewmodel.ResourceUpdateDetail
import vip.mystery0.pixel.text.viewmodel.ResourceUpdateState
import vip.mystery0.pixel.text.viewmodel.SettingsViewModel
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = koinViewModel(),
    onNavigateBack: () -> Unit,
    onNavigateToSampleSubmission: () -> Unit = {},
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsState()
    val resourceUpdateState by viewModel.resourceUpdateState.collectAsState()
    var permissionRefreshKey by remember { mutableIntStateOf(0) }
    var pendingPermissionRequest by remember { mutableStateOf<List<String>>(emptyList()) }
    var pendingPermissionDialogItem by remember { mutableStateOf<PermissionItem?>(null) }
    var showSpamAutoActionDialog by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = {
            pendingPermissionRequest = emptyList()
            permissionRefreshKey++
        }
    )
    val defaultSmsAppLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = {
            val permissions = pendingPermissionRequest
            pendingPermissionRequest = emptyList()
            if (permissions.isNotEmpty()) {
                permissionLauncher.launch(permissions.toTypedArray())
            }
        }
    )
    val permissionItems = remember(context, permissionRefreshKey) {
        buildPermissionItems(context)
    }
    var versionCodeTapCount by remember { mutableIntStateOf(0) }
    val showDebugResourceActions = isDebugModeEnabled()
    val resourceUpdateSummary = when (val state = resourceUpdateState) {
        ResourceUpdateState.Idle -> "手动检查规则和模型资源更新"
        ResourceUpdateState.Checking -> "手动检查规则和模型资源更新"
        is ResourceUpdateState.Available ->
            "发现可安装资源：模型 ${state.detail.modelVersion}，规则 ${state.detail.ruleVersion}"
        is ResourceUpdateState.NoUpdate -> "手动检查规则和模型资源更新"
        ResourceUpdateState.Updating -> "正在更新资源..."
        is ResourceUpdateState.Working -> state.message
        is ResourceUpdateState.Success -> state.message
        is ResourceUpdateState.Error -> state.message
    }
    val resourceUpdateEnabled =
        resourceUpdateState !is ResourceUpdateState.Busy
    val spamAutoActionEnabled =
        settings.spamDetectionEnabled && settings.muteSpamNotificationsEnabled

    fun requestPermissionsAfterDefaultPrompt(permissions: List<String>) {
        if (permissions.isEmpty()) return
        if (context.isDefaultSmsApp()) {
            permissionLauncher.launch(permissions.toTypedArray())
        } else {
            pendingPermissionRequest = permissions
            defaultSmsAppLauncher.launch(context.createDefaultSmsAppRequestIntent())
        }
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
                        "设置",
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(paddingValues)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                ProvidePreferenceTheme {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            top = paddingValues.calculateTopPadding(),
                            bottom = 24.dp
                        )
                    ) {
                        preferenceCategory(
                            key = "category_model_rule",
                            title = { Text("模型与规则") }
                        )
                        item(key = "offline_model_version", contentType = "Preference") {
                            Preference(
                                title = { Text("离线模型版本") },
                                summary = { Text(settings.spamModelResourceVersion) },
                                icon = {
                                    Icon(Icons.Rounded.Memory, contentDescription = null)
                                }
                            )
                        }
                        item(key = "smart_card_rule_version", contentType = "Preference") {
                            Preference(
                                title = { Text("智能卡片规则版本") },
                                summary = { Text(settings.ruleResourceVersion) },
                                icon = {
                                    Icon(Icons.AutoMirrored.Rounded.Rule, contentDescription = null)
                                }
                            )
                        }
                        item(key = "resource_update", contentType = "Preference") {
                            Preference(
                                title = { Text("检查资源更新") },
                                summary = { Text(resourceUpdateSummary) },
                                enabled = resourceUpdateEnabled,
                                icon = {
                                    Icon(Icons.Rounded.Sync, contentDescription = null)
                                },
                                onClick = viewModel::checkResourceUpdates
                            )
                        }
                        if (showDebugResourceActions) {
                            item(
                                key = "debug_delete_downloaded_model",
                                contentType = "Preference"
                            ) {
                                Preference(
                                    title = { Text("删除下载的模型文件") },
                                    summary = { Text("删除已下载的离线模型和词表，回退到内置模型") },
                                    enabled = resourceUpdateEnabled,
                                    icon = {
                                        Icon(Icons.Rounded.DeleteSweep, contentDescription = null)
                                    },
                                    onClick = viewModel::deleteDownloadedModelResource
                                )
                            }
                            item(
                                key = "debug_delete_downloaded_rules",
                                contentType = "Preference"
                            ) {
                                Preference(
                                    title = { Text("删除下载的智能卡片规则文件") },
                                    summary = { Text("删除已下载的规则文件，回退到内置规则版本") },
                                    enabled = resourceUpdateEnabled,
                                    icon = {
                                        Icon(Icons.Rounded.DeleteSweep, contentDescription = null)
                                    },
                                    onClick = viewModel::deleteDownloadedRulesResource
                                )
                            }
                        }
                        item(key = "sample_submission", contentType = "Preference") {
                            Preference(
                                title = { Text("上报脱敏短信样本") },
                                summary = { Text("手动提交已脱敏的短信样本，帮助改进本地规则和模型") },
                                icon = {
                                    Icon(Icons.Rounded.UploadFile, contentDescription = null)
                                },
                                onClick = onNavigateToSampleSubmission
                            )
                        }
                        preferenceCategory(
                            key = "category_app_features",
                            title = { Text("应用功能") }
                        )
                        item(key = "spam_detection", contentType = "SwitchPreference") {
                            SwitchPreference(
                                value = settings.spamDetectionEnabled,
                                onValueChange = viewModel::setSpamDetectionEnabled,
                                title = { Text("骚扰短信识别") },
                                summary = {
                                    Text("接收新短信和扫描历史短信时调用本地模型识别骚扰内容")
                                },
                                icon = {
                                    Icon(Icons.Rounded.Shield, contentDescription = null)
                                }
                            )
                        }
                        item(
                            key = "mute_spam_notifications",
                            contentType = "SwitchPreference"
                        ) {
                            SwitchPreference(
                                value = settings.muteSpamNotificationsEnabled,
                                onValueChange = viewModel::setMuteSpamNotificationsEnabled,
                                title = { Text("骚扰短信不提醒") },
                                summary = {
                                    Text("开启骚扰短信识别后，收到骚扰短信时不显示通知且不做提醒")
                                },
                                enabled = settings.spamDetectionEnabled,
                                icon = {
                                    Icon(Icons.Rounded.NotificationsOff, contentDescription = null)
                                }
                            )
                        }
                        item(key = "spam_auto_action", contentType = "Preference") {
                            Preference(
                                title = { Text("骚扰短信自动执行") },
                                summary = {
                                    Text(
                                        if (spamAutoActionEnabled) {
                                            settings.spamAutoAction.preferenceSummary()
                                        } else {
                                            "需要先开启骚扰短信识别和骚扰短信不提醒"
                                        }
                                    )
                                },
                                enabled = spamAutoActionEnabled,
                                icon = {
                                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                                },
                                onClick = {
                                    if (spamAutoActionEnabled) {
                                        showSpamAutoActionDialog = true
                                    }
                                }
                            )
                        }
                        item(
                            key = "hide_fully_spam_conversations",
                            contentType = "SwitchPreference"
                        ) {
                            SwitchPreference(
                                value = settings.hideFullySpamConversationsEnabled,
                                onValueChange =
                                    viewModel::setHideFullySpamConversationsEnabled,
                                title = { Text("隐藏完全骚扰会话") },
                                summary = {
                                    Text("会话中的短信和彩信全部被标记为骚扰时，从普通会话列表隐藏")
                                },
                                icon = {
                                    Icon(Icons.Rounded.VisibilityOff, contentDescription = null)
                                }
                            )
                        }
                        item(key = "smart_card", contentType = "SwitchPreference") {
                            SwitchPreference(
                                value = settings.smartCardEnabled,
                                onValueChange = viewModel::setSmartCardEnabled,
                                title = { Text("智能短信卡片") },
                                summary = {
                                    Text("在会话详情中将验证码、票务、动账等短信渲染为结构化卡片")
                                },
                                icon = {
                                    Icon(
                                        Icons.Rounded.DashboardCustomize,
                                        contentDescription = null
                                    )
                                }
                            )
                        }
                        item(
                            key = "verification_code_notification_action",
                            contentType = "SwitchPreference"
                        ) {
                            SwitchPreference(
                                value = settings.verificationCodeNotificationActionEnabled,
                                onValueChange =
                                    viewModel::setVerificationCodeNotificationActionEnabled,
                                title = { Text("验证码通知快捷复制") },
                                summary = {
                                    Text("验证码短信通知中显示复制验证码操作")
                                },
                                icon = {
                                    Icon(Icons.Rounded.ContentCopy, contentDescription = null)
                                }
                            )
                        }
                        item(
                            key = "hide_verification_code_on_lock_screen",
                            contentType = "SwitchPreference"
                        ) {
                            SwitchPreference(
                                value = settings.hideVerificationCodeOnLockScreenEnabled,
                                onValueChange =
                                    viewModel::setHideVerificationCodeOnLockScreenEnabled,
                                title = { Text("锁屏时隐藏验证码") },
                                summary = {
                                    Text("锁屏通知中隐藏验证码内容，并暂不显示复制验证码操作")
                                },
                                enabled = settings.verificationCodeNotificationActionEnabled,
                                icon = {
                                    Icon(Icons.Rounded.Lock, contentDescription = null)
                                }
                            )
                        }

                        preferenceCategory(
                            key = "category_permissions",
                            title = { Text("权限申请") }
                        )
                        permissionItems.forEach { permissionItem ->
                            item(
                                key = "permission_${permissionItem.key}",
                                contentType = "Preference"
                            ) {
                                PermissionPreference(
                                    item = permissionItem,
                                    onRequest = {
                                        pendingPermissionDialogItem = permissionItem
                                    }
                                )
                            }
                        }

                        preferenceCategory(
                            key = "category_about",
                            title = { Text("关于") }
                        )
                        item(key = "version_name", contentType = "Preference") {
                            Preference(
                                title = { Text("版本名称") },
                                summary = { Text(BuildConfig.VERSION_NAME) },
                                icon = {
                                    Icon(Icons.Rounded.Info, contentDescription = null)
                                }
                            )
                        }
                        item(key = "version_code", contentType = "Preference") {
                            Preference(
                                title = { Text("版本号") },
                                summary = { Text(BuildConfig.VERSION_CODE.toString()) },
                                icon = {
                                    Icon(
                                        Icons.Default.PrivacyTip,
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    versionCodeTapCount += 1
                                    if (versionCodeTapCount >= DEBUG_MODE_ENABLE_TAP_COUNT) {
                                        if (!isDebugModeEnabled()) {
                                            enableDebugMode()
                                        }
                                        versionCodeTapCount = 0
                                        Toast.makeText(
                                            context,
                                            "调试模式已启用",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            )
                        }
                        item(key = "pixel_tailor", contentType = "Preference") {
                            Preference(
                                title = { Text("Pixel Tailor") },
                                summary = { Text("为中国 Pixel 用户精心缝补纯净 Android 体验") },
                                icon = {
                                    Box(
                                        modifier = Modifier.size(24.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Image(
                                            painterResource(R.drawable.ic_pixel_tailor),
                                            contentDescription = null
                                        )
                                    }
                                },
                                onClick = {
                                    context.openUrl("https://pixel.mystery0.app")
                                }
                            )
                        }
                        item(key = "telegram_channel", contentType = "Preference") {
                            Preference(
                                title = { Text("Telegram 频道") },
                                summary = { Text("关注 Telegram 频道获取最新动态") },
                                icon = {
                                    Icon(Icons.Rounded.Forum, contentDescription = null)
                                },
                                onClick = {
                                    context.openUrl("https://t.me/pixel_tailor_cn")
                                }
                            )
                        }
                        item {
                            Spacer(
                                modifier = Modifier.windowInsetsBottomHeight(
                                    WindowInsets.navigationBars
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    pendingPermissionDialogItem?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingPermissionDialogItem = null },
            title = { Text("需要${item.title}权限") },
            text = {
                Text(
                    item.summary + "\n\n点击申请后，如尚未设置默认短信应用，会先显示默认短信应用请求，再显示系统权限请求。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingPermissionDialogItem = null
                        requestPermissionsAfterDefaultPrompt(item.missingPermissions)
                    }
                ) {
                    Text("申请")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingPermissionDialogItem = null }) {
                    Text("稍后")
                }
            }
        )
    }

    if (showSpamAutoActionDialog) {
        AlertDialog(
            onDismissRequest = { showSpamAutoActionDialog = false },
            title = { Text("骚扰短信自动执行") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SpamAutoAction.entries.forEach { action ->
                        SpamAutoActionOption(
                            action = action,
                            selected = settings.spamAutoAction == action,
                            onClick = {
                                viewModel.setSpamAutoAction(action)
                                showSpamAutoActionDialog = false
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSpamAutoActionDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    if (resourceUpdateState is ResourceUpdateState.Checking) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("正在检查资源更新") },
            text = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LoadingIndicator(modifier = Modifier.size(32.dp))
                    Text(
                        text = "正在连接服务端获取最新规则和模型信息",
                        modifier = Modifier.padding(start = 16.dp)
                    )
                }
            },
            confirmButton = {}
        )
    }

    (resourceUpdateState as? ResourceUpdateState.Available)?.let { state ->
        AlertDialog(
            onDismissRequest = viewModel::dismissResourceUpdateDialog,
            title = { Text("发现资源更新") },
            text = {
                ResourceUpdateDetailContent(detail = state.detail)
            },
            confirmButton = {
                TextButton(onClick = viewModel::installResourceUpdates) {
                    Text("更新")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissResourceUpdateDialog) {
                    Text("稍后")
                }
            }
        )
    }

    (resourceUpdateState as? ResourceUpdateState.NoUpdate)?.let { state ->
        AlertDialog(
            onDismissRequest = viewModel::dismissResourceUpdateDialog,
            title = { Text("暂无可用更新") },
            text = { Text(state.message) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissResourceUpdateDialog) {
                    Text("知道了")
                }
            }
        )
    }
}

@Composable
private fun SpamAutoActionOption(
    action: SpamAutoAction,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick
        )
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(
                text = action.title(),
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = action.dialogSummary(),
                style = MaterialTheme.typography.bodySmall,
                color = if (action == SpamAutoAction.DELETE) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

private fun SpamAutoAction.title(): String {
    return when (this) {
        SpamAutoAction.NONE -> "不处理"
        SpamAutoAction.MARK_READ -> "已读"
        SpamAutoAction.DELETE -> "删除"
    }
}

private fun SpamAutoAction.preferenceSummary(): String {
    return when (this) {
        SpamAutoAction.NONE -> "新收到的骚扰短信仅不提醒，不额外处理"
        SpamAutoAction.MARK_READ -> "新收到的骚扰短信会自动标记为已读"
        SpamAutoAction.DELETE -> "新收到的骚扰短信会自动删除"
    }
}

private fun SpamAutoAction.dialogSummary(): String {
    return when (this) {
        SpamAutoAction.NONE -> "识别为骚扰后只隐藏提醒，保留短信状态"
        SpamAutoAction.MARK_READ -> "识别为骚扰后自动标记这条短信为已读"
        SpamAutoAction.DELETE -> "模型判断不一定完全准确，正常短信也可能被当作骚扰并被移除"
    }
}

@Composable
private fun ResourceUpdateDetailContent(detail: ResourceUpdateDetail) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ResourceUpdateDetailLine(
            label = "模型版本",
            value = detail.modelVersion
        )
        ResourceUpdateDetailLine(
            label = "模型文件大小",
            value = formatSizeBytes(detail.modelSizeBytes)
        )
        ResourceUpdateDetailLine(
            label = "规则版本",
            value = detail.ruleVersion
        )
        ResourceUpdateDetailLine(
            label = "规则文件大小",
            value = formatSizeBytes(detail.ruleSizeBytes)
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "版本说明",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = detail.releaseNotes,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun ResourceUpdateDetailLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterEnd
        ) {
            val scrollState = rememberScrollState()
            LaunchedEffect(value, scrollState.maxValue) {
                scrollState.scrollTo(0)
                if (scrollState.maxValue <= 0) return@LaunchedEffect

                delay(MARQUEE_EDGE_PAUSE_MS)
                while (isActive) {
                    val maxScroll = scrollState.maxValue
                    val durationMillis =
                        (maxScroll * MARQUEE_MILLIS_PER_PIXEL)
                            .coerceIn(MARQUEE_MIN_DURATION_MS, MARQUEE_MAX_DURATION_MS)
                    scrollState.animateScrollTo(
                        value = maxScroll,
                        animationSpec = tween(
                            durationMillis = durationMillis,
                            easing = LinearEasing
                        )
                    )
                    delay(MARQUEE_EDGE_PAUSE_MS)
                    scrollState.animateScrollTo(
                        value = 0,
                        animationSpec = tween(
                            durationMillis = durationMillis,
                            easing = LinearEasing
                        )
                    )
                    delay(MARQUEE_EDGE_PAUSE_MS)
                }
            }
            Text(
                text = value,
                modifier = Modifier.horizontalScroll(
                    state = scrollState,
                    enabled = false
                ),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Visible,
                style = MaterialTheme.typography.bodyMedium
                    .copy(textAlign = TextAlign.End)
            )
        }
    }
}

@Composable
private fun PermissionPreference(
    item: PermissionItem,
    onRequest: () -> Unit
) {
    Preference(
        title = { Text(item.title) },
        summary = { Text(item.summary) },
        icon = {
            Icon(
                if (item.granted) Icons.Default.Check else Icons.Default.Close,
                contentDescription = null,
                tint = if (item.granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
        },
        onClick = if (item.granted) null else onRequest
    )
}

private data class PermissionItem(
    val key: String,
    val title: String,
    val summary: String,
    val missingPermissions: List<String>,
    val granted: Boolean
)

private fun buildPermissionItems(context: Context): List<PermissionItem> {
    return buildList {
        add(
            context.permissionItem(
                key = "phone",
                title = "电话",
                summary = "识别 SIM 卡信息，支持双卡发送和会话中显示发送卡",
                permissions = listOf(Manifest.permission.READ_PHONE_STATE)
            )
        )
        add(
            context.permissionItem(
                key = "sms",
                title = "短信",
                summary = "读取、接收和发送短信和彩信，并更新系统短信数据库",
                permissions = listOf(
                    Manifest.permission.READ_SMS,
                    Manifest.permission.SEND_SMS,
                    Manifest.permission.RECEIVE_SMS,
                    Manifest.permission.RECEIVE_MMS,
                    Manifest.permission.RECEIVE_WAP_PUSH,
                    WRITE_SMS_PERMISSION
                )
            )
        )
        add(
            context.permissionItem(
                key = "contacts",
                title = "联系人和账号",
                summary = "显示联系人名称，并在新建会话时搜索联系人",
                permissions = listOf(Manifest.permission.READ_CONTACTS)
            )
        )
        add(
            context.permissionItem(
                key = "notifications",
                title = "通知",
                summary = "收到新短信、验证码快捷操作和历史识别进度需要发送通知",
                permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    listOf(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    emptyList()
                }
            )
        )
    }
}

private fun Context.permissionItem(
    key: String,
    title: String,
    summary: String,
    permissions: List<String>
): PermissionItem {
    val missingPermissions = permissions.filterNot(::isPermissionGranted)
    return PermissionItem(
        key = key,
        title = title,
        summary = summary,
        missingPermissions = missingPermissions,
        granted = missingPermissions.isEmpty()
    )
}

private fun Context.isPermissionGranted(permission: String): Boolean {
    return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}

private fun Context.openUrl(url: String) {
    runCatching {
        startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    }
}

private fun formatSizeBytes(sizeBytes: Long?): String {
    if (sizeBytes == null || sizeBytes < 0) return "未提供"
    if (sizeBytes < 1024) return "$sizeBytes B"

    val units = listOf("KB", "MB", "GB")
    var size = sizeBytes / 1024.0
    var unitIndex = 0
    while (size >= 1024 && unitIndex < units.lastIndex) {
        size /= 1024.0
        unitIndex += 1
    }
    return String.format(Locale.getDefault(), "%.1f %s", size, units[unitIndex])
}

private const val WRITE_SMS_PERMISSION = "android.permission.WRITE_SMS"
private const val DEBUG_MODE_ENABLE_TAP_COUNT = 6
private val MARQUEE_EDGE_PAUSE_MS = 900L.milliseconds
private const val MARQUEE_MILLIS_PER_PIXEL = 18
private const val MARQUEE_MIN_DURATION_MS = 1200
private const val MARQUEE_MAX_DURATION_MS = 6000
