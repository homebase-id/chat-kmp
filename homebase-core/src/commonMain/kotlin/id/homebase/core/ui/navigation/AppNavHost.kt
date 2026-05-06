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
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.filled.RssFeed
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
import co.touchlab.kermit.Logger
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
import id.homebase.core.ui.screens.defragmenter.DefragmenterScreen
import id.homebase.core.ui.screens.help.HelpScreen
import id.homebase.core.ui.screens.devmenu.DeveloperMenuScreen
import id.homebase.core.ui.screens.feed.FeedScreen
import id.homebase.core.ui.screens.home.HomeScreen
import id.homebase.core.ui.screens.loading.AppLoadingScreen
import id.homebase.core.ui.screens.notifications.NotificationSettingsScreen
import id.homebase.core.ui.screens.settings.SettingsScreen
import id.homebase.core.ui.screens.vault.VaultOnboardingScreen
import id.homebase.core.ui.screens.vault.VaultScreen
import id.homebase.core.ui.screens.vault.VaultSettingsScreen
import id.homebase.core.ui.screens.vault.VaultUiEvent
import id.homebase.core.ui.screens.vault.VaultViewModel
import id.homebase.core.ui.screens.storage.StorageSettingsScreen
import id.homebase.core.ui.screens.widget.RichTextExample
import id.homebase.core.vault.VaultPreferences
import org.koin.compose.koinInject
import id.homebase.core.util.buildNotificationUrl
import id.homebase.core.util.getUriHandler
import id.homebase.core.widget.ConnectionRequestHeaderBanner
import id.homebase.core.widget.InAppNotificationBanner
import id.homebase.core.widget.UpdateAvailableBanner
import id.homebase.imageeditor.ui.CropScreen
import id.homebase.imageeditor.ui.DrawScreen
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
    val vaultPreferences = koinInject<VaultPreferences>()
    val vaultIconVisible by vaultPreferences.iconVisible.collectAsStateWithLifecycle()
    val vaultViewModel: VaultViewModel = koinViewModel()
    val topLevelRoutes = remember(vaultIconVisible) {
        buildList {
            add(TopLevelRoute.Chat)
            add(TopLevelRoute.Feed)
            if (vaultIconVisible) add(TopLevelRoute.Vault)
            add(TopLevelRoute.Home)
        }
    }
    val uriHandler = getUriHandler()

    var hasNotificationPermission by remember { mutableStateOf(false) }
    val permissionManager = createPermissionsManager { type, status, _ ->
        if (type == PermissionType.NOTIFICATION) {
            hasNotificationPermission = status == PermissionStatus.GRANTED
        }
    }

    // Track if we're showing only the detail pane (list hidden) in a top level screen
    var showingOnlyDetailPane by remember { mutableStateOf(false) }

    // Check if current destination is a top-level route. Uses the static route-type
    // check (not topLevelRoutes) so the bottom nav still shows on the Vault screen even
    // when the user has hidden the Vault icon from the nav bar.
    val isTopLevelRoute =
        currentDestination.isTopLevelRoute() ||
        currentDestination?.hasRoute(Route.VaultOnboarding::class) == true ||
        topLevelRoutes.any { topLevelRoute ->
            currentDestination?.hasRoute(topLevelRoute.route::class) == true
        }

    // Only show bottom nav if on a top-level route AND not showing only detail pane
    val isOnTopLevelScreen = isAuthenticated && isTopLevelRoute && !showingOnlyDetailPane
    val showNavigationRail = adaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(
        WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND
    )
    val vaultUiState by vaultViewModel.uiState.collectAsStateWithLifecycle()
    val isVaultGalleryOpen = vaultUiState.fullScreenOverlay != null
    val showBottomNavigationBar = isOnTopLevelScreen && !showNavigationRail && !isVaultGalleryOpen

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
            if (currentDestination != null && !currentDestination.hasRoute(Route.Login::class) && !currentDestination.hasRoute(
                    Route.AppLoading::class
                )
            ) {
                navController.navigate(Route.Login) {
                    popUpTo(0) { inclusive = true }
                }
            }
        }

        // Notification permission request
        if (currentDestination != null && !currentDestination.hasRoute(Route.Login::class) && !currentDestination.hasRoute(
                Route.AppLoading::class
            )
        ) {
            if (authState is YouAuthState.Authenticated && !hasNotificationPermission) {
                permissionManager.askPermission(PermissionType.NOTIFICATION)
            }
        }
    }

    // Route Vault activation/dismiss events to navigation
    LaunchedEffect(Unit) {
        vaultViewModel.events.collect { event ->
            when (event) {
                VaultUiEvent.Activated -> {
                    navController.popBackStack(Route.VaultOnboarding, inclusive = true)
                    navController.navigate(Route.Vault) {
                        popUpTo(Route.ChatList) { saveState = true }
                        launchSingleTop = true
                    }
                }

                VaultUiEvent.CloseOnboarding -> {
                    navController.popBackStack()
                }

                is VaultUiEvent.ShareFileReady, is VaultUiEvent.Error -> { /* handled by VaultScreen */
                }
            }
        }
    }

    val isVaultActivated by vaultViewModel.isActivated.collectAsStateWithLifecycle()

    // Open-Vault helper — if activated (or still checking), go to the Vault screen
    // (which shows loading/biometric gate); only show onboarding when we're certain
    // the vault has never been set up (isActivated == false, not null).
    val openVault: () -> Unit = {
        if (isVaultActivated == false) {
            navController.navigate(Route.VaultOnboarding) {
                popUpTo(Route.ChatList) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        } else {
            navController.navigate(Route.Vault) {
                popUpTo(Route.ChatList) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    // Handle notification tap navigation (needs navController, stays in composable)
    LaunchedEffect(Unit) {
        viewModel.navigationEvents.collect { event ->
            when (event) {
                is NotificationNavigationEvent.OpenConversation -> {
                    val id = Uuid.parseOrNull(event.conversationId) ?: return@collect
                    val topRoute = navController.currentBackStackEntry?.destination?.route
                    Logger.i(tag = "AppNavHost") {
                        "OpenConversation received: id=$id, source=${event.source}, currentDest=$topRoute"
                    }
                    // Gate on ChatList being *anywhere* in the back stack, not
                    // just on top. Top-of-stack gating hangs forever when the
                    // user is warm on Detail/Settings/etc. For notification
                    // taps, conversation resolution lives in
                    // ConversationListViewModel via the PendingNotificationTap
                    // singleton (TTL-retried until drive sync lands the
                    // conversation) — here we only manage the back stack.
                    val stack = navController.currentBackStack.first { stack ->
                        stack.any {
                            it.destination.hasRoute(Route.ChatList::class)
                        }
                    }
                    Logger.i(tag = "AppNavHost") {
                        "ChatList present in stack (size=${stack.size}), popping to it"
                    }
                    val popped = navController.popBackStack(Route.ChatList, inclusive = false)
                    Logger.i(tag = "AppNavHost") { "popBackStack(ChatList)=$popped" }
                    // Share intents carry no messageId, so PendingNotificationTap
                    // cannot resolve them. Drop the conversation id directly into
                    // ChatList's savedStateHandle — the LaunchedEffect on the
                    // ChatList composable picks it up and calls
                    // ConversationListViewModel.selectConversation, which in turn
                    // runs processPendingSharedContent so the shared file lands
                    // in the correct conversation.
                    if (event.source == NotificationNavigationEvent.OpenConversation.Source.ShareIntent) {
                        navController.selectConversationOnChatList(id)
                    }
                }

                is NotificationNavigationEvent.OpenUrl -> uriHandler.openUrl(event.url)
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
                                if (topLevelRoute is TopLevelRoute.Vault) {
                                    openVault()
                                } else {
                                    navController.navigate(topLevelRoute.route) {
                                        popUpTo(Route.ChatList) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
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
                                    if (topLevelRoute is TopLevelRoute.Vault) {
                                        openVault()
                                    } else {
                                        navController.navigate(topLevelRoute.route) {
                                            popUpTo(Route.ChatList) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                })
                        }
                    }
                }

                Column {
                    if (isOnTopLevelScreen) {
                        if (uiState.updateAvailable) {
                            UpdateAvailableBanner(
                                versionName = uiState.updateAvailableVersion,
                                onUpdateClick = { viewModel.triggerUpdate() }
                            )
                        }
                        if (uiState.incomingRequests.isNotEmpty()) {
                            ConnectionRequestHeaderBanner(
                                requestCount = uiState.incomingRequests.size, onBannerClick = {
                                    uiState.currentOdinId?.let {
                                        val requestsUrl = it.buildNotificationUrl()
                                        uriHandler.openUrl(requestsUrl)
                                    }
                                })
                        }
                    }

                    NavHost(
                        navController = navController,
                        startDestination = Route.AppLoading,
                        modifier = Modifier.weight(1f),
                        enterTransition = {
                            if (isBetweenTopLevelRoutes()) EnterTransition.None
                            else slideInHorizontally(
                                initialOffsetX = { 1000 }, animationSpec = tween(500)
                            )
                        },
                        exitTransition = {
                            if (isBetweenTopLevelRoutes()) ExitTransition.None
                            else slideOutHorizontally(
                                targetOffsetX = { -1000 }, animationSpec = tween(500)
                            )
                        },
                        popEnterTransition = {
                            if (isBetweenTopLevelRoutes()) EnterTransition.None
                            else slideInHorizontally(
                                initialOffsetX = { -1000 }, animationSpec = tween(500)
                            )
                        },
                        popExitTransition = {
                            if (isBetweenTopLevelRoutes()) ExitTransition.None
                            else slideOutHorizontally(
                                targetOffsetX = { 1000 }, animationSpec = tween(500)
                            )
                        }) {
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
                                    onNavigateToExamples = { navController.navigate(Route.Examples) })
                            }
                        }

                        composable<Route.Feed> {
                            if (isAuthenticated) {
                                FeedScreen(
                                    viewModel = koinViewModel(),
                                    onNavigateToChat = {
                                        navController.navigate(Route.ChatList) {
                                            popUpTo(Route.ChatList) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                )
                            }
                        }

                        composable<Route.ChatList> { backStackEntry ->
                            if (isAuthenticated) {
                                val conversationListViewModel: ConversationListViewModel =
                                    koinViewModel()
                                val pendingConversationId by backStackEntry.savedStateHandle.getStateFlow<String?>(
                                    "pendingConversationId", null
                                ).collectAsStateWithLifecycle()
                                val pendingScrollToBottom by backStackEntry.savedStateHandle.getStateFlow(
                                    "pendingScrollToBottom", false
                                ).collectAsStateWithLifecycle()
                                LaunchedEffect(pendingConversationId) {
                                    pendingConversationId?.let { idStr ->
                                        Uuid.parseOrNull(idStr)?.let {
                                            Logger.i(tag = "AppNavHost") {
                                                "ChatList observed pendingConversationId=$idStr, calling selectConversation"
                                            }
                                            conversationListViewModel.selectConversation(
                                                it, scrollToBottom = pendingScrollToBottom
                                            )
                                            backStackEntry.savedStateHandle["pendingConversationId"] =
                                                null
                                            backStackEntry.savedStateHandle["pendingScrollToBottom"] =
                                                false
                                        }
                                    }
                                }
                                ConversationListScreen(
                                    viewModel = conversationListViewModel,
                                    archivedConversationsViewModel = koinViewModel(),
                                    extendPermissionViewModel = koinViewModel(),
                                    connectRequestViewModel = koinViewModel(),
                                    onNavigateBack = { navController.popBackStack() },
                                    onNavigateToSettingsScreen = {
                                        navController.navigate(Route.Settings)
                                    },
                                    onNavigateToNewConversation = {
                                        navController.navigate(Route.CreateConversation)
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
                                    onNavigateToCropper = { requestId ->
                                        navController.navigate(Route.Crop(requestId.toString()))
                                    },
                                    onNavigateToDrawer = { requestId ->
                                        navController.navigate(Route.Draw(requestId.toString()))
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
                                        navController.selectConversationOnChatList(
                                            conversationId
                                        )
                                        navController.popBackStack(
                                            Route.CreateConversation, inclusive = true
                                        )
                                    },
                                    onShowCreateGroup = {
                                        navController.navigate(Route.CreateConversationSelectMembers)
                                    })
                            }
                        }

                        composable<Route.CreateConversationSelectMembers> {
                            if (isAuthenticated) {
                                SelectMembersScreen(
                                    viewModel = koinViewModel(),
                                    onNavigateBack = { navController.popBackStack() },
                                    onMembersSelected = { ids ->
                                        navController.navigate(Route.CreateConversationGroup(ids))
                                    })
                            }
                        }

                        composable<Route.CreateConversationGroup> {
                            if (isAuthenticated) {
                                CreateConversationGroupScreen(
                                    viewModel = koinViewModel(),
                                    onNavigateBack = { navController.popBackStack() },
                                    onShowConversation = { conversationId ->
                                        navController.selectConversationOnChatList(
                                            conversationId
                                        )
                                        navController.popBackStack(
                                            Route.ChatList, inclusive = false
                                        )
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
                                        navController.selectConversationOnChatList(
                                            conversationId
                                        )
                                        navController.popBackStack(
                                            Route.ArchivedConversations, inclusive = true
                                        )
                                    },
                                    onNavigateToConversationSettings = { conversationId ->
                                        navController.navigate(
                                            Route.ConversationSettings(
                                                conversationId
                                            )
                                        )
                                    },
                                    onNavigateToGroupSettings = { conversationId ->
                                        navController.navigate(
                                            Route.GroupSettings(
                                                conversationId
                                            )
                                        )
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

                        composable<Route.Crop> {
                            if (isAuthenticated) {
                                CropScreen(
                                    viewModel = koinViewModel(),
                                    onEvent = { _ ->
                                        // The result bus delivers cropped bytes to the
                                        // caller; the screen just pops on any event.
                                        navController.popBackStack()
                                    },
                                )
                            }
                        }

                        composable<Route.Draw> {
                            if (isAuthenticated) {
                                DrawScreen(
                                    viewModel = koinViewModel(),
                                    onEvent = { _ ->
                                        navController.popBackStack()
                                    },
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
                                    onNavigateToStorage = {
                                        navController.navigate(Route.StorageSettings)
                                    },
                                    onNavigateToHelp = {
                                        navController.navigate(Route.Help)
                                    },
                                    onNavigateToVaultSettings = {
                                        navController.navigate(Route.VaultSettings)
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
                                    onShowConversation = { conversationId ->
                                        navController.selectConversationOnChatList(
                                            conversationId
                                        )
                                        navController.popBackStack(
                                            Route.ChatList, inclusive = false
                                        )
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

                        composable<Route.VaultOnboarding> {
                            if (isAuthenticated) {
                                VaultOnboardingScreen(
                                    viewModel = vaultViewModel,
                                )
                            }
                        }

                        composable<Route.Vault> {
                            if (isAuthenticated) {
                                VaultScreen(
                                    vaultExtendPermissionViewModel = vaultViewModel.vaultExtendPermissionViewModel,
                                    viewModel = vaultViewModel,
                                    onNavigateToSettings = { navController.navigate(Route.VaultSettings) },
                                )
                            }
                        }

                        composable<Route.VaultSettings> {
                            if (isAuthenticated) {
                                val fromVault = navController.previousBackStackEntry
                                    ?.destination?.hasRoute(Route.Vault::class) == true
                                VaultSettingsScreen(
                                    viewModel = koinViewModel(),
                                    onBackClick = { navController.popBackStack() },
                                    onOpenVault = openVault,
                                    showOpenVault = !fromVault,
                                )
                            }
                        }

                        composable<Route.VaultEntryDetail> { _ ->
                            if (isAuthenticated) {
                                LaunchedEffect(Unit) { navController.popBackStack() }
                            }
                        }

                        composable<Route.Help> {
                            if (isAuthenticated) {
                                HelpScreen(
                                    viewModel = koinViewModel(),
                                    onBackClick = { navController.popBackStack() },
                                    onNavigateToDeveloperMenu = {
                                        navController.navigate(Route.DeveloperMenu)
                                    },
                                )
                            }
                        }

                        composable<Route.DeveloperMenu> {
                            if (isAuthenticated) {
                                DeveloperMenuScreen(
                                    viewModel = koinViewModel(),
                                    onBackClick = { navController.popBackStack() })
                            }
                        }

                        composable<Route.StorageSettings> {
                            if (isAuthenticated) {
                                StorageSettingsScreen(
                                    viewModel = koinViewModel(),
                                    onBackClick = { navController.popBackStack() },
                                    onNavigateToDefragmenter = {
                                        navController.navigate(Route.Defragmenter)
                                    },
                                )
                            }
                        }

                        composable<Route.Defragmenter> {
                            if (isAuthenticated) {
                                DefragmenterScreen(
                                    viewModel = koinViewModel(),
                                    onClose = { navController.popBackStack() },
                                )
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

private fun NavHostController.selectConversationOnChatList(
    conversationId: Uuid, scrollToBottom: Boolean = false
): Boolean {
    val entry = runCatching { getBackStackEntry<Route.ChatList>() }.getOrNull()
    if (entry == null) {
        Logger.w(tag = "AppNavHost") {
            "ChatList missing from stack — dropping pending conversation $conversationId"
        }
        return false
    }
    entry.savedStateHandle["pendingConversationId"] = conversationId.toString()
    entry.savedStateHandle["pendingScrollToBottom"] = scrollToBottom
    return true
}

private fun NavDestination?.isTopLevelRoute(): Boolean {
    return this?.hasRoute(Route.ChatList::class) == true ||
            this?.hasRoute(Route.Home::class) == true ||
            this?.hasRoute(Route.Feed::class) == true
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.isBetweenTopLevelRoutes(): Boolean {
    return initialState.destination.isTopLevelRoute() && targetState.destination.isTopLevelRoute()
}

sealed class TopLevelRoute(
    val route: Route, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    data object Chat : TopLevelRoute(Route.ChatList, "Chats", BootstrapChat)
    data object Feed : TopLevelRoute(Route.Feed, "Feed", Icons.Default.RssFeed)
    data object Home : TopLevelRoute(Route.Home, "Home", Icons.Default.Home)
    data object Vault : TopLevelRoute(Route.Vault, "Vault", Icons.Outlined.Lock)
}
