package vip.mystery0.pixel.text.ui.message.mms

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import vip.mystery0.pixel.text.domain.model.mms.MmsContactModel
import vip.mystery0.pixel.text.domain.model.mms.MmsPartContent

@Composable
fun MmsContactCard(
    part: MmsPartContent,
    parts: List<MmsPartContent> = emptyList(),
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    selectionMode: Boolean = false,
    interactionEnabled: Boolean = true,
    onMessageClick: () -> Unit = {},
    onFeedback: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val feedback: (String) -> Unit = { onFeedback?.invoke(it) ?: Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    val enabled = interactionEnabled && !selectionMode
    Card(modifier.fillMaxWidth().clickable(enabled = interactionEnabled && selectionMode, onClick = onMessageClick),
        colors = CardDefaults.cardColors(containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("联系人名片 · ${part.displayName}", style = MaterialTheme.typography.labelLarge)
            MmsPartStatus(part)
            part.contacts.forEachIndexed { index, contact ->
                var expanded by rememberSaveable(part.key.toString(), part.contentHash, index) { mutableStateOf(false) }
                val photo = contact.photoBytes ?: parts.singleOrNull { it.key.message == part.key.message && it.key.partId == contact.photoPartId }
                    ?.localUri?.takeIf { Uri.parse(it).scheme in setOf("file", "content") }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            Text(contact.name?.take(1) ?: "人", style = MaterialTheme.typography.titleLarge)
                            if (photo != null) AsyncImage(model = ImageRequest.Builder(context).data(photo).size(96).build(),
                                contentDescription = "联系人照片", modifier = Modifier.size(48.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(contact.name ?: "未命名联系人", style = MaterialTheme.typography.titleMedium)
                        contact.organization?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
                contact.phones.forEach { phone ->
                    Text(phone.value + phone.label.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty())
                    Row {
                        TextButton(enabled = enabled, onClick = {
                            launchMmsStructuredIntent(context, Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phone.value, null)), feedback)
                        }) { Text("拨号") }
                        TextButton(enabled = enabled, onClick = { copyContactText(context, phone.value, feedback) }) { Text("复制号码") }
                    }
                }
                contact.emails.forEach { email ->
                    Text(email.value + email.label.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty())
                    Row {
                        TextButton(enabled = enabled, onClick = {
                            launchMmsStructuredIntent(context, Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", email.value, null)), feedback)
                        }) { Text("邮件") }
                        TextButton(enabled = enabled, onClick = { copyContactText(context, email.value, feedback) }) { Text("复制邮箱") }
                    }
                }
                if (contact.addresses.isNotEmpty() || contact.title != null || contact.notes != null) {
                    TextButton(enabled = enabled, onClick = { expanded = !expanded }) { Text(if (expanded) "收起详情" else "地址、职位与备注") }
                    AnimatedVisibility(expanded) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            contact.addresses.forEach { Text("地址：${it.value}") }
                            contact.title?.let { Text("职位：$it") }
                            contact.notes?.let { Text("备注：$it") }
                        }
                    }
                }
                contact.importWarning?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (photo != null) Text("系统添加不携带照片；完整名片可打开原件导入", style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(enabled = enabled && contact.hasFields(), onClick = {
                        launchMmsStructuredIntent(context, contactInsertIntent(contact), feedback)
                    }) { Text("添加联系人") }
                    TextButton(enabled = enabled && contact.hasFields(), onClick = {
                        copyContactText(context, listOfNotNull(contact.name, contact.organization, contact.title,
                            contact.phones.joinToString("\n") { it.value }, contact.emails.joinToString("\n") { it.value },
                            contact.addresses.joinToString("\n") { it.value }, contact.notes).filter(String::isNotBlank).joinToString("\n"), feedback)
                    }) { Text("复制名片") }
                }
            }
            MmsAttachmentActions(part, enabled = enabled, onFeedback = onFeedback)
        }
    }
}

private fun MmsContactModel.hasFields() = name != null || organization != null || phones.isNotEmpty() || emails.isNotEmpty() || addresses.isNotEmpty() || title != null || notes != null

private fun copyContactText(context: Context, value: String, feedback: (String) -> Unit) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("联系人", value))
    feedback("已复制")
}
