package id.homebase.core.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.material.icons.outlined.AutoAwesome
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.DatabaseUpgradeState
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
import id.homebase.core.TextRenderingHelper
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
import id.homebase.core.ui.screens.moments.CreateMomentGroupScreen
import id.homebase.core.ui.screens.moments.MomentAudienceScreen
import id.homebase.core.ui.screens.moments.MomentComposeScreen
import id.homebase.core.ui.screens.moments.MomentDetailPager
import id.homebase.core.ui.screens.moments.MomentsOnboardingScreen
import id.homebase.core.ui.screens.moments.MomentsScreen
import id.homebase.core.ui.screens.moments.MomentsSettingsScreen
import id.homebase.core.ui.screens.moments.MomentsUiEvent
import id.homebase.core.ui.screens.moments.MomentsViewModel
import id.homebase.core.moments.MomentsPreferences
import id.homebase.core.ui.screens.notifications.NotificationSettingsScreen
import id.homebase.core.ui.screens.settings.SettingsScreen
import androidx.compose.material3.CircularProgressIndicator
import id.homebase.core.ui.screens.vault.VaultScreen
import id.homebase.core.ui.screens.vault.VaultUiEvent
import id.homebase.core.ui.screens.vault.VaultViewModel
import id.homebase.core.ui.screens.vault.note.VaultNoteEditorScreen
import id.homebase.core.ui.screens.vault.note.VaultNoteEditorViewModel
import id.homebase.core.ui.screens.vault.onboarding.VaultOnboardingScreen
import id.homebase.core.ui.screens.vault.settings.VaultSettingsScreen
import id.homebase.core.ui.screens.storage.StorageSettingsScreen
import id.homebase.core.ui.screens.widget.RichTextExample
import id.homebase.core.vault.VaultPreferences
import id.homebase.resources.nav_chats
import id.homebase.resources.nav_feed
import id.homebase.resources.nav_home
import id.homebase.resources.vault_label
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import id.homebase.core.util.buildNotificationUrl
import id.homebase.core.util.getUriHandler
import kotlinx.io.files.Path
import id.homebase.core.widget.ConnectionRequestHeaderBanner
import id.homebase.core.widget.InAppNotificationBanner
import id.homebase.core.widget.UpdateAvailableBanner
import id.homebase.imageeditor.ui.CropScreen
import id.homebase.imageeditor.ui.DrawScreen
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import androidx.navigation.toRoute
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import id.homebase.resources.MR
import id.homebase.resources.nav_moments
import org.jetbrains.compose.resources.StringResource
import kotlin.uuid.Uuid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.TextButton
import id.homebase.core.upgrade.PendingUpgradeState
import id.homebase.resources.cancel
import id.homebase.resources.pending_upgrade_snackbar_message
import id.homebase.resources.pending_upgrade_snackbar_action
import id.homebase.resources.pending_upgrade_title
import id.homebase.resources.database_upgrade_snackbar
import id.homebase.resources.pending_upgrade_message
import id.homebase.resources.pending_upgrade_confirm
import id.homebase.resources.upgrade_running_message

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
    val momentsPreferences = koinInject<MomentsPreferences>()
    val momentsIconVisible by momentsPreferences.iconVisible.collectAsStateWithLifecycle()
    val momentsViewModel: MomentsViewModel = koinViewModel()
    val vaultPreferences = koinInject<VaultPreferences>()
    val vaultIconVisible by vaultPreferences.iconVisible.collectAsStateWithLifecycle()
    val vaultViewModel: VaultViewModel = koinViewModel()
    val topLevelRoutes = remember(momentsIconVisible, vaultIconVisible) {
        buildList {
            add(TopLevelRoute.Chat)
            add(TopLevelRoute.Feed)
            if (momentsIconVisible) add(TopLevelRoute.Moments)
            if (vaultIconVisible) add(TopLevelRoute.Vault)
            add(TopLevelRoute.Home)
        }
    }
    val openMoments: () -> Unit = {
        if (momentsPreferences.activated.value) {
            navController.navigate(Route.Moments) {
                popUpTo(Route.ChatList) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        } else {
            navController.navigate(Route.MomentsOnboarding)
        }
    }
    val uriHandler = getUriHandler()
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarMessage = stringResource(MR.string.pending_upgrade_snackbar_message)
    val snackbarAction = stringResource(MR.string.pending_upgrade_snackbar_action)

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

    // Route Vault events to navigation. Activation is handled declaratively
    // by the composable<Route.Vault> block (recomposes when isActivated flips).
    LaunchedEffect(Unit) {
        vaultViewModel.events.collect { event ->
            when (event) {
                VaultUiEvent.Activated -> { /* recomposition handles the switch */ }

                VaultUiEvent.CloseOnboarding -> {
                    navController.popBackStack()
                }

                is VaultUiEvent.OpenNoteEditor,
                is VaultUiEvent.ShareFileReady,
                is VaultUiEvent.SaveFileReady,
                is VaultUiEvent.Error -> { /* handled by VaultScreen */ }
            }
        }
    }

    val isVaultActivated by vaultViewModel.isActivated.collectAsStateWithLifecycle()

    val openVault: () -> Unit = {
        navController.navigate(Route.Vault) {
            popUpTo(Route.ChatList) { saveState = true }
            launchSingleTop = true
            restoreState = true
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
                    val stack = navController.currentBackStack.firstContaining {
                        it.destination.hasRoute(Route.ChatList::class)
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
                    TextRenderingHelper.nudge()
                }

                is NotificationNavigationEvent.OpenUrl -> uriHandler.openUrl(event.url)

                is NotificationNavigationEvent.OpenMoment -> {
                    val momentId = Uuid.parseOrNull(event.momentId)
                    Logger.i(tag = "AppNavHost") {
                        "OpenMoment received: id=$momentId openComments=${event.openComments} " +
                                "activated=${momentsPreferences.activated.value}"
                    }
                    // Only route when Moments is activated (receiving a moment push
                    // implies the moments drive is subscribed, so this normally holds).
                    if (momentId != null && momentsPreferences.activated.value) {
                        // Cold-start safety: a tap can arrive while the NavHost is
                        // still on Route.AppLoading (startDestination). AppLoadingScreen
                        // finishes by navigating to ChatList with
                        // popUpTo(AppLoading, inclusive = true) — so anything we push
                        // *during* loading (Moments/MomentDetail) gets popped off with
                        // AppLoading and the user lands on ChatList. Gate on ChatList
                        // being present (returns immediately when warm; on cold start
                        // resolves the instant AppLoading→ChatList completes, by which
                        // point AppLoading is already gone) before pushing. Mirrors the
                        // OpenConversation gate above.
                        navController.currentBackStack.firstContaining {
                            it.destination.hasRoute(Route.ChatList::class)
                        }
                        // Push Moments first so back-press from the reels detail lands
                        // on the feed, then open the detail pager on the tapped moment.
                        // The pager resolves the moment from MomentsFeedService's live
                        // feed, which is already syncing post-auth (and waits for it).
                        navController.navigate(Route.Moments) {
                            popUpTo(Route.ChatList) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                        navController.navigate(
                            Route.MomentDetail(
                                momentId = momentId.toString(),
                                openComments = event.openComments,
                            )
                        )
                    }
                    TextRenderingHelper.nudge()
                }
            }
        }
    }

    // Translate Moments onboarding one-shot events into nav-stack changes.
    LaunchedEffect(Unit) {
        momentsViewModel.events.collect { event ->
            when (event) {
                MomentsUiEvent.Activated -> {
                    navController.popBackStack(Route.MomentsOnboarding, inclusive = true)
                    navController.navigate(Route.Moments) {
                        popUpTo(Route.ChatList) { saveState = true }
                        launchSingleTop = true
                    }
                }
                MomentsUiEvent.CloseOnboarding -> navController.popBackStack()
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (showBottomNavigationBar) {
                NavigationBar {
                    topLevelRoutes.forEach { topLevelRoute ->
                        NavigationBarItem(
                            icon = {
                                Icon(
                                    topLevelRoute.icon,
                                    contentDescription = stringResource(topLevelRoute.labelRes)
                                )
                            },
                            label = { Text(stringResource(topLevelRoute.labelRes)) },
                            selected = currentDestination?.hasRoute(
                                topLevelRoute.route::class
                            ) == true,
                            onClick = {
                                TextRenderingHelper.nudge()
                                when {
                                    topLevelRoute is TopLevelRoute.Moments -> openMoments()
                                    topLevelRoute is TopLevelRoute.Vault -> openVault()
                                    else -> navController.navigate(topLevelRoute.route) {
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
                                icon = {
                                    Icon(
                                        topLevelRoute.icon,
                                        contentDescription = stringResource(topLevelRoute.labelRes)
                                    )
                                },
                                // label = { Text(stringResource(topLevelRoute.labelRes)) },
                                selected = currentDestination?.hasRoute(topLevelRoute.route::class) == true,
                                onClick = {
                                    TextRenderingHelper.nudge()
                                    when {
                                        topLevelRoute is TopLevelRoute.Moments -> openMoments()
                                        topLevelRoute is TopLevelRoute.Vault -> openVault()
                                        else -> navController.navigate(topLevelRoute.route) {
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

                        val pendingUpgrade = uiState.pendingUpgrade
                        if (pendingUpgrade is PendingUpgradeState.ShowSnackbar) {
                            LaunchedEffect(pendingUpgrade) {
                                val result = snackbarHostState.showSnackbar(
                                    message = snackbarMessage,
                                    actionLabel = snackbarAction,
                                    duration = SnackbarDuration.Long,
                                )
                                if (result == SnackbarResult.ActionPerformed) {
                                    uriHandler.openUrl(pendingUpgrade.upgradeUrl)
                                }
                            }
                        }

                        // Snackbar fired once per process after DatabaseManager wipes the local
                        // DB on a schema-version bump. Tells the user why their conversations /
                        // vault / feed appear empty while DriveSync repopulates from the server.
                        // Skipped on fresh installs (fromVersion == 0): no prior data, nothing
                        // to "restore". markUpgradeConsumed() flips state back to Idle so
                        // recomposition doesn't re-fire the effect.
                        val dbUpgrade by DatabaseManager.databaseUpgradeState.collectAsStateWithLifecycle()
                        val dbUpgradeSnapshot = dbUpgrade
                        if (dbUpgradeSnapshot is DatabaseUpgradeState.JustUpgraded &&
                            dbUpgradeSnapshot.fromVersion > 0
                        ) {
                            val dbUpgradeMsg = stringResource(MR.string.database_upgrade_snackbar)
                            LaunchedEffect(dbUpgradeSnapshot) {
                                snackbarHostState.showSnackbar(
                                    message = dbUpgradeMsg,
                                    duration = SnackbarDuration.Long,
                                )
                                DatabaseManager.markUpgradeConsumed()
                            }
                        }

                        if (pendingUpgrade is PendingUpgradeState.ShowDialog) {
                            AlertDialog(
                                onDismissRequest = { viewModel.dismissUpgradeDialog() },
                                title = { Text(stringResource(MR.string.pending_upgrade_title)) },
                                text = { Text(stringResource(MR.string.pending_upgrade_message)) },
                                confirmButton = {
                                    TextButton(onClick = { uriHandler.openUrl(pendingUpgrade.upgradeUrl) }) {
                                        Text(stringResource(MR.string.pending_upgrade_confirm))
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { viewModel.dismissUpgradeDialog() }) {
                                        Text(stringResource(MR.string.cancel))
                                    }
                                },
                            )
                        }

                        if (pendingUpgrade is PendingUpgradeState.UpgradeRunning) {
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                tonalElevation = 2.dp,
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Text(
                                        text = stringResource(MR.string.upgrade_running_message),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                }
                            }
                        }
                    }

                    NavHost(
                        navController = navController,
                        startDestination = Route.AppLoading,
                        modifier = Modifier.weight(1f),
                        enterTransition = {
                            if (isBetweenTopLevelRoutes()) EnterTransition.None
                            else if (targetState.destination.isVerticalSlideRoute()) EnterTransition.None
                            else slideInHorizontally(
                                initialOffsetX = { 1000 }, animationSpec = tween(500)
                            )
                        },
                        exitTransition = {
                            if (isBetweenTopLevelRoutes()) ExitTransition.None
                            else if (targetState.destination.isVerticalSlideRoute()) ExitTransition.None
                            else slideOutHorizontally(
                                targetOffsetX = { -1000 }, animationSpec = tween(500)
                            )
                        },
                        popEnterTransition = {
                            if (isBetweenTopLevelRoutes()) EnterTransition.None
                            else if (initialState.destination.isVerticalSlideRoute()) EnterTransition.None
                            else slideInHorizontally(
                                initialOffsetX = { -1000 }, animationSpec = tween(500)
                            )
                        },
                        popExitTransition = {
                            if (isBetweenTopLevelRoutes()) ExitTransition.None
                            else if (initialState.destination.isVerticalSlideRoute()) ExitTransition.None
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
                                    onNavigateToVault = openVault,
                                    onNavigateToMoments = openMoments,
                                    onNavigateToExamples = { navController.navigate(Route.Examples) },
                                )
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
                                    onShowContactInfo = { odinId, conversationId ->
                                        navController.navigate(Route.ContactInfo(odinId, conversationId))
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
                                    onNavigateToMomentsSettings = {
                                        navController.navigate(Route.MomentsSettings)
                                    },
                                    onNavigateToVaultSettings = {
                                        navController.navigate(Route.VaultSettings)
                                    },
                                )
                            }
                        }

                        composable<Route.MomentsOnboarding> {
                            if (isAuthenticated) {
                                MomentsOnboardingScreen(
                                    viewModel = momentsViewModel,
                                    onNavigateBack = { navController.popBackStack() },
                                )
                            }
                        }

                        composable<Route.Moments> {
                            if (isAuthenticated) {
                                MomentsScreen(
                                    viewModel = koinViewModel(),
                                    extendPermissionViewModel = momentsViewModel.momentsExtendPermissionViewModel,
                                    onCreateMoment = {
                                        navController.navigate(Route.MomentCompose)
                                    },
                                    onProfileClick = {
                                        navController.navigate(Route.Settings)
                                    },
                                    onOpenMoment = { id, payloadKey ->
                                        navController.navigate(
                                            Route.MomentDetail(id, payloadKey)
                                        )
                                    },
                                )
                            }
                        }

                        composable<Route.MomentDetail> { backStackEntry ->
                            if (isAuthenticated) {
                                // Full-screen detail uses an Instagram-Reels-
                                // style vertical pager over the in-memory
                                // feed; the route's momentId picks the
                                // initial page. Per-page VMs are allocated
                                // inside [MomentDetailPager] via Koin. The
                                // wide-desktop moments screen still embeds
                                // MomentDetailPane directly for its side
                                // pane (no vertical paging there).
                                val route = backStackEntry.toRoute<Route.MomentDetail>()
                                val momentId = Uuid.parse(route.momentId)
                                MomentDetailPager(
                                    initialMomentId = momentId,
                                    initialPayloadKey = route.initialPayloadKey,
                                    openCommentsInitially = route.openComments,
                                    onNavigateBack = { navController.popBackStack() },
                                )
                            }
                        }

                        composable<Route.MomentCompose> {
                            if (isAuthenticated) {
                                MomentComposeScreen(
                                    viewModel = koinViewModel(),
                                    onNavigateBack = { navController.popBackStack() },
                                    onNavigateToAudience = {
                                        navController.navigate(Route.MomentAudience)
                                    },
                                    onNavigateToCropper = { requestId ->
                                        navController.navigate(Route.Crop(requestId.toString()))
                                    },
                                    onNavigateToDrawer = { requestId ->
                                        navController.navigate(Route.Draw(requestId.toString()))
                                    },
                                )
                            }
                        }

                        composable<Route.MomentAudience> {
                            if (isAuthenticated) {
                                MomentAudienceScreen(
                                    viewModel = koinViewModel(),
                                    onNavigateBack = { navController.popBackStack() },
                                    onPosted = {
                                        // After post: clear the compose flow back
                                        // to the feed. Pop everything between
                                        // here and the Moments root.
                                        navController.popBackStack(
                                            route = Route.Moments,
                                            inclusive = false,
                                        )
                                    },
                                    onCreateGroup = {
                                        navController.navigate(Route.CreateMomentGroup)
                                    },
                                )
                            }
                        }

                        composable<Route.CreateMomentGroup> {
                            if (isAuthenticated) {
                                CreateMomentGroupScreen(
                                    viewModel = koinViewModel(),
                                    onNavigateBack = { navController.popBackStack() },
                                    onCreated = { navController.popBackStack() },
                                )
                            }
                        }

                        composable<Route.MomentsSettings> {
                            if (isAuthenticated) {
                                MomentsSettingsScreen(
                                    viewModel = koinViewModel(),
                                    onBackClick = { navController.popBackStack() },
                                    onOpenMoments = openMoments,
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

                        composable<Route.Vault> {
                            if (isAuthenticated) {
                                when (isVaultActivated) {
                                    null -> {
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            CircularProgressIndicator()
                                        }
                                    }
                                    false -> {
                                        VaultOnboardingScreen(
                                            viewModel = vaultViewModel,
                                        )
                                    }
                                    true -> {
                                        VaultScreen(
                                            vaultExtendPermissionViewModel = vaultViewModel.vaultExtendPermissionViewModel,
                                            viewModel = vaultViewModel,
                                            onNavigateToSettings = { navController.navigate(Route.VaultSettings) },
                                            onNavigateToChats = {
                                                navController.popBackStack(
                                                    Route.ChatList,
                                                    inclusive = false
                                                )
                                            },
                                            onNavigateToNoteEditor = { sectionId, entryId ->
                                                navController.navigate(Route.VaultNoteEditor(sectionId, entryId))
                                            },
                                        )
                                    }
                                }
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

                        composable<Route.VaultNoteEditor>(
                            enterTransition = {
                                slideInVertically(
                                    initialOffsetY = { it },
                                    animationSpec = tween(400),
                                )
                            },
                            exitTransition = {
                                slideOutVertically(
                                    targetOffsetY = { it },
                                    animationSpec = tween(400),
                                )
                            },
                            popEnterTransition = {
                                slideInVertically(
                                    initialOffsetY = { it },
                                    animationSpec = tween(400),
                                )
                            },
                            popExitTransition = {
                                slideOutVertically(
                                    targetOffsetY = { it },
                                    animationSpec = tween(400),
                                )
                            },
                        ) { backStackEntry ->
                            if (isAuthenticated) {
                                val route = backStackEntry.toRoute<Route.VaultNoteEditor>()
                                val sectionUuid = Uuid.parse(route.sectionId)
                                val entryUuid = route.entryId?.let { Uuid.parse(it) }
                                val noteViewModel: VaultNoteEditorViewModel = koinViewModel {
                                    parametersOf(sectionUuid, entryUuid)
                                }
                                VaultNoteEditorScreen(
                                    viewModel = noteViewModel,
                                    onBackClick = { navController.popBackStack() },
                                    onShareFile = { filePath ->
                                        uriHandler.shareFile(Path(filePath))
                                    },
                                )
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
            this?.hasRoute(Route.Feed::class) == true ||
            this?.hasRoute(Route.Moments::class) == true ||
            this?.hasRoute(Route.Home::class) == true ||
            this?.hasRoute(Route.Vault::class) == true
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.isBetweenTopLevelRoutes(): Boolean {
    return initialState.destination.isTopLevelRoute() && targetState.destination.isTopLevelRoute()
}

private fun NavDestination?.isVerticalSlideRoute(): Boolean {
    return this?.hasRoute(Route.VaultNoteEditor::class) == true
}

sealed class TopLevelRoute(
    val route: Route,
    val labelRes: StringResource,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    data object Chat : TopLevelRoute(Route.ChatList, MR.string.nav_chats, BootstrapChat)
    data object Feed : TopLevelRoute(Route.Feed, MR.string.nav_feed, Icons.Default.RssFeed)
    data object Moments : TopLevelRoute(Route.Moments, MR.string.nav_moments, Icons.Outlined.AutoAwesome)
    data object Home : TopLevelRoute(Route.Home, MR.string.nav_home, Icons.Default.Home)
    data object Vault : TopLevelRoute(Route.Vault, MR.string.vault_label, Icons.Outlined.Lock)
}
