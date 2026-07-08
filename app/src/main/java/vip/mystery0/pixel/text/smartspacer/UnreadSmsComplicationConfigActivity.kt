package vip.mystery0.pixel.text.smartspacer

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kieronquinn.app.smartspacer.sdk.SmartspacerConstants
import com.kieronquinn.app.smartspacer.sdk.provider.SmartspacerComplicationProvider
import org.koin.android.ext.android.inject
import vip.mystery0.pixel.text.ui.theme.PixelTextTheme

class UnreadSmsComplicationConfigActivity : ComponentActivity() {
    private val settingsRepository: UnreadSmsComplicationSettingsRepository by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val smartspacerId = intent.getStringExtra(SmartspacerConstants.EXTRA_SMARTSPACER_ID)
        val initialSettings = settingsRepository.getSettings(smartspacerId)

        setContent {
            PixelTextTheme {
                UnreadSmsComplicationConfigScreen(
                    initialSettings = initialSettings,
                    onNavigateBack = ::finish,
                    onSave = { settings ->
                        settingsRepository.setSettings(smartspacerId, settings)
                        SmartspacerComplicationProvider.notifyChange(
                            this,
                            UnreadSmsComplicationProvider::class.java,
                            smartspacerId
                        )
                        setResult(Activity.RESULT_OK)
                        finish()
                    }
                )
            }
        }
    }
}

@Composable
private fun UnreadSmsComplicationConfigScreen(
    initialSettings: UnreadSmsComplicationSettings,
    onNavigateBack: () -> Unit,
    onSave: (UnreadSmsComplicationSettings) -> Unit,
) {
    var includeNormalMessages by rememberSaveable {
        mutableStateOf(initialSettings.includeNormalMessages)
    }
    var includeSpamMessages by rememberSaveable {
        mutableStateOf(initialSettings.includeSpamMessages)
    }
    var includeArchivedMessages by rememberSaveable {
        mutableStateOf(initialSettings.includeArchivedMessages)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Smartspacer 未读统计") },
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
        containerColor = MaterialTheme.colorScheme.background,
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.size(0.dp))
            Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "控制 Smartspacer 未读数量统计哪些短信列表。",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "同一条短信即使同时命中骚扰和归档，也只会累计 1 次。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            UnreadSmsComplicationSwitchRow(
                title = "正常短信",
                summary = "统计未归档且未标记为骚扰的未读短信",
                checked = includeNormalMessages,
                onCheckedChange = { includeNormalMessages = it }
            )
            UnreadSmsComplicationSwitchRow(
                title = "骚扰短信",
                summary = "统计命中骚扰识别的未读短信",
                checked = includeSpamMessages,
                onCheckedChange = { includeSpamMessages = it }
            )
            UnreadSmsComplicationSwitchRow(
                title = "归档短信",
                summary = "统计位于归档会话中的未读短信",
                checked = includeArchivedMessages,
                onCheckedChange = { includeArchivedMessages = it }
            )
            Button(
                onClick = {
                    onSave(
                        UnreadSmsComplicationSettings(
                            includeNormalMessages = includeNormalMessages,
                            includeSpamMessages = includeSpamMessages,
                            includeArchivedMessages = includeArchivedMessages
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 14.dp)
            ) {
                Text("保存")
            }
            Text(
                text = "全部关闭后，Smartspacer 将不显示未读数量。",
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.size(8.dp))
        }
    }
}

@Composable
private fun UnreadSmsComplicationSwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(end = 72.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(
                modifier = Modifier.align(Alignment.CenterEnd),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Switch(
                    checked = checked,
                    onCheckedChange = onCheckedChange
                )
            }
        }
    }
}
