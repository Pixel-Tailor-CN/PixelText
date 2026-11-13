package vip.mystery0.pixel.text.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import vip.mystery0.pixel.text.ui.navigationEnterTransition
import vip.mystery0.pixel.text.ui.navigationExitTransition
import vip.mystery0.pixel.text.ui.navigationPopEnterTransition
import vip.mystery0.pixel.text.ui.navigationPopExitTransition
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
import androidx.annotation.StringRes
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import vip.mystery0.pixel.text.domain.settings.NavigationTransitionStyle
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
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Star
import vip.mystery0.pixel.text.util.AppLanguage
import vip.mystery0.pixel.text.util.AppLocaleManager
import vip.mystery0.pixel.text.util.openAppReview
import vip.mystery0.pixel.text.viewmodel.EngagementPromptViewModel
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Language
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
import vip.mystery0.pixel.text.util.AppStrings
import vip.mystery0.pixel.text.util.enableDebugMode
import vip.mystery0.pixel.text.util.isDebugModeEnabled
import vip.mystery0.pixel.text.viewmodel.ResourceUpdateState
import vip.mystery0.pixel.text.viewmodel.KeywordSpamViewModel
import vip.mystery0.pixel.text.viewmodel.SettingsViewModel
import vip.mystery0.pixel.text.viewmodel.SenderProfileUpdateState
import vip.mystery0.pixel.text.viewmodel.SmsSyncState
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

private enum class AdvancedDataAction(
    @param:StringRes val titleRes: Int,
    @param:StringRes val descriptionRes: Int,
) {
    SyncSms(
        R.string.settings_sync_sms_title,
        R.string.settings_sync_sms_desc,
    ),
    RecognizeSms(
        R.string.settings_recognize_sms_title,
        R.string.settings_recognize_sms_desc,
    ),
    RefreshSmartspacer(
        R.string.settings_refresh_smartspacer_title,
        R.string.settings_refresh_smartspacer_desc,
    ),
}

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = koinViewModel(),
    engagementPromptViewModel: EngagementPromptViewModel = koinViewModel(),
    keywordSpamViewModel: KeywordSpamViewModel = koinViewModel(),
    backupViewModel: BackupViewModel = koinViewModel(),
    whitelistViewModel: SenderWhitelistViewModel = koinViewModel(),
    onNavigateBack: () -> Unit,
    onNavigateToSampleReports: () -> Unit = {},
    onNavigateToSwipeActions: () -> Unit = {},
    onNavigateToNotificationActions: () -> Unit = {},
    onNavigateToSmsNotificationIcons: () -> Unit = {},
    onNavigateToAppIdentity: () -> Unit = {},
    onNavigateToPersonalLearning: () -> Unit = {},
    onNavigateToKeywordSpam: () -> Unit = {},
    onNavigateToBackup: () -> Unit = {},
    onNavigateToOpenSource: () -> Unit = {},
    onNavigateToDeveloperSupport: () -> Unit = {},
    onNavigateToSenderWhitelist: () -> Unit = {},
    onNavigateToConversationDetailCustomization: () -> Unit = {},
    resourceUpdateCheckRequestId: Long? = null,
    onResourceUpdateCheckRequestConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val resources = LocalResources.current
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
    var showAppLanguageDialog by remember { mutableStateOf(false) }
    var showNavigationTransitionDialog by remember { mutableStateOf(false) }
    val currentAppLanguage = remember(context, resources.configuration) {
        AppLocaleManager.currentLanguage(context)
    }
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
            if (context.isDefaultSmsApp() && permissions.isNotEmpty()) {
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
        ResourceUpdateState.Idle -> stringResource(R.string.settings_resource_update_manual_summary)
        ResourceUpdateState.Checking -> stringResource(R.string.settings_resource_update_manual_summary)
        is ResourceUpdateState.Available ->
            stringResource(
                R.string.settings_resource_update_available_summary,
                state.detail.modelVersion,
                state.detail.ruleVersion,
            )
        is ResourceUpdateState.NoUpdate -> stringResource(R.string.settings_resource_update_manual_summary)
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
            add(SettingsSection("message_cards", resources.getString(R.string.settings_section_message_cards), listOf(
                SettingsEntry("smart_card") {
                    SwitchPreference(
                        value = settings.smartCardEnabled,
                        onValueChange = viewModel::setSmartCardEnabled,
                        title = { Text(stringResource(R.string.settings_smart_card_title)) },
                        summary = {
                            Text(stringResource(R.string.settings_smart_card_summary))
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
                        title = { Text(stringResource(R.string.settings_otp_show_content_title)) },
                        summary = { Text(stringResource(R.string.settings_otp_show_content_summary)) },
                        icon = {
                            Icon(Icons.Rounded.Visibility, contentDescription = null)
                        },
                    )
                },
            )))
            add(SettingsSection("message_codes", resources.getString(R.string.settings_section_message_codes), listOf(
                SettingsEntry("verification_code_auto_delete") {
                    SwitchPreference(
                        value = settings.verificationCodeAutoDeleteEnabled,
                        onValueChange = viewModel::setVerificationCodeAutoDeleteEnabled,
                        title = { Text(stringResource(R.string.settings_otp_auto_delete_title)) },
                        summary = { Text(stringResource(R.string.settings_otp_auto_delete_summary)) },
                        icon = {
                            Icon(Icons.Rounded.DeleteSweep, contentDescription = null)
                        },
                    )
                },
                SettingsEntry("verification_code_retention_days") {
                    Preference(
                        enabled = settings.verificationCodeAutoDeleteEnabled,
                        title = { Text(stringResource(R.string.settings_otp_retention_title)) },
                        summary = {
                            val days = settings.verificationCodeRetentionDays
                            Text(
                                when {
                                    settings.verificationCodeAutoDeleteEnabled && days == 1 ->
                                        stringResource(R.string.settings_otp_retention_days_single, days)
                                    settings.verificationCodeAutoDeleteEnabled ->
                                        stringResource(R.string.settings_otp_retention_days_plural, days)
                                    days == 1 ->
                                        stringResource(R.string.settings_otp_retention_days_disabled_single, days)
                                    else ->
                                        stringResource(R.string.settings_otp_retention_days_disabled_plural, days)
                                }
                            )
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
                        title = { Text(stringResource(R.string.settings_otp_notification_privacy_title)) },
                        summary = {
                            Text(
                                when {
                                    !settings.verificationCodeNotificationActionEnabled ->
                                        stringResource(R.string.settings_otp_notification_privacy_off)
                                    settings.hideVerificationCodeOnLockScreenEnabled ->
                                        stringResource(R.string.settings_otp_notification_privacy_hidden)
                                    else ->
                                        stringResource(R.string.settings_otp_notification_privacy_shown)
                                }
                            )
                        },
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
            add(SettingsSection("message_mms", resources.getString(R.string.settings_section_mms), listOf(
                SettingsEntry("auto_download_mms") {
                    SwitchPreference(
                        value = settings.autoDownloadMms,
                        onValueChange = viewModel::setAutoDownloadMms,
                        title = { Text(stringResource(R.string.settings_auto_download_mms_title)) },
                        icon = { Icon(Icons.Rounded.Image, null) },
                        summary = { Text(stringResource(R.string.settings_auto_download_mms_summary)) },
                    )
                },
            )))
        }
        SettingsCategory.DISPLAY -> buildList {
            add(SettingsSection("display_app", resources.getString(R.string.settings_section_display_app), listOf(
                SettingsEntry("app_identity") {
                    AppIdentitySettingsPreference(onClick = onNavigateToAppIdentity)
                },
                SettingsEntry("app_language") {
                    Preference(
                        title = { Text(stringResource(R.string.settings_app_language_title)) },
                        summary = { Text(currentAppLanguage.title()) },
                        icon = { Icon(Icons.Rounded.Language, contentDescription = null) },
                        onClick = { showAppLanguageDialog = true },
                    )
                },
                SettingsEntry("navigation_transition_style") {
                    Preference(
                        title = { Text(stringResource(R.string.settings_transition_title)) },
                        summary = { Text(settings.navigationTransitionStyle.title()) },
                        icon = { Icon(Icons.Rounded.SwapHoriz, contentDescription = null) },
                        onClick = { showNavigationTransitionDialog = true },
                    )
                },
                SettingsEntry("unread_badge") {
                    SwitchPreference(
                        value = settings.unreadBadgeEnabled,
                        onValueChange = viewModel::setUnreadBadgeEnabled,
                        title = { Text(stringResource(R.string.settings_unread_badge_title)) },
                        icon = {
                            Icon(Icons.Rounded.Notifications, contentDescription = null)
                        },
                    )
                },
            )))
            add(SettingsSection("display_conversations", resources.getString(R.string.settings_section_display_conversations), listOf(
                SettingsEntry("conversation_detail_customization") {
                    Preference(
                        title = { Text(stringResource(R.string.settings_conversation_appearance_title)) },
                        summary = { Text(stringResource(R.string.settings_conversation_appearance_summary)) },
                        icon = {
                            Icon(Icons.Rounded.Palette, contentDescription = null)
                        },
                        onClick = onNavigateToConversationDetailCustomization,
                    )
                },
                SettingsEntry("message_time_display_format") {
                    Preference(
                        title = { Text(stringResource(R.string.settings_time_format_title)) },
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
                        title = { Text(stringResource(R.string.settings_swipe_actions_title)) },
                        summary = {
                            Text(
                                stringResource(
                                    R.string.settings_swipe_actions_summary,
                                    settings.rightSwipeAction.settingTitle(),
                                    settings.leftSwipeAction.settingTitle(),
                                )
                            )
                        },
                        icon = {
                            Icon(Icons.Rounded.SwapHoriz, contentDescription = null)
                        },
                        onClick = onNavigateToSwipeActions
                    )
                },
            )))
            add(SettingsSection("display_notifications", resources.getString(R.string.settings_section_display_notifications), listOf(
                SettingsEntry("sms_notification_icon") {
                    Preference(
                        title = { Text(stringResource(R.string.settings_notification_icon_title)) },
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
                        title = { Text(stringResource(R.string.settings_notification_actions_title)) },
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
                        title = { Text(stringResource(R.string.settings_otp_quick_copy_title)) },
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
                        title = { Text(stringResource(R.string.settings_otp_hide_lockscreen_title)) },
                        summary = {
                            Text(
                                stringResource(
                                    if (settings.verificationCodeNotificationActionEnabled) {
                                        R.string.settings_otp_hide_lockscreen_summary
                                    } else {
                                        R.string.settings_otp_hide_lockscreen_disabled
                                    }
                                )
                            )
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
            add(SettingsSection("spam_rules", resources.getString(R.string.settings_section_spam_rules), listOf(
                SettingsEntry("spam_detection") {
                    SwitchPreference(
                        value = settings.spamDetectionEnabled,
                        onValueChange = viewModel::setSpamDetectionEnabled,
                        title = { Text(stringResource(R.string.settings_spam_model_title)) },
                        summary = {
                            Text(stringResource(R.string.settings_spam_model_summary))
                        },
                        icon = {
                            Icon(Icons.Rounded.Shield, contentDescription = null)
                        }
                    )
                },
                SettingsEntry("personal_learning") {
                    Preference(
                        title = { Text(stringResource(R.string.settings_personal_learning_title)) },
                        summary = {
                            Text(
                                stringResource(
                                    if (settings.personalLearningEnabled) {
                                        R.string.settings_state_enabled
                                    } else {
                                        R.string.settings_state_disabled
                                    }
                                )
                            )
                        },
                        icon = { Icon(Icons.Rounded.Memory, null) },
                        onClick = onNavigateToPersonalLearning
                    )
                },
                SettingsEntry("sample_reports") {
                    Preference(
                        title = { Text(stringResource(R.string.settings_my_reports_title)) },
                        icon = {
                            Icon(Icons.Rounded.UploadFile, contentDescription = null)
                        },
                        onClick = onNavigateToSampleReports
                    )
                },
                SettingsEntry("keyword_spam") {
                    Preference(
                        title = { Text(stringResource(R.string.settings_keyword_blocking_title)) },
                        summary = {
                            Text(
                                when {
                                    blockedKeywords.isEmpty() ->
                                        stringResource(R.string.settings_keyword_none)
                                    blockedKeywords.size == 1 ->
                                        stringResource(R.string.settings_keyword_count_single, blockedKeywords.size)
                                    else ->
                                        stringResource(R.string.settings_keyword_count_plural, blockedKeywords.size)
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
                        title = { Text(stringResource(R.string.settings_whitelist_title)) },
                        summary = {
                            Text(
                                if (whitelistRules.size == 1) {
                                    stringResource(R.string.settings_whitelist_count_single, whitelistRules.size)
                                } else {
                                    stringResource(R.string.settings_whitelist_count_plural, whitelistRules.size)
                                }
                            )
                        },
                        icon = { Icon(Icons.Rounded.VerifiedUser, contentDescription = null) },
                        onClick = onNavigateToSenderWhitelist,
                    )
                },
            )))
            add(SettingsSection("spam_actions", resources.getString(R.string.settings_section_spam_actions), listOf(
                SettingsEntry("mute_spam_notifications") {
                    SwitchPreference(
                        value = settings.muteSpamNotificationsEnabled,
                        onValueChange = viewModel::setMuteSpamNotificationsEnabled,
                        title = { Text(stringResource(R.string.settings_mute_spam_title)) },
                        icon = {
                            Icon(Icons.Rounded.NotificationsOff, contentDescription = null)
                        }
                    )
                },
                SettingsEntry("spam_auto_action") {
                    Preference(
                        title = { Text(stringResource(R.string.settings_spam_auto_action_title)) },
                        summary = {
                            Text(
                                if (spamAutoActionEnabled) {
                                    settings.spamAutoAction.preferenceSummary()
                                } else {
                                    stringResource(R.string.settings_spam_auto_action_requires_mute)
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
            add(SettingsSection("spam_display", resources.getString(R.string.settings_section_spam_display), listOf(
                SettingsEntry("spam_isolation") {
                    SwitchPreference(
                        value = settings.spamIsolationEnabled,
                        onValueChange = viewModel::setSpamIsolationEnabled,
                        title = { Text(stringResource(R.string.settings_spam_isolation_title)) },
                        summary = {
                            Text(stringResource(R.string.settings_spam_isolation_summary))
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
                        title = { Text(stringResource(R.string.settings_spam_expand_default_title)) },
                        summary = {
                            Text(stringResource(R.string.settings_spam_expand_default_summary))
                        },
                        icon = {
                            Icon(Icons.Rounded.Visibility, contentDescription = null)
                        }
                    )
                },
            )))
        }
        SettingsCategory.RESOURCES -> buildList {
            add(SettingsSection("resources_current", resources.getString(R.string.settings_section_resources_current), listOf(
                SettingsEntry("offline_model_version") {
                    Preference(
                        title = { Text(stringResource(R.string.settings_offline_model_version_title)) },
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
                        title = { Text(stringResource(R.string.settings_rule_version_title)) },
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
                        title = { Text(stringResource(R.string.settings_sender_profile_title)) },
                        summary = {
                            Text(
                                senderProfileVersion?.let {
                                    stringResource(R.string.settings_sender_profile_version_summary, it)
                                } ?: stringResource(R.string.settings_sender_profile_none_summary)
                            )
                        },
                        enabled = senderProfileUpdateState !is SenderProfileUpdateState.Busy,
                        icon = { Icon(Icons.Rounded.Badge, contentDescription = null) },
                        onClick = viewModel::checkSenderProfileUpdates,
                    )
                },
            )))
            add(SettingsSection("resources_updates", resources.getString(R.string.settings_section_resources_updates), listOf(
                SettingsEntry("resource_update") {
                    Preference(
                        title = { Text(stringResource(R.string.settings_resource_update_title)) },
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
                        title = { Text(stringResource(R.string.settings_resource_auto_check_title)) },
                        summary = {
                            Text(
                                stringResource(
                                    if (notificationGranted) {
                                        R.string.settings_resource_auto_check_summary
                                    } else {
                                        R.string.settings_resource_auto_check_no_notif
                                    }
                                )
                            )
                        },
                        icon = {
                            Icon(Icons.Rounded.Update, contentDescription = null)
                        }
                    )
                },
                SettingsEntry("resource_auto_check_interval") {
                    Preference(
                        title = { Text(stringResource(R.string.settings_resource_check_interval_title)) },
                        summary = {
                            val hours = settings.resourceAutoCheckIntervalHours
                            Text(
                                when {
                                    settings.resourceAutoCheckEnabled && hours == 1L ->
                                        stringResource(R.string.settings_resource_check_interval_hours_single, hours)
                                    settings.resourceAutoCheckEnabled ->
                                        stringResource(R.string.settings_resource_check_interval_hours_plural, hours)
                                    else ->
                                        stringResource(R.string.settings_resource_check_interval_disabled)
                                }
                            )
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
            add(SettingsSection("backup", resources.getString(R.string.settings_section_backup), listOf(
                SettingsEntry("backup_restore") {
                    Preference(
                        title = { Text(stringResource(R.string.settings_backup_restore_title)) },
                        summary = {
                            Text(
                                stringResource(
                                    if (backupState.restoreProtected) {
                                        R.string.settings_backup_restore_protected
                                    } else {
                                        R.string.settings_backup_restore_summary
                                    }
                                )
                            )
                        },
                        icon = { Icon(Icons.Rounded.UploadFile, contentDescription = null) },
                        onClick = onNavigateToBackup,
                    )
                },
            )))
            add(SettingsSection("maintenance", resources.getString(R.string.settings_section_maintenance), listOf(
                SettingsEntry("force_sms_sync") {
                    Preference(
                        title = { Text(stringResource(R.string.settings_sync_sms_title)) },
                        summary = {
                            Text(
                                stringResource(
                                    if (smsSyncState is SmsSyncState.Syncing) {
                                        R.string.settings_sync_sms_running
                                    } else {
                                        R.string.settings_sync_sms_summary
                                    }
                                )
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
                        title = { Text(stringResource(R.string.settings_recognize_sms_title)) },
                        summary = {
                            Text(
                                stringResource(
                                    if (isVerificationCodeIndexRunning) {
                                        R.string.settings_recognize_sms_running
                                    } else {
                                        R.string.settings_recognize_sms_summary
                                    }
                                )
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
                        title = { Text(stringResource(R.string.settings_refresh_smartspacer_title)) },
                        summary = {
                            Text(stringResource(R.string.settings_refresh_smartspacer_summary))
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
            add(SettingsSection("about_version", resources.getString(R.string.settings_section_about_version), listOf(
                SettingsEntry("version_name") {
                    Preference(
                        title = { Text(stringResource(R.string.settings_app_version_title)) },
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
                                    resources.getString(R.string.settings_debug_mode_enabled),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    )
                },
                SettingsEntry("open_source") {
                    Preference(
                        title = { Text(stringResource(R.string.settings_open_source_title)) },
                        icon = { Icon(Icons.Rounded.Code, contentDescription = null) },
                        onClick = onNavigateToOpenSource,
                    )
                },
            )))
            add(SettingsSection("about_community", resources.getString(R.string.settings_section_about_community), listOf(
                SettingsEntry("app_review") {
                    Preference(
                        external = true,
                        title = { Text(stringResource(R.string.settings_rate_app_title)) },
                        icon = { Icon(Icons.Rounded.Star, contentDescription = null) },
                        onClick = {
                            engagementPromptViewModel.dismissThen {
                                if (!context.openAppReview()) {
                                    Toast.makeText(
                                        context,
                                        resources.getString(R.string.settings_rate_app_failed),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        },
                    )
                },
                SettingsEntry("developer_support") {
                    Preference(
                        title = { Text(stringResource(R.string.settings_support_dev_title)) },
                        summary = { Text(stringResource(R.string.settings_support_dev_summary)) },
                        icon = { Icon(Icons.Rounded.Favorite, contentDescription = null) },
                        onClick = { engagementPromptViewModel.dismissThen(onNavigateToDeveloperSupport) },
                    )
                },
                SettingsEntry("pixel_tailor") {
                    Preference(
                        external = true,
                        title = { Text(stringResource(R.string.settings_pixel_tailor_title)) },
                        summary = { Text(stringResource(R.string.settings_pixel_tailor_summary)) },
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
                        title = { Text(stringResource(R.string.settings_telegram_channel_title)) },
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
                add(SettingsSection("debug", resources.getString(R.string.settings_section_debug), listOf(
                    SettingsEntry("debug_delete_downloaded_model") {
                        Preference(
                            title = { Text(stringResource(R.string.settings_debug_delete_model_title)) },
                            summary = { Text(stringResource(R.string.settings_debug_delete_model_summary)) },
                            enabled = resourceUpdateEnabled,
                            icon = {
                                Icon(Icons.Rounded.DeleteSweep, contentDescription = null)
                            },
                            onClick = viewModel::deleteDownloadedModelResource
                        )
                    },
                    SettingsEntry("debug_delete_downloaded_rules") {
                        Preference(
                            title = { Text(stringResource(R.string.settings_debug_delete_rules_title)) },
                            summary = { Text(stringResource(R.string.settings_debug_delete_rules_summary)) },
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
            add(SettingsSection("default_role", resources.getString(R.string.settings_section_default_role), listOf(
                SettingsEntry("default_sms") {
                    Preference(
                        title = { Text(stringResource(R.string.settings_default_sms_title)) },
                        summary = {
                            Text(
                                stringResource(
                                    if (defaultSmsApp) {
                                        R.string.settings_default_sms_granted
                                    } else {
                                        R.string.settings_default_sms_prompt
                                    }
                                )
                            )
                        },
                        icon = { Icon(Icons.Rounded.Shield, null) },
                        onClick = if (defaultSmsApp) null else ({ defaultSmsAppLauncher.launch(context.createDefaultSmsAppRequestIntent()) }),
                    )
                },
            )))
            add(SettingsSection("permissions", resources.getString(R.string.settings_section_permissions), permissionItems.map { item ->
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
            add(SettingsSection("system_permissions", resources.getString(R.string.settings_section_system_permissions), listOf(
                SettingsEntry("system_app_settings") {
                    Preference(
                        title = { Text(stringResource(R.string.settings_system_permissions_title)) },
                        summary = { Text(stringResource(R.string.settings_system_permissions_summary)) },
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
        SettingsCategory.MESSAGES -> resources.getString(R.string.settings_summary_messages)
        SettingsCategory.SPAM -> when {
            settings.spamDetectionEnabled && blockedKeywords.isNotEmpty() -> {
                val count = blockedKeywords.size
                if (count == 1) {
                    resources.getString(R.string.settings_summary_spam_model_and_keywords_single, count)
                } else {
                    resources.getString(R.string.settings_summary_spam_model_and_keywords_plural, count)
                }
            }
            settings.spamDetectionEnabled -> resources.getString(R.string.settings_summary_spam_model_only)
            blockedKeywords.isNotEmpty() ->
                resources.getString(R.string.settings_summary_spam_keywords_only, blockedKeywords.size)
            else -> resources.getString(R.string.settings_summary_spam_none)
        }
        SettingsCategory.DISPLAY -> resources.getString(R.string.settings_summary_display)
        SettingsCategory.RESOURCES ->
            if (resourceUpdateState is ResourceUpdateState.Available) {
                resources.getString(R.string.settings_summary_resources_available)
            } else {
                resources.getString(R.string.settings_summary_resources_default)
            }
        SettingsCategory.BACKUP ->
            if (backupState.restoreProtected) {
                resources.getString(R.string.settings_summary_backup_protected)
            } else {
                resources.getString(R.string.settings_summary_backup_default)
            }
        SettingsCategory.PERMISSIONS -> when {
            !defaultSmsApp -> resources.getString(R.string.settings_summary_permissions_not_default)
            permissionItems.any { !it.granted } -> {
                val count = permissionItems.count { !it.granted }
                if (count == 1) {
                    resources.getString(R.string.settings_summary_permissions_missing_single, count)
                } else {
                    resources.getString(R.string.settings_summary_permissions_missing_plural, count)
                }
            }
            else -> resources.getString(R.string.settings_summary_permissions_ready)
        }
        SettingsCategory.ABOUT -> "$currentDisplayName · ${BuildConfig.VERSION_NAME}"
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
                        (navigationPopEnterTransition(settings.navigationTransitionStyle) togetherWith
                            navigationPopExitTransition(settings.navigationTransitionStyle))
                            .apply { targetContentZIndex = -1f }
                    } else {
                        (navigationEnterTransition(settings.navigationTransitionStyle) togetherWith
                            navigationExitTransition(settings.navigationTransitionStyle))
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
                                Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.settings_action_back))
                            } },
                            title = {
                                Text(
                                    if (wideLayout) {
                                        stringResource(R.string.settings_title)
                                    } else {
                                        pageCategory?.let { stringResource(it.titleRes) }
                                            ?: stringResource(R.string.settings_title)
                                    }
                                )
                            },
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
                            !defaultSmsApp -> stringResource(R.string.settings_notice_not_default)
                            !notificationGranted -> stringResource(R.string.settings_notice_no_notifications)
                            permissionItems.any { it.key == "sms" && !it.granted } -> stringResource(R.string.settings_notice_no_sms)
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
            title = { Text(stringResource(action.titleRes)) },
            text = { Text(stringResource(action.descriptionRes)) },
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
                                    resources.getString(R.string.settings_recognize_sms_scheduled),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                            AdvancedDataAction.RefreshSmartspacer -> {
                                SmartspacerIntegration.notifyChanged(context)
                                Toast.makeText(
                                    context,
                                    resources.getString(R.string.settings_refresh_smartspacer_triggered),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.settings_action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingAdvancedDataAction = null }) {
                    Text(stringResource(R.string.settings_action_cancel))
                }
            },
        )
    }

    pendingPermissionDialogItem?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingPermissionDialogItem = null },
            title = { Text(stringResource(R.string.settings_permission_dialog_title, item.title)) },
            text = {
                Text(stringResource(R.string.settings_permission_dialog_body, item.summary))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingPermissionDialogItem = null
                        requestPermissionsAfterDefaultPrompt(item.missingPermissions)
                    }
                ) {
                    Text(stringResource(R.string.settings_action_grant))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingPermissionDialogItem = null }) {
                    Text(stringResource(R.string.settings_action_later))
                }
            }
        )
    }

    if (showNavigationTransitionDialog) {
        AlertDialog(
            onDismissRequest = { showNavigationTransitionDialog = false },
            title = { Text(stringResource(R.string.settings_transition_title)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.selectableGroup()) {
                        NavigationTransitionStyle.entries.forEach { style ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = settings.navigationTransitionStyle == style,
                                        role = Role.RadioButton,
                                        onClick = {
                                            viewModel.setNavigationTransitionStyle(style)
                                            showNavigationTransitionDialog = false
                                        },
                                    )
                                    .padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = settings.navigationTransitionStyle == style,
                                    onClick = null,
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(style.title(), style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        style.description(),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showNavigationTransitionDialog = false }) {
                    Text(stringResource(R.string.settings_action_cancel))
                }
            },
        )
    }

    if (showAppLanguageDialog) {
        AlertDialog(
            onDismissRequest = { showAppLanguageDialog = false },
            title = { Text(stringResource(R.string.settings_app_language_title)) },
            text = {
                Column(Modifier.selectableGroup()) {
                    AppLanguage.entries.forEach { language ->
                        val selected = currentAppLanguage == language
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selected,
                                    role = Role.RadioButton,
                                    onClick = {
                                        showAppLanguageDialog = false
                                        AppLocaleManager.setLanguage(context, language)
                                    },
                                )
                                .padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = null,
                            )
                            Text(
                                text = language.title(),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAppLanguageDialog = false }) {
                    Text(stringResource(R.string.settings_action_cancel))
                }
            },
        )
    }

    if (showMessageTimeFormatDialog) {
        val sampleTimePattern = stringResource(R.string.settings_sample_time_pattern)
        val sampleTime = remember(sampleTimePattern) {
            java.time.LocalDateTime.now().minusMinutes(5)
                .format(java.time.format.DateTimeFormatter.ofPattern(sampleTimePattern, Locale.getDefault()))
        }
        AlertDialog(
            onDismissRequest = { showMessageTimeFormatDialog = false },
            title = { Text(stringResource(R.string.settings_time_format_title)) },
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
                    Text(stringResource(R.string.settings_action_cancel))
                }
            }
        )
    }

    if (showSpamAutoActionDialog) {
        AlertDialog(
            onDismissRequest = { showSpamAutoActionDialog = false },
            title = { Text(stringResource(R.string.settings_spam_auto_action_title)) },
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
                    Text(stringResource(R.string.settings_action_cancel))
                }
            }
        )
    }

    if (confirmSpamDeletion) {
        AlertDialog(
            onDismissRequest = { confirmSpamDeletion = false },
            title = { Text(stringResource(R.string.settings_spam_delete_confirm_title)) },
            text = { Text(stringResource(R.string.settings_spam_delete_confirm_body)) },
            confirmButton = { TextButton(enabled = settings.muteSpamNotificationsEnabled, onClick = {
                if (settings.muteSpamNotificationsEnabled) viewModel.setSpamAutoAction(SpamAutoAction.DELETE)
                confirmSpamDeletion = false
            }) { Text(stringResource(R.string.settings_spam_delete_confirm_button)) } },
            dismissButton = { TextButton(onClick = { confirmSpamDeletion = false }) { Text(stringResource(R.string.settings_action_cancel)) } },
        )
    }

    if (showResourceAutoCheckIntervalDialog) {
        AlertDialog(
            onDismissRequest = { showResourceAutoCheckIntervalDialog = false },
            title = { Text(stringResource(R.string.settings_resource_check_interval_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = resourceAutoCheckIntervalInput,
                        onValueChange = {
                            resourceAutoCheckIntervalInput = it
                            resourceAutoCheckIntervalError = null
                        },
                        label = { Text(stringResource(R.string.settings_input_hours_label)) },
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
                            resourceAutoCheckIntervalError = resources.getString(R.string.settings_input_hours_error)
                            return@TextButton
                        }
                        showResourceAutoCheckIntervalDialog = false
                    }
                ) {
                    Text(stringResource(R.string.settings_action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResourceAutoCheckIntervalDialog = false }) {
                    Text(stringResource(R.string.settings_action_cancel))
                }
            }
        )
    }

    if (showVerificationCodeRetentionDaysDialog) {
        AlertDialog(
            onDismissRequest = { showVerificationCodeRetentionDaysDialog = false },
            title = { Text(stringResource(R.string.settings_otp_retention_title)) },
            text = {
                OutlinedTextField(
                    value = verificationCodeRetentionDaysInput,
                    onValueChange = { input ->
                        if (input.any { !it.isDigit() }) return@OutlinedTextField
                        verificationCodeRetentionDaysInput = input
                        verificationCodeRetentionDaysError = null
                    },
                    label = { Text(stringResource(R.string.settings_input_days_label)) },
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
                            verificationCodeRetentionDaysError = resources.getString(R.string.settings_input_days_error)
                            return@TextButton
                        }
                        showVerificationCodeRetentionDaysDialog = false
                    },
                ) {
                    Text(stringResource(R.string.settings_action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showVerificationCodeRetentionDaysDialog = false }) {
                    Text(stringResource(R.string.settings_action_cancel))
                }
            },
        )
    }

    if (smsSyncState is SmsSyncState.Syncing) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.settings_sync_sms_dialog_title)) },
            text = { Text(stringResource(R.string.settings_sync_sms_dialog_body)) },
            confirmButton = {}
        )
    }

    (smsSyncState as? SmsSyncState.Error)?.let { state ->
        AlertDialog(
            onDismissRequest = viewModel::dismissSmsSyncError,
            title = { Text(stringResource(R.string.settings_sync_sms_failed_title)) },
            text = { Text(state.message) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissSmsSyncError) {
                    Text(stringResource(R.string.settings_action_ok))
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
                            append(
                                stringResource(
                                    R.string.settings_resource_installed_model,
                                    friendlyResourceVersion(viewModel.displaySpamModelResourceVersion(settings.spamModelResourceVersion))
                                )
                            )
                            append("\n")
                            append(
                                stringResource(
                                    R.string.settings_resource_installed_vocab,
                                    friendlyResourceVersion(settings.vocabResourceVersion)
                                )
                            )
                            append("\n")
                            append(
                                stringResource(
                                    R.string.settings_resource_installed_rules,
                                    friendlyResourceVersion(viewModel.displayRuleResourceVersion(settings.ruleResourceVersion))
                                )
                            )
                            if (settings.resourceUpdatedAt > 0) {
                                append("\n")
                                append(
                                    stringResource(
                                        R.string.settings_resource_last_updated,
                                        java.text.DateFormat.getDateTimeInstance().format(java.util.Date(settings.resourceUpdatedAt))
                                    )
                                )
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
                Text(stringResource(R.string.settings_sender_profile_checking), style = MaterialTheme.typography.titleLarge)
                ResourceUpdateBusyRow(stringResource(R.string.settings_sender_profile_connecting))
            }

            is SenderProfileUpdateState.Available -> {
                Text(stringResource(R.string.settings_sender_profile_available), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.settings_sender_profile_ver, state.info.version))
                Text(stringResource(R.string.settings_sender_profile_size, formatBytes(state.info.sizeBytes)))
                Text(
                    state.info.releaseNotes,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_action_later))
                    }
                    Button(onClick = onInstall, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_sender_profile_install))
                    }
                }
            }

            is SenderProfileUpdateState.NoUpdate -> {
                Text(stringResource(R.string.settings_update_none), style = MaterialTheme.typography.titleLarge)
                Text(state.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_action_ok))
                }
            }

            is SenderProfileUpdateState.Installing -> {
                val progress = state.progress.coerceIn(0f, 1f)
                Text(stringResource(R.string.settings_sender_profile_installing), style = MaterialTheme.typography.titleLarge)
                Text(state.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LinearWavyProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            is SenderProfileUpdateState.Success -> {
                Text(stringResource(R.string.settings_install_complete), style = MaterialTheme.typography.titleLarge)
                Text(state.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_action_ok))
                }
            }

            is SenderProfileUpdateState.Error -> {
                Text(stringResource(R.string.settings_install_failed), style = MaterialTheme.typography.titleLarge)
                Text(state.message, color = MaterialTheme.colorScheme.error)
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_action_close))
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
                    text = stringResource(R.string.settings_resource_checking),
                    style = MaterialTheme.typography.titleLarge
                )
                ResourceUpdateBusyRow(message = stringResource(R.string.settings_resource_connecting))
            }

            is ResourceUpdateState.Available -> {
                Text(
                    text = stringResource(R.string.settings_resource_available),
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
                        Text(stringResource(R.string.settings_action_later))
                    }
                    Button(
                        onClick = onInstall,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.settings_resource_update_btn))
                    }
                }
            }

            is ResourceUpdateState.NoUpdate -> {
                Text(
                    text = stringResource(R.string.settings_update_none),
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
                    Text(stringResource(R.string.settings_action_ok))
                }
            }

            is ResourceUpdateState.Updating -> {
                val progress = state.progress.coerceIn(0f, 1f)
                Text(
                    text = stringResource(R.string.settings_resource_updating),
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
                    text = stringResource(R.string.settings_resource_working),
                    style = MaterialTheme.typography.titleLarge
                )
                ResourceUpdateBusyRow(message = state.message)
            }

            is ResourceUpdateState.Success -> {
                Text(
                    text = stringResource(R.string.settings_resource_success),
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
                    Text(stringResource(R.string.settings_action_ok))
                }
            }

            is ResourceUpdateState.Error -> {
                Text(
                    text = stringResource(R.string.settings_resource_error),
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
                    Text(stringResource(R.string.settings_action_close))
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

private fun NavigationTransitionStyle.title(): String = AppStrings.get(
    when (this) {
        NavigationTransitionStyle.DEFAULT -> R.string.settings_transition_default
        NavigationTransitionStyle.SLIDE -> R.string.settings_transition_slide
        NavigationTransitionStyle.FADE -> R.string.settings_transition_fade
        NavigationTransitionStyle.NONE -> R.string.settings_transition_none
    }
)

private fun NavigationTransitionStyle.description(): String = AppStrings.get(
    when (this) {
        NavigationTransitionStyle.DEFAULT -> R.string.settings_transition_default_desc
        NavigationTransitionStyle.SLIDE -> R.string.settings_transition_slide_desc
        NavigationTransitionStyle.FADE -> R.string.settings_transition_fade_desc
        NavigationTransitionStyle.NONE -> R.string.settings_transition_none_desc
    }
)

private fun MessageTimeDisplayFormat.title(): String = AppStrings.get(
    when (this) {
        MessageTimeDisplayFormat.HUMANIZED -> R.string.settings_time_format_humanized
        MessageTimeDisplayFormat.DETAILED -> R.string.settings_time_format_detailed
    }
)

private fun MessageTimeDisplayFormat.dialogSummary(sampleTime: String): String {
    return when (this) {
        MessageTimeDisplayFormat.HUMANIZED -> AppStrings.get(R.string.settings_time_format_humanized_sample)
        MessageTimeDisplayFormat.DETAILED -> sampleTime
    }
}

private fun SpamAutoAction.title(): String = AppStrings.get(
    when (this) {
        SpamAutoAction.NONE -> R.string.settings_spam_action_none
        SpamAutoAction.MARK_READ -> R.string.settings_spam_action_mark_read
        SpamAutoAction.DELETE -> R.string.settings_spam_action_delete
    }
)

private fun SpamAutoAction.preferenceSummary(): String = AppStrings.get(
    when (this) {
        SpamAutoAction.NONE -> R.string.settings_spam_action_none_pref
        SpamAutoAction.MARK_READ -> R.string.settings_spam_action_mark_read_pref
        SpamAutoAction.DELETE -> R.string.settings_spam_action_delete_pref
    }
)

private fun SpamAutoAction.dialogSummary(): String = AppStrings.get(
    when (this) {
        SpamAutoAction.NONE -> R.string.settings_spam_action_none_dialog
        SpamAutoAction.MARK_READ -> R.string.settings_spam_action_mark_read_dialog
        SpamAutoAction.DELETE -> R.string.settings_spam_action_delete_dialog
    }
)

@Composable
private fun ResourceUpdateDetailContent(detail: ResourceUpdateDetail) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ResourceUpdateDetailLine(
            label = stringResource(R.string.settings_resource_detail_model_ver),
            value = detail.modelVersion
        )
        ResourceUpdateDetailLine(
            label = stringResource(R.string.settings_resource_detail_model_size),
            value = formatSizeBytes(detail.modelSizeBytes)
        )
        ResourceUpdateDetailLine(
            label = stringResource(R.string.settings_resource_detail_rule_ver),
            value = detail.ruleVersion
        )
        ResourceUpdateDetailLine(
            label = stringResource(R.string.settings_resource_detail_rule_size),
            value = formatSizeBytes(detail.ruleSizeBytes)
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.settings_resource_detail_notes),
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
    val statusText = stringResource(
        if (item.granted) {
            R.string.settings_permission_granted
        } else {
            R.string.settings_permission_grant
        }
    )
    Preference(
        title = { Text(item.title) },
        summary = { Text("$statusText · ${item.summary}") },
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
                title = context.getString(R.string.settings_permission_phone_title),
                summary = context.getString(R.string.settings_permission_phone_summary),
                permissions = listOf(Manifest.permission.READ_PHONE_STATE)
            )
        )
        add(
            context.permissionItem(
                key = "sms",
                title = context.getString(R.string.settings_permission_sms_title),
                summary = context.getString(R.string.settings_permission_sms_summary),
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
                title = context.getString(R.string.settings_permission_contacts_title),
                summary = context.getString(R.string.settings_permission_contacts_summary),
                permissions = listOf(Manifest.permission.READ_CONTACTS)
            )
        )
        add(
            context.permissionItem(
                key = "notifications",
                title = context.getString(R.string.settings_permission_notifications_title),
                summary = context.getString(R.string.settings_permission_notifications_summary),
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
    val missingPermissions = permissions.filterNot { isPermissionGranted(it) }
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
    if (sizeBytes == null || sizeBytes < 0) return AppStrings.get(R.string.settings_size_not_provided)
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
        version == "builtin" -> AppStrings.get(R.string.settings_version_builtin)
        version.startsWith("builtin(") ->
            AppStrings.get(R.string.settings_version_builtin_with_suffix, version.removePrefix("builtin"))
        else -> version
    }

@Composable
private fun AppLanguage.title(): String = stringResource(
    when (this) {
        AppLanguage.SYSTEM -> R.string.settings_app_language_system
        AppLanguage.ENGLISH -> R.string.settings_app_language_english
        AppLanguage.SIMPLIFIED_CHINESE -> R.string.settings_app_language_chinese
    }
)
