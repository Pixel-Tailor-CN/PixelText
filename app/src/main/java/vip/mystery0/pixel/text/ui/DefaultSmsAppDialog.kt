package vip.mystery0.pixel.text.ui

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * 判断当前应用是否为默认短信应用。
 */
fun Context.isDefaultSmsApp(): Boolean {
    return getSystemService(RoleManager::class.java)
        .isRoleHeld(RoleManager.ROLE_SMS)
}

/**
 * 创建引导用户授予默认短信应用角色的系统 Intent。
 */
fun Context.createDefaultSmsAppRequestIntent(): Intent {
    return getSystemService(RoleManager::class.java)
        .createRequestRoleIntent(RoleManager.ROLE_SMS)
}

/**
 * 检测当前应用是否为默认短信应用，若不是则弹出引导对话框。
 *
 * 检测时机：
 * - 首次进入时检测一次（[LaunchedEffect]）
 * - 用户离开系统授权界面后结束本次引导，取消时不反复请求
 */
@Composable
fun DefaultSmsAppDialog() {
    val context = LocalContext.current

    var showDialog by remember { mutableStateOf(false) }

    // 仅在首次进入时检测一次
    LaunchedEffect(Unit) {
        showDialog = !context.isDefaultSmsApp()
    }

    val setDefaultLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        // 尊重系统选择页的取消操作，后续可从设置主动发起。
        showDialog = false
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
                    text = "设为默认应用以收发短信和彩信；暂不设置仍可查看短信记录。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDialog = false
                        setDefaultLauncher.launch(context.createDefaultSmsAppRequestIntent())
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
