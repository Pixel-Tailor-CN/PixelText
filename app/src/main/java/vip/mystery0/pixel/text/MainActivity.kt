package vip.mystery0.pixel.text

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import vip.mystery0.pixel.text.ui.AppNavigation
import vip.mystery0.pixel.text.ui.ConversationDeepLink
import vip.mystery0.pixel.text.ui.theme.PixelTextTheme

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_THREAD_ID = "extra_thread_id"
        const val EXTRA_ADDRESS = "extra_address"
    }

    // 跨 onCreate / onNewIntent 共享的"待打开会话"状态
    private var pendingDeepLink by mutableStateOf<ConversationDeepLink?>(null)

    // Android 13+ 需要运行时请求通知权限
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            // 无论用户是否授权，应用都正常运行；未授权时只是不弹通知
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 检查并请求通知权限（Android 13+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        pendingDeepLink = parseDeepLink(intent)

        setContent {
            PixelTextTheme {
                AppNavigation(
                    pendingDeepLink = pendingDeepLink,
                    onDeepLinkConsumed = { pendingDeepLink = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 应用已在前台时，通过覆盖 intent 唤起新的 deep link
        setIntent(intent)
        parseDeepLink(intent)?.let { pendingDeepLink = it }
    }

    private fun parseDeepLink(intent: Intent?): ConversationDeepLink? {
        if (intent == null) return null
        val threadId = intent.getLongExtra(EXTRA_THREAD_ID, -1L)
        val address = intent.getStringExtra(EXTRA_ADDRESS).orEmpty()
        // threadId 为 0 表示未分配，地址为空则无法跳转
        if (threadId <= 0L || address.isBlank()) return null
        return ConversationDeepLink(threadId = threadId, address = address)
    }
}
