package vip.mystery0.pixel.text.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import android.text.format.DateFormat
import java.util.Date
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.platform.LocalContext
import vip.mystery0.pixel.text.util.SimInfoProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.koin.androidx.compose.koinViewModel
import vip.mystery0.pixel.text.R
import vip.mystery0.pixel.text.domain.model.mirror.SourceMessageKey
import vip.mystery0.pixel.text.ui.message.MirrorSyncBanner
import vip.mystery0.pixel.text.viewmodel.MirrorMessageDetailViewModel

@Composable
fun MirrorMessageDetailScreen(key: SourceMessageKey, onBack: () -> Unit,
    onOpenPart: (vip.mystery0.pixel.text.domain.model.mms.MmsPartKey) -> Unit = {}) {
    val contentViewModel = koinViewModel<vip.mystery0.pixel.text.viewmodel.MmsContentViewModel>()
    val content by contentViewModel.content.collectAsState()
    LaunchedEffect(key) { if (key.transport == vip.mystery0.pixel.text.domain.model.mirror.MessageTransport.MMS) contentViewModel.load(key) }
    vip.mystery0.pixel.text.ui.message.mms.MmsPlaybackSession(org.koin.compose.koinInject())
    val viewModel = koinViewModel<MirrorMessageDetailViewModel>()
    val context = LocalContext.current
    val message by viewModel.message.collectAsState()
    val loaded by viewModel.loaded.collectAsState()
    LaunchedEffect(key) { viewModel.load(key) }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.conv_message_detail_title)) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.conv_cd_back)) }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            MirrorSyncBanner()
            val current = message
            if (current == null) {
                Text(
                    stringResource(if (loaded) R.string.conv_message_not_found else R.string.conv_message_loading),
                    Modifier.padding(16.dp),
                )
            } else {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    val incoming = current.boxType == 1
                    val primary = current.address?.takeIf { it.isNotBlank() }
                        ?: current.addresses.firstOrNull { it.type == if (incoming) 137 else 151 }?.address
                    // 号码、时间和短信正文支持长按自由选择复制；彩信正文含附件手势，不包裹选择容器。
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(primary ?: stringResource(R.string.conv_unknown_number), style = MaterialTheme.typography.titleLarge)
                            Text(listOfNotNull(
                                stringResource(if (incoming) R.string.conv_message_received else R.string.conv_message_sent),
                                current.timestamp?.let { DateFormat.format("yyyy-MM-dd HH:mm", Date(it)).toString() },
                                current.subscriptionId?.takeIf { it >= 0 }?.let { id ->
                                    SimInfoProvider.getActiveSimList(context).firstOrNull { it.subscriptionId == id }?.let { sim ->
                                        if (sim.slotIndex >= 0) {
                                            stringResource(R.string.conv_sim_slot_with_name, sim.slotIndex + 1, sim.displayName)
                                        } else {
                                            stringResource(R.string.conv_sim_with_name, sim.displayName)
                                        }
                                    }
                                        ?: stringResource(R.string.conv_sim_unavailable)
                                },
                            ).joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val separator = stringResource(R.string.conv_addr_separator)
                            listOf(
                                137 to stringResource(R.string.conv_addr_from),
                                151 to stringResource(R.string.conv_addr_to),
                                130 to stringResource(R.string.conv_addr_cc),
                                129 to stringResource(R.string.conv_addr_bcc),
                            ).forEach { (type, label) ->
                                val addresses = current.addresses.filter { it.type == type }.mapNotNull { it.address }
                                    .filter { it.isNotBlank() && it != primary && it != "insert-address-token" }.distinct()
                                if (addresses.isNotEmpty()) Text(
                                    stringResource(R.string.conv_addr_line, label, addresses.joinToString(separator)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    if (key.transport == vip.mystery0.pixel.text.domain.model.mirror.MessageTransport.MMS) {
                        content?.let { vip.mystery0.pixel.text.ui.message.mms.MmsContent(it, isSelected = false, interactionEnabled = true, onOpenPart, detailMode = true) }
                            ?: Text(stringResource(R.string.conv_mms_loading))
                    } else current.body?.let { SelectionContainer { Text(it) } }

                }
            }
        }
    }
}
