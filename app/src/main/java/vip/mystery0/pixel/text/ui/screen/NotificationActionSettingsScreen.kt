package vip.mystery0.pixel.text.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import org.koin.androidx.compose.koinViewModel
import vip.mystery0.pixel.text.domain.settings.NOTIFICATION_CODE_PLACEHOLDER
import vip.mystery0.pixel.text.domain.settings.NotificationQuickActionConfig
import vip.mystery0.pixel.text.domain.settings.NotificationQuickActionType
import vip.mystery0.pixel.text.domain.settings.defaultLabelTemplate
import vip.mystery0.pixel.text.domain.settings.renderLabel
import vip.mystery0.pixel.text.domain.settings.validationError
import vip.mystery0.pixel.text.viewmodel.SettingsViewModel

@Composable
fun NotificationActionSettingsScreen(
    viewModel: SettingsViewModel = koinViewModel(),
    onNavigateBack: () -> Unit,
) {
    val settings by viewModel.settings.collectAsState()
    val savedConfigs = settings.notificationQuickActionConfigs
    var draftActions by remember {
        mutableStateOf(savedConfigs.toDrafts())
    }
    var showDiscardDialog by remember { mutableStateOf(false) }

    val currentConfigs = remember(draftActions) { draftActions.toConfigs() }
    val validationErrors = remember(draftActions) {
        draftActions.associate { draft ->
            val config = NotificationQuickActionConfig(
                type = draft.type,
                labelTemplate = draft.labelTemplate.trim(),
                order = draftActions.indexOfFirst { it.type == draft.type }.coerceAtLeast(0)
            )
            draft.type to config.validationError()
        }
    }
    val hasChanges = currentConfigs != savedConfigs
    val canSave = hasChanges && validationErrors.values.all { it == null }

    fun saveAndExit() {
        if (!canSave) return
        viewModel.setNotificationQuickActionConfigs(currentConfigs)
        onNavigateBack()
    }

    fun requestNavigateBack() {
        if (hasChanges) {
            showDiscardDialog = true
        } else {
            onNavigateBack()
        }
    }

    BackHandler(onBack = ::requestNavigateBack)

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = ::requestNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                title = {
                    Text(
                        text = "通知快捷操作",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                actions = {
                    TextButton(
                        onClick = ::saveAndExit,
                        enabled = canSave
                    ) {
                        Text("保存")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0.dp),
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(paddingValues),
            contentPadding = PaddingValues(
                start = 20.dp,
                top = paddingValues.calculateTopPadding() + 16.dp,
                end = 20.dp,
                bottom = 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item(key = "helper_card") {
                NotificationActionHelperCard()
            }
            item(key = "preview_card") {
                NotificationPreviewCard(actions = currentConfigs)
            }
            item(key = "editor_title") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "按钮顺序与文案",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "长按右侧图标拖动排序；普通短信会自动隐藏复制按钮，并保持其他按钮的相对顺序",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            item(key = "editor_list") {
                ReorderableNotificationActionList(
                    actions = draftActions,
                    validationErrors = validationErrors,
                    onActionsChange = { draftActions = it },
                    onLabelChange = { type, label ->
                        draftActions = draftActions.map { action ->
                            if (action.type == type) {
                                action.copy(labelTemplate = label)
                            } else {
                                action
                            }
                        }
                    }
                )
            }
            item(key = "navigation_bar_spacer") {
                Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("保存更改？") },
            text = {
                Text(
                    if (validationErrors.values.all { it == null }) {
                        "当前修改尚未保存，离开后会丢失。"
                    } else {
                        "当前修改尚未保存，且仍有文案错误需要修正。"
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardDialog = false
                        saveAndExit()
                    },
                    enabled = canSave
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            showDiscardDialog = false
                            onNavigateBack()
                        }
                    ) {
                        Text("放弃更改")
                    }
                    TextButton(onClick = { showDiscardDialog = false }) {
                        Text("继续编辑")
                    }
                }
            }
        )
    }
}

@Composable
private fun NotificationActionHelperCard() {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(Icons.Rounded.Info, contentDescription = null)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "验证码按钮支持可选占位符 {code}",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = "文案不能为空，最多 4 个中文或 8 个英文；验证码按钮只计算固定文案部分长度",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NotificationPreviewCard(actions: List<NotificationQuickActionConfig>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "验证码通知预览",
                style = MaterialTheme.typography.titleMedium
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Forum,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Pixel 安全中心",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = "刚刚",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = "您的登录验证码为 123456，5 分钟内有效，请勿泄露给他人。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                actions.forEach { action ->
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                        contentColor = MaterialTheme.colorScheme.primary
                    ) {
                        Text(
                            text = action.renderLabel("123456"),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReorderableNotificationActionList(
    actions: List<NotificationActionDraft>,
    validationErrors: Map<NotificationQuickActionType, String?>,
    onActionsChange: (List<NotificationActionDraft>) -> Unit,
    onLabelChange: (NotificationQuickActionType, String) -> Unit,
) {
    val latestActions by rememberUpdatedState(actions)
    val latestOnActionsChange by rememberUpdatedState(onActionsChange)
    var dragState by remember { mutableStateOf<DragState?>(null) }
    val itemBounds = remember { mutableStateMapOf<NotificationQuickActionType, ItemBounds>() }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        actions.forEach { action ->
            key(action.type) {
                val currentDragState = dragState
                val isDragging = currentDragState?.type == action.type
                val currentBounds = itemBounds[action.type]
                val translationY = if (isDragging && currentBounds != null) {
                    currentDragState.draggedCenterY - currentBounds.centerY
                } else {
                    0f
                }
                NotificationActionEditorCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { coordinates ->
                            itemBounds[action.type] = ItemBounds(
                                top = coordinates.positionInParent().y,
                                height = coordinates.size.height.toFloat()
                            )
                        }
                        .graphicsLayer {
                            this.translationY = translationY
                        }
                        .zIndex(if (isDragging) 1f else 0f),
                    type = action.type,
                    labelTemplate = action.labelTemplate,
                    error = validationErrors[action.type],
                    onLabelChange = { onLabelChange(action.type, it) },
                    dragHandleModifier = Modifier.pointerInput(action.type) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                val bounds = itemBounds[action.type]
                                if (bounds != null) {
                                    dragState = DragState(
                                        type = action.type,
                                        draggedCenterY = bounds.centerY
                                    )
                                }
                            },
                            onDragCancel = {
                                dragState = null
                            },
                            onDragEnd = {
                                dragState = null
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val state = dragState ?: return@detectDragGesturesAfterLongPress
                                val actionsNow = latestActions
                                val boundsNow = itemBounds
                                val currentIndex =
                                    actionsNow.indexOfFirst { item -> item.type == state.type }
                                if (currentIndex < 0) return@detectDragGesturesAfterLongPress
                                val newDraggedCenter = state.draggedCenterY + dragAmount.y
                                var targetIndex = currentIndex

                                while (targetIndex > 0) {
                                    val previousType = actionsNow[targetIndex - 1].type
                                    val previousBounds = boundsNow[previousType] ?: break
                                    if (newDraggedCenter < previousBounds.centerY) {
                                        targetIndex -= 1
                                    } else {
                                        break
                                    }
                                }
                                while (targetIndex < actionsNow.lastIndex) {
                                    val nextType = actionsNow[targetIndex + 1].type
                                    val nextBounds = boundsNow[nextType] ?: break
                                    if (newDraggedCenter > nextBounds.centerY) {
                                        targetIndex += 1
                                    } else {
                                        break
                                    }
                                }

                                if (targetIndex != currentIndex) {
                                    val reordered = actionsNow.toMutableList().apply {
                                        move(currentIndex, targetIndex)
                                    }
                                    latestOnActionsChange(reordered)
                                }
                                dragState = state.copy(draggedCenterY = newDraggedCenter)
                            }
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun NotificationActionEditorCard(
    modifier: Modifier,
    type: NotificationQuickActionType,
    labelTemplate: String,
    error: String?,
    onLabelChange: (String) -> Unit,
    dragHandleModifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = type.settingTitle(),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = type.settingSummary(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box(
                    modifier = dragHandleModifier
                        .size(40.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DragHandle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            OutlinedTextField(
                value = labelTemplate,
                onValueChange = onLabelChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = error != null,
                label = { Text("按钮文案") },
                supportingText = {
                    Text(
                        text = error
                            ?: if (type == NotificationQuickActionType.COPY_CODE) {
                                "可选使用 $NOTIFICATION_CODE_PLACEHOLDER，占位符不计入长度"
                            } else {
                                "最多 4 个中文或 8 个英文"
                            }
                    )
                }
            )
        }
    }
}

private fun List<NotificationActionDraft>.toConfigs(): List<NotificationQuickActionConfig> {
    return mapIndexed { index, draft ->
        NotificationQuickActionConfig(
            type = draft.type,
            labelTemplate = draft.labelTemplate.trim(),
            order = index
        )
    }
}

private fun List<NotificationQuickActionConfig>.toDrafts(): List<NotificationActionDraft> {
    return map { config ->
        NotificationActionDraft(
            type = config.type,
            labelTemplate = config.labelTemplate.ifBlank { config.type.defaultLabelTemplate() }
        )
    }
}

private fun MutableList<NotificationActionDraft>.move(fromIndex: Int, toIndex: Int) {
    if (fromIndex == toIndex) return
    val item = removeAt(fromIndex)
    add(toIndex, item)
}

private data class NotificationActionDraft(
    val type: NotificationQuickActionType,
    val labelTemplate: String,
)

private data class DragState(
    val type: NotificationQuickActionType,
    val draggedCenterY: Float,
)

private data class ItemBounds(
    val top: Float,
    val height: Float,
) {
    val centerY: Float
        get() = top + height / 2f
}
