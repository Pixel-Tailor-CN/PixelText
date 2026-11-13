package vip.mystery0.pixel.text.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import vip.mystery0.pixel.text.R
import vip.mystery0.pixel.text.domain.backup.BackupSection
import vip.mystery0.pixel.text.domain.backup.BackupSummary

/** 备份页的任务分组与预览容器，仅负责呈现。 */
@Composable
internal fun BackupTaskCard(
    title: String,
    cornerRadius: Dp = 24.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(cornerRadius),
        colors = CardDefaults.cardColors(
            containerColor = if (cornerRadius == 28.dp) MaterialTheme.colorScheme.surfaceContainerHigh
            else MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
internal fun BackupSectionSelection(
    sections: List<BackupSection>,
    selection: Set<BackupSection>,
    onSelectionChange: (Set<BackupSection>) -> Unit,
) {
    Column {
        sections.forEach { section ->
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                    value = section in selection,
                    role = Role.Checkbox,
                    onValueChange = { selected ->
                        onSelectionChange(if (selected) selection + section else selection - section)
                    },
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Checkbox(checked = section in selection, onCheckedChange = null)
                Text(section.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
internal fun BackupPasswordField(
    password: String,
    onPasswordChange: (String) -> Unit,
    label: String? = null,
) {
    val resolvedLabel = label ?: stringResource(R.string.settings_backup_password_label)
    OutlinedTextField(
        value = password,
        onValueChange = onPasswordChange,
        label = { Text(resolvedLabel) },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun BackupResultDetails(result: BackupSummary) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(R.string.settings_backup_result_counts, result.inserted, result.existing),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            stringResource(R.string.settings_backup_result_errors, result.failed, result.remaining),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            stringResource(R.string.settings_backup_result_outgoing, result.convertedOutgoing, result.skippedAssociations),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (result.completedSections.isNotEmpty()) {
            Text(
                stringResource(
                    R.string.settings_backup_result_completed,
                    result.completedSections.joinToString { it.label },
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
