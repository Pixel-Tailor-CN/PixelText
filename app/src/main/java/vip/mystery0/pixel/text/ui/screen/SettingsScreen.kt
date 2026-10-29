package vip.mystery0.pixel.text.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import vip.mystery0.pixel.text.ui.activityLikeEnterTransition
import vip.mystery0.pixel.text.ui.activityLikeExitTransition
import vip.mystery0.pixel.text.ui.activityLikePopEnterTransition
import vip.mystery0.pixel.text.ui.activityLikePopExitTransition
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.LifecycleResumeEffect
import vip.mystery0.pixel.text.ui.screen.settings.SettingsCategory
import vip.mystery0.pixel.text.ui.screen.settings.SettingsEntry
import vip.mystery0.pixel.text.ui.screen.settings.SettingsSection
import vip.mystery0.pixel.text.ui.screen.settings.SettingsPage
import vip.mystery0.pixel.text.viewmodel.BackupViewModel
import vip.mystery0.pixel.text.viewmodel.SenderWhitelistViewModel
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Rule
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DashboardCustomize
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import vip.mystery0.pixel.text.ui.screen.settings.SettingsPreference as Preference
import vip.mystery0.pixel.text.ui.screen.settings.SettingsSwitchPreference as SwitchPreference
import org.koin.androidx.compose.koinViewModel
import vip.mystery0.pixel.text.BuildConfig
import vip.mystery0.pixel.text.R
import vip.mystery0.pixel.text.domain.hub.ResourceUpdateDetail
import vip.mystery0.pixel.text.domain.settings.MessageTimeDisplayFormat
import vip.mystery0.pixel.text.domain.settings.SpamAutoAction
import vip.mystery0.pixel.text.domain.settings.SmsNotificationIcon
import vip.mystery0.pixel.text.smartspacer.SmartspacerIntegration
import vip.mystery0.pixel.text.ui.createDefaultSmsAppRequestIntent
import vip.mystery0.pixel.text.ui.isDefaultSmsApp
import vip.mystery0.pixel.text.util.enableDebugMode
import vip.mystery0.pixel.text.util.isDebugModeEnabled
import vip.mystery0.pixel.text.viewmodel.ResourceUpdateState
import vip.mystery0.pixel.text.viewmodel.KeywordSpamViewModel
import vip.mystery0.pixel.text.viewmodel.SettingsViewModel
import vip.mystery0.pixel.text.viewmodel.SenderProfileUpdateState
import vip.mystery0.pixel.text.viewmodel.SmsSyncState
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

private enum class AdvancedDataAction(val title: String, val description: String) {
    SyncSms(
        "强制同步短信数据",
        "将重新读取系统短信和彩信，并刷新本地会话缓存。短信较多时可能耗时较长，是否确认执行？",
    ),
    RecognizeSms(
        "重新识别全部短信",
        "将按照当前识别规则重新扫描全部短信，并刷新验证码索引。短信较多时可能耗时较长，是否确认执行？",
    ),
    RefreshSmartspacer(
        "刷新 Smartspacer 数据",
        "将触发 Smartspacer 重新计算未读数量和快捷卡片。此操作可能需要一段时间，是否确认执行？",
    ),
}

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = koinViewModel(),
    keywordSpamViewModel: KeywordSpamViewModel = koinViewModel(),
    backupViewModel: BackupViewModel = koinViewModel(),
    whitelistViewModel: SenderWhitelistViewModel = koinViewModel(),
    onNavigateBack: () -> Unit,
    onNavigateToSampleReports: () -> Unit = {},
    onNavigateToSwipeActions: () -> Unit = {},
    onNavigateToNotificationActions: () -> Unit = {},
    onNavigateToSmsNotificationIcons: () -> Unit = {},
    onNavigateToAppIdentity: () -> Unit = {},
    onNavigateToKeywordSpam: () -> Unit = {},
    onNavigateToBackup: () -> Unit = {},
    onNavigateToOpenSource: () -> Unit = {},
    onNavigateToSenderWhitelist: () -> Unit = {},
    onNavigateToConversationDetailCustomization: () -> Unit = {},
    resourceUpdateCheckRequestId: Long? = null,
    onResourceUpdateCheckRequestConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val backupState by backupViewModel.state.collectAsState()
    val whitelistRules by whitelistViewModel.rules.collectAsState()
    val currentDisplayName = currentAppDisplayName()
    var selectedCategory by rememberSaveable { mutableStateOf<SettingsCategory?>(null) }
    var returnCategory by rememberSaveable { mutableStateOf<SettingsCategory?>(null) }
    var targetSection by rememberSaveable { mutableStateOf<String?>(null) }
    var navigatingBack by remember { mutableStateOf(false) }
    val categoryStates = rememberSaveableStateHolder()
    fun navigateBack() {
        navigatingBack = true
        if (selectedCategory != null) {
            selectedCategory = returnCategory
            returnCategory = null
            targetSection = null
        } else onNavigateBack()
    }
    val settings by viewModel.settings.collectAsState()
    val blockedKeywords by keywordSpamViewModel.keywords.collectAsState()
    val resourceUpdateState by viewModel.resourceUpdateState.collectAsState()
    val senderProfileUpdateState by viewModel.senderProfileUpdateState.collectAsState()
    val senderProfileVersion by viewModel.senderProfileVersion.collectAsState()
    val smsSyncState by viewModel.smsSyncState.collectAsState()
    val isVerificationCodeIndexRunning by
        viewModel.isVerificationCodeIndexRunning.collectAsState()
    var permissionRefreshKey by remember { mutableIntStateOf(0) }
    var pendingPermissionRequest by remember { mutableStateOf<List<String>>(emptyList()) }
    var pendingPermissionDialogItem by remember { mutableStateOf<PermissionItem?>(null) }
    var pendingAdvancedDataAction by remember { mutableStateOf<AdvancedDataAction?>(null) }
    var confirmSpamDeletion by remember { mutableStateOf(false) }
    var showSpamAutoActionDialog by remember { mutableStateOf(false) }
    var showMessageTimeFormatDialog by remember { mutableStateOf(false) }
    var showResourceAutoCheckIntervalDialog by remember { mutableStateOf(false) }
    var resourceAutoCheckIntervalInput by remember { mutableStateOf("") }
    var resourceAutoCheckIntervalError by remember { mutableStateOf<String?>(null) }
    var showVerificationCodeRetentionDaysDialog by remember { mutableStateOf(false) }
    var verificationCodeRetentionDaysInput by remember { mutableStateOf("") }
    var verificationCodeRetentionDaysError by remember { mutableStateOf<String?>(null) }
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
            permissionRefreshKey++
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
    val defaultSmsApp = remember(permissionRefreshKey) { context.isDefaultSmsApp() }
    val notificationGranted = permissionItems.firstOrNull { it.key == "notifications" }?.granted != false
    var showDebugResourceActions by remember { mutableStateOf(isDebugModeEnabled()) }
    LifecycleResumeEffect(Unit) {
        permissionRefreshKey++
        showDebugResourceActions = isDebugModeEnabled()
        onPauseOrDispose { }
    }
    var versionCodeTapCount by remember { mutableIntStateOf(0) }
    val resourceUpdateSummary = when (val state = resourceUpdateState) {
        ResourceUpdateState.Idle -> "手动检查规则和模型资源更新"
        ResourceUpdateState.Checking -> "手动检查规则和模型资源更新"
        is ResourceUpdateState.Available ->
            "发现可安装资源：模型 ${state.detail.modelVersion}，规则 ${state.detail.ruleVersion}"
        is ResourceUpdateState.NoUpdate -> "手动检查规则和模型资源更新"
        is ResourceUpdateState.Updating -> state.message
        is ResourceUpdateState.Working -> state.message
        is ResourceUpdateState.Success -> state.message
        is ResourceUpdateState.Error -> state.message
    }
    val resourceUpdateEnabled =
        resourceUpdateState !is ResourceUpdateState.Busy
    val smsSyncEnabled = smsSyncState !is SmsSyncState.Syncing
    val spamAutoActionEnabled = settings.muteSpamNotificationsEnabled

    LaunchedEffect(resourceUpdateCheckRequestId) {
        if (resourceUpdateCheckRequestId != null) {
            navigatingBack = false
            selectedCategory = SettingsCategory.RESOURCES
            returnCategory = null
            targetSection = null
            viewModel.checkResourceUpdates()
            onResourceUpdateCheckRequestConsumed()
        }
    }

    fun requestPermissionsAfterDefaultPrompt(permissions: List<String>) {
        if (permissions.isEmpty()) return
        if (context.isDefaultSmsApp()) {
            permissionLauncher.launch(permissions.toTypedArray())
        } else {
            pendingPermissionRequest = permissions
            defaultSmsAppLauncher.launch(context.createDefaultSmsAppRequestIntent())
        }
    }

    fun sectionsFor(category: SettingsCategory?) = when (category) {
        SettingsCategory.MESSAGES -> buildList {
            add(SettingsSection("message_cards", "短信卡片", listOf(
                SettingsEntry("smart_card") {
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
                },
                SettingsEntry("show_verification_code_content_by_default") {
                    SwitchPreference(
                        value = settings.showVerificationCodeContentByDefault,
                        onValueChange =
                            viewModel::setShowVerificationCodeContentByDefault,
                        title = { Text("验证码默认显示原文") },
                        summary = { Text("开启智能短信卡片时，在验证码下方显示完整短信内容") },
                        icon = {
                            Icon(Icons.Rounded.Visibility, contentDescription = null)
                        },
                    )
                },
            )))
            add(SettingsSection("message_codes", "验证码管理", listOf(
                SettingsEntry("verification_code_auto_delete") {
                    SwitchPreference(
                        value = settings.verificationCodeAutoDeleteEnabled,
                        onValueChange = viewModel::setVerificationCodeAutoDeleteEnabled,
                        title = { Text("自动删除验证码") },
                        summary = { Text("定期删除超过保留期限的验证码短信") },
                        icon = {
                            Icon(Icons.Rounded.DeleteSweep, contentDescription = null)
                        },
                    )
                },
                SettingsEntry("verification_code_retention_days") {
                    Preference(
                        enabled = settings.verificationCodeAutoDeleteEnabled,
                        title = { Text("验证码保留时间") },
                        summary = {
                            Text(if (settings.verificationCodeAutoDeleteEnabled) "${settings.verificationCodeRetentionDays} 天" else "${settings.verificationCodeRetentionDays} 天 · 开启自动删除后生效")
                        },
                        icon = {
                            Icon(Icons.Rounded.Schedule, contentDescription = null)
                        },
                        onClick = {
                            verificationCodeRetentionDaysInput =
                                settings.verificationCodeRetentionDays.toString()
                            verificationCodeRetentionDaysError = null
                            showVerificationCodeRetentionDaysDialog = true
                        },
                    )
                },
                SettingsEntry("notification_link") {
                    Preference(
                        title = { Text("验证码通知与锁屏隐私") },
                        summary = { Text(if (!settings.verificationCodeNotificationActionEnabled) "快捷复制已关闭" else if (settings.hideVerificationCodeOnLockScreenEnabled) "快捷复制已开启 · 锁屏时隐藏" else "快捷复制已开启 · 锁屏时显示") },
                        icon = { Icon(Icons.Rounded.Lock, null) },
                        onClick = {
                            returnCategory = SettingsCategory.MESSAGES
                            navigatingBack = false
                            selectedCategory = SettingsCategory.DISPLAY
                            targetSection = "display_notifications"
                        },
                    )
                },
            )))
            add(SettingsSection("message_mms", "彩信", listOf(
                SettingsEntry("auto_download_mms") {
                    SwitchPreference(
                        value = settings.autoDownloadMms,
                        onValueChange = viewModel::setAutoDownloadMms,
                        title = { Text("自动下载彩信") },
                        icon = { Icon(Icons.Rounded.Image, null) },
                        summary = { Text("开启后自动下载新收到的彩信，可能使用移动数据") },
                    )
                },
            )))
        }
        SettingsCategory.DISPLAY -> buildList {
            add(SettingsSection("display_app", "应用与主界面", listOf(
                SettingsEntry("app_identity") {
                    AppIdentitySettingsPreference(onClick = onNavigateToAppIdentity)
                },
                SettingsEntry("unread_badge") {
                    SwitchPreference(
                        value = settings.unreadBadgeEnabled,
                        onValueChange = viewModel::setUnreadBadgeEnabled,
                        title = { Text("底部未读数量") },
                        summary = { Text("在底部会话按钮显示未读短信数量") },
                        icon = {
                            Icon(Icons.Rounded.Notifications, contentDescription = null)
                        },
                    )
                },
            )))
            add(SettingsSection("display_conversations", "会话列表与详情", listOf(
                SettingsEntry("conversation_detail_customization") {
                    Preference(
                        title = { Text("会话详情显示") },
                        summary = { Text("气泡、背景、输入区域与 SIM 信息") },
                        icon = {
                            Icon(Icons.Rounded.Palette, contentDescription = null)
                        },
                        onClick = onNavigateToConversationDetailCustomization,
                    )
                },
                SettingsEntry("message_time_display_format") {
                    Preference(
                        title = { Text("短信时间显示格式") },
                        summary = {
                            Text(settings.messageTimeDisplayFormat.title())
                        },
                        icon = {
                            Icon(Icons.Rounded.Schedule, contentDescription = null)
                        },
                        onClick = { showMessageTimeFormatDialog = true }
                    )
                },
                SettingsEntry("conversation_swipe_actions") {
                    Preference(
                        title = { Text("滑动操作") },
                        summary = {
                            Text(
                                "右滑：${settings.rightSwipeAction.settingTitle()}；" +
                                        "左滑：${settings.leftSwipeAction.settingTitle()}"
                            )
                        },
                        icon = {
                            Icon(Icons.Rounded.SwapHoriz, contentDescription = null)
                        },
                        onClick = onNavigateToSwipeActions
                    )
                },
            )))
            add(SettingsSection("display_notifications", "短信通知", listOf(
                SettingsEntry("sms_notification_icon") {
                    Preference(
                        title = { Text("短信通知图标") },
                        summary = {
                            Text(
                                SmsNotificationIcon.fromId(
                                    settings.smsNotificationIconId
                                ).displayName
                            )
                        },
                        icon = {
                            Icon(
                                painter = painterResource(
                                    SmsNotificationIcon.fromId(
                                        settings.smsNotificationIconId
                                    ).drawableRes
                                ),
                                contentDescription = null
                            )
                        },
                        onClick = onNavigateToSmsNotificationIcons
                    )
                },
                SettingsEntry("notification_quick_actions") {
                    Preference(
                        title = { Text("通知快捷操作") },
                        summary = {
                            Text(settings.notificationQuickActionConfigs.settingsSummary())
                        },
                        icon = {
                            Icon(Icons.Rounded.Forum, contentDescription = null)
                        },
                        onClick = onNavigateToNotificationActions
                    )
                },
                SettingsEntry("verification_code_notification_action") {
                    SwitchPreference(
                        value = settings.verificationCodeNotificationActionEnabled,
                        onValueChange =
                            viewModel::setVerificationCodeNotificationActionEnabled,
                        title = { Text("验证码通知快捷复制") },
                        summary = {
                            Text("在通知中显示验证码复制按钮")
                        },
                        icon = {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = null)
                        }
                    )
                },
                SettingsEntry("hide_verification_code_on_lock_screen") {
                    SwitchPreference(
                        value = settings.hideVerificationCodeOnLockScreenEnabled,
                        onValueChange =
                            viewModel::setHideVerificationCodeOnLockScreenEnabled,
                        title = { Text("锁屏时隐藏验证码") },
                        summary = {
                            Text(if (settings.verificationCodeNotificationActionEnabled) "锁屏通知中隐藏验证码内容和复制操作" else "需要先开启验证码通知快捷复制")
                        },
                        enabled = settings.verificationCodeNotificationActionEnabled,
                        icon = {
                            Icon(Icons.Rounded.Lock, contentDescription = null)
                        }
                    )
                },
            )))
        }
        SettingsCategory.SPAM -> buildList {
            add(SettingsSection("spam_rules", "识别规则", listOf(
                SettingsEntry("spam_detection") {
                    SwitchPreference(
                        value = settings.spamDetectionEnabled,
                        onValueChange = viewModel::setSpamDetectionEnabled,
                        title = { Text("本地模型识别") },
                        summary = {
                            Text("控制本地模型识别；自定义关键词规则不受此开关影响")
                        },
                        icon = {
                            Icon(Icons.Rounded.Shield, contentDescription = null)
                        }
                    )
                },
                SettingsEntry("keyword_spam") {
                    Preference(
                        title = { Text("关键词拦截") },
                        summary = {
                            Text(
                                if (blockedKeywords.isEmpty()) {
                                    "添加只保存在设备本地的短信正文关键词"
                                } else {
                                    "已添加 ${blockedKeywords.size} 个关键词"
                                }
                            )
                        },
                        icon = {
                            Icon(Icons.Rounded.FilterAlt, contentDescription = null)
                        },
                        onClick = onNavigateToKeywordSpam,
                    )
                },
                SettingsEntry("sender_whitelist") {
                    Preference(
                        title = { Text("号码白名单") },
                        summary = { Text("${whitelistRules.size} 条规则；命中号码优先放行，不受模型或关键词拦截") },
                        icon = { Icon(Icons.Rounded.VerifiedUser, contentDescription = null) },
                        onClick = onNavigateToSenderWhitelist,
                    )
                },
            )))
            add(SettingsSection("spam_actions", "处理方式", listOf(
                SettingsEntry("mute_spam_notifications") {
                    SwitchPreference(
                        value = settings.muteSpamNotificationsEnabled,
                        onValueChange = viewModel::setMuteSpamNotificationsEnabled,
                        title = { Text("骚扰短信不提醒") },
                        summary = {
                            Text("模型或关键词识别为骚扰时，不显示通知且不做提醒")
                        },
                        icon = {
                            Icon(Icons.Rounded.NotificationsOff, contentDescription = null)
                        }
                    )
                },
                SettingsEntry("spam_auto_action") {
                    Preference(
                        title = { Text("收到骚扰短信后") },
                        summary = {
                            Text(
                                if (spamAutoActionEnabled) {
                                    settings.spamAutoAction.preferenceSummary()
                                } else {
                                    "需要先开启骚扰短信不提醒"
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
                },
            )))
            add(SettingsSection("spam_display", "内容显示", listOf(
                SettingsEntry("spam_isolation") {
                    SwitchPreference(
                        value = settings.spamIsolationEnabled,
                        onValueChange = viewModel::setSpamIsolationEnabled,
                        title = { Text("单独显示骚扰短信") },
                        summary = {
                            Text("开启后，普通会话与骚扰会话分别只显示对应类型的短信")
                        },
                        icon = {
                            Icon(Icons.Rounded.VisibilityOff, contentDescription = null)
                        }
                    )
                },
                SettingsEntry("show_spam_content_by_default") {
                    SwitchPreference(
                        value = settings.showSpamContentByDefault,
                        onValueChange = viewModel::setShowSpamContentByDefault,
                        title = { Text("默认展开骚扰短信") },
                        summary = {
                            Text("开启后仍保留骚扰标识，但不再默认隐藏短信内容")
                        },
                        icon = {
                            Icon(Icons.Rounded.Visibility, contentDescription = null)
                        }
                    )
                },
            )))
        }
        SettingsCategory.RESOURCES -> buildList {
            add(SettingsSection("resources_current", "当前资源", listOf(
                SettingsEntry("offline_model_version") {
                    Preference(
                        title = { Text("离线模型版本") },
                        summary = {
                            Text(
                                friendlyResourceVersion(viewModel.displaySpamModelResourceVersion(
                                    settings.spamModelResourceVersion
                                ))
                            )
                        },
                        icon = {
                            Icon(Icons.Rounded.Memory, contentDescription = null)
                        }
                    )
                },
                SettingsEntry("smart_card_rule_version") {
                    Preference(
                        title = { Text("智能卡片规则版本") },
                        summary = {
                            Text(
                                friendlyResourceVersion(viewModel.displayRuleResourceVersion(
                                    settings.ruleResourceVersion
                                ))
                            )
                        },
                        icon = {
                            Icon(Icons.AutoMirrored.Rounded.Rule, contentDescription = null)
                        }
                    )
                },
                SettingsEntry("sender_profile_update") {
                    Preference(
                        title = { Text("发件方名称与头像") },
                        summary = {
                            Text(
                                senderProfileVersion?.let { "当前版本：$it，点按检查更新" }
                                    ?: "未安装发件方资料，点按检查更新"
                            )
                        },
                        enabled = senderProfileUpdateState !is SenderProfileUpdateState.Busy,
                        icon = { Icon(Icons.Rounded.Badge, contentDescription = null) },
                        onClick = viewModel::checkSenderProfileUpdates,
                    )
                },
            )))
            add(SettingsSection("resources_updates", "更新设置", listOf(
                SettingsEntry("resource_update") {
                    Preference(
                        title = { Text("检查资源更新") },
                        summary = { Text(resourceUpdateSummary) },
                        enabled = resourceUpdateEnabled,
                        icon = {
                            Icon(Icons.Rounded.Sync, contentDescription = null)
                        },
                        onClick = viewModel::checkResourceUpdates
                    )
                },
                SettingsEntry("resource_auto_check") {
                    SwitchPreference(
                        value = settings.resourceAutoCheckEnabled,
                        onValueChange = viewModel::setResourceAutoCheckEnabled,
                        title = { Text("自动检查资源更新") },
                        summary = {
                            Text(if (notificationGranted) "定期检查并通知，不会自动下载" else "定期检查，不会自动下载；需要通知权限以接收更新提醒")
                        },
                        icon = {
                            Icon(Icons.Rounded.Update, contentDescription = null)
                        }
                    )
                },
                SettingsEntry("resource_auto_check_interval") {
                    Preference(
                        title = { Text("检查间隔时间") },
                        summary = {
                            Text(if (settings.resourceAutoCheckEnabled) "每 ${settings.resourceAutoCheckIntervalHours} 小时检查一次" else "开启自动检查后生效")
                        },
                        enabled = settings.resourceAutoCheckEnabled,
                        icon = {
                            Icon(Icons.Rounded.Schedule, contentDescription = null)
                        },
                        onClick = {
                            resourceAutoCheckIntervalInput =
                                settings.resourceAutoCheckIntervalHours.toString()
                            resourceAutoCheckIntervalError = null
                            showResourceAutoCheckIntervalDialog = true
                        }
                    )
                },
            )))
        }
        SettingsCategory.BACKUP -> buildList {
            add(SettingsSection("backup", "备份与恢复", listOf(
                SettingsEntry("backup_restore") {
                    Preference(
                        title = { Text("备份与恢复") },
                        summary = { Text(if (backupState.restoreProtected) "恢复保护已开启，请进入确认恢复结果" else "支持加密；不含彩信、附件及应用名称与图标选择") },
                        icon = { Icon(Icons.Rounded.UploadFile, contentDescription = null) },
                        onClick = onNavigateToBackup,
                    )
                },
            )))
            add(SettingsSection("maintenance", "数据维护", listOf(
                SettingsEntry("force_sms_sync") {
                    Preference(
                        title = { Text("强制同步短信数据") },
                        summary = {
                            Text(
                                if (smsSyncState is SmsSyncState.Syncing) {
                                    "正在同步短信数据，请稍候…"
                                } else {
                                    "重新读取系统短信和彩信并刷新本地会话缓存"
                                }
                            )
                        },
                        enabled = smsSyncEnabled,
                        icon = {
                            Icon(Icons.Rounded.Sync, contentDescription = null)
                        },
                        onClick = {
                            pendingAdvancedDataAction = AdvancedDataAction.SyncSms
                        }
                    )
                },
                SettingsEntry("rebuild_verification_code_index") {
                    Preference(
                        title = { Text("重新识别全部短信") },
                        summary = {
                            Text(
                                if (isVerificationCodeIndexRunning) {
                                    "正在重新识别短信，请稍候…"
                                } else {
                                    "按照当前识别规则重新扫描短信并刷新验证码索引"
                                }
                            )
                        },
                        enabled = !isVerificationCodeIndexRunning,
                        icon = {
                            Icon(Icons.AutoMirrored.Rounded.Rule, contentDescription = null)
                        },
                        onClick = {
                            pendingAdvancedDataAction = AdvancedDataAction.RecognizeSms
                        }
                    )
                },
                SettingsEntry("refresh_smartspacer") {
                    Preference(
                        title = { Text("刷新 Smartspacer 数据") },
                        summary = {
                            Text("手动触发 Smartspacer 重新计算未读数量和快捷卡片")
                        },
                        icon = {
                            Icon(Icons.Rounded.DashboardCustomize, contentDescription = null)
                        },
                        onClick = {
                            pendingAdvancedDataAction = AdvancedDataAction.RefreshSmartspacer
                        }
                    )
                },
            )))
        }
        SettingsCategory.ABOUT -> buildList {
            add(SettingsSection("about_version", "版本与开源", listOf(
                SettingsEntry("version_name") {
                    Preference(
                        title = { Text("应用版本") },
                        summary = { Text("$currentDisplayName · ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})") },
                        icon = {
                            CurrentAppIdentityIcon()
                        },
                        trailing = {},
                        onClick = {
                            versionCodeTapCount += 1
                            if (versionCodeTapCount >= DEBUG_MODE_ENABLE_TAP_COUNT) {
                                if (!isDebugModeEnabled()) {
                                    enableDebugMode()
                                }
                                showDebugResourceActions = true
                                versionCodeTapCount = 0
                                Toast.makeText(
                                    context,
                                    "调试模式已启用",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    )
                },
                SettingsEntry("open_source") {
                    Preference(
                        title = { Text("开源声明") },
                        summary = { Text("查看开源组件、源码及许可信息") },
                        icon = { Icon(Icons.Rounded.Code, contentDescription = null) },
                        onClick = onNavigateToOpenSource,
                    )
                },
            )))
            add(SettingsSection("about_community", "社区与帮助改进", listOf(
                SettingsEntry("sample_reports") {
                    Preference(
                        title = { Text("我的上报") },
                        summary = {
                            Text("查看上报记录与审核结果，主动提交脱敏短信样本")
                        },
                        icon = {
                            Icon(Icons.Rounded.UploadFile, contentDescription = null)
                        },
                        onClick = onNavigateToSampleReports
                    )
                },
                SettingsEntry("pixel_tailor") {
                    Preference(
                        external = true,
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
                },
                SettingsEntry("telegram_channel") {
                    Preference(
                        external = true,
                        title = { Text("Telegram 频道") },
                        summary = { Text("关注 Telegram 频道获取最新动态") },
                        icon = {
                            Icon(Icons.Rounded.Forum, contentDescription = null)
                        },
                        onClick = {
                            context.openUrl("https://t.me/pixel_tailor_cn")
                        }
                    )
                },
            )))
            if (showDebugResourceActions) {
                add(SettingsSection("debug", "调试工具", listOf(
                    SettingsEntry("debug_delete_downloaded_model") {
                        Preference(
                            title = { Text("删除下载的模型文件") },
                            summary = { Text("删除已下载的离线模型和词表，回退到内置模型") },
                            enabled = resourceUpdateEnabled,
                            icon = {
                                Icon(Icons.Rounded.DeleteSweep, contentDescription = null)
                            },
                            onClick = viewModel::deleteDownloadedModelResource
                        )
                    },
                    SettingsEntry("debug_delete_downloaded_rules") {
                        Preference(
                            title = { Text("删除下载的智能卡片规则文件") },
                            summary = { Text("删除已下载的规则文件，回退到内置规则版本") },
                            enabled = resourceUpdateEnabled,
                            icon = {
                                Icon(Icons.Rounded.DeleteSweep, contentDescription = null)
                            },
                            onClick = viewModel::deleteDownloadedRulesResource
                        )
                    },
                )))
            }
        }
        SettingsCategory.PERMISSIONS -> buildList {
            add(SettingsSection("default_role", "默认短信角色", listOf(
                SettingsEntry("default_sms") {
                    Preference(
                        title = { Text("默认短信应用") },
                        summary = { Text(if (defaultSmsApp) "已设为默认短信应用" else "设置后可完整接收、发送和管理短信") },
                        icon = { Icon(Icons.Rounded.Shield, null) },
                        onClick = if (defaultSmsApp) null else ({ defaultSmsAppLauncher.launch(context.createDefaultSmsAppRequestIntent()) }),
                    )
                },
            )))
            add(SettingsSection("permissions", "应用权限", permissionItems.map { item ->
                SettingsEntry("permission_${item.key}") {
                    PermissionPreference(item) {
                        if (item.openNotificationSettings) {
                            context.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName))
                        } else {
                            pendingPermissionDialogItem = item
                        }
                    }
                }
            }))
            add(SettingsSection("system_permissions", "系统管理", listOf(
                SettingsEntry("system_app_settings") {
                    Preference(
                        title = { Text("在系统中管理权限") },
                        summary = { Text("权限被拒绝后，可在系统应用详情中重新授权或调整通知") },
                        icon = { Icon(Icons.Rounded.Shield, null) },
                        external = true,
                        onClick = {
                            context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                "package:${context.packageName}".toUri()))
                        },
                    )
                },
            )))
        }
        null -> emptyList()
    }

    fun categorySummary(category: SettingsCategory): String = when (category) {
        SettingsCategory.MESSAGES -> "智能卡片、验证码管理与彩信"
        SettingsCategory.SPAM -> when {
            settings.spamDetectionEnabled && blockedKeywords.isNotEmpty() -> "模型识别已开启 · ${blockedKeywords.size} 个关键词"
            settings.spamDetectionEnabled -> "本地模型识别已开启"
            blockedKeywords.isNotEmpty() -> "仅关键词拦截生效（${blockedKeywords.size} 个）"
            else -> "未开启自动识别"
        }
        SettingsCategory.DISPLAY -> "应用图标、会话样式与通知操作"
        SettingsCategory.RESOURCES -> if (resourceUpdateState is ResourceUpdateState.Available) "发现可用资源更新" else "离线模型、卡片规则与发件方资料"
        SettingsCategory.BACKUP -> if (backupState.restoreProtected) "恢复保护已开启，请确认恢复结果" else "备份恢复、数据同步与修复"
        SettingsCategory.PERMISSIONS -> when {
            !defaultSmsApp -> "尚未设为默认短信应用"
            permissionItems.any { !it.granted } -> "${permissionItems.count { !it.granted }} 项权限待授权"
            else -> "已设为默认短信应用 · 权限齐全"
        }
        SettingsCategory.ABOUT -> "${currentDisplayName} · ${BuildConfig.VERSION_NAME}"
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wideLayout = maxWidth >= 840.dp
        fun handleBack() {
            if (wideLayout && returnCategory == null) onNavigateBack() else navigateBack()
        }
        BackHandler(enabled = selectedCategory != null || wideLayout, onBack = ::handleBack)
        // 宽窄布局切换直接重建容器，分类间转场与应用导航复用同一组动效。
        key(wideLayout) {
            AnimatedContent(
                targetState = if (wideLayout) null else selectedCategory,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    if (navigatingBack) {
                        (activityLikePopEnterTransition() togetherWith activityLikePopExitTransition())
                            .apply { targetContentZIndex = -1f }
                    } else {
                        (activityLikeEnterTransition() togetherWith activityLikeExitTransition())
                            .apply { targetContentZIndex = 1f }
                    }.using(null)
                },
                label = "settings_navigation",
            ) { animatedCategory ->
                val pageCategory = if (wideLayout) selectedCategory else animatedCategory
                Scaffold(
                    topBar = {
                        TopAppBar(
                            navigationIcon = { IconButton(onClick = ::handleBack) {
                                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回")
                            } },
                            title = { Text(if (wideLayout) "设置" else pageCategory?.title ?: "设置") },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                        )
                    },
                    containerColor = MaterialTheme.colorScheme.background,
                ) { padding ->
                    SettingsPage(
                        category = pageCategory, wide = wideLayout, sections = sectionsFor(pageCategory),
                        states = categoryStates, summary = ::categorySummary,
                        onSelect = { navigatingBack = false; selectedCategory = it; returnCategory = null; targetSection = null },
                        notice = when {
                            !defaultSmsApp -> "尚未设为默认短信应用"
                            !notificationGranted -> "通知权限未开启，收到短信时无法提醒"
                            permissionItems.any { it.key == "sms" && !it.granted } -> "短信权限未授予"
                            else -> null
                        },
                        onNoticeClick = { navigatingBack = false; selectedCategory = SettingsCategory.PERMISSIONS; returnCategory = null },
                        targetSection = targetSection.takeIf { pageCategory == selectedCategory },
                        onTargetConsumed = { targetSection = null },
                        modifier = Modifier.padding(padding).consumeWindowInsets(padding),
                    )
                }
            }
        }
    }

    pendingAdvancedDataAction?.let { action ->
        val actionEnabled = when (action) {
            AdvancedDataAction.SyncSms -> smsSyncEnabled
            AdvancedDataAction.RecognizeSms -> !isVerificationCodeIndexRunning
            AdvancedDataAction.RefreshSmartspacer -> true
        }
        AlertDialog(
            onDismissRequest = { pendingAdvancedDataAction = null },
            title = { Text(action.title) },
            text = { Text(action.description) },
            confirmButton = {
                TextButton(
                    enabled = actionEnabled,
                    onClick = {
                        if (pendingAdvancedDataAction != action || !actionEnabled) {
                            return@TextButton
                        }
                        pendingAdvancedDataAction = null
                        when (action) {
                            AdvancedDataAction.SyncSms -> viewModel.forceSyncSmsData()
                            AdvancedDataAction.RecognizeSms -> {
                                viewModel.rebuildVerificationCodeIndex()
                                Toast.makeText(
                                    context,
                                    "已安排重新识别全部短信",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                            AdvancedDataAction.RefreshSmartspacer -> {
                                SmartspacerIntegration.notifyChanged(context)
                                Toast.makeText(
                                    context,
                                    "已触发 Smartspacer 刷新",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    },
                ) {
                    Text("确认执行")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingAdvancedDataAction = null }) {
                    Text("取消")
                }
            },
        )
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

    if (showMessageTimeFormatDialog) {
        val sampleTime = remember {
            java.time.LocalDateTime.now().minusMinutes(5)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm:ss"))
        }
        AlertDialog(
            onDismissRequest = { showMessageTimeFormatDialog = false },
            title = { Text("短信时间显示格式") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    MessageTimeDisplayFormat.entries.forEach { format ->
                        MessageTimeFormatOption(
                            format = format,
                            selected = settings.messageTimeDisplayFormat == format,
                            sampleTime = sampleTime,
                            onClick = {
                                viewModel.setMessageTimeDisplayFormat(format)
                                showMessageTimeFormatDialog = false
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showMessageTimeFormatDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    if (showSpamAutoActionDialog) {
        AlertDialog(
            onDismissRequest = { showSpamAutoActionDialog = false },
            title = { Text("收到骚扰短信后") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SpamAutoAction.entries.forEach { action ->
                        SpamAutoActionOption(
                            action = action,
                            selected = settings.spamAutoAction == action,
                            onClick = {
                                showSpamAutoActionDialog = false
                                if (action == SpamAutoAction.DELETE && settings.spamAutoAction != action) {
                                    confirmSpamDeletion = true
                                } else {
                                    viewModel.setSpamAutoAction(action)
                                }
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

    if (confirmSpamDeletion) {
        AlertDialog(
            onDismissRequest = { confirmSpamDeletion = false },
            title = { Text("自动删除骚扰短信？") },
            text = { Text("模型和关键词可能误判。启用后，新收到并被判定为骚扰的短信会被自动删除。请确认白名单已正确配置。") },
            confirmButton = { TextButton(enabled = settings.muteSpamNotificationsEnabled, onClick = {
                if (settings.muteSpamNotificationsEnabled) viewModel.setSpamAutoAction(SpamAutoAction.DELETE)
                confirmSpamDeletion = false
            }) { Text("启用自动删除") } },
            dismissButton = { TextButton(onClick = { confirmSpamDeletion = false }) { Text("取消") } },
        )
    }

    if (showResourceAutoCheckIntervalDialog) {
        AlertDialog(
            onDismissRequest = { showResourceAutoCheckIntervalDialog = false },
            title = { Text("检查间隔时间") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = resourceAutoCheckIntervalInput,
                        onValueChange = {
                            resourceAutoCheckIntervalInput = it
                            resourceAutoCheckIntervalError = null
                        },
                        label = { Text("小时") },
                        singleLine = true,
                        isError = resourceAutoCheckIntervalError != null,
                        supportingText = resourceAutoCheckIntervalError?.let { error ->
                            { Text(error) }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val value = resourceAutoCheckIntervalInput.trim().toLongOrNull()
                        if (value == null || !viewModel.setResourceAutoCheckIntervalHours(value)) {
                            resourceAutoCheckIntervalError = "请输入大于 0 的整数"
                            return@TextButton
                        }
                        showResourceAutoCheckIntervalDialog = false
                    }
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResourceAutoCheckIntervalDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    if (showVerificationCodeRetentionDaysDialog) {
        AlertDialog(
            onDismissRequest = { showVerificationCodeRetentionDaysDialog = false },
            title = { Text("验证码保留时间") },
            text = {
                OutlinedTextField(
                    value = verificationCodeRetentionDaysInput,
                    onValueChange = { input ->
                        if (input.any { !it.isDigit() }) return@OutlinedTextField
                        verificationCodeRetentionDaysInput = input
                        verificationCodeRetentionDaysError = null
                    },
                    label = { Text("天数") },
                    supportingText = verificationCodeRetentionDaysError?.let { error ->
                        { Text(error) }
                    },
                    isError = verificationCodeRetentionDaysError != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val days = verificationCodeRetentionDaysInput.toIntOrNull()
                        if (days == null || !viewModel.setVerificationCodeRetentionDays(days)) {
                            verificationCodeRetentionDaysError = "请输入 1–365 之间的整数"
                            return@TextButton
                        }
                        showVerificationCodeRetentionDaysDialog = false
                    },
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showVerificationCodeRetentionDaysDialog = false }) {
                    Text("取消")
                }
            },
        )
    }

    if (smsSyncState is SmsSyncState.Syncing) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("正在同步数据") },
            text = { Text("首次启动需要同步短信数据，请稍候…") },
            confirmButton = {}
        )
    }

    (smsSyncState as? SmsSyncState.Error)?.let { state ->
        AlertDialog(
            onDismissRequest = viewModel::dismissSmsSyncError,
            title = { Text("同步失败") },
            text = { Text(state.message) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissSmsSyncError) {
                    Text("知道了")
                }
            }
        )
    }

    if (senderProfileUpdateState !is SenderProfileUpdateState.Idle) {
        ModalBottomSheet(
            onDismissRequest = viewModel::dismissSenderProfileUpdateDialog
        ) {
            SenderProfileUpdateSheetContent(
                state = senderProfileUpdateState,
                onInstall = viewModel::installSenderProfileUpdate,
                onDismiss = viewModel::dismissSenderProfileUpdateDialog,
            )
        }
    }

    if (resourceUpdateState.shouldShowResourceUpdateSheet()) {
        ModalBottomSheet(
            onDismissRequest = {
                if (resourceUpdateState !is ResourceUpdateState.Busy) {
                    viewModel.dismissResourceUpdateDialog()
                }
            }
        ) {
            AnimatedContent(
                targetState = resourceUpdateState.sheetMode(),
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "ResourceUpdateContent"
            ) { sheetMode ->
                key(sheetMode) {
                    ResourceUpdateSheetContent(
                        state = resourceUpdateState,
                        installedSummary = buildString {
                            append("当前模型：${friendlyResourceVersion(viewModel.displaySpamModelResourceVersion(settings.spamModelResourceVersion))}")
                            append("\n当前词表：${friendlyResourceVersion(settings.vocabResourceVersion)}")
                            append("\n当前规则：${friendlyResourceVersion(viewModel.displayRuleResourceVersion(settings.ruleResourceVersion))}")
                            if (settings.resourceUpdatedAt > 0) {
                                append("\n上次更新：${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(settings.resourceUpdatedAt))}")
                            }
                        },
                        onInstall = viewModel::installResourceUpdates,
                        onDismiss = viewModel::dismissResourceUpdateDialog
                    )
                }
            }
        }
    }
}

@Composable
private fun SenderProfileUpdateSheetContent(
    state: SenderProfileUpdateState,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (state) {
            SenderProfileUpdateState.Idle -> Unit
            SenderProfileUpdateState.Checking -> {
                Text("正在检查发件方资料", style = MaterialTheme.typography.titleLarge)
                ResourceUpdateBusyRow("正在连接服务端获取最新发件方资料")
            }

            is SenderProfileUpdateState.Available -> {
                Text("发现发件方资料更新", style = MaterialTheme.typography.titleLarge)
                Text("版本：${state.info.version}")
                Text("大小：${formatBytes(state.info.sizeBytes)}")
                Text(
                    state.info.releaseNotes,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text("稍后")
                    }
                    Button(onClick = onInstall, modifier = Modifier.weight(1f)) {
                        Text("安装")
                    }
                }
            }

            is SenderProfileUpdateState.NoUpdate -> {
                Text("暂无可用更新", style = MaterialTheme.typography.titleLarge)
                Text(state.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("知道了")
                }
            }

            is SenderProfileUpdateState.Installing -> {
                val progress = state.progress.coerceIn(0f, 1f)
                Text("正在安装发件方资料", style = MaterialTheme.typography.titleLarge)
                Text(state.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LinearWavyProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            is SenderProfileUpdateState.Success -> {
                Text("安装完成", style = MaterialTheme.typography.titleLarge)
                Text(state.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("知道了")
                }
            }

            is SenderProfileUpdateState.Error -> {
                Text("安装失败", style = MaterialTheme.typography.titleLarge)
                Text(state.message, color = MaterialTheme.colorScheme.error)
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("关闭")
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.getDefault(), "%.1f MiB", bytes / 1024f / 1024f)
    bytes >= 1024L -> String.format(Locale.getDefault(), "%.1f KiB", bytes / 1024f)
    else -> "$bytes B"
}

@Composable
private fun ResourceUpdateSheetContent(
    state: ResourceUpdateState,
    installedSummary: String,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when (state) {
            ResourceUpdateState.Idle -> Unit

            ResourceUpdateState.Checking -> {
                Text(
                    text = "正在检查资源更新",
                    style = MaterialTheme.typography.titleLarge
                )
                ResourceUpdateBusyRow(message = "正在连接服务端获取最新规则和模型信息")
            }

            is ResourceUpdateState.Available -> {
                Text(
                    text = "发现资源更新",
                    style = MaterialTheme.typography.titleLarge
                )
                ResourceUpdateDetailContent(detail = state.detail)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("稍后")
                    }
                    Button(
                        onClick = onInstall,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("更新")
                    }
                }
            }

            is ResourceUpdateState.NoUpdate -> {
                Text(
                    text = "暂无可用更新",
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("知道了")
                }
            }

            is ResourceUpdateState.Updating -> {
                val progress = state.progress.coerceIn(0f, 1f)
                Text(
                    text = "正在更新资源",
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LinearWavyProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            is ResourceUpdateState.Working -> {
                Text(
                    text = "正在处理资源",
                    style = MaterialTheme.typography.titleLarge
                )
                ResourceUpdateBusyRow(message = state.message)
            }

            is ResourceUpdateState.Success -> {
                Text(
                    text = "操作完成",
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("知道了")
                }
            }

            is ResourceUpdateState.Error -> {
                Text(
                    text = "操作失败",
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("关闭")
                }
            }
        }
        HorizontalDivider()
        Text(installedSummary, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ResourceUpdateBusyRow(message: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LoadingIndicator(modifier = Modifier.size(32.dp))
        Text(
            text = message,
            modifier = Modifier.padding(start = 16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun ResourceUpdateState.shouldShowResourceUpdateSheet(): Boolean {
    return this !is ResourceUpdateState.Idle
}

private fun ResourceUpdateState.sheetMode(): ResourceUpdateSheetMode {
    return when (this) {
        ResourceUpdateState.Idle -> ResourceUpdateSheetMode.Idle
        ResourceUpdateState.Checking -> ResourceUpdateSheetMode.Checking
        is ResourceUpdateState.Available -> ResourceUpdateSheetMode.Available
        is ResourceUpdateState.NoUpdate -> ResourceUpdateSheetMode.NoUpdate
        is ResourceUpdateState.Updating -> ResourceUpdateSheetMode.Updating
        is ResourceUpdateState.Working -> ResourceUpdateSheetMode.Working
        is ResourceUpdateState.Success -> ResourceUpdateSheetMode.Success
        is ResourceUpdateState.Error -> ResourceUpdateSheetMode.Error
    }
}

private enum class ResourceUpdateSheetMode {
    Idle,
    Checking,
    Available,
    NoUpdate,
    Updating,
    Working,
    Success,
    Error,
}

@Composable
private fun MessageTimeFormatOption(
    format: MessageTimeDisplayFormat,
    selected: Boolean,
    sampleTime: String,
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
                text = format.title(),
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = format.dialogSummary(sampleTime),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
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

private fun MessageTimeDisplayFormat.title(): String {
    return when (this) {
        MessageTimeDisplayFormat.HUMANIZED -> "人性化时间"
        MessageTimeDisplayFormat.DETAILED -> "详细时间"
    }
}

private fun MessageTimeDisplayFormat.dialogSummary(sampleTime: String): String {
    return when (this) {
        MessageTimeDisplayFormat.HUMANIZED -> "同一条示例短信：5分钟前；点按消息时间可临时切换为详细时间"
        MessageTimeDisplayFormat.DETAILED -> "同一条示例短信：$sampleTime；点按消息时间可临时切换为人性化时间"
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
        summary = { Text("${if (item.granted) "已授权" else "去授权"} · ${item.summary}") },
        icon = {
            Icon(
                when (item.key) {
                    "phone" -> Icons.Rounded.Phone
                    "sms" -> Icons.Rounded.Sms
                    "contacts" -> Icons.Rounded.Contacts
                    else -> Icons.Rounded.Notifications
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailing = if (item.granted) ({ Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary) }) else null,
        external = item.openNotificationSettings,
        onClick = if (item.granted) null else onRequest
    )
}

private data class PermissionItem(
    val key: String,
    val title: String,
    val summary: String,
    val missingPermissions: List<String>,
    val granted: Boolean,
    val openNotificationSettings: Boolean = false,
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
            ).let { item ->
                // 运行时权限已授予时，系统仍可能关闭整个应用的通知。
                val notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
                item.copy(
                    granted = item.granted && notificationsEnabled,
                    openNotificationSettings = item.granted && !notificationsEnabled,
                )
            }
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

private fun friendlyResourceVersion(version: String): String =
    when {
        version == "builtin" -> "内置版本"
        version.startsWith("builtin(") -> "内置版本${version.removePrefix("builtin")}"
        else -> version
    }
