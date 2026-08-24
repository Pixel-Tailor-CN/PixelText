package vip.mystery0.pixel.text.ui.screen

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
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
import vip.mystery0.pixel.text.ui.component.theme.ConversationDetailPreview
import vip.mystery0.pixel.text.ui.component.theme.ConversationDetailPreviewMessageSpecs
import vip.mystery0.pixel.text.ui.component.theme.ConversationDetailPreviewTheme
import vip.mystery0.pixel.text.ui.component.theme.ThemeColorPickerSheet
import vip.mystery0.pixel.text.ui.screen.mock.MockMessageFactory
import vip.mystery0.pixel.text.ui.theme.resolveConversationDetailStyle
import vip.mystery0.pixel.text.ui.theme.resolveOr
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

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ThemeCustomizationEvent.Message -> {
                    snackbarHostState.showSnackbar(event.text)
                }
                ThemeCustomizationEvent.Saved -> {
                    snackbarHostState.showSnackbar("已保存")
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

    BackHandler(enabled = !state.isSaving, onBack = ::requestNavigateBack)

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
                            contentDescription = "返回",
                        )
                    }
                },
                title = {
                    Text(
                        text = "会话详情显示",
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                actions = {
                    TextButton(
                        onClick = { viewModel.save(exitAfterSave = false) },
                        enabled = state.hasUnsavedChanges && !state.isSaving,
                    ) {
                        Text(if (state.isSaving) "保存中" else "保存")
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
                ConversationDetailPreviewTheme(mode = state.previewMode) { previewScheme ->
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
                        text = { Text("日间") },
                    )
                    Tab(
                        selected = state.previewMode == ThemeMode.DARK,
                        onClick = { viewModel.setPreviewMode(ThemeMode.DARK) },
                        text = { Text("暗黑") },
                    )
                }
            }

            item(key = "bubble_section") {
                ConversationDetailPreviewTheme(mode = state.previewMode) { previewScheme ->
                    val appearance = state.draftTheme.conversationDetail.appearance(state.previewMode)
                    val colorControlsEnabled =
                        !state.highTextContrastEnabled && !state.isSaving
                    SettingsSectionCard(title = "原文气泡") {
                        ColorSettingRow(
                            title = "接收文字颜色",
                            reference = appearance.receivedTextColor,
                            colorScheme = previewScheme,
                            fallback = previewScheme.onSurface,
                            enabled = colorControlsEnabled,
                            onClick = { colorTarget = ThemeColorTarget.RECEIVED_TEXT },
                        )
                        HorizontalDivider()
                        ColorSettingRow(
                            title = "接收气泡颜色",
                            reference = appearance.receivedBubbleColor,
                            colorScheme = previewScheme,
                            fallback = previewScheme.surfaceVariant,
                            enabled = colorControlsEnabled,
                            onClick = { colorTarget = ThemeColorTarget.RECEIVED_BUBBLE },
                        )
                        HorizontalDivider()
                        ColorSettingRow(
                            title = "发送文字颜色",
                            reference = appearance.sentTextColor,
                            colorScheme = previewScheme,
                            fallback = previewScheme.onSurface,
                            enabled = colorControlsEnabled,
                            onClick = { colorTarget = ThemeColorTarget.SENT_TEXT },
                        )
                        HorizontalDivider()
                        ColorSettingRow(
                            title = "发送气泡颜色",
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
                val appearance = state.draftTheme.conversationDetail.appearance(state.previewMode)
                val hasBackground = appearance.backgroundImage != null
                val backgroundEnabled = !state.highTextContrastEnabled && !state.isSaving
                SettingsSectionCard(title = "会话背景") {
                    ListItem(
                        modifier = Modifier.clickable(enabled = backgroundEnabled) {
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
                                if (hasBackground) {
                                    "已设置当前主题背景"
                                } else {
                                    "仅覆盖消息列表区域"
                                },
                            )
                        },
                        colors = listItemColors(enabled = backgroundEnabled),
                        content = {
                            Text(if (hasBackground) "更换背景图片" else "选择背景图片")
                        },
                    )
                    HorizontalDivider()
                    ListItem(
                        modifier = Modifier.clickable(
                            enabled = backgroundEnabled && hasBackground,
                        ) {
                            viewModel.removeBackground(state.previewMode)
                        },
                        supportingContent = {
                            Text(if (hasBackground) "清除当前主题背景" else "当前未设置背景")
                        },
                        colors = listItemColors(enabled = backgroundEnabled && hasBackground),
                        content = { Text("移除背景图片") },
                    )
                }
            }

            item(key = "input_section") {
                ConversationDetailPreviewTheme(mode = state.previewMode) { previewScheme ->
                    val appearance =
                        state.draftTheme.conversationDetail.appearance(state.previewMode)
                    val colorControlsEnabled =
                        !state.highTextContrastEnabled && !state.isSaving
                    SettingsSectionCard(title = "输入区域") {
                        ColorSettingRow(
                            title = "输入区域背景色",
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
                                label = { Text("提示文字") },
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
                SettingsSectionCard(title = "消息信息") {
                    ListItem(
                        trailingContent = {
                            Switch(
                                checked = state.draftTheme.conversationDetail.showSimInfo,
                                onCheckedChange = viewModel::setShowSimInfo,
                                enabled = !state.isSaving,
                            )
                        },
                        supportingContent = { Text("在消息时间旁显示 SIM 标签") },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        content = { Text("显示 SIM 信息") },
                    )
                    HorizontalDivider()
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        val scale = state.draftTheme.conversationDetail.textScale
                        Text(
                            text = "文字缩放 ${"%.2f".format(Locale.US, scale)}x",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = "范围 $MIN_CONVERSATION_DETAIL_TEXT_SCALE – $MAX_CONVERSATION_DETAIL_TEXT_SCALE",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Slider(
                            value = scale,
                            onValueChange = viewModel::setTextScale,
                            valueRange =
                                MIN_CONVERSATION_DETAIL_TEXT_SCALE..MAX_CONVERSATION_DETAIL_TEXT_SCALE,
                            enabled = !state.isSaving,
                        )
                    }
                }
            }

            item(key = "reset_section") {
                SettingsSectionCard(title = "恢复") {
                    ListItem(
                        modifier = Modifier.clickable(enabled = !state.isSaving) {
                            viewModel.resetCurrentMode()
                        },
                        supportingContent = {
                            Text(
                                if (state.previewMode == ThemeMode.LIGHT) {
                                    "重置日间外观为官方默认"
                                } else {
                                    "重置暗黑外观为官方默认"
                                },
                            )
                        },
                        colors = listItemColors(enabled = !state.isSaving),
                        content = { Text("恢复当前主题") },
                    )
                    HorizontalDivider()
                    ListItem(
                        modifier = Modifier.clickable(enabled = !state.isSaving) {
                            showResetAllDialog = true
                        },
                        supportingContent = { Text("重置日间、暗黑与全部非颜色项") },
                        colors = listItemColors(enabled = !state.isSaving),
                        content = { Text("恢复全部") },
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
            title = { Text("有未保存的修改") },
            text = { Text("保存后会应用到会话详情页。是否保存本次修改？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExitDialog = false
                        viewModel.save(exitAfterSave = true)
                    },
                ) {
                    Text("保存并退出")
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
                        Text("不保存")
                    }
                    TextButton(onClick = { showExitDialog = false }) {
                        Text("继续编辑")
                    }
                }
            },
        )
    }

    if (showResetAllDialog) {
        AlertDialog(
            onDismissRequest = { showResetAllDialog = false },
            title = { Text("恢复全部默认？") },
            text = {
                Text("将把日间、暗黑外观以及 SIM、提示文字和文字缩放重置为官方默认。仍需点击保存后才会生效。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetAllDialog = false
                        viewModel.resetAll()
                    },
                ) {
                    Text("恢复全部")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetAllDialog = false }) {
                    Text("取消")
                }
            },
        )
    }

    val activeColorTarget = colorTarget
    if (activeColorTarget != null) {
        ConversationDetailPreviewTheme(mode = state.previewMode) { previewScheme ->
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
                text = "系统高对比度文字已启用，颜色和背景图片自定义暂时不会生效",
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
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
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
    val selectedActsAsBackground: Boolean = false,
)

private fun colorPickerConfig(
    target: ThemeColorTarget,
    appearance: ConversationDetailAppearance,
    colorScheme: ColorScheme,
): ColorPickerConfig {
    val defaultText = colorScheme.onSurface
    val defaultBubble = colorScheme.surfaceVariant
    val receivedText = appearance.receivedTextColor.resolveOr(colorScheme, defaultText)
    val receivedBubble = appearance.receivedBubbleColor.resolveOr(colorScheme, defaultBubble)
    val sentText = appearance.sentTextColor.resolveOr(colorScheme, defaultText)
    val sentBubble = appearance.sentBubbleColor.resolveOr(colorScheme, defaultBubble)
    return when (target) {
        ThemeColorTarget.RECEIVED_TEXT -> ColorPickerConfig(
            title = "接收文字颜色",
            current = appearance.receivedTextColor,
            comparisonBackground = receivedBubble,
        )
        ThemeColorTarget.RECEIVED_BUBBLE -> ColorPickerConfig(
            title = "接收气泡颜色",
            current = appearance.receivedBubbleColor,
            comparisonBackground = receivedText,
            selectedActsAsBackground = true,
        )
        ThemeColorTarget.SENT_TEXT -> ColorPickerConfig(
            title = "发送文字颜色",
            current = appearance.sentTextColor,
            comparisonBackground = sentBubble,
        )
        ThemeColorTarget.SENT_BUBBLE -> ColorPickerConfig(
            title = "发送气泡颜色",
            current = appearance.sentBubbleColor,
            comparisonBackground = sentText,
            selectedActsAsBackground = true,
        )
        ThemeColorTarget.INPUT_BACKGROUND -> ColorPickerConfig(
            title = "输入区域背景色",
            current = appearance.inputBackgroundColor,
            comparisonBackground = colorScheme.onSurfaceVariant,
            selectedActsAsBackground = true,
        )
    }
}

private fun colorReferenceLabel(reference: ThemeColorReference?): String {
    if (reference == null) {
        return "官方默认"
    }
    return when (reference.type) {
        ThemeColorType.MATERIAL_ROLE -> {
            val role = MaterialColorRole.fromStorageValue(reference.value)
            role?.let { "MD3 · ${it.storageValue}" } ?: "官方默认"
        }
        ThemeColorType.CUSTOM_ARGB -> reference.value.ifBlank { "自定义" }
    }
}
