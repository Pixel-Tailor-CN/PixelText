package vip.mystery0.pixel.text.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.zhanghai.compose.preference.Preference
import me.zhanghai.compose.preference.ProvidePreferenceTheme
import me.zhanghai.compose.preference.SwitchPreference
import me.zhanghai.compose.preference.preferenceCategory
import org.koin.androidx.compose.koinViewModel
import vip.mystery0.pixel.text.BuildConfig
import vip.mystery0.pixel.text.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = koinViewModel(),
    onNavigateBack: () -> Unit,
) {
    val settings by viewModel.settings.collectAsState()

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
                            key = "category_features",
                            title = { Text("功能开关") }
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
                                    Icon(Icons.Rounded.Info, contentDescription = null)
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
}
