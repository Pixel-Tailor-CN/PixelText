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
                    val threadId = remember(targetAddress) {
                        if (targetAddress.isBlank()) -1L else getThreadIdForAddress(targetAddress)
                    }

                    if (targetAddress.isBlank()) {
                        RecipientEntryScreen(
                            onNavigateBack = { finish() },
                            onRecipientConfirmed = { targetAddress = it }
                        )
                    } else {
                        ConversationDetailScreen(
                            threadId = threadId,
                            address = targetAddress,
                            initialMessageText = body,
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
        contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.THREAD_ID),
            "${Telephony.Sms.ADDRESS} = ?",
            arrayOf(address),
            "${Telephony.Sms.DATE} DESC LIMIT 1"
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getLong(0)
            }
        }
        return -1L
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
