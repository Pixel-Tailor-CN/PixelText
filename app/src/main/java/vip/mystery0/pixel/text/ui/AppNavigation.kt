package vip.mystery0.pixel.text.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import vip.mystery0.pixel.text.ui.message.ConversationDetailScreen
import vip.mystery0.pixel.text.ui.message.ConversationListScreen

@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "conversations",
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None }
    ) {
        composable("conversations") {
            ConversationListScreen(
                onNavigateToDetail = { threadId, address ->
                    navController.navigate("conversation_detail/$threadId/$address")
                },
                onNavigateToMock = {
                    navController.navigate("mock_messages")
                }
            )
        }
        composable("conversation_detail/{threadId}/{address}") { backStackEntry ->
            val threadId = backStackEntry.arguments?.getString("threadId")?.toLongOrNull() ?: -1L
            val address = backStackEntry.arguments?.getString("address") ?: ""
            ConversationDetailScreen(
                threadId = threadId,
                address = address,
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable("mock_messages") {
            vip.mystery0.pixel.text.ui.mock.MockMessageScreen()
        }
    }
}
