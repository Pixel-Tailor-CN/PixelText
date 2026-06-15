package vip.mystery0.pixel.text.ui

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import vip.mystery0.pixel.text.ui.message.search.SearchScreen
import vip.mystery0.pixel.text.ui.screen.ArchivedConversationListScreen
import vip.mystery0.pixel.text.ui.screen.ConversationDetailScreen
import vip.mystery0.pixel.text.ui.screen.ConversationListScreen
import vip.mystery0.pixel.text.ui.screen.SampleSubmissionScreen
import vip.mystery0.pixel.text.ui.screen.SettingsScreen
import vip.mystery0.pixel.text.ui.screen.SpamConversationListScreen

/**
 * 通知点击等外部入口带过来的会话跳转目标。
 */
data class ConversationDeepLink(
    val threadId: Long,
    val address: String,
)

@Composable
fun AppNavigation(
    pendingDeepLink: ConversationDeepLink? = null,
    onDeepLinkConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()

    // 收到外部 deep link 时，直接跳转到对应会话详情
    LaunchedEffect(pendingDeepLink) {
        val link = pendingDeepLink ?: return@LaunchedEffect
        navController.navigate(conversationDetailRoute(link.threadId, link.address))
        onDeepLinkConsumed()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        NavHost(
            navController = navController,
            startDestination = "conversations",
            modifier = Modifier.fillMaxSize(),
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None }
        ) {
            composable("conversations") {
                ConversationListScreen(
                    onNavigateToDetail = { threadId, address ->
                        navController.navigate(conversationDetailRoute(threadId, address))
                    },
                    onNavigateToSearch = {
                        navController.navigate("search")
                    },
                    onNavigateToMock = {
                        navController.navigate("mock_messages")
                    },
                    onNavigateToArchive = {
                        navController.navigate("archived_conversations")
                    },
                    onNavigateToSpam = {
                        navController.navigate("spam_conversations")
                    },
                    onNavigateToSettings = {
                        navController.navigate("settings")
                    }
                )
            }
            composable("archived_conversations") {
                ArchivedConversationListScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToDetail = { threadId, address ->
                        navController.navigate(conversationDetailRoute(threadId, address))
                    }
                )
            }
            composable("spam_conversations") {
                SpamConversationListScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToDetail = { threadId, address ->
                        navController.navigate(conversationDetailRoute(threadId, address))
                    }
                )
            }
            composable("settings") {
                SettingsScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToSampleSubmission = {
                        navController.navigate("sample_submission")
                    }
                )
            }
            composable("sample_submission") {
                SampleSubmissionScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(
                route = "search",
                enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left) },
                exitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Left) },
                popEnterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Right) },
                popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right) }
            ) {
                SearchScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onResultClick = { message ->
                        navController.navigate(
                            conversationDetailRoute(
                                message.threadId,
                                message.sender
                            )
                        )
                    }
                )
            }
            composable("conversation_detail/{threadId}/{address}") { backStackEntry ->
                val threadId =
                    backStackEntry.arguments?.getString("threadId")?.toLongOrNull() ?: -1L
                val address = backStackEntry.arguments?.getString("address") ?: ""
                ConversationDetailScreen(
                    threadId = threadId,
                    address = address,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable("mock_messages") {
                vip.mystery0.pixel.text.ui.screen.mock.MockMessageScreen()
            }
        }
    }
}

private fun conversationDetailRoute(threadId: Long, address: String): String {
    return "conversation_detail/$threadId/${Uri.encode(address)}"
}
