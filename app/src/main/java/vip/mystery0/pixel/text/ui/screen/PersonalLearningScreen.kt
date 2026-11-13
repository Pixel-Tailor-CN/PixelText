package vip.mystery0.pixel.text.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.koin.androidx.compose.koinViewModel
import vip.mystery0.pixel.text.R
import vip.mystery0.pixel.text.domain.spam.learning.*
import vip.mystery0.pixel.text.ui.component.AppEmptyState
import vip.mystery0.pixel.text.util.AppStrings
import vip.mystery0.pixel.text.viewmodel.LearningRuleConflict
import vip.mystery0.pixel.text.viewmodel.PersonalLearningViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun PersonalLearningScreen(
    onNavigateBack: () -> Unit,
    onContributeSample: (content: String, category: String) -> Unit = { _, _ -> },
    viewModel: PersonalLearningViewModel = koinViewModel(),
) {
    val settings by viewModel.settings.collectAsState()
    val samples by viewModel.samples.collectAsState()
    val state by viewModel.learningState.collectAsState()
    val error by viewModel.error.collectAsState()
    val updatingSample by viewModel.updatingSample.collectAsState()
    var enableConfirmation by rememberSaveable { mutableStateOf(false) }
    var detailSampleId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletionId by rememberSaveable { mutableStateOf<String?>(null) }
    var clearConfirmation by rememberSaveable { mutableStateOf(false) }
    var showAboutDialog by rememberSaveable { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_personal_learning_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.settings_action_back),
                        )
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showMoreMenu = true }) {
                            Icon(
                                Icons.Rounded.MoreVert,
                                contentDescription = stringResource(R.string.settings_action_more_options),
                            )
                        }
                        DropdownMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.settings_learning_clear_menu)) },
                                enabled = samples.isNotEmpty(),
                                onClick = {
                                    showMoreMenu = false
                                    clearConfirmation = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.settings_learning_about_menu)) },
                                onClick = {
                                    showMoreMenu = false
                                    showAboutDialog = true
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            item(key = "master_switch") {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = if (settings.personalLearningEnabled) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    contentColor = if (settings.personalLearningEnabled) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .toggleable(
                            value = settings.personalLearningEnabled,
                            role = Role.Switch,
                            onValueChange = { enabled ->
                                if (enabled) enableConfirmation = true else viewModel.setEnabled(false)
                            },
                        ),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            stringResource(R.string.settings_learning_master_switch),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = settings.personalLearningEnabled,
                            onCheckedChange = null,
                            modifier = Modifier.clearAndSetSemantics {},
                        )
                    }
                }
            }

            item(key = "status") {
                Spacer(Modifier.height(20.dp))
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PersonalLearningStatusSection(
                        enabled = settings.personalLearningEnabled,
                        spamDetectionEnabled = settings.spamDetectionEnabled,
                        sampleCount = samples.size,
                        state = state,
                        onRetry = viewModel::retry,
                    )
                    error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            item(key = "records_header") {
                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.settings_learning_records_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(
                            if (samples.size == 1) R.string.settings_learning_records_count_single
                            else R.string.settings_learning_records_count_plural,
                            samples.size,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (samples.isEmpty()) {
                item(key = "empty_records") {
                    AppEmptyState(
                        text = stringResource(R.string.settings_learning_records_empty),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    )
                }
            } else {
                items(samples, key = { it.id }) { sample ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { detailSampleId = sample.id }
                            .padding(vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = sample.text,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = buildString {
                                    append(
                                        stringResource(
                                            if (sample.label == LearningLabel.SPAM) R.string.settings_learning_label_spam
                                            else R.string.settings_learning_label_normal,
                                        ),
                                    )
                                    append(" · ")
                                    append(formatLearningSampleDate(sample.updatedAt))
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = stringResource(R.string.settings_learning_view_details_cd),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    samples.firstOrNull { it.id == detailSampleId }?.let { sample ->
        LearningSampleDetailSheet(
            sample = sample,
            busy = updatingSample,
            error = error,
            onDismiss = { detailSampleId = null },
            onUpdateLabel = { newLabel ->
                viewModel.updateSampleLabel(sample.id, newLabel)
            },
            onContribute = {
                viewModel.prepareStoredSampleContribution(sample.id) { content, category ->
                    detailSampleId = null
                    onContributeSample(content, category)
                }
            },
            onRequestDelete = {
                detailSampleId = null
                deletionId = sample.id
            },
        )
    }

    if (enableConfirmation) {
        AlertDialog(
            onDismissRequest = { enableConfirmation = false },
            title = { Text(stringResource(R.string.settings_learning_enable_title)) },
            text = {
                Text(
                    stringResource(R.string.settings_learning_notice) +
                        if (!settings.spamDetectionEnabled) {
                            "\n\n" + stringResource(R.string.settings_learning_enable_also_model)
                        } else ""
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setEnabled(true)
                    enableConfirmation = false
                }) { Text(stringResource(R.string.settings_learning_enable_btn)) }
            },
            dismissButton = {
                TextButton(onClick = { enableConfirmation = false }) { Text(stringResource(R.string.settings_action_cancel)) }
            },
        )
    }

    samples.firstOrNull { it.id == deletionId }?.let { sample ->
        AlertDialog(
            onDismissRequest = { deletionId = null },
            title = { Text(stringResource(R.string.settings_learning_delete_title)) },
            text = {
                Text(
                    stringResource(R.string.settings_learning_delete_body) +
                        if (sample.linkedMessageCount > 1) {
                            stringResource(R.string.settings_learning_delete_linked, sample.linkedMessageCount)
                        } else ""
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSample(sample.id)
                    deletionId = null
                }) { Text(stringResource(R.string.settings_action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deletionId = null }) { Text(stringResource(R.string.settings_action_cancel)) }
            },
        )
    }

    if (clearConfirmation) {
        AlertDialog(
            onDismissRequest = { clearConfirmation = false },
            title = { Text(stringResource(R.string.settings_learning_clear_title)) },
            text = { Text(stringResource(R.string.settings_learning_clear_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clear()
                    clearConfirmation = false
                }) { Text(stringResource(R.string.settings_learning_clear_btn), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { clearConfirmation = false }) { Text(stringResource(R.string.settings_action_cancel)) }
            },
        )
    }

    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = { Text(stringResource(R.string.settings_learning_about_menu)) },
            text = { Text(stringResource(R.string.settings_learning_notice)) },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) { Text(stringResource(R.string.settings_action_ok)) }
            },
        )
    }
}

@Composable
private fun PersonalLearningStatusSection(
    enabled: Boolean,
    spamDetectionEnabled: Boolean,
    sampleCount: Int,
    state: LearningState,
    onRetry: () -> Unit,
) {
    val title: String
    val subtitle: String
    val isActive = enabled && spamDetectionEnabled && sampleCount > 0
    val isFailed = isActive && state.status == LearningStatus.FAILED
    val isRunning = isActive && state.status == LearningStatus.RUNNING
    val isApplied = isActive && state.status == LearningStatus.APPLIED && state.hasActiveParameters
    val isMissingParameters = isActive && state.status == LearningStatus.APPLIED && !state.hasActiveParameters
    val canRetry = isFailed || isMissingParameters

    when {
        !enabled && sampleCount == 0 -> {
            title = stringResource(R.string.settings_learning_status_not_enabled)
            subtitle = ""
        }
        !enabled -> {
            title = stringResource(R.string.settings_learning_status_paused)
            subtitle = ""
        }
        !spamDetectionEnabled -> {
            title = stringResource(R.string.settings_learning_status_model_off)
            subtitle = stringResource(R.string.settings_learning_status_model_off_sub)
        }
        sampleCount == 0 -> {
            title = stringResource(R.string.settings_learning_status_waiting)
            subtitle = ""
        }
        state.status == LearningStatus.APPLIED && state.hasActiveParameters -> {
            title = stringResource(R.string.settings_learning_status_active)
            subtitle = ""
        }
        state.status == LearningStatus.APPLIED -> {
            title = stringResource(R.string.settings_learning_status_not_ready)
            subtitle = stringResource(R.string.settings_learning_status_not_ready_sub)
        }
        state.status == LearningStatus.RUNNING -> {
            title = stringResource(R.string.settings_learning_status_running)
            subtitle = ""
        }
        state.status == LearningStatus.FAILED -> {
            title = stringResource(
                if (state.hasActiveParameters) {
                    R.string.settings_learning_status_failed_with_params
                } else {
                    R.string.settings_learning_status_failed_no_params
                },
            )
            subtitle = state.error.orEmpty()
        }
        else -> {
            title = stringResource(R.string.settings_learning_status_pending)
            subtitle = ""
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(modifier = Modifier.padding(top = 2.dp)) {
            when {
                isRunning -> CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.5.dp,
                )
                isApplied -> Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                isFailed -> Icon(
                    imageVector = Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                else -> Icon(
                    imageVector = Icons.Rounded.Schedule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = when {
                    isFailed -> MaterialTheme.colorScheme.error
                    isApplied -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (canRetry) {
                TextButton(
                    onClick = onRetry,
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                ) {
                    Text(stringResource(R.string.settings_learning_retry_btn))
                }
            }
        }
    }
}

@Composable
private fun LearningSampleDetailSheet(
    sample: LearningSample,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onUpdateLabel: (LearningLabel) -> Unit,
    onContribute: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    var showMore by remember { mutableStateOf(false) }
    var editingLabel by rememberSaveable(sample.id) { mutableStateOf(false) }
    var selectedLabel by rememberSaveable(sample.id, sample.label) { mutableStateOf(sample.label) }
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_learning_records_title), style = MaterialTheme.typography.titleLarge)
                    Text(
                        formatLearningSampleDetailTime(sample.updatedAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    IconButton(onClick = { showMore = true }, enabled = !busy) {
                        Icon(
                            Icons.Rounded.MoreVert,
                            contentDescription = stringResource(R.string.settings_learning_record_options_cd),
                        )
                    }
                    DropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.settings_learning_delete_record), color = MaterialTheme.colorScheme.error) },
                            onClick = { showMore = false; onRequestDelete() },
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.settings_action_close))
                }
            }
            Column(
                modifier = Modifier.weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            ) {
                SelectionContainer(Modifier.padding(vertical = 24.dp)) {
                    Text(sample.text, style = MaterialTheme.typography.bodyLarge)
                }
                ListItem(
                    content = { Text(stringResource(R.string.settings_learning_msg_category)) },
                    supportingContent = {
                        Text(
                            stringResource(
                                if (sample.label == LearningLabel.SPAM) R.string.settings_learning_label_spam
                                else R.string.settings_learning_label_normal,
                            ),
                        )
                    },
                    trailingContent = {
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
                    },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    modifier = Modifier.clickable(enabled = !busy) {
                        selectedLabel = sample.label
                        editingLabel = true
                    },
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Button(
                onClick = onContribute,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)
                    .heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.settings_learning_contribute_btn)) }
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
    if (editingLabel) {
        AlertDialog(
            onDismissRequest = { editingLabel = false },
            title = { Text(stringResource(R.string.settings_learning_msg_category)) },
            text = {
                Column {
                    LearningLabelRadioGroup(
                        selectedLabel = selectedLabel,
                        enabled = true,
                        onSelect = { selectedLabel = it },
                    )
                    if (sample.linkedMessageCount > 1) {
                        Text(
                            stringResource(R.string.settings_learning_linked_update_note, sample.linkedMessageCount),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { onUpdateLabel(selectedLabel); editingLabel = false },
                    enabled = selectedLabel != sample.label,
                ) { Text(stringResource(R.string.settings_action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { editingLabel = false }) { Text(stringResource(R.string.settings_action_cancel)) }
            },
        )
    }
}

@Composable
internal fun PersonalLearningDialog(
    viewModel: PersonalLearningViewModel,
    onOpenWhitelist: () -> Unit = {},
    onOpenKeywords: () -> Unit = {},
) {
    val interaction by viewModel.interaction.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val operationError by viewModel.error.collectAsState()
    val message = interaction.message ?: return
    var summaryExpanded by rememberSaveable(message.id) { mutableStateOf(false) }
    val latestBusy = rememberUpdatedState(interaction.busy)
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        confirmValueChange = remember {
            { targetValue ->
                targetValue != SheetValue.Hidden || !latestBusy.value
            }
        },
    )

    ModalBottomSheet(
        onDismissRequest = viewModel::dismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.settings_learning_dialog_title), style = MaterialTheme.typography.headlineSmall)

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(enabled = message.content.isNotBlank()) {
                        summaryExpanded = !summaryExpanded
                    },
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = message.content.ifBlank { stringResource(R.string.settings_learning_no_text) },
                        maxLines = if (summaryExpanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (message.content.length > 56 || message.content.count { it == '\n' } >= 2) {
                        Text(
                            text = stringResource(
                                if (summaryExpanded) R.string.settings_learning_collapse
                                else R.string.settings_learning_expand,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (interaction.linkedMessageCount > 1) {
                        Text(
                            text = stringResource(R.string.settings_learning_linked_save_note, interaction.linkedMessageCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (message.content.isNotBlank()) {
                LearningLabelRadioGroup(
                    selectedLabel = interaction.selectedLabel,
                    enabled = !interaction.busy,
                    onSelect = viewModel::selectLabel,
                )
            }

            if (interaction.needsEnable && message.content.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(stringResource(R.string.settings_learning_first_enable_title), style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = stringResource(R.string.settings_learning_first_enable_body) +
                                if (!settings.spamDetectionEnabled) {
                                    "\n" + stringResource(R.string.settings_learning_first_enable_also_model)
                                } else {
                                    ""
                                },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            interaction.ruleConflict?.let { conflict ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(
                                when (conflict) {
                                    LearningRuleConflict.WHITELIST -> R.string.settings_learning_conflict_whitelist
                                    LearningRuleConflict.KEYWORD -> R.string.settings_learning_conflict_keyword
                                },
                            ),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        TextButton(
                            onClick = {
                                viewModel.dismiss()
                                when (conflict) {
                                    LearningRuleConflict.WHITELIST -> onOpenWhitelist()
                                    LearningRuleConflict.KEYWORD -> onOpenKeywords()
                                }
                            },
                            enabled = !interaction.busy,
                        ) {
                            Text(
                                stringResource(
                                    when (conflict) {
                                        LearningRuleConflict.WHITELIST -> R.string.settings_learning_view_whitelist
                                        LearningRuleConflict.KEYWORD -> R.string.settings_learning_view_rules
                                    },
                                ),
                            )
                        }
                    }
                }
            }

            if (message.content.isNotBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .toggleable(
                            value = interaction.contributeSample,
                            enabled = !interaction.busy,
                            role = Role.Checkbox,
                            onValueChange = viewModel::setContributeSample,
                        )
                        .heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = interaction.contributeSample,
                        onCheckedChange = null,
                        enabled = !interaction.busy,
                        modifier = Modifier.padding(12.dp),
                    )
                    Text(stringResource(R.string.settings_learning_contribute_checkbox), style = MaterialTheme.typography.bodyLarge)
                }
            }
            Text(
                text = stringResource(
                    if (interaction.contributeSample) R.string.settings_learning_contribute_on_hint
                    else R.string.settings_learning_contribute_off_hint,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            interaction.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            operationError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (interaction.busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (message.content.isBlank()) {
                Button(
                    onClick = viewModel::dismiss,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.settings_action_ok))
                }
            } else {
                Button(
                    onClick = viewModel::saveSelection,
                    enabled = interaction.selectedLabel != null && !interaction.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(
                        stringResource(
                            if (interaction.needsEnable) R.string.settings_learning_enable_and_save
                            else R.string.settings_learning_save_correction,
                        ),
                    )
                }
                TextButton(
                    onClick = viewModel::dismiss,
                    enabled = !interaction.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.settings_action_cancel))
                }
            }

            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

@Composable
private fun LearningLabelRadioGroup(
    selectedLabel: LearningLabel?,
    enabled: Boolean,
    onSelect: (LearningLabel) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LearningLabelRadioRow(
            title = stringResource(R.string.settings_learning_label_normal),
            subtitle = stringResource(R.string.settings_learning_normal_desc),
            selected = selectedLabel == LearningLabel.NORMAL,
            enabled = enabled,
            onClick = { onSelect(LearningLabel.NORMAL) },
        )
        LearningLabelRadioRow(
            title = stringResource(R.string.settings_learning_label_spam),
            subtitle = stringResource(R.string.settings_learning_spam_desc),
            selected = selectedLabel == LearningLabel.SPAM,
            enabled = enabled,
            onClick = { onSelect(LearningLabel.SPAM) },
        )
    }
}

@Composable
private fun LearningLabelRadioRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .clip(RoundedCornerShape(16.dp))
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatLearningSampleDate(timestamp: Long): String {
    val now = Calendar.getInstance()
    val target = Calendar.getInstance().apply { timeInMillis = timestamp }
    val sameYear = now.get(Calendar.YEAR) == target.get(Calendar.YEAR)
    val nowDay = now.get(Calendar.DAY_OF_YEAR)
    val targetDay = target.get(Calendar.DAY_OF_YEAR)
    return when {
        sameYear && nowDay == targetDay -> AppStrings.get(R.string.settings_learning_date_today)
        sameYear && nowDay - targetDay == 1 -> AppStrings.get(R.string.settings_learning_date_yesterday)
        sameYear -> SimpleDateFormat(
            AppStrings.get(R.string.settings_learning_date_same_year_pattern),
            Locale.getDefault(),
        ).format(Date(timestamp))
        else -> SimpleDateFormat(
            AppStrings.get(R.string.settings_learning_date_other_year_pattern),
            Locale.getDefault(),
        ).format(Date(timestamp))
    }
}

private fun formatLearningSampleDetailTime(timestamp: Long): String {
    return SimpleDateFormat(
        AppStrings.get(R.string.settings_learning_detail_time_pattern),
        Locale.getDefault(),
    ).format(Date(timestamp))
}
