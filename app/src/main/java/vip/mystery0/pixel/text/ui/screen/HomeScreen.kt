package vip.mystery0.pixel.text.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

private const val CONVERSATIONS_ROUTE = "conversations"
private const val VERIFICATION_CODES_ROUTE = "verification_codes"

@Composable
fun HomeScreen(
    onNavigateToDetail: (Long, String) -> Unit,
    onNavigateToSearch: () -> Unit,
    onNavigateToMock: () -> Unit,
    onNavigateToArchive: () -> Unit,
    onNavigateToSpam: () -> Unit,
    onNavigateToSettings: () -> Unit,
) {
    val conversationListViewModel: ConversationListViewModel = koinViewModel()
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: CONVERSATIONS_ROUTE
    var isFloatingActionButtonExpanded by remember { mutableStateOf(true) }
    var isConversationFloatingActionButtonVisible by remember { mutableStateOf(true) }
    var showNewChatSheet by remember { mutableStateOf(false) }
    val startChat = { showNewChatSheet = true }

    LaunchedEffect(currentRoute) {
        isFloatingActionButtonExpanded = true
    }

    BackHandler(enabled = currentRoute == VERIFICATION_CODES_ROUTE) {
        navController.popBackStack(CONVERSATIONS_ROUTE, inclusive = false)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            NavigationBar {
                HomeDestination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = currentRoute == destination.route,
                        onClick = {
                            isFloatingActionButtonExpanded = true
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
                                Icon(
                                    painter = painterResource(R.drawable.ic_notification_sms),
                                    contentDescription = destination.label,
                                )
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
        },
        floatingActionButton = {
            if (
                currentRoute == VERIFICATION_CODES_ROUTE ||
                isConversationFloatingActionButtonVisible
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
                    expanded = isFloatingActionButtonExpanded,
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
                    onNavigateToDetail = onNavigateToDetail,
                    onNavigateToSearch = onNavigateToSearch,
                    onNavigateToMock = onNavigateToMock,
                    onNavigateToArchive = onNavigateToArchive,
                    onNavigateToSpam = onNavigateToSpam,
                    onNavigateToSettings = onNavigateToSettings,
                    onScrollDirectionChanged = { isScrollingDown ->
                        isFloatingActionButtonExpanded = !isScrollingDown
                    },
                    onFloatingActionButtonVisibilityChanged = { visible ->
                        isConversationFloatingActionButtonVisible = visible
                    },
                )
            }
            composable(VERIFICATION_CODES_ROUTE) {
                VerificationCodeScreen(
                    onNavigateToConversation = onNavigateToDetail,
                    onNavigateToMock = onNavigateToMock,
                    onNavigateToArchive = onNavigateToArchive,
                    onNavigateToSpam = onNavigateToSpam,
                    onNavigateToSettings = onNavigateToSettings,
                    conversationListViewModel = conversationListViewModel,
                    onScrollDirectionChanged = { isScrollingDown ->
                        isFloatingActionButtonExpanded = !isScrollingDown
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
