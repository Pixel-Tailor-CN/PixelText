package vip.mystery0.pixel.text.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.koin.androidx.compose.koinViewModel
import vip.mystery0.pixel.text.domain.settings.ConversationSwipeAction
import vip.mystery0.pixel.text.viewmodel.SettingsViewModel

@Composable
fun SwipeActionSettingsScreen(
    viewModel: SettingsViewModel = koinViewModel(),
    onNavigateBack: () -> Unit,
) {
    val settings by viewModel.settings.collectAsState()
    var editingDirection by remember { mutableStateOf<SwipeDirection?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                title = {
                    Text(
                        "滑动操作",
                        style = MaterialTheme.typography.titleLarge
                    )
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
                start = 24.dp,
                top = paddingValues.calculateTopPadding() + 28.dp,
                end = 24.dp,
                bottom = 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(38.dp)
        ) {
            item(key = "right_swipe_action") {
                SwipeActionOverviewSection(
                    direction = SwipeDirection.Right,
                    selectedAction = settings.rightSwipeAction,
                    onCustomizeClick = { editingDirection = SwipeDirection.Right }
                )
            }
            item(key = "left_swipe_action") {
                SwipeActionOverviewSection(
                    direction = SwipeDirection.Left,
                    selectedAction = settings.leftSwipeAction,
                    onCustomizeClick = { editingDirection = SwipeDirection.Left }
                )
            }
            item(key = "navigation_bar_spacer") {
                Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        }
    }

    editingDirection?.let { direction ->
        SwipeActionPickerDialog(
            direction = direction,
            selectedAction = when (direction) {
                SwipeDirection.Right -> settings.rightSwipeAction
                SwipeDirection.Left -> settings.leftSwipeAction
            },
            onDismiss = { editingDirection = null },
            onActionSelected = { action ->
                when (direction) {
                    SwipeDirection.Right -> viewModel.setRightSwipeAction(action)
                    SwipeDirection.Left -> viewModel.setLeftSwipeAction(action)
                }
                editingDirection = null
            }
        )
    }
}

@Composable
private fun SwipeActionOverviewSection(
    direction: SwipeDirection,
    selectedAction: ConversationSwipeAction,
    onCustomizeClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = direction.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = selectedAction.settingTitle(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onCustomizeClick) {
                Text("自定义")
            }
        }
        SwipeGestureIllustration(
            direction = direction,
            action = selectedAction
        )
    }
}

@Composable
private fun SwipeGestureIllustration(
    direction: SwipeDirection,
    action: ConversationSwipeAction,
) {
    val actionWidth = 88.dp
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(84.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            SwipeActionPanel(
                modifier = Modifier
                    .align(direction.actionAlignment)
                    .width(actionWidth)
                    .fillMaxHeight(),
                direction = direction,
                action = action
            )
            MessagePreview(
                direction = direction,
                actionWidth = actionWidth,
                modifier = Modifier.align(Alignment.CenterStart)
            )
        }
    }
}

@Composable
private fun SwipeActionPanel(
    modifier: Modifier,
    direction: SwipeDirection,
    action: ConversationSwipeAction,
) {
    val colors = action.previewColors()
    Box(
        modifier = modifier
            .clip(direction.actionShape)
            .background(colors.container),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = action.previewIcon(),
            contentDescription = null,
            tint = colors.content,
            modifier = Modifier.size(26.dp)
        )
    }
}

@Composable
private fun MessagePreview(
    direction: SwipeDirection,
    actionWidth: Dp,
    modifier: Modifier = Modifier,
) {
    val startPadding = if (direction == SwipeDirection.Right) actionWidth + 18.dp else 18.dp
    val endPadding = if (direction == SwipeDirection.Left) actionWidth + 18.dp else 18.dp
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = startPadding, end = endPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (direction == SwipeDirection.Right) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Forum,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MessagePreviewLine(widthFraction = 0.72f)
            MessagePreviewLine(widthFraction = 0.46f)
        }
    }
}

@Composable
private fun MessagePreviewLine(widthFraction: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(8.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
    )
}

@Composable
private fun SwipeActionPickerDialog(
    direction: SwipeDirection,
    selectedAction: ConversationSwipeAction,
    onDismiss: () -> Unit,
    onActionSelected: (ConversationSwipeAction) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(direction.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ConversationSwipeAction.entries.forEach { action ->
                    ConversationSwipeActionOption(
                        action = action,
                        selected = selectedAction == action,
                        onClick = { onActionSelected(action) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun ConversationSwipeActionOption(
    action: ConversationSwipeAction,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick
        )
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(
                text = action.settingTitle(),
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = action.settingSummary(),
                style = MaterialTheme.typography.bodySmall,
                color = if (action == ConversationSwipeAction.DELETE) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

private enum class SwipeDirection(
    val title: String,
    val actionAlignment: Alignment,
    val actionShape: RoundedCornerShape,
) {
    Right(
        title = "向右滑动",
        actionAlignment = Alignment.CenterStart,
        actionShape = RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)
    ),
    Left(
        title = "向左滑动",
        actionAlignment = Alignment.CenterEnd,
        actionShape = RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp)
    )
}

private data class SwipeActionPreviewColors(
    val container: Color,
    val content: Color,
)

@Composable
private fun ConversationSwipeAction.previewColors(): SwipeActionPreviewColors {
    return when (this) {
        ConversationSwipeAction.ARCHIVE -> SwipeActionPreviewColors(
            container = MaterialTheme.colorScheme.primaryContainer,
            content = MaterialTheme.colorScheme.onPrimaryContainer
        )

        ConversationSwipeAction.DELETE -> SwipeActionPreviewColors(
            container = MaterialTheme.colorScheme.error,
            content = MaterialTheme.colorScheme.onError
        )

        ConversationSwipeAction.TOGGLE_READ -> SwipeActionPreviewColors(
            container = MaterialTheme.colorScheme.primary,
            content = MaterialTheme.colorScheme.onPrimary
        )

        ConversationSwipeAction.NONE -> SwipeActionPreviewColors(
            container = MaterialTheme.colorScheme.surfaceContainerHighest,
            content = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun ConversationSwipeAction.previewIcon(): ImageVector {
    return when (this) {
        ConversationSwipeAction.ARCHIVE -> Icons.Rounded.Archive
        ConversationSwipeAction.DELETE -> Icons.Rounded.Delete
        ConversationSwipeAction.TOGGLE_READ -> Icons.Rounded.DoneAll
        ConversationSwipeAction.NONE -> Icons.Rounded.Close
    }
}
