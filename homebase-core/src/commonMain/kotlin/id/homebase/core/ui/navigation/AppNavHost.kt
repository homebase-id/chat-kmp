package id.homebase.core.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import id.homebase.chat.ChatListScreen
import id.homebase.core.ui.screens.home.HomeScreen
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun AppNavHost(
    navController: NavHostController = rememberNavController(),
) {
    val startDestination = remember { Route.Home }

    NavHost(navController = navController, startDestination = startDestination) {
        composable<Route.Home> {
            HomeScreen(
                viewModel = koinViewModel(),
                onNavigateToChatList = { navController.navigate(Route.ChatList) }
            )
        }

        composable<Route.ChatList> {
            ChatListScreen(
                viewModel = koinViewModel(),
                onNavigateBack = { navController.popBackStack() },
                onNavigateToMessages = { conversationId ->
                    navController.navigate(Route.ChatMessages(conversationId))
                }
            )

        }

//        // ChatMessages route (shows messages for a conversation)
//        composable<Route.ChatMessages> { backStackEntry ->
//            val conversationId =
//                    backStackEntry.arguments?.read { getString("conversationId") }
//                            ?: error("conversationId missing")
//
//            AuthenticatedRouteWithFlowManager(
//                    authState = youAuthFlowManager.authState,
//                    onUnauthenticated = {
//                        navController.navigate(Route.Login) { popUpTo(0) { inclusive = true } }
//                    }
//            ) {
//                ChatMessagesPage(
//                        conversationId = conversationId,
//                        youAuthFlowManager = youAuthFlowManager,
//                        onNavigateBack = { navController.popBackStack() },
//                        onNavigateToMessageDetail = { driveId, fileId ->
//                            navController.navigate(Route.ChatMessageDetail(driveId, fileId))
//                        }
//                )
//            }
//        }
//
//        // ChatMessageDetail route
//        composable<Route.ChatMessageDetail> { backStackEntry ->
//            val driveId =
//                    backStackEntry.arguments?.read { getString("driveId") }
//                            ?: error("driveId missing")
//
//            val fileId =
//                    backStackEntry.arguments?.read { getString("fileId") }
//                            ?: error("fileId missing")
//
//            val viewModel =
//                    koinViewModel<ChatMessageDetailViewModel>(
//                            parameters = { parametersOf(Uuid.parse(driveId), Uuid.parse(fileId)) }
//                    )
//
//            ChatMessageDetailPage(
//                    viewModel = viewModel,
//                    onNavigateBack = { navController.popBackStack() }
//            )
//        }
    }
}