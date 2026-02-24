package id.homebase.core.ui.navigation

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import id.homebase.api.youauth.YouAuthFlowManager
import id.homebase.api.youauth.YouAuthState
import id.homebase.auth.login.LoginScreen
import id.homebase.auth.login.LoginViewModel
import id.homebase.chat.contactinfo.ContactInfoScreen
import id.homebase.chat.conversationlist.ConversationListScreen
import id.homebase.chat.messageinfo.MessageInfoScreen
import id.homebase.chat.newconversation.NewConversationScreen
import id.homebase.core.ui.assets.BootstrapChat
import id.homebase.core.ui.screens.home.HomeScreen
import id.homebase.core.ui.screens.notifications.NotificationSettingsScreen
import id.homebase.core.ui.screens.settings.SettingsScreen
import kotlinx.coroutines.flow.StateFlow
import org.koin.compose.viewmodel.koinViewModel

sealed class TopLevelRoute(
    val route: Route, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    data object Chat : TopLevelRoute(Route.ChatList(), "Chats", BootstrapChat)
    data object Home : TopLevelRoute(Route.Home, "Home", Icons.Default.Home)
}

@Composable
fun AppNavHost(
    navController: NavHostController = rememberNavController(),
    youAuthFlowManager: YouAuthFlowManager
) {

    val authState by youAuthFlowManager.authState.collectAsState()
    val isAuthenticated = authState is YouAuthState.Authenticated

    val adaptiveInfo = currentWindowAdaptiveInfo()
    val useNavigationRail = adaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(
        WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND
    )
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val topLevelRoutes = remember { listOf(TopLevelRoute.Chat, TopLevelRoute.Home) }

    // Track if we're showing only the detail pane (list hidden) in a top level screen
    var showingOnlyDetailPane by remember { mutableStateOf(false) }

    // Check if current destination is a top-level route
    val isTopLevelRoute = topLevelRoutes.any { topLevelRoute ->
        currentDestination?.hasRoute(topLevelRoute.route::class) == true
    }

    // Only show bottom nav if on a top-level route AND not showing only detail pane
    val shouldShowBottomNav =
        isAuthenticated && !useNavigationRail && isTopLevelRoute && !showingOnlyDetailPane

    Scaffold(
        bottomBar = {
            if (shouldShowBottomNav) {
                NavigationBar {
                    topLevelRoutes.forEach { topLevelRoute ->
                        NavigationBarItem(
                            icon = {
                                Icon(
                                    topLevelRoute.icon,
                                    contentDescription = null
                                )
                            },
                            label = { Text(topLevelRoute.label) },
                            selected = currentDestination?.hasRoute(
                                topLevelRoute.route::class
                            ) == true,
                            onClick = {
                                navController.navigate(topLevelRoute.route) {
                                    popUpTo(Route.ChatList()) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            })
                    }
                }
            }
        }) { paddingValues ->
        Row(
            modifier = Modifier.fillMaxSize().consumeWindowInsets(paddingValues)
                .padding(paddingValues)
        ) {
            if (useNavigationRail && isAuthenticated) {
                NavigationRail(header = { Spacer(modifier = Modifier.height(12.dp)) }) {
                    topLevelRoutes.forEach { topLevelRoute ->
                        NavigationRailItem(
                            icon = { Icon(topLevelRoute.icon, contentDescription = null) },
                            // label = { Text(topLevelRoute.label) },
                            selected = currentDestination?.hasRoute(topLevelRoute.route::class) == true,
                            onClick = {
                                navController.navigate(topLevelRoute.route) {
                                    popUpTo(Route.ChatList()) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            })
                    }
                }
            }

            NavHost(
                navController = navController,
                startDestination = if (isAuthenticated) Route.ChatList() else Route.Login,
                modifier = Modifier.weight(1f)
            ) {
                composable<Route.Login> {
                    val vm = koinViewModel<LoginViewModel>()
                    LoginScreen(
                        viewModel = vm, onNavigateHome = {
                            navController.navigate(Route.ChatList()) {
                                popUpTo(Route.Login) { inclusive = true }
                            }
                        })
                }

                composable<Route.Home> {
                    AuthenticatedRouteWithFlowManager(
                        authState = youAuthFlowManager.authState, onUnauthenticated = {
                            navController.navigate(Route.Login) {
                                popUpTo(0) { inclusive = true }
                            }
                        }) {
                        HomeScreen(
                            viewModel = koinViewModel(),
                            onNavigateToChatList = { navController.navigate(Route.ChatList()) })
                    }
                }

                composable<Route.ChatList> {
                    AuthenticatedRouteWithFlowManager(
                        authState = youAuthFlowManager.authState,
                        onUnauthenticated = {
                            navController.navigate(Route.Login) {
                                popUpTo(0) { inclusive = true }
                            }
                        },
                    ) {
                        ConversationListScreen(
                            viewModel = koinViewModel(),
                            onNavigateBack = { navController.popBackStack() },
                            onNavigateToSettingsScreen = {
                                navController.navigate(Route.Settings)
                            },
                            onNavigateToNewConversation = {
                                navController.navigate(Route.NewConversation)
                            },
                            onNavigateToContactInfo = {
                                navController.navigate(Route.ContactInfo(it))
                            },
                            onNavigateToMessageInfo = { conversationId, messageId, fileId ->
                                navController.navigate(
                                    Route.MessageInfo(
                                        conversationId = conversationId.toString(),
                                        messageId = messageId.toString(),
                                        fileId = fileId.toString()
                                    )
                                )
                            },
                            onDetailPaneVisibilityChanged = { showingOnlyDetailPane = it }
                        )
                    }
                }

                composable<Route.NewConversation> {
                    AuthenticatedRouteWithFlowManager(
                        authState = youAuthFlowManager.authState, onUnauthenticated = {
                            navController.navigate(Route.Login) {
                                popUpTo(0) { inclusive = true }
                            }
                        }) {
                        NewConversationScreen(
                            viewModel = koinViewModel(),
                            onNavigateBack = { navController.popBackStack() },
                            onShowConversation = { conversationId ->
                                navController.navigate(Route.ChatList(conversationId.toString())) {
                                    popUpTo(Route.NewConversation) { inclusive = true }
                                }
                            },
                            onShowCreateGroup = {
                                navController.navigate(Route.NewGroup)
                            }
                        )
                    }
                }

                composable<Route.ContactInfo> {
                    AuthenticatedRouteWithFlowManager(
                        authState = youAuthFlowManager.authState, onUnauthenticated = {
                            navController.navigate(Route.Login) {
                                popUpTo(0) { inclusive = true }
                            }
                        }) {
                        ContactInfoScreen(
                            viewModel = koinViewModel(),
                            onNavigateBack = { navController.popBackStack() },
                        )
                    }
                }

                composable<Route.MessageInfo> {
                    AuthenticatedRouteWithFlowManager(
                        authState = youAuthFlowManager.authState, onUnauthenticated = {
                            navController.navigate(Route.Login) {
                                popUpTo(0) { inclusive = true }
                            }
                        }) {
                        MessageInfoScreen(
                            viewModel = koinViewModel(),
                            onNavigateBack = { navController.popBackStack() },
                        )
                    }
                }

                composable<Route.Settings> {
                    AuthenticatedRouteWithFlowManager(
                        authState = youAuthFlowManager.authState, onUnauthenticated = {
                            navController.navigate(Route.Login) {
                                popUpTo(0) { inclusive = true }
                            }
                        }) {
                        SettingsScreen(
                            viewModel = koinViewModel(),
                            onBackClick = { navController.popBackStack() },
                            onNavigateToNotifications = {
                                navController.navigate(Route.NotificationSettings)
                            })
                    }
                }

                composable<Route.NotificationSettings> {
                    AuthenticatedRouteWithFlowManager(
                        authState = youAuthFlowManager.authState, onUnauthenticated = {
                            navController.navigate(Route.Login) {
                                popUpTo(0) { inclusive = true }
                            }
                        }) {
                        NotificationSettingsScreen(
                            viewModel = koinViewModel(),
                            onBackClick = { navController.popBackStack() })
                    }
                }
            }
        }
    }
}

/** Wrapper for routes that require authentication using YouAuthFlowManager. */
@Composable
private fun AuthenticatedRouteWithFlowManager(
    authState: StateFlow<YouAuthState>,
    onUnauthenticated: () -> Unit,
    content: @Composable () -> Unit
) {
    val currentAuthState by authState.collectAsState()

    when (currentAuthState) {
        is YouAuthState.Authenticated -> content()
        is YouAuthState.Unauthenticated -> onUnauthenticated()
        is YouAuthState.Authenticating -> {
            // Show loading or nothing while authenticating
        }

        is YouAuthState.Error -> onUnauthenticated()
    }
}
