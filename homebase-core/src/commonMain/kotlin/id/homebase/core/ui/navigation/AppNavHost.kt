package id.homebase.core.ui.navigation

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.window.core.layout.WindowSizeClass
import id.homebase.chat.ChatListScreen
import id.homebase.core.ui.assets.BootstrapChat
import id.homebase.core.ui.screens.home.HomeScreen
import id.homebase.core.ui.screens.settings.SettingsScreen
import org.koin.compose.viewmodel.koinViewModel

sealed class TopLevelRoute(
    val route: Route,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    data object Chat : TopLevelRoute(Route.ChatList, "Chats", BootstrapChat)
    data object Settings : TopLevelRoute(Route.Settings, "Settings", Icons.Default.Settings)
}

@Composable
fun AppNavHost(
    navController: NavHostController = rememberNavController(),
) {
    val adaptiveInfo = currentWindowAdaptiveInfo()
    val useNavigationRail =
        adaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val topLevelRoutes = remember {
        listOf(TopLevelRoute.Chat, TopLevelRoute.Settings)
    }
    
    // Track if we are in a screen where bottom menu should be hidden
    var shouldHideBottomMenu by remember { mutableStateOf(false) }
    val shouldShowBottomNav = !useNavigationRail && !shouldHideBottomMenu

    Scaffold(
        bottomBar = {
            if (shouldShowBottomNav) {
                NavigationBar {
                    topLevelRoutes.forEach { topLevelRoute ->
                        NavigationBarItem(
                            icon = { Icon(topLevelRoute.icon, contentDescription = null) },
                            label = { Text(topLevelRoute.label) },
                            selected = currentDestination?.hasRoute(topLevelRoute.route::class) == true,
                            onClick = {
                                navController.navigate(topLevelRoute.route) {
                                    popUpTo(Route.ChatList) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(paddingValues)
                .padding(paddingValues)
        ) {
            if (useNavigationRail) {
                NavigationRail(
                    header = {
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                ) {
                    topLevelRoutes.forEach { topLevelRoute ->
                        NavigationRailItem(
                            icon = { Icon(topLevelRoute.icon, contentDescription = null) },
                            //label = { Text(topLevelRoute.label) },
                            selected = currentDestination?.hasRoute(topLevelRoute.route::class) == true,
                            onClick = {
                                navController.navigate(topLevelRoute.route) {
                                    popUpTo(Route.ChatList) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }

            NavHost(
                navController = navController,
                startDestination = Route.ChatList,
                modifier = Modifier.weight(1f)
            ) {
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
                        onDetailPaneVisibilityChanged = { isShowingDetail ->
                            shouldHideBottomMenu = isShowingDetail
                        }
                    )
                }

                composable<Route.Settings> {
                    SettingsScreen(
                        viewModel = koinViewModel()
                    )
                }
            }
        }
    }
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
