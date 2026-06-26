package vip.mystery0.pixel.text.ui

import android.net.Uri
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

data class SettingsDeepLink(
    val triggerResourceUpdateCheck: Boolean,
    val requestId: Long,
)

@Composable
fun AppNavigation(
    pendingDeepLink: ConversationDeepLink? = null,
    onDeepLinkConsumed: () -> Unit = {},
    pendingSettingsDeepLink: SettingsDeepLink? = null,
    onSettingsDeepLinkConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    var resourceUpdateCheckRequestId by remember { mutableStateOf<Long?>(null) }

    // 收到外部 deep link 时，直接跳转到对应会话详情
    LaunchedEffect(pendingDeepLink) {
        val link = pendingDeepLink ?: return@LaunchedEffect
        navController.navigate(conversationDetailRoute(link.threadId, link.address))
        onDeepLinkConsumed()
    }

    // 资源更新通知只进入设置页，并把一次性检查请求交给设置页处理。
    LaunchedEffect(pendingSettingsDeepLink) {
        val link = pendingSettingsDeepLink ?: return@LaunchedEffect
        resourceUpdateCheckRequestId = link
            .takeIf { it.triggerResourceUpdateCheck }
            ?.requestId
        navController.navigate("settings") {
            launchSingleTop = true
        }
        onSettingsDeepLinkConsumed()
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
                    },
                    resourceUpdateCheckRequestId = resourceUpdateCheckRequestId,
                    onResourceUpdateCheckRequestConsumed = {
                        resourceUpdateCheckRequestId = null
                    },
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

private fun activityLikeEnterTransition(): EnterTransition {
    return slideInHorizontally(
        initialOffsetX = { fullWidth -> fullWidth },
        animationSpec = tween(
            durationMillis = ACTIVITY_FOREGROUND_DURATION_MS,
            easing = FastOutSlowInEasing
        )
    ) +
            fadeIn(animationSpec = tween(durationMillis = NAV_ENTER_FADE_DURATION_MS)) +
            scaleIn(
                initialScale = ACTIVITY_FOREGROUND_INITIAL_SCALE,
                animationSpec = tween(
                    durationMillis = ACTIVITY_FOREGROUND_DURATION_MS,
                    easing = FastOutSlowInEasing
                )
            )
}

private fun activityLikeExitTransition(): ExitTransition {
    return slideOutHorizontally(
        targetOffsetX = { fullWidth -> -fullWidth / ACTIVITY_BACKGROUND_OFFSET_DIVISOR },
        animationSpec = tween(
            durationMillis = ACTIVITY_BACKGROUND_DURATION_MS,
            easing = FastOutSlowInEasing
        )
    ) +
            activityBackgroundFadeOut() +
            scaleOut(
                targetScale = ACTIVITY_BACKGROUND_TARGET_SCALE,
                animationSpec = tween(
                    durationMillis = ACTIVITY_BACKGROUND_DURATION_MS,
                    easing = FastOutSlowInEasing
                )
            )
}

private fun activityLikePopEnterTransition(): EnterTransition {
    return slideInHorizontally(
        initialOffsetX = { fullWidth -> -fullWidth / ACTIVITY_BACKGROUND_OFFSET_DIVISOR },
        animationSpec = tween(
            durationMillis = ACTIVITY_BACKGROUND_DURATION_MS,
            easing = FastOutSlowInEasing
        )
    ) +
            fadeIn(animationSpec = tween(durationMillis = NAV_ENTER_FADE_DURATION_MS)) +
            scaleIn(
                initialScale = ACTIVITY_BACKGROUND_TARGET_SCALE,
                animationSpec = tween(
                    durationMillis = ACTIVITY_BACKGROUND_DURATION_MS,
                    easing = FastOutSlowInEasing
                )
            )
}

private fun activityLikePopExitTransition(): ExitTransition {
    return slideOutHorizontally(
        targetOffsetX = { fullWidth -> fullWidth },
        animationSpec = tween(
            durationMillis = ACTIVITY_FOREGROUND_DURATION_MS,
            easing = FastOutSlowInEasing
        )
    ) +
            activityForegroundFadeOut() +
            scaleOut(
                targetScale = ACTIVITY_FOREGROUND_POP_EXIT_SCALE,
                animationSpec = tween(
                    durationMillis = ACTIVITY_FOREGROUND_DURATION_MS,
                    easing = FastOutSlowInEasing
                )
            )
}

private const val ACTIVITY_FOREGROUND_DURATION_MS = 300
private const val ACTIVITY_BACKGROUND_DURATION_MS = 260
private const val ACTIVITY_BACKGROUND_OFFSET_DIVISOR = 4
private const val ACTIVITY_FOREGROUND_INITIAL_SCALE = 0.96f
private const val ACTIVITY_FOREGROUND_POP_EXIT_SCALE = 0.90f
private const val ACTIVITY_BACKGROUND_TARGET_SCALE = 0.92f
private const val NAV_ENTER_FADE_DURATION_MS = 90
private const val NAV_EXIT_FADE_DURATION_MS = 90
private const val NAV_EXIT_FADE_DELAY_MS = 120
private const val NAV_FOREGROUND_EXIT_FADE_DELAY_MS = 210

private fun activityBackgroundFadeOut(): ExitTransition {
    return fadeOut(
        animationSpec = tween(
            durationMillis = NAV_EXIT_FADE_DURATION_MS,
            delayMillis = NAV_EXIT_FADE_DELAY_MS
        )
    )
}

private fun activityForegroundFadeOut(): ExitTransition {
    return fadeOut(
        animationSpec = tween(
            durationMillis = NAV_EXIT_FADE_DURATION_MS,
            delayMillis = NAV_FOREGROUND_EXIT_FADE_DELAY_MS
        )
    )
}
