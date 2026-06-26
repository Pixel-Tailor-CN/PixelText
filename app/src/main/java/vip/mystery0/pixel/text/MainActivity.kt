package vip.mystery0.pixel.text

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import vip.mystery0.pixel.text.ui.AppNavigation
import vip.mystery0.pixel.text.ui.ConversationDeepLink
import vip.mystery0.pixel.text.ui.SettingsDeepLink
import vip.mystery0.pixel.text.ui.theme.PixelTextTheme

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_THREAD_ID = "extra_thread_id"
        const val EXTRA_ADDRESS = "extra_address"
        const val EXTRA_OPEN_SETTINGS = "extra_open_settings"
        const val EXTRA_TRIGGER_RESOURCE_UPDATE_CHECK =
            "extra_trigger_resource_update_check"
    }

    // 跨 onCreate / onNewIntent 共享的"待打开会话"状态
    private var pendingDeepLink by mutableStateOf<ConversationDeepLink?>(null)
    private var pendingSettingsDeepLink by mutableStateOf<SettingsDeepLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        pendingDeepLink = parseDeepLink(intent)
        pendingSettingsDeepLink = parseSettingsDeepLink(intent)

        setContent {
            PixelTextTheme {
                AppNavigation(
                    pendingDeepLink = pendingDeepLink,
                    onDeepLinkConsumed = { pendingDeepLink = null },
                    pendingSettingsDeepLink = pendingSettingsDeepLink,
                    onSettingsDeepLinkConsumed = { pendingSettingsDeepLink = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 应用已在前台时，通过覆盖 intent 唤起新的 deep link
        setIntent(intent)
        parseDeepLink(intent)?.let { pendingDeepLink = it }
        parseSettingsDeepLink(intent)?.let { pendingSettingsDeepLink = it }
    }

    private fun parseDeepLink(intent: Intent?): ConversationDeepLink? {
        if (intent == null) return null
        val threadId = intent.getLongExtra(EXTRA_THREAD_ID, -1L)
        val address = intent.getStringExtra(EXTRA_ADDRESS).orEmpty()
        // threadId 为 0 表示未分配，地址为空则无法跳转
        if (threadId <= 0L || address.isBlank()) return null
        return ConversationDeepLink(threadId = threadId, address = address)
    }

    private fun parseSettingsDeepLink(intent: Intent?): SettingsDeepLink? {
        if (intent?.getBooleanExtra(EXTRA_OPEN_SETTINGS, false) != true) return null
        return SettingsDeepLink(
            triggerResourceUpdateCheck =
                intent.getBooleanExtra(EXTRA_TRIGGER_RESOURCE_UPDATE_CHECK, false),
            requestId = System.nanoTime()
        )
    }
}
