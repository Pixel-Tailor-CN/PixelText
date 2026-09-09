package vip.mystery0.pixel.text.ui.screen.mock

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.koin.compose.koinInject
import vip.mystery0.pixel.text.domain.model.mirror.MessageTransport
import vip.mystery0.pixel.text.domain.model.mirror.SourceMessageKey
import vip.mystery0.pixel.text.domain.model.mms.MmsContentKind
import vip.mystery0.pixel.text.domain.model.mms.MmsContentModel
import vip.mystery0.pixel.text.domain.repository.MmsContentRepository
import vip.mystery0.pixel.text.ui.message.mms.MmsFileCard
import vip.mystery0.pixel.text.ui.message.mms.MmsImageContent
import vip.mystery0.pixel.text.ui.message.mms.MmsImageViewer
import vip.mystery0.pixel.text.ui.message.mms.MmsMediaCard
import vip.mystery0.pixel.text.ui.message.mms.MmsMediaViewer
import vip.mystery0.pixel.text.ui.message.mms.MmsHtmlCard
import vip.mystery0.pixel.text.ui.message.mms.MmsHtmlViewer
import vip.mystery0.pixel.text.ui.message.mms.MmsContactCard
import vip.mystery0.pixel.text.ui.message.mms.MmsCalendarCard

/** 现有 Mock 页的本地消息入口，复用正式组件，不生成或修改消息。 */
@Composable
fun MockMmsContentScreen(onBack: () -> Unit, repository: MmsContentRepository = koinInject()) {
    var input by rememberSaveable { mutableStateOf("") }
    var messageId by rememberSaveable { mutableStateOf<Long?>(null) }
    var openedPartId by rememberSaveable { mutableStateOf<Long?>(null) }
    val model by produceState<MmsContentModel?>(null, messageId) {
        value = null
        messageId?.let { id -> repository.observe(SourceMessageKey(MessageTransport.MMS, id)).collect { value = it } }
    }
    val opened = model?.parts?.firstOrNull { it.key.partId == openedPartId }
    if (opened != null) {
        when (opened.kind) {
            MmsContentKind.IMAGE -> MmsImageViewer(opened, onBack = { openedPartId = null })
            MmsContentKind.AUDIO, MmsContentKind.VIDEO -> MmsMediaViewer(opened, onBack = { openedPartId = null })
            MmsContentKind.HTML -> MmsHtmlViewer(opened, model?.parts.orEmpty(), onBack = { openedPartId = null })
            else -> MmsFileCard(opened)
        }
        return
    }
    BackHandler(onBack = onBack)
    Scaffold(topBar = { TopAppBar(title = { Text("本地彩信预览") }, navigationIcon = {
        TextButton(onClick = onBack) { Text("返回") }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(input, onValueChange = { input = it.filter(Char::isDigit) }, label = { Text("MMS 消息 ID") },
                    modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Button(onClick = { openedPartId = null; messageId = input.toLongOrNull() }, enabled = input.toLongOrNull() != null) { Text("加载") }
            }
            Text(model?.subject ?: if (messageId == null) "输入已同步到镜像的彩信 ID" else "消息不存在或正在读取")
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(model?.parts.orEmpty(), key = { it.key.partId }) { part ->
                    when (part.kind) {
                        MmsContentKind.IMAGE -> MmsImageContent(part, onOpen = { openedPartId = part.key.partId })
                        MmsContentKind.AUDIO, MmsContentKind.VIDEO -> MmsMediaCard(part, onOpenPart = { openedPartId = it.partId })
                        MmsContentKind.HTML -> MmsHtmlCard(part, onOpenPart = { openedPartId = it.partId })
                        MmsContentKind.CONTACT -> MmsContactCard(part, parts = model?.parts.orEmpty())
                        MmsContentKind.CALENDAR -> MmsCalendarCard(part)
                        else -> MmsFileCard(part)
                    }
                }
            }
        }
    }
}
