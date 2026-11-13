package vip.mystery0.pixel.text.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import vip.mystery0.pixel.text.R

/** 规则条目以编辑为主要操作，删除仅在主动打开菜单时展示。 */
@Composable
fun RuleItemCard(
    title: String,
    subtitle: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    enabled: Boolean = true,
    titleMaxLines: Int = Int.MAX_VALUE,
    editDescription: String? = null,
    deleteDescription: String? = null,
) {
    val resolvedEditDescription = editDescription ?: stringResource(R.string.settings_rule_edit_cd, title)
    val resolvedDeleteDescription = deleteDescription ?: stringResource(R.string.settings_action_delete)
    var showMenu by remember { mutableStateOf(false) }
    Card(
        onClick = onEdit,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = resolvedEditDescription },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .padding(start = 18.dp, top = 14.dp, bottom = 14.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = titleMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Box {
                IconButton(onClick = { showMenu = true }, enabled = enabled) {
                    Icon(
                        Icons.Rounded.MoreVert,
                        contentDescription = stringResource(R.string.settings_rule_options_cd, title),
                    )
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.settings_action_delete), color = MaterialTheme.colorScheme.error) },
                        modifier = Modifier.semantics { contentDescription = resolvedDeleteDescription },
                        enabled = enabled,
                        onClick = { showMenu = false; onDelete() },
                    )
                }
            }
        }
    }
}
