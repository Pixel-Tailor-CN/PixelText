package vip.mystery0.pixel.text.ui.message.mms

import androidx.core.net.toUri

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import vip.mystery0.pixel.text.R
import vip.mystery0.pixel.text.domain.model.mirror.MirrorAttachmentState
import vip.mystery0.pixel.text.domain.model.mms.MmsContactModel
import vip.mystery0.pixel.text.domain.model.mms.MmsPartContent
import vip.mystery0.pixel.text.ui.message.cards.smartCardContainerColor

@Composable
fun MmsContactCard(
    part: MmsPartContent,
    modifier: Modifier = Modifier,
    parts: List<MmsPartContent> = emptyList(),
    isSelected: Boolean = false,
    selectionMode: Boolean = false,
    interactionEnabled: Boolean = true,
    onMessageClick: () -> Unit = {},
    onFeedback: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val feedback: (String) -> Unit = { onFeedback?.invoke(it) ?: Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    val enabled = interactionEnabled && !selectionMode
    if (part.contacts.isEmpty()) {
        MmsFileCard(part, modifier, isSelected, selectionMode, interactionEnabled, onMessageClick, onFeedback = onFeedback)
        return
    }
    val baseColor = MaterialTheme.colorScheme.primary
    val accent = if (isSelected) MaterialTheme.colorScheme.inverseOnSurface else baseColor
    val container = if (isSelected) MaterialTheme.colorScheme.inverseSurface else smartCardContainerColor(baseColor)
    val foreground = if (isSelected) MaterialTheme.colorScheme.inverseOnSurface else MaterialTheme.colorScheme.onSurface
    val secondary = if (isSelected) foreground.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        part.contacts.forEachIndexed { index, contact ->
            var expanded by rememberSaveable(part.key.toString(), part.contentHash, index) { mutableStateOf(false) }
            val photo = contact.photoBytes ?: parts.singleOrNull {
                it.key.message == part.key.message && it.key.partId == contact.photoPartId
            }?.localUri?.takeIf { it.toUri().scheme in setOf("file", "content") }
            Surface(
                modifier = Modifier.fillMaxWidth().clickable(enabled = interactionEnabled && selectionMode, onClick = onMessageClick),
                shape = RoundedCornerShape(16.dp), color = container, contentColor = foreground,
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.msg_mms_contact_name_label), style = MaterialTheme.typography.bodyMedium, color = secondary)
                            Spacer(Modifier.height(4.dp))
                            Text(contact.name ?: stringResource(R.string.msg_mms_contact_unnamed),
                                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold), color = accent)
                        }
                        if (photo != null) Surface(shape = CircleShape,
                            color = if (isSelected) foreground.copy(alpha = 0.1f) else MaterialTheme.colorScheme.primaryContainer) {
                            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Person, contentDescription = null, tint = accent)
                                AsyncImage(model = ImageRequest.Builder(context).data(photo).size(96).build(),
                                    contentDescription = stringResource(R.string.msg_mms_contact_photo_cd),
                                    modifier = Modifier.size(48.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                            }
                        }
                        MmsAttachmentActions(part, enabled = enabled, onFeedback = onFeedback, menuOnly = true)
                    }
                    contact.phones.firstOrNull()?.let { phone ->
                        Column {
                            Text(phone.label.ifBlank { stringResource(R.string.msg_mms_contact_phone_label) }, style = MaterialTheme.typography.bodyMedium, color = secondary)
                            ContactLink(phone.value, accent, enabled, stringResource(R.string.msg_mms_contact_dial_label),
                                stringResource(R.string.msg_mms_contact_copy_number_label),
                                style = MaterialTheme.typography.displaySmallEmphasized.copy(fontSize = 26.sp, fontWeight = FontWeight.SemiBold),
                                onCopy = { copyContactText(context, phone.value, feedback) }, onOpen = {
                                    launchMmsStructuredIntent(context, Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phone.value, null)), feedback)
                                })
                        }
                    }
                    if (contact.organization != null || contact.phones.size > 1 || contact.emails.isNotEmpty()) {
                        Surface(shape = RoundedCornerShape(12.dp), color = accent.copy(alpha = 0.08f)) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                contact.organization?.let {
                                    ContactDetailField(stringResource(R.string.msg_mms_contact_company_label), it, secondary, foreground)
                                }
                                contact.phones.drop(1).forEach { phone ->
                                    ContactValueRow(phone.label.ifBlank { stringResource(R.string.msg_mms_contact_other_phone_label) }, phone.value, secondary, accent,
                                        enabled, false, { copyContactText(context, phone.value, feedback) }) {
                                        launchMmsStructuredIntent(context, Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phone.value, null)), feedback)
                                    }
                                }
                                contact.emails.forEach { email ->
                                    val emailLabel = email.label.takeIf { it.isNotBlank() }?.let {
                                        stringResource(R.string.msg_mms_contact_email_with_label, it)
                                    } ?: stringResource(R.string.msg_mms_contact_email_label)
                                    ContactValueRow(emailLabel,
                                        email.value, secondary, accent, enabled, true,
                                        { copyContactText(context, email.value, feedback) }) {
                                        launchMmsStructuredIntent(context, Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", email.value, null)), feedback)
                                    }
                                }
                            }
                        }
                    }
                    if (contact.addresses.isNotEmpty() || contact.title != null || contact.notes != null) {
                        Column {
                            TextButton(enabled = enabled, onClick = { expanded = !expanded }) {
                                Text(
                                    stringResource(
                                        if (expanded) R.string.msg_mms_contact_collapse_details
                                        else R.string.msg_mms_contact_expand_details,
                                    ),
                                    color = if (enabled) accent else secondary,
                                )
                            }
                            AnimatedVisibility(expanded) {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    contact.addresses.forEach {
                                        ContactDetailField(stringResource(R.string.msg_mms_contact_address_label), it.value, secondary, foreground)
                                    }
                                    contact.title?.let {
                                        ContactDetailField(stringResource(R.string.msg_mms_contact_title_label), it, secondary, foreground)
                                    }
                                    contact.notes?.let {
                                        ContactDetailField(stringResource(R.string.msg_mms_contact_notes_label), it, secondary, foreground)
                                    }
                                }
                            }
                        }
                    }
                    if (part.state != MirrorAttachmentState.READY || part.issue != null || part.inlineTextCopy) MmsPartStatus(part)
                    contact.importWarning?.let { Text(it, color = if (isSelected) foreground else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall) }
                    if (photo != null) Text(stringResource(R.string.msg_mms_contact_photo_warning),
                        color = secondary, style = MaterialTheme.typography.bodySmall)
                    Column {
                        Button(enabled = enabled && contact.hasFields(), onClick = {
                            launchMmsStructuredIntent(context, contactInsertIntent(contact), feedback)
                        }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = container)) {
                            Text(stringResource(R.string.msg_mms_contact_add_btn))
                        }
                        TextButton(enabled = enabled && contact.hasFields(), modifier = Modifier.align(Alignment.CenterHorizontally), onClick = {
                            copyContactText(context, listOfNotNull(contact.name, contact.organization, contact.title,
                                contact.phones.joinToString("\n") { it.value }, contact.emails.joinToString("\n") { it.value },
                                contact.addresses.joinToString("\n") { it.value }, contact.notes).filter(String::isNotBlank).joinToString("\n"), feedback)
                        }) { Text(stringResource(R.string.msg_mms_contact_copy_btn), color = if (enabled) accent else secondary) }
                    }
                }
            }
        }
    }
}

/** 按字段纵向排版，长邮箱和地址不会挤压标签。 */
@Composable
private fun ContactDetailField(label: String, value: String, labelColor: Color, valueColor: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = labelColor)
        Text(value, style = MaterialTheme.typography.bodyMediumEmphasized, color = valueColor)
    }
}

@Composable
private fun ContactValueRow(label: String, value: String, labelColor: Color,
    accent: Color, enabled: Boolean, email: Boolean, onCopy: () -> Unit, onOpen: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = labelColor)
        ContactLink(
            value,
            accent,
            enabled,
            stringResource(if (email) R.string.msg_mms_contact_send_email_label else R.string.msg_mms_contact_dial_label),
            stringResource(if (email) R.string.msg_mms_contact_copy_email_label else R.string.msg_mms_contact_copy_number_label),
            onCopy = onCopy,
            onOpen = onOpen,
        )
    }
}

/** 链接手势由子项处理，避免长按复制同时触发外层消息选择。 */
internal class MmsLinkGestureState { var active = false }
internal val LocalMmsLinkGesture = staticCompositionLocalOf<MmsLinkGestureState?> { null }

@Composable
private fun ContactLink(value: String, color: Color, enabled: Boolean, openLabel: String, copyLabel: String,
    style: TextStyle = MaterialTheme.typography.bodyMediumEmphasized, onCopy: () -> Unit, onOpen: () -> Unit) {
    val gesture = LocalMmsLinkGesture.current
    Text(value, color = color, style = style, textDecoration = TextDecoration.Underline,
        modifier = Modifier.pointerInput(enabled, gesture) {
            if (enabled && gesture != null) awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                gesture.active = true
                try {
                    do { val event = awaitPointerEvent(PointerEventPass.Final) }
                    while (event.changes.any { it.pressed })
                } finally { gesture.active = false }
            }
        }.combinedClickable(enabled = enabled, onClickLabel = openLabel, onLongClickLabel = copyLabel,
            onClick = onOpen, onLongClick = onCopy))
}

private fun MmsContactModel.hasFields() = name != null || organization != null || phones.isNotEmpty() || emails.isNotEmpty() || addresses.isNotEmpty() || title != null || notes != null

private fun copyContactText(context: Context, value: String, feedback: (String) -> Unit) {
    // SDK 的 getter 可空而 setter 非空，Kotlin 无法使用属性赋值。
    @Suppress("UsePropertyAccessSyntax")
    context.getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText(context.getString(R.string.msg_mms_contact_clip_label), value))
    feedback(context.getString(R.string.msg_mms_copied))
}
