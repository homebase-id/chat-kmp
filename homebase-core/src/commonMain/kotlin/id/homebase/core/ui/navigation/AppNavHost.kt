package id.homebase.core.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.toRoute
import androidx.window.core.layout.WindowSizeClass
import id.homebase.api.youauth.YouAuthFlowManager
import id.homebase.api.youauth.YouAuthState
import id.homebase.auth.login.LoginScreen
import id.homebase.chat.addgroupmembers.AddGroupMembersScreen
import id.homebase.chat.archivedconversations.ArchivedConversationsScreen
import id.homebase.chat.contactinfo.ContactInfoScreen
import id.homebase.chat.conversationlist.ConversationListScreen
import id.homebase.chat.conversationsettings.ConversationSettingsScreen
import id.homebase.chat.createconversation.CreateConversationScreen
import id.homebase.chat.createconversationgroup.CreateConversationGroupScreen
import id.homebase.chat.editconversationgroup.EditConversationGroupScreen
import id.homebase.chat.groupsettings.GroupSettingsScreen
import id.homebase.chat.messageinfo.MessageInfoScreen
import id.homebase.chat.selectmembers.SelectMembersScreen
import id.homebase.core.permissions.PermissionStatus
import id.homebase.core.permissions.PermissionType
import id.homebase.core.permissions.createPermissionsManager
import id.homebase.core.ui.assets.BootstrapChat
import id.homebase.core.ui.screens.appearance.AppearanceSettingsScreen
import id.homebase.core.ui.screens.help.HelpScreen
import id.homebase.core.ui.screens.home.HomeScreen
import id.homebase.core.ui.screens.loading.AppLoadingScreen
import id.homebase.core.ui.screens.notifications.NotificationSettingsScreen
import id.homebase.core.ui.screens.settings.SettingsScreen
import id.homebase.core.ui.screens.widget.RichTextExample
import id.homebase.core.notifications.BadgeManager
import id.homebase.core.notifications.NotificationNavigationEvent
import id.homebase.core.notifications.NotificationService
import id.homebase.core.util.buildNotificationUrl
import id.homebase.core.util.getUriHandler
import id.homebase.core.widget.ConnectionRequestHeaderBanner
import kotlinx.coroutines.awaitCancellation
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

sealed class TopLevelRoute(
    val route: Route, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    data object Chat : TopLevelRoute(Route.ChatList(), "Chats", BootstrapChat)
    data object Home : TopLevelRoute(Route.Home, "Home", Icons.Default.Home)
}

@Composable
fun AppNavHost(
    viewModel: AppViewModel,
    navController: NavHostController,
    youAuthFlowManager: YouAuthFlowManager
) {
    val uiState by viewModel.uiState.collectAsState()
    val authState by youAuthFlowManager.authState.collectAsState()
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

    // Launch a coroutine that observes the lifecycle
    val notificationService = koinInject<NotificationService>()

    LaunchedEffect(lifecycleOwner) {
        // Repeat the block every time the lifecycle enters RESUMED state
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                notificationService.isAppInForeground = true
                viewModel.refreshData()
                BadgeManager.clear()
                awaitCancellation()
            } finally {
                notificationService.isAppInForeground = false
            }
        }
    }

    // Track which conversation is currently being viewed
    LaunchedEffect(currentDestination) {
        notificationService.activeConversationId =
            if (currentDestination?.hasRoute(Route.ChatList::class) == true) {
                try {
                    navController.currentBackStackEntry?.toRoute<Route.ChatList>()?.conversationId
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
    }



    // Global auth guard - navigate to login when unauthenticated
    LaunchedEffect(authState, currentDestination) {
        if (authState is YouAuthState.Unauthenticated || authState is YouAuthState.Error) {
            // Only navigate if we're not already on the login screen and NavHost is initialized
            if (currentDestination != null && !currentDestination.hasRoute(Route.Login::class)&& !currentDestination.hasRoute(Route.AppLoading::class)) {
                navController.navigate(Route.Login) {
                    popUpTo(0) { inclusive = true }
                }
            }
        }
    }

    // Handle notification tap navigation
    LaunchedEffect(Unit) {
        notificationService.navigationEvents.collect { event ->
            when (event) {
                is NotificationNavigationEvent.OpenConversation -> {
                    navController.navigate(Route.ChatList(event.conversationId)) {
                        // Pop back to ChatList (no conversationId) so we don't stack
                        // duplicate ChatList entries, and use launchSingleTop so
                        // navigation works even when already on the ChatList screen.
                        popUpTo(Route.ChatList()) { inclusive = true }
                        launchSingleTop = true
                    }
                }
                is NotificationNavigationEvent.OpenUrl ->
                    uriHandler.openUrl(event.url)
            }
        }
    }

    // Show notification permission popup when relevant
    LaunchedEffect(authState, currentDestination) {
        if (currentDestination != null && !currentDestination.hasRoute(Route.Login::class) && !currentDestination.hasRoute(Route.AppLoading::class)) {
            if (authState is YouAuthState.Authenticated && !hasNotificationPermission) {
                permissionManager.askPermission(PermissionType.NOTIFICATION)
            }
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
                                    popUpTo(Route.ChatList()) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                        )
                    }
                }
            }
        }) { paddingValues ->
        Row(
            modifier = Modifier.fillMaxSize().consumeWindowInsets(paddingValues)
                .padding(paddingValues)
        ) {
            if (showNavigationRail && isAuthenticated) {
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
                        slideInHorizontally(
                            initialOffsetX = { 1000 },
                            animationSpec = tween(500)
                        )
                    },
                    exitTransition = {
                        slideOutHorizontally(
                            targetOffsetX = { -1000 },
                            animationSpec = tween(500)
                        )
                    },
                    popEnterTransition = {
                        slideInHorizontally(
                            initialOffsetX = { -1000 },
                            animationSpec = tween(500)
                        )
                    },
                    popExitTransition = {
                        slideOutHorizontally(
                            targetOffsetX = { 1000 },
                            animationSpec = tween(500)
                        )
                    }
                ) {
                    composable<Route.AppLoading> {
                        AppLoadingScreen(
                            viewModel = koinViewModel(),
                            onNavigateToMainScreen = {
                                navController.navigate(Route.ChatList()) {
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
                                navController.navigate(Route.ChatList()) {
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

                    composable<Route.ChatList> {
                        if (isAuthenticated) {
                            ConversationListScreen(
                                viewModel = koinViewModel(),
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
                                onNavigateBack = { navController.popBackStack() },
                                onShowConversation = { conversationId ->
                                    navController.navigate(Route.ChatList(conversationId.toString())) {
                                        popUpTo(Route.CreateConversation) { inclusive = true }
                                    }
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
                                    navController.navigate(Route.ChatList(conversationId.toString())) {
                                        popUpTo(Route.CreateConversation) { inclusive = true }
                                    }
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
                                    navController.navigate(Route.ChatList(conversationId)) {
                                        popUpTo(Route.ArchivedConversations) { inclusive = true }
                                    }
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
    }
}

