package vip.mystery0.pixel.text.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import org.koin.androidx.compose.koinViewModel
import vip.mystery0.pixel.text.R
import vip.mystery0.pixel.text.viewmodel.ConversationListViewModel
import vip.mystery0.pixel.text.viewmodel.UnreadBadgeViewModel

private const val CONVERSATIONS_ROUTE = "conversations"
private const val VERIFICATION_CODES_ROUTE = "verification_codes"
private const val NAVIGATION_CONTROLS_ANIMATION_DURATION_MILLIS = 250

@Composable
fun HomeScreen(
    onNavigateToDetail: (Long, String) -> Unit,
    onNavigateToMessage: (Long, String, Long) -> Unit,
    onNavigateToSearch: () -> Unit,
    onNavigateToMock: () -> Unit,
    onNavigateToArchive: () -> Unit,
    onNavigateToSpam: () -> Unit,
    onNavigateToSettings: () -> Unit,
) {
    val conversationListViewModel: ConversationListViewModel = koinViewModel()
    val unreadBadgeViewModel: UnreadBadgeViewModel = koinViewModel()
    val unreadCount by unreadBadgeViewModel.unreadCount.collectAsState()
    val settings by conversationListViewModel.settings.collectAsState()
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: CONVERSATIONS_ROUTE
    var areNavigationControlsVisible by remember { mutableStateOf(true) }
    var isConversationFloatingActionButtonVisible by remember { mutableStateOf(true) }
    var showNewChatSheet by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val startChat = { showNewChatSheet = true }

    LaunchedEffect(currentRoute) {
        areNavigationControlsVisible = true
    }

    BackHandler(enabled = currentRoute == VERIFICATION_CODES_ROUTE) {
        navController.popBackStack(CONVERSATIONS_ROUTE, inclusive = false)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            AnimatedVisibility(
                visible = areNavigationControlsVisible,
                enter = slideInVertically(
                    animationSpec = tween(NAVIGATION_CONTROLS_ANIMATION_DURATION_MILLIS),
                    initialOffsetY = { it },
                ) + expandVertically(
                    animationSpec = tween(NAVIGATION_CONTROLS_ANIMATION_DURATION_MILLIS),
                    expandFrom = Alignment.Bottom,
                ) + fadeIn(tween(NAVIGATION_CONTROLS_ANIMATION_DURATION_MILLIS)),
                exit = slideOutVertically(
                    animationSpec = tween(NAVIGATION_CONTROLS_ANIMATION_DURATION_MILLIS),
                    targetOffsetY = { it },
                ) + shrinkVertically(
                    animationSpec = tween(NAVIGATION_CONTROLS_ANIMATION_DURATION_MILLIS),
                    shrinkTowards = Alignment.Bottom,
                ) + fadeOut(tween(NAVIGATION_CONTROLS_ANIMATION_DURATION_MILLIS)),
            ) {
                NavigationBar {
                    HomeDestination.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = {
                                areNavigationControlsVisible = true
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                if (destination == HomeDestination.Conversations) {
                                    BadgedBox(
                                        badge = {
                                            if (settings.unreadBadgeEnabled && unreadCount > 0) {
                                                Badge {
                                                    Text(
                                                        if (unreadCount > 99) "99+"
                                                        else unreadCount.toString()
                                                    )
                                                }
                                            }
                                        },
                                    ) {
                                        Icon(
                                            painter = painterResource(
                                                R.drawable.ic_notification_sms
                                            ),
                                            contentDescription = destination.label,
                                        )
                                    }
                                } else {
                                    Icon(
                                        imageVector = destination.icon,
                                        contentDescription = destination.label,
                                    )
                                }
                            },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            AnimatedVisibility(
                visible = areNavigationControlsVisible &&
                    currentRoute == CONVERSATIONS_ROUTE &&
                    isConversationFloatingActionButtonVisible,
                enter = slideInVertically(
                    animationSpec = tween(NAVIGATION_CONTROLS_ANIMATION_DURATION_MILLIS),
                    initialOffsetY = { it / 2 },
                ) + fadeIn(tween(NAVIGATION_CONTROLS_ANIMATION_DURATION_MILLIS)) +
                    scaleIn(tween(NAVIGATION_CONTROLS_ANIMATION_DURATION_MILLIS)),
                exit = slideOutVertically(
                    animationSpec = tween(NAVIGATION_CONTROLS_ANIMATION_DURATION_MILLIS),
                    targetOffsetY = { it / 2 },
                ) + fadeOut(tween(NAVIGATION_CONTROLS_ANIMATION_DURATION_MILLIS)) +
                    scaleOut(tween(NAVIGATION_CONTROLS_ANIMATION_DURATION_MILLIS)),
            ) {
                ExtendedFloatingActionButton(
                    modifier = Modifier.clearAndSetSemantics {
                        contentDescription = "开始聊天"
                        role = Role.Button
                        onClick(label = "开始聊天") {
                            startChat()
                            true
                        }
                    },
                    expanded = true,
                    text = { Text("开始聊天") },
                    icon = {
                        Icon(
                            Icons.AutoMirrored.Rounded.Chat,
                            contentDescription = null,
                        )
                    },
                    onClick = startChat,
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { contentPadding ->
        NavHost(
            navController = navController,
            startDestination = CONVERSATIONS_ROUTE,
            modifier = Modifier.padding(contentPadding),
        ) {
            composable(CONVERSATIONS_ROUTE) {
                ConversationListScreen(
                    viewModel = conversationListViewModel,
                    snackbarHostState = snackbarHostState,
                    onNavigateToDetail = onNavigateToDetail,
                    onNavigateToSearch = onNavigateToSearch,
                    onNavigateToMock = onNavigateToMock,
                    onNavigateToArchive = onNavigateToArchive,
                    onNavigateToSpam = onNavigateToSpam,
                    onNavigateToSettings = onNavigateToSettings,
                    onScrollDirectionChanged = { isScrollingDown ->
                        areNavigationControlsVisible = !isScrollingDown
                    },
                    onFloatingActionButtonVisibilityChanged = { visible ->
                        isConversationFloatingActionButtonVisible = visible
                    },
                )
            }
            composable(VERIFICATION_CODES_ROUTE) {
                VerificationCodeScreen(
                    onNavigateToConversation = onNavigateToMessage,
                    onNavigateToMock = onNavigateToMock,
                    onNavigateToArchive = onNavigateToArchive,
                    onNavigateToSpam = onNavigateToSpam,
                    onNavigateToSettings = onNavigateToSettings,
                    conversationListViewModel = conversationListViewModel,
                    onScrollDirectionChanged = { isScrollingDown ->
                        areNavigationControlsVisible = !isScrollingDown
                    },
                )
            }
        }
    }

    if (showNewChatSheet) {
        ModalBottomSheet(
            onDismissRequest = { showNewChatSheet = false },
            sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden),
        ) {
            NewChatBottomSheet(
                onDismiss = { showNewChatSheet = false },
                onNavigateToDetail = onNavigateToDetail,
            )
        }
    }
}

private enum class HomeDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    Conversations(
        route = CONVERSATIONS_ROUTE,
        label = "会话",
        icon = Icons.Rounded.ChatBubble,
    ),
    VerificationCodes(
        route = VERIFICATION_CODES_ROUTE,
        label = "验证码",
        icon = Icons.Rounded.Password,
    ),
}
