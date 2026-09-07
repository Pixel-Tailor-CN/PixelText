package vip.mystery0.pixel.text

import android.content.Intent
import android.os.Bundle
import android.provider.Telephony
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import vip.mystery0.pixel.text.ui.screen.ConversationDetailScreen
import vip.mystery0.pixel.text.ui.theme.PixelTextTheme

class ComposeSmsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val address = parseAddress(intent)
        val body = parseBody(intent)

        setContent {
            PixelTextTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var targetAddress by rememberSaveable { mutableStateOf(address) }
                    var threadId by remember(targetAddress) { mutableStateOf<Long?>(null) }
                    LaunchedEffect(targetAddress) {
                        if (targetAddress.isNotBlank()) {
                            threadId = try {
                                withContext(Dispatchers.IO) { getThreadIdForAddress(targetAddress) }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                // 权限或 Provider 暂不可用时仍保留按号码编辑和发送的入口。
                                -1L
                            }
                        }
                    }

                    var openedMmsId by rememberSaveable { mutableStateOf<Long?>(null) }
                    var openedPartId by rememberSaveable { mutableStateOf<Long?>(null) }
                    // 只保存身份与上一层来源，系统重建后仍可逐层返回。
                    var partFromDetail by rememberSaveable { mutableStateOf(false) }
                    val backFromMms: () -> Unit = {
                        if (openedPartId != null && openedPartId!! >= 0 && partFromDetail) {
                            openedPartId = -1L
                            partFromDetail = false
                        } else {
                            openedMmsId = null
                            openedPartId = null
                            partFromDetail = false
                        }
                    }
                    val mmsId = openedMmsId
                    val partId = openedPartId
                    if (mmsId != null && partId != null) {
                        androidx.activity.compose.BackHandler(onBack = backFromMms)
                        val key = vip.mystery0.pixel.text.domain.model.mirror.SourceMessageKey(
                            vip.mystery0.pixel.text.domain.model.mirror.MessageTransport.MMS, mmsId)
                        if (partId < 0) vip.mystery0.pixel.text.ui.screen.MirrorMessageDetailScreen(key,
                            onBack = backFromMms,
                            onOpenPart = { partFromDetail = true; openedPartId = it.partId })
                        else vip.mystery0.pixel.text.ui.screen.MmsPartScreen(
                            vip.mystery0.pixel.text.domain.model.mms.MmsPartKey(key, partId),
                            onBack = backFromMms)
                    } else if (targetAddress.isBlank()) {
                        RecipientEntryScreen(
                            onNavigateBack = { finish() },
                            onRecipientConfirmed = { targetAddress = it }
                        )
                    } else if (threadId == null) {
                        // 身份未解析时先等待，避免稍后切换线程重置用户刚输入的草稿。
                        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                            androidx.compose.material3.CircularProgressIndicator()
                        }
                    } else {
                        ConversationDetailScreen(
                            threadId = threadId ?: -1L,
                            address = targetAddress,
                            initialMessageText = body,
                            onOpenMmsPart = { partFromDetail = false; openedMmsId = it.message.sourceId; openedPartId = it.partId },
                            onNavigateBack = { finish() }
                        )
                    }
                }
            }
        }
    }

    private fun parseAddress(intent: Intent): String {
        return intent.data
            ?.schemeSpecificPart
            ?.substringBefore('?')
            ?.trim()
            .orEmpty()
    }

    private fun parseBody(intent: Intent): String {
        intent.getStringExtra(Intent.EXTRA_TEXT)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        intent.getStringExtra("sms_body")
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        return runCatching {
            intent.data?.getQueryParameter("body").orEmpty()
        }.getOrDefault("")
    }

    private fun getThreadIdForAddress(address: String): Long {
        // 系统按完整收件人集合解析规范线程；纯 MMS 也可找到，不会借用包含该号码的群聊。
        return Telephony.Threads.getOrCreateThreadId(this, setOf(address))
    }
}

@Composable
private fun RecipientEntryScreen(
    onNavigateBack: () -> Unit,
    onRecipientConfirmed: (String) -> Unit,
) {
    var recipient by rememberSaveable { mutableStateOf("") }
    val isValidRecipient = recipient.isNotBlank() && recipient.matches(Regex("^[0-9+\\s-]+$"))

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("选择收件人") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            TextField(
                value = recipient,
                onValueChange = { recipient = it },
                placeholder = { Text("输入电话号码") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Phone,
                    imeAction = ImeAction.Done
                ),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                    unfocusedIndicatorColor = MaterialTheme.colorScheme.outline
                )
            )
            Button(
                onClick = { onRecipientConfirmed(recipient.trim()) },
                enabled = isValidRecipient,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("下一步")
            }
        }
    }
}
