package vip.mystery0.pixel.text.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import vip.mystery0.pixel.text.ui.message.ConversationDetailScreen
import vip.mystery0.pixel.text.ui.message.ConversationListScreen

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "conversations") {
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
