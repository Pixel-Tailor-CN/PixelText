package vip.mystery0.pixel.text.ui

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import vip.mystery0.pixel.text.ui.message.search.SearchScreen
import vip.mystery0.pixel.text.ui.screen.ArchivedConversationListScreen
import vip.mystery0.pixel.text.ui.screen.ConversationDetailScreen
import vip.mystery0.pixel.text.ui.screen.ConversationListScreen
import vip.mystery0.pixel.text.ui.screen.SAMPLE_SUBMISSION_DRAFT_CONTENT
import vip.mystery0.pixel.text.ui.screen.SAMPLE_SUBMISSION_DRAFT_SENDER
import vip.mystery0.pixel.text.ui.screen.SampleSubmissionScreen
import vip.mystery0.pixel.text.ui.screen.SettingsScreen
import vip.mystery0.pixel.text.ui.screen.SpamConversationListScreen
import vip.mystery0.pixel.text.ui.screen.SwipeActionSettingsScreen

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
            enterTransition = { activityLikeEnterTransition() },
            exitTransition = { activityLikeExitTransition() },
            popEnterTransition = { activityLikePopEnterTransition() },
            popExitTransition = { activityLikePopExitTransition() }
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
                    },
                    onNavigateToSwipeActions = {
                        navController.navigate("swipe_actions")
                    }
                )
            }
            composable("swipe_actions") {
                SwipeActionSettingsScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable("sample_submission") {
                val draftHandle = navController.previousBackStackEntry?.savedStateHandle
                val initialContent = remember(it) {
                    draftHandle?.remove<String>(SAMPLE_SUBMISSION_DRAFT_CONTENT).orEmpty()
                }
                val initialSender = remember(it) {
                    draftHandle?.remove<String>(SAMPLE_SUBMISSION_DRAFT_SENDER).orEmpty()
                }
                SampleSubmissionScreen(
                    onNavigateBack = { navController.popBackStack() },
                    initialContent = initialContent,
                    initialSender = initialSender
                )
            }
            composable("search") {
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
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToSampleSubmission = { content, sender ->
                        navController.currentBackStackEntry?.savedStateHandle?.apply {
                            set(SAMPLE_SUBMISSION_DRAFT_CONTENT, content)
                            set(SAMPLE_SUBMISSION_DRAFT_SENDER, sender)
                        }
                        navController.navigate("sample_submission")
                    }
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

private fun AnimatedContentTransitionScope<*>.activityLikeEnterTransition(): EnterTransition {
    return slideIntoContainer(
        towards = AnimatedContentTransitionScope.SlideDirection.Left,
        animationSpec = tween(
            durationMillis = NAV_TRANSITION_DURATION_MS,
            easing = FastOutSlowInEasing
        )
    ) + fadeIn(animationSpec = tween(durationMillis = NAV_ENTER_FADE_DURATION_MS))
}

private fun AnimatedContentTransitionScope<*>.activityLikeExitTransition(): ExitTransition {
    return slideOutOfContainer(
        towards = AnimatedContentTransitionScope.SlideDirection.Left,
        animationSpec = tween(
            durationMillis = NAV_TRANSITION_DURATION_MS,
            easing = FastOutSlowInEasing
        )
    ) + delayedExitFadeOut()
}

private fun AnimatedContentTransitionScope<*>.activityLikePopEnterTransition(): EnterTransition {
    return slideIntoContainer(
        towards = AnimatedContentTransitionScope.SlideDirection.Right,
        animationSpec = tween(
            durationMillis = NAV_TRANSITION_DURATION_MS,
            easing = FastOutSlowInEasing
        )
    ) + fadeIn(animationSpec = tween(durationMillis = NAV_ENTER_FADE_DURATION_MS))
}

private fun AnimatedContentTransitionScope<*>.activityLikePopExitTransition(): ExitTransition {
    return slideOutOfContainer(
        towards = AnimatedContentTransitionScope.SlideDirection.Right,
        animationSpec = tween(
            durationMillis = NAV_TRANSITION_DURATION_MS,
            easing = FastOutSlowInEasing
        )
    ) + delayedExitFadeOut()
}

private const val NAV_TRANSITION_DURATION_MS = 300
private const val NAV_ENTER_FADE_DURATION_MS = 90
private const val NAV_EXIT_FADE_HOLD_MS = 225

private fun delayedExitFadeOut(): ExitTransition {
    return fadeOut(
        animationSpec = keyframes {
            durationMillis = NAV_TRANSITION_DURATION_MS
            1f at NAV_EXIT_FADE_HOLD_MS
            0f at NAV_TRANSITION_DURATION_MS
        }
    )
}
