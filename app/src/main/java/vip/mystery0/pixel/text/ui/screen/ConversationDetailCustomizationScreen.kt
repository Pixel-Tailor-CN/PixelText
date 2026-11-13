package vip.mystery0.pixel.text.ui.screen

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import vip.mystery0.pixel.text.R
import vip.mystery0.pixel.text.domain.settings.AppSettingsRepository
import vip.mystery0.pixel.text.domain.theme.ConversationDetailAppearance
import vip.mystery0.pixel.text.domain.theme.MAX_CONVERSATION_DETAIL_TEXT_SCALE
import vip.mystery0.pixel.text.domain.theme.MAX_CONVERSATION_INPUT_PLACEHOLDER_LENGTH
import vip.mystery0.pixel.text.domain.theme.MIN_CONVERSATION_DETAIL_TEXT_SCALE
import vip.mystery0.pixel.text.domain.theme.MaterialColorRole
import vip.mystery0.pixel.text.domain.theme.ThemeAssetRepository
import vip.mystery0.pixel.text.domain.theme.ThemeColorReference
import vip.mystery0.pixel.text.domain.theme.ThemeColorType
import vip.mystery0.pixel.text.domain.theme.ThemeMode
import vip.mystery0.pixel.text.domain.theme.appearance
import vip.mystery0.pixel.text.domain.theme.backgroundMode
import vip.mystery0.pixel.text.ui.component.theme.ConversationDetailPreview
import vip.mystery0.pixel.text.ui.component.theme.ConversationDetailPreviewMessageSpecs
import vip.mystery0.pixel.text.ui.component.theme.ConversationDetailPreviewTheme
import vip.mystery0.pixel.text.ui.component.theme.ThemeColorPickerSheet
import vip.mystery0.pixel.text.ui.screen.mock.MockMessageFactory
import vip.mystery0.pixel.text.ui.theme.resolveConversationDetailStyle
import vip.mystery0.pixel.text.ui.theme.resolveOr
import vip.mystery0.pixel.text.util.AppStrings
import vip.mystery0.pixel.text.viewmodel.ConversationDetailCustomizationViewModel
import vip.mystery0.pixel.text.viewmodel.ThemeCustomizationEvent
import java.util.Locale

private enum class ThemeColorTarget {
    RECEIVED_TEXT,
    RECEIVED_BUBBLE,
    SENT_TEXT,
    SENT_BUBBLE,
    INPUT_BACKGROUND,
}

@Composable
fun ConversationDetailCustomizationScreen(
    onNavigateBack: () -> Unit,
    viewModel: ConversationDetailCustomizationViewModel = koinViewModel(),
    settingsRepository: AppSettingsRepository = koinInject(),
    @Suppress("UNUSED_PARAMETER")
    themeAssetRepository: ThemeAssetRepository = koinInject(),
    messageFactory: MockMessageFactory = koinInject(),
) {
    val state by viewModel.uiState.collectAsState()
    val appSettings by settingsRepository.settings.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showExitDialog by remember { mutableStateOf(false) }
    var showResetAllDialog by remember { mutableStateOf(false) }
    var colorTarget by remember { mutableStateOf<ThemeColorTarget?>(null) }

    val previewMessages = remember(messageFactory) {
        messageFactory.createSpecs(ConversationDetailPreviewMessageSpecs)
    }
    // Resolve on each recomposition so draft-map updates are visible immediately.
    val backgroundFile = viewModel.resolvePreviewBackground(state.previewMode)

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        uri?.let { viewModel.selectBackground(it.toString()) }
    }

    val savedSnackbarText = stringResource(R.string.settings_customization_saved)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ThemeCustomizationEvent.Message -> {
                    snackbarHostState.showSnackbar(event.text)
                }
                ThemeCustomizationEvent.Saved -> {
                    snackbarHostState.showSnackbar(savedSnackbarText)
                }
                ThemeCustomizationEvent.SavedAndExit -> {
                    onNavigateBack()
                }
            }
        }
    }

    fun requestNavigateBack() {
        if (state.isSaving) {
            return
        }
        if (state.hasUnsavedChanges) {
            showExitDialog = true
        } else {
            onNavigateBack()
        }
    }

    // Always enabled so system back is consumed while saving; requestNavigateBack no-ops then.
    BackHandler(onBack = ::requestNavigateBack)

    val view = LocalView.current
    val systemDarkTheme = isSystemInDarkTheme()
    DisposableEffect(view, state.previewMode, systemDarkTheme) {
        val window = view.context.findActivity()?.window
        window?.let {
            WindowCompat.getInsetsController(it, view).isAppearanceLightStatusBars =
                state.previewMode == ThemeMode.LIGHT
        }
        onDispose {
            window?.let {
                WindowCompat.getInsetsController(it, view).isAppearanceLightStatusBars =
                    !systemDarkTheme
            }
        }
    }

    ConversationDetailPreviewTheme(
        mode = state.previewMode,
        backgroundFile = backgroundFile,
        generateColorsFromBackgroundImage =
            state.draftTheme.conversationDetail.generateColorsFromBackgroundImage &&
                !state.highTextContrastEnabled,
        applyGeneratedColorScheme = false,
    ) { backgroundPreviewScheme, backgroundColorsApplied, backgroundColorsLoading ->
        Scaffold(
            topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = ::requestNavigateBack,
                        enabled = !state.isSaving,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.settings_action_back),
                        )
                    }
                },
                title = {
                    Text(
                        text = stringResource(R.string.settings_conversation_appearance_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                actions = {
                    TextButton(
                        onClick = { viewModel.save(exitAfterSave = false) },
                        enabled = state.hasUnsavedChanges && !state.isSaving,
                    ) {
                        Text(
                            stringResource(
                                if (state.isSaving) R.string.settings_action_saving
                                else R.string.settings_action_save,
                            ),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0.dp),
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(paddingValues),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = paddingValues.calculateTopPadding() + 8.dp,
                end = 16.dp,
                bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.highTextContrastEnabled) {
                item(key = "high_contrast_banner") {
                    HighContrastBanner()
                }
            }

            item(key = "preview") {
                ConversationDetailPreviewTheme(
                    mode = state.previewMode,
                    fallbackColorScheme = backgroundPreviewScheme,
                ) { previewScheme, _, _ ->
                    val previewStyle = resolveConversationDetailStyle(
                        configuration = state.draftTheme,
                        mode = state.previewMode,
                        colorScheme = previewScheme,
                        highTextContrastEnabled = state.highTextContrastEnabled,
                    )
                    ConversationDetailPreview(
                        messages = previewMessages,
                        style = previewStyle,
                        timeDisplayFormat = appSettings.messageTimeDisplayFormat,
                        backgroundFile = if (previewStyle.customizationSuppressed) {
                            null
                        } else {
                            backgroundFile
                        },
                        modifier = Modifier.height(320.dp),
                    )
                }
            }

            item(key = "mode_tabs") {
                val selectedIndex = if (state.previewMode == ThemeMode.LIGHT) 0 else 1
                SecondaryTabRow(selectedTabIndex = selectedIndex) {
                    Tab(
                        selected = state.previewMode == ThemeMode.LIGHT,
                        onClick = { viewModel.setPreviewMode(ThemeMode.LIGHT) },
                        text = { Text(stringResource(R.string.settings_customization_mode_light)) },
                    )
                    Tab(
                        selected = state.previewMode == ThemeMode.DARK,
                        onClick = { viewModel.setPreviewMode(ThemeMode.DARK) },
                        text = { Text(stringResource(R.string.settings_customization_mode_dark)) },
                    )
                }
            }

            item(key = "bubble_section") {
                ConversationDetailPreviewTheme(
                    mode = state.previewMode,
                    fallbackColorScheme = backgroundPreviewScheme,
                ) { previewScheme, _, _ ->
                    val appearance = state.draftTheme.conversationDetail.appearance(state.previewMode)
                    val colorControlsEnabled =
                        !state.highTextContrastEnabled && !state.isSaving
                    SettingsSectionCard(title = stringResource(R.string.settings_customization_bubbles_section)) {
                        ColorSettingRow(
                            title = stringResource(R.string.settings_customization_received_text),
                            reference = appearance.receivedTextColor,
                            colorScheme = previewScheme,
                            fallback = previewScheme.onSurface,
                            enabled = colorControlsEnabled,
                            onClick = { colorTarget = ThemeColorTarget.RECEIVED_TEXT },
                        )
                        HorizontalDivider()
                        ColorSettingRow(
                            title = stringResource(R.string.settings_customization_received_bubble),
                            reference = appearance.receivedBubbleColor,
                            colorScheme = previewScheme,
                            fallback = previewScheme.surfaceVariant,
                            enabled = colorControlsEnabled,
                            onClick = { colorTarget = ThemeColorTarget.RECEIVED_BUBBLE },
                        )
                        HorizontalDivider()
                        ColorSettingRow(
                            title = stringResource(R.string.settings_customization_sent_text),
                            reference = appearance.sentTextColor,
                            colorScheme = previewScheme,
                            fallback = previewScheme.onSurface,
                            enabled = colorControlsEnabled,
                            onClick = { colorTarget = ThemeColorTarget.SENT_TEXT },
                        )
                        HorizontalDivider()
                        ColorSettingRow(
                            title = stringResource(R.string.settings_customization_sent_bubble),
                            reference = appearance.sentBubbleColor,
                            colorScheme = previewScheme,
                            fallback = previewScheme.surfaceVariant,
                            enabled = colorControlsEnabled,
                            onClick = { colorTarget = ThemeColorTarget.SENT_BUBBLE },
                        )
                    }
                }
            }

            item(key = "background_section") {
                val module = state.draftTheme.conversationDetail
                val backgroundMode = module.backgroundMode(state.previewMode)
                val appearance = module.appearance(backgroundMode)
                val hasBackground = appearance.backgroundImage != null
                // Prefer resolved file when available; otherwise ask for re-selection in-place.
                val backgroundMissing = hasBackground &&
                    viewModel.isBackgroundMissing(state.previewMode)
                val backgroundCustomizationEnabled =
                    !state.highTextContrastEnabled && !state.isSaving
                val currentModeOwnsBackground = !module.useSameBackgroundImage ||
                    state.previewMode == module.sharedBackgroundImageMode
                val backgroundControlsEnabled =
                    backgroundCustomizationEnabled && currentModeOwnsBackground
                val sourceModeLabel = stringResource(
                    if (module.sharedBackgroundImageMode == ThemeMode.LIGHT) {
                        R.string.settings_customization_mode_light
                    } else {
                        R.string.settings_customization_mode_dark
                    },
                )
                SettingsSectionCard(title = stringResource(R.string.settings_customization_background_section)) {
                    ListItem(
                        trailingContent = {
                            Switch(
                                checked = module.useSameBackgroundImage,
                                onCheckedChange = viewModel::setUseSameBackgroundImage,
                                enabled = backgroundCustomizationEnabled,
                            )
                        },
                        supportingContent = {
                            Text(
                                if (module.useSameBackgroundImage) {
                                    stringResource(R.string.settings_customization_same_bg_using_mode, sourceModeLabel)
                                } else {
                                    stringResource(R.string.settings_customization_same_bg_using_current)
                                },
                            )
                        },
                        colors = listItemColors(enabled = backgroundCustomizationEnabled),
                        content = { Text(stringResource(R.string.settings_customization_same_bg_title)) },
                    )
                    HorizontalDivider()
                    ListItem(
                        trailingContent = {
                            Switch(
                                checked = module.generateColorsFromBackgroundImage,
                                onCheckedChange =
                                    viewModel::setGenerateColorsFromBackgroundImage,
                                enabled = backgroundCustomizationEnabled,
                            )
                        },
                        supportingContent = {
                            Text(
                                stringResource(
                                    when {
                                        !module.generateColorsFromBackgroundImage ->
                                            R.string.settings_customization_bg_colors_off
                                        backgroundMissing ->
                                            R.string.settings_customization_bg_colors_missing
                                        !hasBackground ->
                                            R.string.settings_customization_bg_colors_none
                                        backgroundColorsLoading ->
                                            R.string.settings_customization_bg_colors_loading
                                        backgroundColorsApplied ->
                                            R.string.settings_customization_bg_colors_applied
                                        else -> R.string.settings_customization_bg_colors_failed
                                    },
                                ),
                            )
                        },
                        colors = listItemColors(enabled = backgroundCustomizationEnabled),
                        content = { Text(stringResource(R.string.settings_customization_bg_colors_title)) },
                    )
                    HorizontalDivider()
                    ListItem(
                        modifier = Modifier.clickable(enabled = backgroundControlsEnabled) {
                            photoPicker.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly,
                                ),
                            )
                        },
                        leadingContent = {
                            Icon(
                                imageVector = Icons.Rounded.Image,
                                contentDescription = null,
                            )
                        },
                        supportingContent = {
                            Text(
                                when {
                                    !currentModeOwnsBackground ->
                                        stringResource(R.string.settings_customization_bg_select_controlled_summary, sourceModeLabel)
                                    backgroundMissing -> stringResource(R.string.settings_customization_bg_select_missing_summary)
                                    hasBackground -> stringResource(R.string.settings_customization_bg_select_change_summary)
                                    else -> stringResource(R.string.settings_customization_bg_select_choose_summary)
                                },
                            )
                        },
                        colors = listItemColors(enabled = backgroundControlsEnabled),
                        content = {
                            Text(
                                when {
                                    !currentModeOwnsBackground ->
                                        stringResource(R.string.settings_customization_bg_select_controlled_title, sourceModeLabel)
                                    backgroundMissing -> stringResource(R.string.settings_customization_bg_select_missing_title)
                                    hasBackground -> stringResource(R.string.settings_customization_bg_select_change_title)
                                    else -> stringResource(R.string.settings_customization_bg_select_choose_title)
                                },
                            )
                        },
                    )
                    HorizontalDivider()
                    ListItem(
                        modifier = Modifier.clickable(
                            enabled = backgroundControlsEnabled && hasBackground,
                        ) {
                            viewModel.removeBackground(state.previewMode)
                        },
                        supportingContent = {
                            Text(
                                stringResource(
                                    when {
                                        !currentModeOwnsBackground -> R.string.settings_customization_bg_remove_controlled
                                        backgroundMissing -> R.string.settings_customization_bg_remove_missing
                                        hasBackground -> R.string.settings_customization_bg_remove_clear
                                        else -> R.string.settings_customization_bg_remove_none
                                    },
                                ),
                            )
                        },
                        colors = listItemColors(
                            enabled = backgroundControlsEnabled && hasBackground,
                        ),
                        content = { Text(stringResource(R.string.settings_customization_bg_remove_title)) },
                    )
                }
            }

            item(key = "input_section") {
                ConversationDetailPreviewTheme(
                    mode = state.previewMode,
                    fallbackColorScheme = backgroundPreviewScheme,
                ) { previewScheme, _, _ ->
                    val appearance =
                        state.draftTheme.conversationDetail.appearance(state.previewMode)
                    val colorControlsEnabled =
                        !state.highTextContrastEnabled && !state.isSaving
                    SettingsSectionCard(title = stringResource(R.string.settings_customization_input_section)) {
                        ColorSettingRow(
                            title = stringResource(R.string.settings_customization_input_bg),
                            reference = appearance.inputBackgroundColor,
                            colorScheme = previewScheme,
                            fallback = previewScheme.surfaceVariant.copy(alpha = 0.5f),
                            enabled = colorControlsEnabled,
                            onClick = { colorTarget = ThemeColorTarget.INPUT_BACKGROUND },
                        )
                        HorizontalDivider()
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            OutlinedTextField(
                                value = state.draftTheme.conversationDetail.inputPlaceholder,
                                onValueChange = { value ->
                                    if (value.length <= MAX_CONVERSATION_INPUT_PLACEHOLDER_LENGTH) {
                                        viewModel.setInputPlaceholder(value)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !state.isSaving,
                                label = { Text(stringResource(R.string.settings_customization_input_placeholder_label)) },
                                placeholder = { Text(stringResource(R.string.settings_input_placeholder_default_hint)) },
                                singleLine = true,
                                supportingText = {
                                    Text(
                                        "${state.draftTheme.conversationDetail.inputPlaceholder.length}/$MAX_CONVERSATION_INPUT_PLACEHOLDER_LENGTH",
                                    )
                                },
                            )
                        }
                    }
                }
            }

            item(key = "message_info_section") {
                SettingsSectionCard(title = stringResource(R.string.settings_customization_msg_info_section)) {
                    ListItem(
                        trailingContent = {
                            Switch(
                                checked = state.draftTheme.conversationDetail.showSimInfo,
                                onCheckedChange = viewModel::setShowSimInfo,
                                enabled = !state.isSaving,
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        content = { Text(stringResource(R.string.settings_customization_show_sim)) },
                    )
                    HorizontalDivider()
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        val scale = state.draftTheme.conversationDetail.textScale
                        Text(
                            text = stringResource(
                                R.string.settings_customization_text_scale,
                                "%.2f".format(Locale.US, scale),
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        val sliderState = remember {
                            SliderState(
                                value = scale,
                                trackRange = MIN_CONVERSATION_DETAIL_TEXT_SCALE..MAX_CONVERSATION_DETAIL_TEXT_SCALE,
                            )
                        }.apply { value = scale }
                        Slider(
                            state = sliderState,
                            onValueChange = viewModel::setTextScale,
                            enabled = !state.isSaving,
                        )
                    }
                }
            }

            item(key = "reset_section") {
                SettingsSectionCard(title = stringResource(R.string.settings_customization_reset_section)) {
                    ListItem(
                        modifier = Modifier.clickable(enabled = !state.isSaving) {
                            viewModel.resetCurrentMode()
                        },
                        supportingContent = {
                            Text(
                                stringResource(
                                    if (state.previewMode == ThemeMode.LIGHT) {
                                        R.string.settings_customization_reset_light_summary
                                    } else {
                                        R.string.settings_customization_reset_dark_summary
                                    },
                                ),
                            )
                        },
                        colors = listItemColors(enabled = !state.isSaving),
                        content = { Text(stringResource(R.string.settings_customization_reset_current)) },
                    )
                    HorizontalDivider()
                    ListItem(
                        modifier = Modifier.clickable(enabled = !state.isSaving) {
                            showResetAllDialog = true
                        },
                        supportingContent = { Text(stringResource(R.string.settings_customization_reset_all_summary)) },
                        colors = listItemColors(enabled = !state.isSaving),
                        content = { Text(stringResource(R.string.settings_customization_reset_all)) },
                    )
                }
            }

            item(key = "navigation_bar_spacer") {
                Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        }
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text(stringResource(R.string.settings_customization_unsaved_title)) },
            text = { Text(stringResource(R.string.settings_customization_unsaved_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExitDialog = false
                        viewModel.save(exitAfterSave = true)
                    },
                ) {
                    Text(stringResource(R.string.settings_customization_save_exit))
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            showExitDialog = false
                            viewModel.discardChanges()
                            onNavigateBack()
                        },
                    ) {
                        Text(stringResource(R.string.settings_customization_discard))
                    }
                    TextButton(onClick = { showExitDialog = false }) {
                        Text(stringResource(R.string.settings_customization_keep_editing))
                    }
                }
            },
        )
    }

    if (showResetAllDialog) {
        AlertDialog(
            onDismissRequest = { showResetAllDialog = false },
            title = { Text(stringResource(R.string.settings_customization_reset_all_confirm_title)) },
            text = {
                Text(stringResource(R.string.settings_customization_reset_all_confirm_body))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetAllDialog = false
                        viewModel.resetAll()
                    },
                ) {
                    Text(stringResource(R.string.settings_customization_reset_all))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetAllDialog = false }) {
                    Text(stringResource(R.string.settings_action_cancel))
                }
            },
        )
    }

    val activeColorTarget = colorTarget
    if (activeColorTarget != null) {
        ConversationDetailPreviewTheme(
                    mode = state.previewMode,
                    fallbackColorScheme = backgroundPreviewScheme,
                ) { previewScheme, _, _ ->
            val appearance = state.draftTheme.conversationDetail.appearance(state.previewMode)
            val pickerConfig = colorPickerConfig(
                target = activeColorTarget,
                appearance = appearance,
                colorScheme = previewScheme,
            )
            ThemeColorPickerSheet(
                title = pickerConfig.title,
                current = pickerConfig.current,
                colorScheme = previewScheme,
                comparisonBackground = pickerConfig.comparisonBackground,
                officialDefault = pickerConfig.officialDefault,
                onDismiss = { colorTarget = null },
                onConfirm = { reference ->
                    when (activeColorTarget) {
                        ThemeColorTarget.RECEIVED_TEXT ->
                            viewModel.setReceivedTextColor(reference)
                        ThemeColorTarget.RECEIVED_BUBBLE ->
                            viewModel.setReceivedBubbleColor(reference)
                        ThemeColorTarget.SENT_TEXT ->
                            viewModel.setSentTextColor(reference)
                        ThemeColorTarget.SENT_BUBBLE ->
                            viewModel.setSentBubbleColor(reference)
                        ThemeColorTarget.INPUT_BACKGROUND ->
                            viewModel.setInputBackgroundColor(reference)
                    }
                    colorTarget = null
                },
                selectedActsAsBackground = pickerConfig.selectedActsAsBackground,
            )
        }
    }
    }
}

@Composable
private fun HighContrastBanner() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Rounded.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = stringResource(R.string.settings_customization_high_contrast_banner),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
            shape = RoundedCornerShape(20.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun ColorSettingRow(
    title: String,
    reference: ThemeColorReference?,
    colorScheme: ColorScheme,
    fallback: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val resolved = reference.resolveOr(colorScheme, fallback)
    ListItem(
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
        trailingContent = {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(resolved)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = CircleShape,
                    ),
            )
        },
        supportingContent = { Text(colorReferenceLabel(reference)) },
        colors = listItemColors(enabled = enabled),
        content = { Text(title) },
    )
}

@Composable
private fun listItemColors(enabled: Boolean) = ListItemDefaults.colors(
    containerColor = Color.Transparent,
    headlineColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    },
    supportingColor = if (enabled) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    },
    leadingIconColor = if (enabled) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    },
)

private data class ColorPickerConfig(
    val title: String,
    val current: ThemeColorReference?,
    val comparisonBackground: Color,
    val officialDefault: Color,
    val selectedActsAsBackground: Boolean = false,
)

private fun colorPickerConfig(
    target: ThemeColorTarget,
    appearance: ConversationDetailAppearance,
    colorScheme: ColorScheme,
): ColorPickerConfig {
    val defaultText = colorScheme.onSurface
    val defaultBubble = colorScheme.surfaceVariant
    val defaultInput = colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val receivedText = appearance.receivedTextColor.resolveOr(colorScheme, defaultText)
    val receivedBubble = appearance.receivedBubbleColor.resolveOr(colorScheme, defaultBubble)
    val sentText = appearance.sentTextColor.resolveOr(colorScheme, defaultText)
    val sentBubble = appearance.sentBubbleColor.resolveOr(colorScheme, defaultBubble)
    return when (target) {
        ThemeColorTarget.RECEIVED_TEXT -> ColorPickerConfig(
            title = AppStrings.get(R.string.settings_customization_received_text),
            current = appearance.receivedTextColor,
            comparisonBackground = receivedBubble,
            officialDefault = defaultText,
        )
        ThemeColorTarget.RECEIVED_BUBBLE -> ColorPickerConfig(
            title = AppStrings.get(R.string.settings_customization_received_bubble),
            current = appearance.receivedBubbleColor,
            comparisonBackground = receivedText,
            officialDefault = defaultBubble,
            selectedActsAsBackground = true,
        )
        ThemeColorTarget.SENT_TEXT -> ColorPickerConfig(
            title = AppStrings.get(R.string.settings_customization_sent_text),
            current = appearance.sentTextColor,
            comparisonBackground = sentBubble,
            officialDefault = defaultText,
        )
        ThemeColorTarget.SENT_BUBBLE -> ColorPickerConfig(
            title = AppStrings.get(R.string.settings_customization_sent_bubble),
            current = appearance.sentBubbleColor,
            comparisonBackground = sentText,
            officialDefault = defaultBubble,
            selectedActsAsBackground = true,
        )
        ThemeColorTarget.INPUT_BACKGROUND -> ColorPickerConfig(
            title = AppStrings.get(R.string.settings_customization_input_bg),
            current = appearance.inputBackgroundColor,
            comparisonBackground = colorScheme.onSurfaceVariant,
            officialDefault = defaultInput,
            selectedActsAsBackground = true,
        )
    }
}

private tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}

private fun colorReferenceLabel(reference: ThemeColorReference?): String {
    if (reference == null) {
        return AppStrings.get(R.string.settings_customization_color_builtin)
    }
    return when (reference.type) {
        ThemeColorType.MATERIAL_ROLE -> {
            val role = MaterialColorRole.fromStorageValue(reference.value)
            role?.let { "MD3 · ${it.storageValue}" }
                ?: AppStrings.get(R.string.settings_customization_color_builtin)
        }
        ThemeColorType.CUSTOM_ARGB -> reference.value.ifBlank {
            AppStrings.get(R.string.settings_customization_color_custom)
        }
    }
}
