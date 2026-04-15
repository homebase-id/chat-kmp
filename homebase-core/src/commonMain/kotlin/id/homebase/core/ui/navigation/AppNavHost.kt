package id.homebase.core.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.window.core.layout.WindowSizeClass
import id.homebase.api.youauth.YouAuthFlowManager
import id.homebase.api.youauth.YouAuthState
import id.homebase.auth.login.LoginScreen
import id.homebase.chat.addgroupmembers.AddGroupMembersScreen
import id.homebase.chat.archivedconversations.ArchivedConversationsScreen
import id.homebase.chat.contactinfo.ContactInfoScreen
import id.homebase.chat.conversationlist.ConversationListScreen
import id.homebase.chat.conversationlist.ConversationListViewModel
import id.homebase.chat.conversationsettings.ConversationSettingsScreen
import id.homebase.chat.createconversation.CreateConversationScreen
import id.homebase.chat.createconversationgroup.CreateConversationGroupScreen
import id.homebase.chat.editconversationgroup.EditConversationGroupScreen
import id.homebase.chat.groupsettings.GroupSettingsScreen
import id.homebase.chat.messageinfo.MessageInfoScreen
import id.homebase.chat.selectmembers.SelectMembersScreen
import id.homebase.core.navigation.ActiveConversation
import id.homebase.core.notifications.NotificationNavigationEvent
import id.homebase.core.permissions.PermissionStatus
import id.homebase.core.permissions.PermissionType
import id.homebase.core.permissions.createPermissionsManager
import id.homebase.core.ui.assets.BootstrapChat
import id.homebase.core.ui.screens.appearance.AppearanceSettingsScreen
import id.homebase.core.ui.screens.connections.ConnectionsScreen
import id.homebase.core.ui.screens.help.HelpScreen
import id.homebase.core.ui.screens.home.HomeScreen
import id.homebase.core.ui.screens.loading.AppLoadingScreen
import id.homebase.core.ui.screens.notifications.NotificationSettingsScreen
import id.homebase.core.ui.screens.settings.SettingsScreen
import id.homebase.core.ui.screens.widget.RichTextExample
import id.homebase.core.util.buildNotificationUrl
import id.homebase.core.util.getUriHandler
import id.homebase.core.widget.ConnectionRequestHeaderBanner
import id.homebase.core.widget.InAppNotificationBanner
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import org.koin.compose.viewmodel.koinViewModel
import kotlin.uuid.Uuid

@Composable
fun AppNavHost(
    viewModel: AppViewModel,
    navController: NavHostController,
    youAuthFlowManager: YouAuthFlowManager
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val authState by youAuthFlowManager.authState.collectAsStateWithLifecycle()
    val isAuthenticated = authState is YouAuthState.Authenticated
    val adaptiveInfo = currentWindowAdaptiveInfo()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val topLevelRoutes = remember { listOf(TopLevelRoute.Chat, TopLevelRoute.Home) }
    val uriHandler = getUriHandler()

    var hasNotificationPermission by remember { mutableStateOf(false) }
    val permissionManager = createPermissionsManager { type, status, _ ->
        if (type == PermissionType.NOTIFICATION) {
            hasNotificationPermission = status == PermissionStatus.GRANTED
        }
    }

    // Track if we're showing only the detail pane (list hidden) in a top level screen
    var showingOnlyDetailPane by remember { mutableStateOf(false) }

    // Check if current destination is a top-level route
    val isTopLevelRoute = topLevelRoutes.any { topLevelRoute ->
        currentDestination?.hasRoute(topLevelRoute.route::class) == true
    }

    // Only show bottom nav if on a top-level route AND not showing only detail pane
    val isOnTopLevelScreen = isAuthenticated && isTopLevelRoute && !showingOnlyDetailPane
    val showNavigationRail = adaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(
        WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND
    )
    val showBottomNavigationBar = isOnTopLevelScreen && !showNavigationRail

    // Get the lifecycle owner of the current composable
    val lifecycleOwner = LocalLifecycleOwner.current

    // Lifecycle: foreground tracking, refresh, badge clear
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                viewModel.onResumed()
                awaitCancellation()
            } finally {
                viewModel.onPaused()
            }
        }
    }

    // Track active conversation + auth guard + notification permission
    LaunchedEffect(authState, currentDestination) {
        // Update active conversation for notification suppression
        val isChatRoute = currentDestination?.hasRoute(Route.ChatList::class) == true
        ActiveConversation.setDisplayingChatList(isChatRoute)

        // Auth guard - navigate to login when unauthenticated
        if (authState is YouAuthState.Unauthenticated || authState is YouAuthState.Error) {
            if (currentDestination != null && !currentDestination.hasRoute(Route.Login::class) && !currentDestination.hasRoute(Route.AppLoading::class)) {
                navController.navigate(Route.Login) {
                    popUpTo(0) { inclusive = true }
                }
            }
        }

        // Notification permission request
        if (currentDestination != null && !currentDestination.hasRoute(Route.Login::class) && !currentDestination.hasRoute(Route.AppLoading::class)) {
            if (authState is YouAuthState.Authenticated && !hasNotificationPermission) {
                permissionManager.askPermission(PermissionType.NOTIFICATION)
            }
        }
    }

    // Handle notification tap navigation (needs navController, stays in composable)
    LaunchedEffect(Unit) {
        viewModel.navigationEvents.collect { event ->
            when (event) {
                is NotificationNavigationEvent.OpenConversation -> {
                    Uuid.parseOrNull(event.conversationId)?.let {
                        navController.selectConversationOnChatList(it, scrollToBottom = true)
                    }
                    navController.popBackStack(Route.ChatList, inclusive = false)
                }
                is NotificationNavigationEvent.OpenUrl ->
                    uriHandler.openUrl(event.url)
            }
        }
    }

    // Auto-dismiss in-app banner after 4 seconds
    val inAppNotification = uiState.inAppNotification
    LaunchedEffect(inAppNotification) {
        if (inAppNotification != null) {
            delay(4000)
            viewModel.dismissInAppBanner()
        }
    }

    Scaffold(
        bottomBar = {
            if (showBottomNavigationBar) {
                NavigationBar {
                    topLevelRoutes.forEach { topLevelRoute ->
                        NavigationBarItem(
                            icon = { Icon(topLevelRoute.icon, contentDescription = null) },
                            label = { Text(topLevelRoute.label) },
                            selected = currentDestination?.hasRoute(
                                topLevelRoute.route::class
                            ) == true,
                            onClick = {
                                navController.navigate(topLevelRoute.route) {
                                    popUpTo(Route.ChatList) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                        )
                    }
                }
            }
        }) { paddingValues ->
        Box(
            modifier = Modifier.fillMaxSize().consumeWindowInsets(paddingValues)
                .padding(paddingValues)
        ) {
        Row(modifier = Modifier.fillMaxSize()) {
            if (showNavigationRail && isAuthenticated && isOnTopLevelScreen) {
                NavigationRail(header = { Spacer(modifier = Modifier.height(12.dp)) }) {
                    topLevelRoutes.forEach { topLevelRoute ->
                        NavigationRailItem(
                            icon = { Icon(topLevelRoute.icon, contentDescription = null) },
                            // label = { Text(topLevelRoute.label) },
                            selected = currentDestination?.hasRoute(topLevelRoute.route::class) == true,
                            onClick = {
                                navController.navigate(topLevelRoute.route) {
                                    popUpTo(Route.ChatList) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            })
                    }
                }
            }

            Column {
                if (isOnTopLevelScreen) {
                    if (uiState.incomingRequests.isNotEmpty()) {
                        ConnectionRequestHeaderBanner(
                            requestCount = uiState.incomingRequests.size,
                            onBannerClick = {
                                uiState.currentOdinId?.let {
                                    val requestsUrl = it.buildNotificationUrl()
                                    uriHandler.openUrl(requestsUrl)
                                }
                            }
                        )
                    }
                }

                NavHost(
                    navController = navController,
                    startDestination = Route.AppLoading,
                    modifier = Modifier.weight(1f),
                    enterTransition = {
                        if (isBetweenTopLevelRoutes()) EnterTransition.None
                        else slideInHorizontally(
                            initialOffsetX = { 1000 },
                            animationSpec = tween(500)
                        )
                    },
                    exitTransition = {
                        if (isBetweenTopLevelRoutes()) ExitTransition.None
                        else slideOutHorizontally(
                            targetOffsetX = { -1000 },
                            animationSpec = tween(500)
                        )
                    },
                    popEnterTransition = {
                        if (isBetweenTopLevelRoutes()) EnterTransition.None
                        else slideInHorizontally(
                            initialOffsetX = { -1000 },
                            animationSpec = tween(500)
                        )
                    },
                    popExitTransition = {
                        if (isBetweenTopLevelRoutes()) ExitTransition.None
                        else slideOutHorizontally(
                            targetOffsetX = { 1000 },
                            animationSpec = tween(500)
                        )
                    }
                ) {
                    composable<Route.AppLoading> {
                        AppLoadingScreen(
                            viewModel = koinViewModel(),
                            onNavigateToMainScreen = {
                                navController.navigate(Route.ChatList) {
                                    popUpTo(Route.AppLoading) { inclusive = true }
                                }
                            },
                            onNavigateToLogin = {
                                navController.navigate(Route.Login) {
                                    popUpTo(Route.AppLoading) { inclusive = true }
                                }
                            },
                        )
                    }

                    composable<Route.Login> {
                        LoginScreen(
                            viewModel = koinViewModel(),
                            onNavigateHome = {
                                navController.navigate(Route.ChatList) {
                                    popUpTo(Route.Login) { inclusive = true }
                                }
                            },
                        )
                    }

                    composable<Route.Home> {
                        if (isAuthenticated) {
                            HomeScreen(
                                viewModel = koinViewModel(),
                                onNavigateToExamples = { navController.navigate(Route.Examples) }
                            )
                        }
                    }

                    composable<Route.ChatList> { backStackEntry ->
                        if (isAuthenticated) {
                            val conversationListViewModel: ConversationListViewModel = koinViewModel()
                            val pendingConversationId by backStackEntry.savedStateHandle.getStateFlow<String?>("pendingConversationId", null).collectAsStateWithLifecycle()
                            val pendingScrollToBottom by backStackEntry.savedStateHandle.getStateFlow("pendingScrollToBottom", false).collectAsStateWithLifecycle()
                            LaunchedEffect(pendingConversationId) {
                                pendingConversationId?.let { idStr ->
                                    Uuid.parseOrNull(idStr)?.let {
                                        conversationListViewModel.selectConversation(it, scrollToBottom = pendingScrollToBottom)
                                        backStackEntry.savedStateHandle["pendingConversationId"] = null
                                        backStackEntry.savedStateHandle["pendingScrollToBottom"] = false
                                    }
                                }
                            }
                            ConversationListScreen(
                                viewModel = conversationListViewModel,
                                extendPermissionViewModel = koinViewModel(),
                                onNavigateBack = { navController.popBackStack() },
                                onNavigateToSettingsScreen = {
                                    navController.navigate(Route.Settings)
                                },
                                onNavigateToNewConversation = {
                                    navController.navigate(Route.CreateConversation)
                                },
                                onNavigateToArchivedConversations = {
                                    navController.navigate(Route.ArchivedConversations)
                                },
                                onNavigateToContactInfo = {
                                    navController.navigate(Route.ContactInfo(it))
                                },
                                onNavigateToConversationSettings = {
                                    navController.navigate(Route.ConversationSettings(it))
                                },
                                onNavigateToGroupSettings = {
                                    navController.navigate(Route.GroupSettings(it))
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
                                onDetailPaneVisibilityChanged = {
                                    // THIS IS USED, THE WARNING IS WRONG, IT'S A KNOWN ISSUE
                                    @Suppress("AssignedValueIsNeverRead")
                                    showingOnlyDetailPane = it
                                },
                            )
                        }
                    }

                    composable<Route.CreateConversation> {
                        if (isAuthenticated) {
                            CreateConversationScreen(
                                viewModel = koinViewModel(),
                                connectRequestViewModel = koinViewModel(),
                                onNavigateBack = { navController.popBackStack() },
                                onShowConversation = { conversationId ->
                                    navController.selectConversationOnChatList(conversationId)
                                    navController.popBackStack(Route.CreateConversation, inclusive = true)
                                },
                                onShowCreateGroup = {
                                    navController.navigate(Route.CreateConversationSelectMembers)
                                }
                            )
                        }
                    }

                    composable<Route.CreateConversationSelectMembers> {
                        if (isAuthenticated) {
                            SelectMembersScreen(
                                viewModel = koinViewModel(),
                                onNavigateBack = { navController.popBackStack() },
                                onMembersSelected = { ids ->
                                    navController.navigate(Route.CreateConversationGroup(ids))
                                }
                            )
                        }
                    }

                    composable<Route.CreateConversationGroup> {
                        if (isAuthenticated) {
                            CreateConversationGroupScreen(
                                viewModel = koinViewModel(),
                                onNavigateBack = { navController.popBackStack() },
                                onShowConversation = { conversationId ->
                                    navController.selectConversationOnChatList(conversationId)
                                    navController.popBackStack(Route.ChatList, inclusive = false)
                                },
                            )
                        }
                    }

                    composable<Route.ArchivedConversations> {
                        if (isAuthenticated) {
                            ArchivedConversationsScreen(
                                viewModel = koinViewModel(),
                                onNavigateBack = { navController.popBackStack() },
                                onShowConversation = { conversationId ->
                                    navController.selectConversationOnChatList(conversationId)
                                    navController.popBackStack(Route.ArchivedConversations, inclusive = true)
                                },
                                onNavigateToConversationSettings = { conversationId ->
                                    navController.navigate(Route.ConversationSettings(conversationId))
                                },
                                onNavigateToGroupSettings = { conversationId ->
                                    navController.navigate(Route.GroupSettings(conversationId))
                                },
                            )
                        }
                    }

                    composable<Route.ContactInfo> {
                        if (isAuthenticated) {
                            ContactInfoScreen(
                                viewModel = koinViewModel(),
                                onNavigateBack = { navController.popBackStack() },
                            )
                        }
                    }

                    composable<Route.MessageInfo> {
                        if (isAuthenticated) {
                            MessageInfoScreen(
                                viewModel = koinViewModel(),
                                onNavigateBack = { navController.popBackStack() },
                            )
                        }
                    }

                    composable<Route.ConversationSettings> {
                        if (isAuthenticated) {
                            ConversationSettingsScreen(
                                viewModel = koinViewModel(),
                                onNavigateBack = { navController.popBackStack() },
                                onShowContactInfo = {
                                    navController.navigate(Route.ContactInfo(it))
                                },
                            )
                        }
                    }

                    composable<Route.GroupSettings> {
                        if (isAuthenticated) {
                            GroupSettingsScreen(
                                viewModel = koinViewModel(),
                                onNavigateBack = { navController.popBackStack() },
                                onShowContactInfo = {
                                    navController.navigate(Route.ContactInfo(it))
                                },
                                onAddMembers = {
                                    navController.navigate(Route.GroupAddMembers(it))
                                },
                                onEditGroup = {
                                    navController.navigate(Route.GroupEdit(it))
                                },
                            )
                        }
                    }

                    composable<Route.GroupAddMembers> {
                        if (isAuthenticated) {
                            AddGroupMembersScreen(
                                viewModel = koinViewModel(),
                                onNavigateBack = { navController.popBackStack() },
                            )
                        }
                    }

                    composable<Route.GroupEdit> {
                        if (isAuthenticated) {
                            EditConversationGroupScreen(
                                viewModel = koinViewModel(),
                                onNavigateBack = { navController.popBackStack() },
                            )
                        }
                    }

                    composable<Route.Examples> {
                        if (isAuthenticated) {
                            RichTextExample()
                        }
                    }

                    composable<Route.Settings> {
                        if (isAuthenticated) {
                            SettingsScreen(
                                viewModel = koinViewModel(),
                                onBackClick = { navController.popBackStack() },
                                onNavigateToConnections = {
                                    navController.navigate(Route.Connections)
                                },
                                onNavigateToNotifications = {
                                    navController.navigate(Route.NotificationSettings)
                                },
                                onNavigateToAppearance = {
                                    navController.navigate(Route.AppearanceSettings)
                                },
                                onNavigateToHelp = {
                                    navController.navigate(Route.Help)
                                },
                            )
                        }
                    }

                    composable<Route.Connections> {
                        if (isAuthenticated) {
                            ConnectionsScreen(
                                viewModel = koinViewModel(),
                                connectRequestViewModel = koinViewModel(),
                                onBackClick = { navController.popBackStack() },
                            )
                        }
                    }

                    composable<Route.NotificationSettings> {
                        if (isAuthenticated) {
                            NotificationSettingsScreen(
                                viewModel = koinViewModel(),
                                onBackClick = { navController.popBackStack() })
                        }
                    }

                    composable<Route.AppearanceSettings> {
                        if (isAuthenticated) {
                            AppearanceSettingsScreen(
                                viewModel = koinViewModel(),
                                onBackClick = { navController.popBackStack() })
                        }
                    }

                    composable<Route.Help> {
                        if (isAuthenticated) {
                            HelpScreen(
                                viewModel = koinViewModel(),
                                onBackClick = { navController.popBackStack() })
                        }
                    }
                }
            }
        }

        // In-app notification banner overlay
        InAppNotificationBanner(
            event = uiState.inAppNotification,
            visible = uiState.inAppNotification != null,
            onTap = { data ->
                viewModel.onInAppBannerTapped(data.payloadData)
            },
            modifier = Modifier.align(Alignment.TopCenter),
        )
        }
    }
}

private fun NavHostController.selectConversationOnChatList(conversationId: Uuid, scrollToBottom: Boolean = false) {
    getBackStackEntry<Route.ChatList>().savedStateHandle["pendingConversationId"] = conversationId.toString()
    getBackStackEntry<Route.ChatList>().savedStateHandle["pendingScrollToBottom"] = scrollToBottom
}

// Helper to check if a destination is a top-level route
private fun NavDestination?.isTopLevelRoute(): Boolean {
    return this?.hasRoute(Route.ChatList::class) == true ||
            this?.hasRoute(Route.Home::class) == true
}

// Helper to check if we're navigating between top-level routes
private fun AnimatedContentTransitionScope<NavBackStackEntry>.isBetweenTopLevelRoutes(): Boolean {
    return initialState.destination.isTopLevelRoute() &&
            targetState.destination.isTopLevelRoute()
}

sealed class TopLevelRoute(
    val route: Route, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    data object Chat : TopLevelRoute(Route.ChatList, "Chats", BootstrapChat)
    data object Home : TopLevelRoute(Route.Home, "Home", Icons.Default.Home)
}