package vip.mystery0.pixel.text.ui.screen

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import me.zhanghai.compose.preference.Preference
import me.zhanghai.compose.preference.ProvidePreferenceTheme
import me.zhanghai.compose.preference.SwitchPreference
import me.zhanghai.compose.preference.preferenceCategory
import org.koin.androidx.compose.koinViewModel
import vip.mystery0.pixel.text.BuildConfig
import vip.mystery0.pixel.text.R
import vip.mystery0.pixel.text.ui.createDefaultSmsAppRequestIntent
import vip.mystery0.pixel.text.ui.isDefaultSmsApp
import vip.mystery0.pixel.text.util.enableDebugMode
import vip.mystery0.pixel.text.util.isDebugModeEnabled
import vip.mystery0.pixel.text.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = koinViewModel(),
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsState()
    var permissionRefreshKey by remember { mutableIntStateOf(0) }
    var pendingPermissionRequest by remember { mutableStateOf<List<String>>(emptyList()) }
    var pendingPermissionDialogItem by remember { mutableStateOf<PermissionItem?>(null) }
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
                            key = "category_app_features",
                            title = { Text("应用功能") }
                        )
                        item(key = "offline_model_version", contentType = "Preference") {
                            Preference(
                                title = { Text("离线模型版本") },
                                summary = { Text("内置") },
                                enabled = false,
                                icon = {
                                    Icon(Icons.Rounded.CloudOff, contentDescription = null)
                                }
                            )
                        }
                        item(key = "smart_card_rule_version", contentType = "Preference") {
                            Preference(
                                title = { Text("智能卡片规则版本") },
                                summary = { Text("内置") },
                                enabled = false,
                                icon = {
                                    Icon(Icons.Rounded.Style, contentDescription = null)
                                }
                            )
                        }
                        item(key = "spam_detection", contentType = "SwitchPreference") {
                            SwitchPreference(
                                value = settings.spamDetectionEnabled,
                                onValueChange = viewModel::setSpamDetectionEnabled,
                                title = { Text("骚扰短信识别") },
                                summary = {
                                    Text("接收新短信和扫描历史短信时调用本地模型识别骚扰内容")
                                },
                                icon = {
                                    Icon(Icons.Rounded.Security, contentDescription = null)
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
                                    Icon(Icons.Rounded.Security, contentDescription = null)
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
                                    Icon(Icons.Rounded.Settings, contentDescription = null)
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
                                    Icon(Icons.Rounded.Notifications, contentDescription = null)
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

private const val WRITE_SMS_PERMISSION = "android.permission.WRITE_SMS"
private const val DEBUG_MODE_ENABLE_TAP_COUNT = 6
