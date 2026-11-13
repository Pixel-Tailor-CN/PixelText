package vip.mystery0.pixel.text.smartspacer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kieronquinn.app.smartspacer.sdk.SmartspacerConstants
import com.kieronquinn.app.smartspacer.sdk.provider.SmartspacerComplicationProvider
import org.koin.android.ext.android.inject
import vip.mystery0.pixel.text.R
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
                        setResult(RESULT_OK)
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
                title = { Text(stringResource(R.string.sys_smartspacer_unread_config_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.sys_back)
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
            UnreadSmsComplicationSwitchRow(
                title = stringResource(R.string.sys_smartspacer_normal_messages),
                checked = includeNormalMessages,
                onCheckedChange = { includeNormalMessages = it }
            )
            UnreadSmsComplicationSwitchRow(
                title = stringResource(R.string.sys_smartspacer_spam_messages),
                checked = includeSpamMessages,
                onCheckedChange = { includeSpamMessages = it }
            )
            UnreadSmsComplicationSwitchRow(
                title = stringResource(R.string.sys_smartspacer_archived_messages),
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
                Text(stringResource(R.string.sys_smartspacer_save))
            }
            if (!includeNormalMessages && !includeSpamMessages && !includeArchivedMessages) Text(
                text = stringResource(R.string.sys_smartspacer_unread_hidden),
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
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
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
            }
            Row(
                modifier = Modifier.align(Alignment.CenterEnd),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Switch(
                    checked = checked,
                    onCheckedChange = null,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
        }
    }
}
