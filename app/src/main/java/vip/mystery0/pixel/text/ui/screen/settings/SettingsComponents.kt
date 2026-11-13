package vip.mystery0.pixel.text.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import vip.mystery0.pixel.text.R

/** 分类页的分组与条目；稳定键同时用于滚动定位和状态恢复。 */
data class SettingsSection(val key: String, val title: String?, val rows: List<SettingsEntry>)
data class SettingsEntry(val key: String, val content: @Composable () -> Unit)

@Composable
fun SettingsRowContainer(first: Boolean, last: Boolean, active: Boolean = false, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(
            topStart = if (first) 24.dp else 4.dp,
            topEnd = if (first) 24.dp else 4.dp,
            bottomStart = if (last) 24.dp else 4.dp,
            bottomEnd = if (last) 24.dp else 4.dp,
        )).background(if (active) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow),
    ) { content() }
}

@Composable
fun SettingsPreference(
    title: @Composable () -> Unit,
    summary: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    external: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    SettingsRow(
        modifier = if (onClick == null) Modifier else Modifier.clickable(
            enabled = enabled, role = Role.Button, onClick = onClick,
        ),
        title = title, summary = summary, icon = icon, enabled = enabled,
        trailing = trailing ?: if (onClick != null) {
            {
                Icon(
                    if (external) Icons.AutoMirrored.Rounded.OpenInNew
                    else Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = if (external) stringResource(R.string.settings_open_external_cd) else null,
                    modifier = Modifier.alpha(if (enabled) 1f else 0.38f),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else null,
    )
}

@Composable
fun SettingsSwitchPreference(
    value: Boolean,
    onValueChange: (Boolean) -> Unit,
    title: @Composable () -> Unit,
    summary: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) {
    SettingsRow(
        modifier = Modifier.toggleable(
            value = value, enabled = enabled, role = Role.Switch, onValueChange = onValueChange,
        ),
        title = title, summary = summary, icon = icon, enabled = enabled,
        trailing = {
            // 整行提供唯一的开关语义，避免 TalkBack 重复朗读或切换两次。
            Switch(value, onCheckedChange = null, enabled = enabled,
                modifier = Modifier.clearAndSetSemantics {})
        },
    )
}

@Composable
private fun SettingsRow(
    modifier: Modifier,
    title: @Composable () -> Unit,
    summary: (@Composable () -> Unit)?,
    icon: (@Composable () -> Unit)?,
    enabled: Boolean,
    trailing: (@Composable () -> Unit)?,
) {
    Row(
        modifier.fillMaxWidth().defaultMinSize(minHeight = if (summary == null) 56.dp else 72.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (icon != null) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                Box(Modifier.size(32.dp).alpha(if (enabled) 1f else 0.38f), contentAlignment = Alignment.Center) {
                    icon()
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface.copy(
                alpha = if (enabled) 1f else 0.6f,
            )) {
                ProvideTextStyle(MaterialTheme.typography.bodyLarge, title)
            }
            if (summary != null) {
                // 原因说明与禁用标题同层级，但比图标保留更高透明度以方便阅读。
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant.copy(
                    alpha = if (enabled) 1f else 0.6f,
                )) {
                    ProvideTextStyle(MaterialTheme.typography.bodyMedium, summary)
                }
            }
        }
        trailing?.invoke()
    }
}
