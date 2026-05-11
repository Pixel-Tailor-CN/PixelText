package vip.mystery0.pixel.text.ui

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.provider.Telephony
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * 判断当前应用是否为默认短信应用。
 *
 * Android Q+ 使用 [RoleManager.isRoleHeld]（Role 系统的权威 API）；
 * 低版本回退到 [Telephony.Sms.getDefaultSmsPackage]。
 */
private fun isDefaultSmsApp(context: Context): Boolean {
    return context.getSystemService(RoleManager::class.java)
        .isRoleHeld(RoleManager.ROLE_SMS)
}

/**
 * 检测当前应用是否为默认短信应用，若不是则弹出引导对话框。
 *
 * 检测时机：
 * - 首次进入时检测一次（[LaunchedEffect]）
 * - 用户从系统授权界面返回时，通过 [ActivityResultContracts] 回调再次检测
 */
@Composable
fun DefaultSmsAppDialog() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showDialog by remember { mutableStateOf(false) }

    // 仅在首次进入时检测一次
    LaunchedEffect(Unit) {
        showDialog = !isDefaultSmsApp(context)
    }

    val setDefaultLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // 用户在系统界面确认授权 → 直接关闭对话框
            showDialog = false
        } else {
            // 用户取消或未授权 → 延迟 500ms 后重新检测
            scope.launch {
                delay(500.milliseconds)
                showDialog = !isDefaultSmsApp(context)
            }
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.Message,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            title = {
                Text(text = "设为默认短信应用")
            },
            text = {
                Text(
                    text = "将 PixelText 设为默认短信应用后，才能接收短信通知、发送短信和彩信。\n\n不设置也可以正常读取短信记录。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val intent = context.getSystemService(RoleManager::class.java)
                            .createRequestRoleIntent(RoleManager.ROLE_SMS)
                        setDefaultLauncher.launch(intent)
                    }
                ) {
                    Text("去设置")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("稍后")
                }
            },
        )
    }
}
