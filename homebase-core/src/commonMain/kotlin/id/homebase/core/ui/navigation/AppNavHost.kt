package id.homebase.core.ui.navigation

import id.homebase.core.ui.screens.email.thunderbird.EmailThunderbirdSetupScreen
import id.homebase.core.ui.screens.email.secrets.EmailSecretsScreen
import id.homebase.resources.email_label
import androidx.compose.material.icons.outlined.MailOutline
import id.homebase.core.ui.screens.email.settings.EmailSettingsScreen
import id.homebase.core.ui.screens.email.EmailViewModel
import id.homebase.core.ui.screens.email.EmailScreen
import id.homebase.core.email.EmailPreferences
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.navigation.NavGraphBuilder
import id.homebase.chat.conversationlist.chatOwnsWindow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.FloatingWindow
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import co.touchlab.kermit.Logger
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.DatabaseUpgradeState
import id.homebase.api.youauth.YouAuthFlowManager
import id.homebase.api.youauth.YouAuthState
import id.homebase.auth.login.LoginScreen
import id.homebase.chat.addgroupmembers.AddGroupMembersScreen
import id.homebase.chat.archivedconversations.ArchivedConversationsScreen
import id.homebase.api.crypto.Md5
import id.homebase.chat.contactcard.ContactCardDescriptor
import id.homebase.chat.conversationlist.ConversationListScreen
import id.homebase.chat.conversationmedia.ConversationMediaScreen
import id.homebase.chat.conversationlist.ConversationListViewModel
import id.homebase.chat.conversationlist.ConversationLoadTrigger
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
import id.homebase.core.ui.screens.defragmenter.DefragmenterScreen
import id.homebase.core.ui.screens.help.HelpScreen
import id.homebase.core.ui.screens.devmenu.DeveloperMenuScreen
import id.homebase.core.settings.DeveloperPreferences
import id.homebase.core.settings.UserPreferences
import id.homebase.core.ui.screens.devmenu.scheduledpush.DeveloperScheduledPushTestScreen
import id.homebase.core.ui.screens.feed.FeedScreen
import id.homebase.core.ui.screens.feed.FeedTimelineScreen
import id.homebase.core.ui.screens.feed.PostDetailScreen
import id.homebase.core.ui.screens.home.HomeScreen
import id.homebase.core.ui.screens.loading.AppLoadingScreen
import id.homebase.core.ui.screens.keyboard.KeyboardSettingsScreen
import id.homebase.core.ui.screens.media.MediaSettingsScreen
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
import id.homebase.core.moments.services.MomentsFeedService
import id.homebase.core.location.LocationPreferences
import id.homebase.core.ui.screens.location.EmergencyContactPickerScreen
import id.homebase.core.connections.ConnectRequestAction
import id.homebase.core.connections.ConnectRequestBottomSheet
import id.homebase.core.connections.ConnectRequestViewModel
import id.homebase.core.contactbook.EmergencyContactService
import id.homebase.core.ui.screens.location.LocationEmergencyScreen
import id.homebase.core.ui.screens.location.LocationHistoryOverviewScreen
import id.homebase.core.ui.screens.location.LocationLiveSharingScreen
import id.homebase.core.ui.screens.location.LocationScreen
import id.homebase.core.ui.screens.location.LocationSettingsScreen
import id.homebase.core.ui.screens.location.LocationUiEvent
import id.homebase.core.ui.screens.location.LocationViewModel
import id.homebase.core.ui.screens.location.devices.FindDeviceScreen
import id.homebase.core.ui.screens.location.history.LocationHistoryScreen
import id.homebase.core.ui.screens.location.livelocation.LiveLocationScreen
import id.homebase.core.ui.screens.location.onboarding.LocationOnboardingScreen
import id.homebase.core.ui.screens.location.share.ShareLocationScreen
import id.homebase.core.ui.screens.notifications.NotificationSettingsScreen
import id.homebase.core.haptics.HapticEvent
import id.homebase.core.haptics.rememberHaptics
import id.homebase.core.ui.screens.card.CardTapShareDriver
import id.homebase.core.ui.screens.card.ProfileCardEditorScreen
import id.homebase.core.ui.screens.card.ProfileCardScreen
import id.homebase.core.ui.screens.card.StartCardHostWhenSettled
import id.homebase.core.ui.screens.profile.ProfileAvatarEditScreen
import id.homebase.core.ui.screens.profile.ProfileEditScreen
import id.homebase.core.ui.screens.settings.SettingsActions
import id.homebase.core.ui.screens.settings.SettingsPaneActions
import id.homebase.core.ui.screens.settings.SettingsPaneHost
import id.homebase.core.ui.screens.settings.SettingsScreen
import androidx.compose.material3.CircularProgressIndicator
import id.homebase.core.ui.screens.vault.VaultScreen
import id.homebase.core.ui.screens.vault.auth.VaultSessionTracker
import id.homebase.core.ui.screens.vault.VaultUiEvent
import id.homebase.core.ui.screens.vault.VaultViewModel
import id.homebase.core.ui.screens.webdrop.WebDropScreen
import id.homebase.core.ui.screens.webdrop.WebDropUiEvent
import id.homebase.core.ui.screens.webdrop.WebDropViewModel
import id.homebase.core.ui.screens.webdrop.onboarding.WebDropOnboardingScreen
import id.homebase.core.ui.screens.vault.note.VaultNoteEditorScreen
import id.homebase.core.ui.screens.vault.note.VaultNoteEditorViewModel
import id.homebase.core.ui.screens.vault.onboarding.VaultOnboardingScreen
import id.homebase.core.ui.screens.vault.settings.VaultSettingsScreen
import id.homebase.core.ui.screens.storage.StorageSettingsScreen
import id.homebase.core.ui.screens.widget.RichTextExample
import id.homebase.core.vault.VaultPreferences
import id.homebase.core.contactbook.ContactBookPreferences
import id.homebase.core.ui.screens.contactbook.CircleMemberPickerScreen
import id.homebase.core.ui.screens.contactbook.ContactBookScreen
import id.homebase.core.ui.screens.contactbook.add.AddContactScreen
import id.homebase.core.ui.screens.contactbook.ContactBookUiAction
import id.homebase.core.ui.screens.contactbook.ContactBookUiEvent
import id.homebase.core.ui.screens.contactbook.ContactBookViewModel
import id.homebase.core.ui.screens.contactbook.ShareContactPickerScreen
import id.homebase.core.ui.screens.contactbook.components.ContactCardDescriptorSaver
import id.homebase.core.ui.screens.contactbook.components.ContactCardSaveHost
import id.homebase.core.ui.screens.contactbook.detail.ContactDetailScreen
import id.homebase.core.ui.screens.contactbook.onboarding.ContactBookOnboardingScreen
import id.homebase.core.ui.screens.contactbook.settings.ContactBookSettingsScreen
import id.homebase.core.ui.screens.contactbook.enrollment.EnrollmentCandidatesScreen
import id.homebase.resources.chat_contact_card_saved_body
import id.homebase.resources.chat_contact_card_saved_open
import id.homebase.resources.contactbook_label
import id.homebase.resources.nav_chats
import id.homebase.resources.nav_feed
import id.homebase.resources.nav_home
import id.homebase.resources.location_attention_cd
import id.homebase.resources.location_label
import id.homebase.resources.location_emergency_action_failed
import id.homebase.resources.location_locate_fetch_failed
import id.homebase.resources.vault_label
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import id.homebase.core.ui.theme.NavigationIndicatorShape
import id.homebase.core.util.getUriHandler
import id.homebase.core.util.isDesktopOrWeb
import id.homebase.core.util.isWeb
import id.homebase.core.util.isExpandedLayout
import id.homebase.chat.conversationlist.ConversationListUiAction
import id.homebase.resources.chat_archived_chats
import id.homebase.resources.settings
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.io.files.Path
import id.homebase.core.widget.InAppNotificationBanner
import id.homebase.core.widget.UpdateAvailableBanner
import id.homebase.imageeditor.ui.CropScreen
import id.homebase.imageeditor.ui.DrawScreen
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import id.homebase.resources.profile_card_nfc_shared
import id.homebase.resources.pending_upgrade_snackbar_action
import id.homebase.resources.pending_upgrade_title
import id.homebase.resources.database_upgrade_snackbar
import id.homebase.resources.pending_upgrade_message
import id.homebase.resources.pending_upgrade_confirm
import id.homebase.resources.upgrade_running_message
import id.homebase.core.session.IdentitySessionScope

// Set on the current destination when its already-selected bottom-nav / rail item is re-tapped.
private const val SCROLL_TO_TOP_KEY = "scrollToTop"

// Set on the ChatList entry by the rail's Archive action; the list pane owns the archived
// view as state, so it cannot be reached by navigating to a route.
private const val SHOW_ARCHIVED_KEY = "showArchived"

// Material's 80dp rail is tuned for touch; desktop chat clients sit at 64dp.
private val NavigationRailWidth = 64.dp
private val RailIndicatorSize = 48.dp
private val RailIconSize = 20.dp

@Composable
fun AppNavHost(
    viewModel: AppViewModel,
    navController: NavHostController,
    youAuthFlowManager: YouAuthFlowManager,
    topInset: Dp = 0.dp,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val authState by youAuthFlowManager.authState.collectAsStateWithLifecycle()
    // Gated on the identity scope being open as well as the auth state, because the two are
    // observed independently: AuthConnectionCoordinator collects the same authState flow, so
    // this composition can see Authenticated a frame before the scope exists, and every
    // authenticated route in this graph is gated on this one flag.
    // `closed`, not `!= null`: the emitted reference outlives the scope it names, so the
    // teardown direction reads open for as long as this collector lags. It is only a gate —
    // what keeps the resolutions above it alive across a teardown is IdentityScope (#1373).
    val identityScope by koinInject<IdentitySessionScope>().currentScope.collectAsStateWithLifecycle()
    val isAuthenticated = authState is YouAuthState.Authenticated && identityScope?.closed == false
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    // A settings pane floats over the screen beneath it, which stays mounted. The rail and bottom
    // bar must keep tracking that screen or they vanish (and reflow it) the moment a pane opens.
    val backStack by navController.currentBackStack.collectAsStateWithLifecycle()
    val chromeEntry = if (isDesktopOrWeb()) {
        backStack.lastOrNull {
            it.destination !is FloatingWindow && it.destination !is NavGraph
        }
    } else {
        navBackStackEntry
    }
    val chromeDestination = chromeEntry?.destination
    val momentsPreferences = koinInject<MomentsPreferences>()
    val momentsIconVisible by momentsPreferences.iconVisible.collectAsStateWithLifecycle()
    val momentsFeedService = koinInject<MomentsFeedService>()
    val momentsUnseenCount by momentsFeedService.unseenCount.collectAsStateWithLifecycle()
    val momentsViewModel: MomentsViewModel = koinViewModel()
    val vaultPreferences = koinInject<VaultPreferences>()
    val vaultIconVisible by vaultPreferences.iconVisible.collectAsStateWithLifecycle()
    val vaultViewModel: VaultViewModel = koinViewModel()
    val locationPreferences = koinInject<LocationPreferences>()
    val locationIconVisible by locationPreferences.iconVisible.collectAsStateWithLifecycle()
    val locationViewModel: LocationViewModel = koinViewModel()
    val locationAttention by koinInject<EmergencyContactService>().hasStale.collectAsStateWithLifecycle()
    val contactBookPreferences = koinInject<ContactBookPreferences>()
    val contactBookIconVisible by contactBookPreferences.iconVisible.collectAsStateWithLifecycle()
    val contactBookOnboardingComplete by contactBookPreferences.onboardingComplete.collectAsStateWithLifecycle()
    val contactBookViewModel: ContactBookViewModel = koinViewModel()
    val emailPreferences = koinInject<EmailPreferences>()
    val emailIconVisible by emailPreferences.iconVisible.collectAsStateWithLifecycle()
    // Null until this host has answered once: no icon rather than one that leads to "no email
    // here". Hosts that do run mail cache a yes and get the icon on the first frame after that.
    val serverSupportsMail by emailPreferences.serverSupportsMail.collectAsStateWithLifecycle()
    val emailViewModel: EmailViewModel = koinViewModel()
    val emailUiState by emailViewModel.uiState.collectAsStateWithLifecycle()
    val profileCardEnabled by koinInject<DeveloperPreferences>().profileCardEnabled.collectAsStateWithLifecycle()
    val emailUnreadCount = emailUiState.mailboxStatus
        ?.takeIf { it.available }
        ?.inboxUnread ?: 0
    val topLevelRoutes = remember(
        momentsIconVisible,
        vaultIconVisible,
        locationIconVisible,
        contactBookIconVisible,
        emailIconVisible,
        serverSupportsMail,
    ) {
        buildList {
            add(TopLevelRoute.Chat)
            add(TopLevelRoute.Feed)
            if (momentsIconVisible) add(TopLevelRoute.Moments)
            if (vaultIconVisible) add(TopLevelRoute.Vault)
            if (locationIconVisible) add(TopLevelRoute.Location)
            if (contactBookIconVisible) add(TopLevelRoute.ContactBook)
            if (emailIconVisible && serverSupportsMail == true) add(TopLevelRoute.Email)
            add(TopLevelRoute.Home)
        }
    }
    // Read at call time: the event collectors below capture these lambdas once.
    val tabRoutes by rememberUpdatedState(topLevelRoutes.map { it.route })
    // The nearest tab root with a bar item: it stays lit under a pushed screen or a hidden add-on.
    val selectedTab = backStack.currentTabRoot(tabRoutes)?.destination
    val openEmail: () -> Unit = { navController.openApp(Route.Email, tabRoutes) }
    val openContactBook: () -> Unit = { navController.openApp(Route.ContactBook, tabRoutes) }
    val openMoments: () -> Unit = { navController.openApp(Route.Moments, tabRoutes) }
    val openLocation: () -> Unit = { navController.openApp(Route.Location, tabRoutes) }
    val openChats: () -> Unit = { navController.switchTab(Route.ChatList, tabRoutes) }
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

    // This Scaffold's SnackbarHost is anchored to the bottom of the window, where the chat
    // composer is: a notice raised here would sit on top of the input field.
    var isChatComposerOpen by remember { mutableStateOf(false) }

    val cardSharedMessage = stringResource(MR.string.profile_card_nfc_shared)
    val haptics = rememberHaptics()
    val cardSharedScope = rememberCoroutineScope()
    CardTapShareDriver {
        haptics.perform(HapticEvent.Confirm)
        if (!isChatComposerOpen) cardSharedScope.launch { snackbarHostState.showSnackbar(cardSharedMessage) }
    }

    // Latched out of the composer gate below so the notice survives being suppressed on a chat
    // screen, and is consumed only once it has actually run its course.
    val dbUpgrade by DatabaseManager.databaseUpgradeState.collectAsStateWithLifecycle()
    var pendingDbUpgradeNotice by remember { mutableStateOf(false) }
    val dbUpgradeSnapshot = dbUpgrade
    if (dbUpgradeSnapshot is DatabaseUpgradeState.JustUpgraded &&
        dbUpgradeSnapshot.fromVersion > 0
    ) {
        LaunchedEffect(dbUpgradeSnapshot) {
            pendingDbUpgradeNotice = true
            DatabaseManager.markUpgradeConsumed()
        }
    }

    // Check if current destination is a top-level route. Uses the static route-type
    // check (not topLevelRoutes) so the bottom nav still shows on the Vault screen even
    // when the user has hidden the Vault icon from the nav bar.
    val isTopLevelRoute = chromeDestination.isTopLevelRoute()

    val showNavigationRail = isExpandedLayout()
    val vaultUiState by vaultViewModel.uiState.collectAsStateWithLifecycle()
    // The full-screen image editor (newly-picked images) is a state-driven overlay, not a nav
    // destination, so it folds into the same gate the gallery uses.
    val isVaultOverlayOpen = vaultUiState.fullScreenOverlay != null || vaultUiState.pendingEditor != null
    val chromeOwnedByScreen =
        chromeEntry != null && entryOwnsWindow(chromeEntry, showNavigationRail, isVaultOverlayOpen)
    val isOnTopLevelScreen = isAuthenticated && isTopLevelRoute && !chromeOwnedByScreen

    // Safe only because login's top-left is bare in both its layouts: brand artwork on the
    // two-pane, plain surface in portrait — the traffic lights land on nothing either way.
    val paintsUnderTitleBar = chromeDestination?.hasRoute(Route.Login::class) == true
    // The card fills its sheet to the screen's bottom edge; its own chrome pads for the navigation bar.
    val paintsUnderNavigationBar = chromeDestination.isCardRoute()
    val showBottomNavigationBar = isOnTopLevelScreen && !showNavigationRail
    val railVisible = isOnTopLevelScreen && showNavigationRail
    val contentInsets = ScaffoldDefaults.contentWindowInsets.only(
        if (paintsUnderNavigationBar) WindowInsetsSides.Horizontal
        else WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
    )
    // Tab roots pad for the bar themselves, so a push or pop never re-measures the screen under it.
    var bottomBarHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val bottomBarPadding = with(density) {
        (bottomBarHeightPx - contentInsets.getBottom(density)).coerceAtLeast(0).toDp()
    }
    val chromeMotion = MaterialTheme.motionScheme
    val pendingUpgradeState = uiState.pendingUpgrade
    val tabRoot: @Composable (NavBackStackEntry, @Composable () -> Unit) -> Unit = { entry, content ->
        val ownsWindow = entryOwnsWindow(entry, showNavigationRail, isVaultOverlayOpen)
        val barPadding = if (showNavigationRail || ownsWindow) 0.dp else bottomBarPadding
        val showUpdate = uiState.updateAvailable && !ownsWindow
        val showUpgradeRunning = pendingUpgradeState is PendingUpgradeState.UpgradeRunning && !ownsWindow
        Column(
            modifier = Modifier
                .consumeWindowInsets(PaddingValues(bottom = barPadding))
                .padding(bottom = barPadding),
        ) {
            // Not statusBarsPadding(): outside Android it consumes into a legacy modifier-local
            // channel the screens' TopAppBars cannot see, so they would re-pad the top inset.
            AnimatedVisibility(
                visible = showUpdate,
                enter = expandVertically(chromeMotion.defaultSpatialSpec()) + fadeIn(chromeMotion.defaultEffectsSpec()),
                exit = shrinkVertically(chromeMotion.fastSpatialSpec()) + fadeOut(chromeMotion.fastEffectsSpec()),
            ) {
                UpdateAvailableBanner(
                    modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars),
                    versionName = uiState.updateAvailableVersion,
                    onUpdateClick = { viewModel.triggerUpdate() },
                )
            }
            AnimatedVisibility(
                visible = showUpgradeRunning,
                enter = expandVertically(chromeMotion.defaultSpatialSpec()) + fadeIn(chromeMotion.defaultEffectsSpec()),
                exit = shrinkVertically(chromeMotion.fastSpatialSpec()) + fadeOut(chromeMotion.fastEffectsSpec()),
            ) {
                UpgradeRunningStrip(
                    modifier = if (showUpdate) Modifier else Modifier.windowInsetsPadding(WindowInsets.statusBars),
                )
            }
            Box(
                modifier = Modifier.weight(1f).then(
                    if (showUpdate || showUpgradeRunning) {
                        Modifier.consumeWindowInsets(WindowInsets.statusBars)
                    } else {
                        Modifier
                    }
                ),
            ) {
                content()
            }
        }
    }

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

    // Keeps the Vault biometric session alive across every Vault sub-screen — owned by
    // the vault feature, not this nav host.
    VaultSessionTracker(navController)

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
            // Not on web: a browser only shows the permission prompt from a user gesture, so the
            // ask has to come from the offer banner's Enable button instead.
            if (authState is YouAuthState.Authenticated && !hasNotificationPermission && !isWeb()) {
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
                is VaultUiEvent.NavigateToCropper,
                is VaultUiEvent.NavigateToDrawer,
                is VaultUiEvent.OpenWebDrop,
                is VaultUiEvent.Error -> { /* handled by VaultScreen */ }
            }
        }
    }

    // Contact Book events → navigation. OpenConversation lands the chat list on
    // the (created-if-needed) 1:1 conversation; CloseOnboarding pops back out of
    // the contacts tab after a skip.
    LaunchedEffect(Unit) {
        contactBookViewModel.events.collect { event ->
            when (event) {
                is ContactBookUiEvent.OpenConversation -> {
                    navController.selectConversationOnChatList(event.conversationId)
                    openChats()
                }
                is ContactBookUiEvent.OpenDetail ->
                    navController.navigate(Route.ContactBookDetail(event.uniqueId, event.odinId))
                ContactBookUiEvent.OpenAddContact ->
                    navController.navigate(Route.AddContact())
                is ContactBookUiEvent.OpenCircleMemberAdd ->
                    navController.navigate(Route.CircleMemberAdd(event.circleId, event.circleName))

                ContactBookUiEvent.OpenEnrollmentCandidates ->
                    navController.navigate(Route.EnrollmentCandidates)
                ContactBookUiEvent.CloseOnboarding -> navController.popBackStack()
                else -> { /* Error handled by ContactBookScreen */ }
            }
        }
    }

    val isVaultActivated by vaultViewModel.isActivated.collectAsStateWithLifecycle()

    val openVault: () -> Unit = { navController.openApp(Route.Vault, tabRoutes) }
    val openWebDrop: () -> Unit = { navController.openApp(Route.WebDrop, tabRoutes) }

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
                    openChats()
                    // Share intents carry no messageId, so PendingNotificationTap
                    // cannot resolve them. Drop the conversation id directly into
                    // ChatList's savedStateHandle — the LaunchedEffect on the
                    // ChatList composable picks it up and calls
                    // ConversationListViewModel.selectConversation, which in turn
                    // runs processPendingSharedContent so the shared file lands
                    // in the correct conversation.
                    if (event.source == NotificationNavigationEvent.OpenConversation.Source.ShareIntent) {
                        // Tag this as a share-caused navigation so processPendingSharedContent
                        // actually sends the pending descriptor (and only this path does).
                        navController.selectConversationOnChatList(id, fromShareIntent = true)
                    }
                }

                is NotificationNavigationEvent.OpenUrl -> uriHandler.openUrl(event.url)

                is NotificationNavigationEvent.OpenConnectionRequest -> {
                    val domain = event.odinId.lowercase()
                    Logger.i(tag = "AppNavHost") { "OpenConnectionRequest received: $domain" }
                    // Cold-start safety, as in OpenMoment below: a tap can land while the host is
                    // still on Route.AppLoading, whose ChatList navigation pops (inclusive) —
                    // anything pushed before that would go with it. Gate on ChatList being in the
                    // stack (immediate when warm).
                    navController.currentBackStack.firstContaining {
                        it.destination.hasRoute(Route.ChatList::class)
                    }
                    // Push the contact book first so back-press from the request lands on the
                    // contacts tab rather than dropping straight out to chat.
                    openContactBook()
                    navController.navigate(
                        // Same synthetic key the contact book uses for an identity with no saved
                        // contact record — a pending requester never has one.
                        Route.ContactBookDetail(
                            uniqueId = Md5.toGuidId(domain).toString(),
                            odinId = domain,
                        )
                    )
                }

                is NotificationNavigationEvent.OpenMoment -> {
                    val momentId = Uuid.parseOrNull(event.momentId)
                    Logger.i(tag = "AppNavHost") {
                        "OpenMoment received: id=$momentId openComments=${event.openComments} " +
                                "activated=${momentsViewModel.isActivated.value}"
                    }
                    // Only route when Moments is activated (receiving a moment push
                    // implies the moments drive is subscribed, so this normally holds).
                    if (momentId != null && momentsViewModel.isActivated.value) {
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
                        openMoments()
                        navController.navigate(
                            Route.MomentDetail(
                                momentId = momentId.toString(),
                                openComments = event.openComments,
                            )
                        )
                    }
                }

                is NotificationNavigationEvent.OpenMomentCompose -> {
                    Logger.i(tag = "AppNavHost") {
                        "OpenMomentCompose received: activated=${momentsViewModel.isActivated.value}"
                    }
                    // The share flow seeded MomentCreateFlowState before launching
                    // us; MomentComposeViewModel reads that draft on init. Gate on
                    // Moments being activated (the share picker only offers "New
                    // Moment" when it is) and mirror the OpenMoment back-stack
                    // handling: push Moments first so back-press from the composer
                    // lands on the feed, then open the composer.
                    if (momentsViewModel.isActivated.value) {
                        navController.currentBackStack.firstContaining {
                            it.destination.hasRoute(Route.ChatList::class)
                        }
                        openMoments()
                        navController.navigate(Route.MomentCompose)
                    }
                }

                is NotificationNavigationEvent.OpenWebDropCompose -> {
                    Logger.i(tag = "AppNavHost") { "OpenWebDropCompose received" }
                    // The share flow seeded WebDropShareFlowState before launching us;
                    // WebDropViewModel consumes it on init and opens the compose sheet.
                    // The share picker only offers "New WebDrop" when the drive is
                    // activated, so no extra gate here - and if it ever fires without
                    // activation, the WebDrop screen itself shows onboarding.
                    openWebDrop()
                }
            }
        }
    }

    // Translate Moments onboarding one-shot events into nav-stack changes.
    LaunchedEffect(Unit) {
        momentsViewModel.events.collect { event ->
            when (event) {
                // Onboarding is the tab root's own content, which swaps to the feed on activation.
                MomentsUiEvent.Activated -> Unit
                MomentsUiEvent.CloseOnboarding -> navController.popBackStack()
            }
        }
    }

    // Translate Location onboarding one-shot events into nav-stack changes.
    val locateFetchFailedMsg = stringResource(MR.string.location_locate_fetch_failed)
    val emergencyContactActionFailedMsg = stringResource(MR.string.location_emergency_action_failed)
    LaunchedEffect(Unit) {
        locationViewModel.events.collect { event ->
            when (event) {
                LocationUiEvent.Activated -> Unit
                LocationUiEvent.CloseOnboarding -> navController.popBackStack()

                is LocationUiEvent.OpenPeerHistory -> navController.navigate(
                    Route.LocationPeerHistory(
                        peerDomain = event.peerDomain,
                        peerName = event.peerName,
                    )
                )

                LocationUiEvent.LocateFetchFailed -> snackbarHostState.showSnackbar(
                    message = locateFetchFailedMsg,
                    duration = SnackbarDuration.Long,
                )

                LocationUiEvent.EmergencyContactActionFailed -> snackbarHostState.showSnackbar(
                    message = emergencyContactActionFailedMsg,
                    duration = SnackbarDuration.Long,
                )
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
        // Leave the top inset to each screen: consuming it here pads everything
        // below the status bar, so no screen's TopAppBar can extend behind it.
        contentWindowInsets = contentInsets,
        bottomBar = {
            AnimatedVisibility(
                visible = showBottomNavigationBar,
                enter = slideInVertically(chromeMotion.defaultSpatialSpec()) { it } +
                    fadeIn(chromeMotion.defaultEffectsSpec()),
                exit = slideOutVertically(chromeMotion.defaultSpatialSpec()) { it } +
                    fadeOut(chromeMotion.fastEffectsSpec()),
            ) {
                ShortNavigationBar(modifier = Modifier.onSizeChanged { bottomBarHeightPx = it.height }) {
                    topLevelRoutes.forEach { topLevelRoute ->
                        val isSelected =
                            selectedTab?.hasRoute(topLevelRoute.route::class) == true
                        ShortNavigationBarItem(
                            icon = {
                                TopLevelNavIcon(
                                    topLevelRoute = topLevelRoute,
                                    showMomentsBadge = momentsUnseenCount > 0,
                                    showEmailBadge = emailUnreadCount > 0,
                                    showLocationBadge = locationAttention,
                                )
                            },
                            label = {
                                Text(
                                    stringResource(topLevelRoute.labelRes),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                )
                            },
                            selected = isSelected,
                            onClick = {
                                // Re-tapping the tab on screen scrolls to the top; a lit tab under a
                                // hidden add-on is switched back to.
                                if (isSelected && chromeDestination?.hasRoute(topLevelRoute.route::class) == true) {
                                    navController.currentBackStackEntry
                                        ?.savedStateHandle?.set(SCROLL_TO_TOP_KEY, true)
                                } else {
                                    navController.switchTab(topLevelRoute.route, tabRoutes)
                                }
                            },
                        )
                    }
                }
            }
        }) { _ ->
        // Laid out under the bar, not above it: tabRoot pads the tab screens instead.
        Box(modifier = Modifier.fillMaxSize().windowInsetsPadding(contentInsets)) {
            Row(modifier = Modifier.fillMaxSize()) {
                AnimatedVisibility(
                    visible = railVisible,
                    enter = expandHorizontally(chromeMotion.defaultSpatialSpec()) +
                        fadeIn(chromeMotion.defaultEffectsSpec()),
                    exit = shrinkHorizontally(chromeMotion.defaultSpatialSpec()) +
                        fadeOut(chromeMotion.fastEffectsSpec()),
                ) {
                    Row {
                        NavigationRail(
                            modifier = Modifier.width(NavigationRailWidth),
                            header = { Spacer(modifier = Modifier.height(8.dp + topInset)) },
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            topLevelRoutes.forEach { topLevelRoute ->
                                val isSelected =
                                    selectedTab?.hasRoute(topLevelRoute.route::class) == true
                                RailItem(
                                    topLevelRoute = topLevelRoute,
                                    selected = isSelected,
                                    showMomentsBadge = momentsUnseenCount > 0,
                                    showLocationBadge = locationAttention,
                                    onClick = {
                                        if (isSelected && chromeDestination?.hasRoute(topLevelRoute.route::class) == true) {
                                            navController.currentBackStackEntry
                                                ?.savedStateHandle?.set(SCROLL_TO_TOP_KEY, true)
                                        } else {
                                            navController.switchTab(topLevelRoute.route, tabRoutes)
                                        }
                                    })
                            }

                            if (isDesktopOrWeb()) {
                                Spacer(modifier = Modifier.weight(1f))
                                RailActionItem(
                                    icon = Icons.Default.Archive,
                                    contentDescription = stringResource(MR.string.chat_archived_chats),
                                    onClick = {
                                        openChats()
                                        runCatching { navController.getBackStackEntry<Route.ChatList>() }
                                            .getOrNull()
                                            ?.savedStateHandle?.set(SHOW_ARCHIVED_KEY, true)
                                    },
                                )
                                RailActionItem(
                                    icon = Icons.Outlined.Settings,
                                    contentDescription = stringResource(MR.string.settings),
                                    onClick = { navController.navigate(Route.Settings) },
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        }
                        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }

                Column(
                    modifier = Modifier.padding(top = if (railVisible || paintsUnderTitleBar) 0.dp else topInset),
                ) {
                    if (isOnTopLevelScreen) {
                        val pendingUpgrade = uiState.pendingUpgrade
                        if (pendingUpgrade is PendingUpgradeState.ShowSnackbar && !isChatComposerOpen) {
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

                        // Shown once per process after DatabaseManager wipes the local DB on a
                        // schema-version bump. Tells the user why their conversations / vault /
                        // feed appear empty while DriveSync repopulates from the server.
                        if (pendingDbUpgradeNotice && !isChatComposerOpen) {
                            val dbUpgradeMsg = stringResource(MR.string.database_upgrade_snackbar)
                            LaunchedEffect(Unit) {
                                snackbarHostState.showSnackbar(
                                    message = dbUpgradeMsg,
                                    duration = SnackbarDuration.Long,
                                )
                                pendingDbUpgradeNotice = false
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
                    }

                    NavHost(
                        navController = navController,
                        startDestination = Route.AppLoading,
                        modifier = Modifier.weight(1f),
                        enterTransition = { navEnter(chromeMotion) },
                        exitTransition = { navExit(chromeMotion) },
                        popEnterTransition = { navPopEnter(chromeMotion) },
                        popExitTransition = { navPopExit(chromeMotion) },
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

                        tab<Route.Home>(tabRoot) {
                            if (isAuthenticated) {
                                HomeScreen(
                                    viewModel = koinViewModel(),
                                    onNavigateToVault = openVault,
                                    onNavigateToWebDrop = openWebDrop,
                                    onNavigateToMoments = openMoments,
                                    onNavigateToLocation = openLocation,
                                    onNavigateToContacts = openContactBook,
                                    onNavigateToExamples = { navController.navigate(Route.Examples) },
                                )
                            }
                        }

                        tab<Route.Feed>(tabRoot) { entry ->
                            if (isAuthenticated) {
                                // Read on each Feed entry so the Settings toggle takes effect on return.
                                val useNativeFeed = koinInject<UserPreferences>().useNativeFeed
                                if (useNativeFeed) {
                                    val scrollFeedToTop by entry.savedStateHandle
                                        .getStateFlow(SCROLL_TO_TOP_KEY, false)
                                        .collectAsStateWithLifecycle()
                                    // ponytail: post composer disabled for now — the feed is read-only
                                    // for posts. Restore the composer nav + Route.PostCompose below.
                                    FeedTimelineScreen(
                                        viewModel = koinViewModel(),
                                        onNavigateToDetail = {
                                            navController.navigate(Route.PostDetail(it.toString()))
                                        },
                                        onAuthorClick = {
                                            navController.navigateToIdentity(it.domainName)
                                        },
                                        onFullScreenMediaChanged = { entry.savedStateHandle[OWNS_WINDOW_KEY] = it },
                                        scrollToTop = scrollFeedToTop,
                                        onScrollToTopHandled = {
                                            entry.savedStateHandle[SCROLL_TO_TOP_KEY] = false
                                        },
                                    )
                                } else {
                                    // Legacy WebView feed — user opted out of the native feed.
                                    FeedScreen(viewModel = koinViewModel())
                                }
                            }
                        }

                        composable<Route.PostDetail> { entry ->
                            if (isAuthenticated) {
                                val r = entry.toRoute<Route.PostDetail>()
                                PostDetailScreen(
                                    viewModel = koinViewModel(key = "post-detail-" + r.postId) {
                                        parametersOf(Uuid.parse(r.postId))
                                    },
                                    onBack = { navController.popBackStack() },
                                    onAuthorClick = {
                                        navController.navigateToIdentity(it.domainName)
                                    },
                                )
                            }
                        }

                        // ponytail: Route.Following is unregistered — the v2 API has no followers
                        // controller, so the screen could only show its 404 empty state. The screen,
                        // VM and provider are kept; re-add this destination once the routes ship.

                        tab<Route.ContactBook>(tabRoot) {
                            if (isAuthenticated) {
                                if (!contactBookOnboardingComplete) {
                                    ContactBookOnboardingScreen(viewModel = contactBookViewModel)
                                } else {
                                    ContactBookScreen(
                                        viewModel = contactBookViewModel,
                                        connectRequestViewModel = koinViewModel(),
                                        onProfileClick = {
                                            navController.navigate(Route.Settings)
                                        },
                                        onOpenConversation = { conversationId ->
                                            navController.selectConversationOnChatList(conversationId)
                                            openChats()
                                        },
                                    )
                                }
                            }
                        }

                        composable<Route.CircleMemberAdd> { backStackEntry ->
                            if (isAuthenticated) {
                                val route = backStackEntry.toRoute<Route.CircleMemberAdd>()
                                CircleMemberPickerScreen(
                                    viewModel = koinViewModel(
                                        key = route.circleId,
                                        parameters = {
                                            org.koin.core.parameter.parametersOf(
                                                Uuid.parseHex(route.circleId),
                                                route.circleName,
                                            )
                                        },
                                    ),
                                    onNavigateBack = { navController.popBackStack() },
                                )
                            }
                        }

                        composable<Route.EnrollmentCandidates> {
                            if (isAuthenticated) {
                                EnrollmentCandidatesScreen(
                                    viewModel = koinViewModel(),
                                    onNavigateBack = { navController.popBackStack() },
                                )
                            }
                        }

                        composable<Route.ContactBookSettings> {
                            if (isAuthenticated) {
                                val fromContacts = navController.previousBackStackEntry
                                    ?.destination?.hasRoute(Route.ContactBook::class) == true
                                ContactBookSettingsScreen(
                                    viewModel = koinViewModel(),
                                    onBackClick = { navController.popBackStack() },
                                    onOpenContacts = openContactBook,
                                    showOpenContacts = !fromContacts,
                                )
                            }
                        }

                        composable<Route.AddContact> { backStackEntry ->
                            if (isAuthenticated) {
                                val route = backStackEntry.toRoute<Route.AddContact>()
                                AddContactScreen(
                                    viewModel = koinViewModel(),
                                    connectRequestViewModel = koinViewModel(),
                                    identityOnly = route.identityOnly,
                                    onBack = { navController.popBackStack() },
                                    onOpenConversation = { conversationId ->
                                        navController.selectConversationOnChatList(conversationId)
                                        openChats()
                                    },
                                )
                            }
                        }

                        paneDestination<Route.ContactBookDetail>(onDismiss = { navController.popBackStack() }) {
                            if (isAuthenticated) {
                                ContactDetailScreen(
                                    viewModel = koinViewModel(),
                                    connectRequestViewModel = koinViewModel(),
                                    onBack = { navController.popBackStack() },
                                    onDeleted = {
                                        // The deleted contact may have been the search's only
                                        // match — clear the query so the contact book shows the
                                        // full list instead of a stale empty "no results" (#876).
                                        contactBookViewModel.onAction(
                                            ContactBookUiAction.SearchChanged("")
                                        )
                                        navController.popBackStack()
                                    },
                                    onOpenConversation = { conversationId ->
                                        navController.selectConversationOnChatList(conversationId)
                                        openChats()
                                    },
                                    onSeeAllMedia = { conversationId ->
                                        navController.navigate(Route.ConversationMedia(conversationId))
                                    },
                                    onOpenContact = { uniqueId, odinId ->
                                        navController.navigate(
                                            Route.ContactBookDetail(uniqueId, odinId)
                                        ) {
                                            if (isDesktopOrWeb()) {
                                                popUpTo<Route.ContactBookDetail> {
                                                    inclusive = true
                                                }
                                            }
                                        }
                                    },
                                )
                            }
                        }

                        tab<Route.ChatList>(tabRoot) { backStackEntry ->
                            if (isAuthenticated) {
                                val conversationListViewModel: ConversationListViewModel =
                                    koinViewModel()
                                val pendingConversationId by backStackEntry.savedStateHandle.getStateFlow<String?>(
                                    "pendingConversationId", null
                                ).collectAsStateWithLifecycle()
                                val pendingScrollToBottom by backStackEntry.savedStateHandle.getStateFlow(
                                    "pendingScrollToBottom", false
                                ).collectAsStateWithLifecycle()
                                val pendingScrollToMessageId by backStackEntry.savedStateHandle.getStateFlow<String?>(
                                    "pendingScrollToMessageId", null
                                ).collectAsStateWithLifecycle()
                                val pendingFromShareIntent by backStackEntry.savedStateHandle.getStateFlow(
                                    "pendingFromShareIntent", false
                                ).collectAsStateWithLifecycle()
                                LaunchedEffect(pendingConversationId) {
                                    pendingConversationId?.let { idStr ->
                                        Uuid.parseOrNull(idStr)?.let {
                                            Logger.i(tag = "AppNavHost") {
                                                "ChatList observed pendingConversationId=$idStr, calling selectConversation"
                                            }
                                            conversationListViewModel.selectConversation(
                                                it,
                                                messageId = pendingScrollToMessageId?.let { m -> Uuid.parseOrNull(m) },
                                                scrollToBottom = pendingScrollToBottom,
                                                // Only a share-intent navigation may auto-send the
                                                // pending share descriptor (processPendingSharedContent).
                                                trigger = if (pendingFromShareIntent) {
                                                    ConversationLoadTrigger.ShareIntent
                                                } else {
                                                    ConversationLoadTrigger.Navigation
                                                },
                                            )
                                            backStackEntry.savedStateHandle["pendingConversationId"] =
                                                null
                                            backStackEntry.savedStateHandle["pendingScrollToBottom"] =
                                                false
                                            backStackEntry.savedStateHandle["pendingScrollToMessageId"] =
                                                null
                                            backStackEntry.savedStateHandle["pendingFromShareIntent"] =
                                                false
                                        }
                                    }
                                }
                                val showArchivedRequested by backStackEntry.savedStateHandle
                                    .getStateFlow(SHOW_ARCHIVED_KEY, false)
                                    .collectAsStateWithLifecycle()
                                LaunchedEffect(showArchivedRequested) {
                                    if (showArchivedRequested) {
                                        conversationListViewModel.onAction(
                                            ConversationListUiAction.ShowArchivedMessagesClicked
                                        )
                                        backStackEntry.savedStateHandle[SHOW_ARCHIVED_KEY] = false
                                    }
                                }
                                var pendingContactCard by rememberSaveable(
                                    stateSaver = ContactCardDescriptorSaver,
                                ) { mutableStateOf<ContactCardDescriptor?>(null) }
                                var pendingContactCardSaved by rememberSaveable {
                                    mutableStateOf(false)
                                }
                                var savedContact by remember {
                                    mutableStateOf<Pair<String, Uuid?>?>(null)
                                }
                                savedContact?.let { (savedName, savedId) ->
                                    val message = stringResource(
                                        MR.string.chat_contact_card_saved_body,
                                        savedName,
                                    )
                                    val open = stringResource(MR.string.chat_contact_card_saved_open)
                                    LaunchedEffect(savedContact) {
                                        val result = snackbarHostState.showSnackbar(
                                            message = message,
                                            actionLabel = if (savedId != null) open else null,
                                            duration = SnackbarDuration.Short,
                                        )
                                        if (result == SnackbarResult.ActionPerformed && savedId != null) {
                                            navController.navigate(
                                                Route.ContactBookDetail(savedId.toString(), null)
                                            )
                                        }
                                        savedContact = null
                                    }
                                }
                                ContactCardSaveHost(
                                    descriptor = pendingContactCard,
                                    alreadySaved = pendingContactCardSaved,
                                    onDismiss = { pendingContactCard = null },
                                    onOpenContact = { uniqueId, odinId ->
                                        navController.navigate(
                                            Route.ContactBookDetail(uniqueId.toString(), odinId)
                                        )
                                    },
                                    onSaved = { name, uniqueId -> savedContact = name to uniqueId },
                                )
                                val connectRequestViewModel: ConnectRequestViewModel = koinViewModel()
                                ConversationListScreen(
                                    viewModel = conversationListViewModel,
                                    archivedConversationsViewModel = koinViewModel(),
                                    extendPermissionViewModel = koinViewModel(),
                                    onOpenConnectRequest = {
                                        connectRequestViewModel.onAction(
                                            ConnectRequestAction.OpenDialogWithRecipient(it)
                                        )
                                    },
                                    connectRequestSheet = { snackbar ->
                                        ConnectRequestBottomSheet(connectRequestViewModel, snackbar)
                                    },
                                    onNavigateBack = { navController.popBackStack() },
                                    onNavigateToSettingsScreen = {
                                        navController.navigate(Route.Settings)
                                    },
                                    onNavigateToNewConversation = {
                                        navController.navigate(Route.CreateConversation)
                                    },
                                    onNavigateToLiveLocationMap = {
                                        navController.navigate(Route.LocationLive)
                                    },
                                    // Reuse the same entry the Location nav icon uses: onboarding when
                                    // the add-on isn't activated, else the dashboard (which requests
                                    // permission) — covers both "not set up" gate-fail cases.
                                    onNavigateToLocationSetup = openLocation,
                                    onNavigateToShareLocation = { conversationId ->
                                        navController.navigate(Route.LocationShare(conversationId))
                                    },
                                    onNavigateToShareContact = { conversationId ->
                                        navController.navigate(Route.ShareContact(conversationId))
                                    },
                                    onNavigateToContactInfo = {
                                        // 1:1 contact info is the full contact-detail screen
                                        // (keyed by the contact uniqueId = md5(odinId)).
                                        navController.navigate(
                                            Route.ContactBookDetail(
                                                uniqueId = Md5.toGuidId(it.lowercase()).toString(),
                                                odinId = it,
                                            )
                                        )
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
                                    onComposerVisibilityChanged = {
                                        @Suppress("AssignedValueIsNeverRead")
                                        isChatComposerOpen = it
                                    },
                                    onSaveContactCard = { card, alreadySaved ->
                                        pendingContactCard = card
                                        pendingContactCardSaved = alreadySaved
                                    },
                                    newConversationPane = { onDismiss, onConversationOpened ->
                                        NewConversationPaneHost(
                                            onDismiss = onDismiss,
                                            onShowConversation = onConversationOpened,
                                            onCreateGroup = { ids ->
                                                // Closed before the hand-off: naming the group is
                                                // a destination, so returning from it lands on the
                                                // list, not a half-finished picker behind it.
                                                onDismiss()
                                                navController.navigate(
                                                    Route.CreateConversationGroup(ids)
                                                )
                                            },
                                            onAddContact = {
                                                navController.navigate(
                                                    Route.AddContact(identityOnly = true)
                                                )
                                            },
                                        )
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
                                        navController.selectConversationOnChatList(
                                            conversationId
                                        )
                                        navController.popBackStack(
                                            Route.CreateConversation, inclusive = true
                                        )
                                    },
                                    onShowCreateGroup = {
                                        navController.navigate(Route.CreateConversationSelectMembers)
                                    },
                                    onAddContact = {
                                        // From a chat flow: a contact is only useful with a
                                        // Homebase ID, so hide manual entry.
                                        navController.navigate(Route.AddContact(identityOnly = true))
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

                        paneDestination<Route.CreateConversationGroup>(onDismiss = { navController.popBackStack() }) {
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

                        paneDestination<Route.ConversationSettings>(onDismiss = { navController.popBackStack() }) {
                            if (isAuthenticated) {
                                ConversationSettingsScreen(
                                    viewModel = koinViewModel(),
                                    onNavigateBack = { navController.popBackStack() },
                                    onSeeAllMedia = { conversationId ->
                                        navController.navigate(Route.ConversationMedia(conversationId))
                                    },
                                    onOpenConversation = { conversationId ->
                                        navController.selectConversationOnChatList(conversationId)
                                        openChats()
                                    },
                                    onNavigateToLiveLocationMap = {
                                        navController.navigate(Route.LocationLive)
                                    },
                                )
                            }
                        }

                        composable<Route.ConversationMedia> { backStackEntry ->
                            if (isAuthenticated) {
                                val route = backStackEntry.toRoute<Route.ConversationMedia>()
                                ConversationMediaScreen(
                                    viewModel = koinViewModel(),
                                    onNavigateBack = { navController.popBackStack() },
                                    onNavigateToMessage = { messageId ->
                                        Uuid.parseOrNull(route.conversationId)?.let { convId ->
                                            navController.selectConversationOnChatList(
                                                convId, messageId = messageId
                                            )
                                            navController.popBackStack(
                                                Route.ChatList, inclusive = false
                                            )
                                        }
                                    },
                                )
                            }
                        }

                        paneDestination<Route.GroupSettings>(onDismiss = { navController.popBackStack() }) {
                            if (isAuthenticated) {
                                GroupSettingsScreen(
                                    viewModel = koinViewModel(),
                                    onNavigateBack = { navController.popBackStack() },
                                    onShowContactInfo = {
                                        // 1:1 contact info is the full contact-detail screen
                                        // (keyed by the contact uniqueId = md5(odinId)).
                                        navController.navigate(
                                            Route.ContactBookDetail(
                                                uniqueId = Md5.toGuidId(it.lowercase()).toString(),
                                                odinId = it,
                                            )
                                        )
                                    },
                                    onAddMembers = {
                                        navController.navigate(Route.GroupAddMembers(it))
                                    },
                                    onEditGroup = {
                                        navController.navigate(Route.GroupEdit(it))
                                    },
                                    // Same destination the 1:1 settings screen uses —
                                    // ConversationMedia is keyed by conversationId only,
                                    // so it needs no group-specific handling (#1157).
                                    onSeeAllMedia = { conversationId ->
                                        navController.navigate(Route.ConversationMedia(conversationId))
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

                        paneDestination<Route.Settings>(
                            onDismiss = { navController.popBackStack() },
                            paneContent = {
                                if (isAuthenticated) {
                                    SettingsPaneHost(
                                        onDismiss = { navController.popBackStack() },
                                        actions = SettingsPaneActions(
                                            onOpenWebDrop = openWebDrop,
                                            onLocation = openLocation,
                                            onOpenMoments = openMoments,
                                            onOpenVault = openVault,
                                            onOpenEmail = openEmail,
                                            onOpenContacts = openContactBook,
                                            onNavigateToCropper = { requestId ->
                                                navController.navigate(
                                                    Route.Crop(
                                                        requestId.toString(),
                                                        lockedAspect = "square",
                                                    )
                                                )
                                            },
                                            onNavigateToDeveloperMenu = {
                                                navController.navigate(Route.DeveloperMenu)
                                            },
                                            onNavigateToDefragmenter = {
                                                navController.navigate(Route.Defragmenter)
                                            },
                                        ),
                                        profileCardEnabled = profileCardEnabled,
                                    )
                                }
                            },
                        ) {
                            if (isAuthenticated) {
                                // Pre-warms the card page; its host lives as long as this entry.
                                if (profileCardEnabled) StartCardHostWhenSettled(koinViewModel())
                                SettingsScreen(
                                    viewModel = koinViewModel(),
                                    actions = SettingsActions(
                                        onBack = { navController.popBackStack() },
                                        onNotifications = {
                                            navController.navigate(Route.NotificationSettings)
                                        },
                                        onAppearance = {
                                            navController.navigate(Route.AppearanceSettings)
                                        },
                                        onMedia = {
                                            navController.navigate(Route.MediaSettings)
                                        },
                                        onKeyboard = {
                                            navController.navigate(Route.KeyboardSettings)
                                        },
                                        onStorage = {
                                            navController.navigate(Route.StorageSettings)
                                        },
                                        onHelp = {
                                            navController.navigate(Route.Help)
                                        },
                                        onMomentsSettings = {
                                            navController.navigate(Route.MomentsSettings)
                                        },
                                        onVaultSettings = {
                                            navController.navigate(Route.VaultSettings)
                                        },
                                        onEmailSettings = {
                                            navController.navigate(Route.EmailSettings)
                                        },
                                        onOpenWebDrop = openWebDrop,
                                        onLocation = openLocation,
                                        onContactBookSettings = {
                                            navController.navigate(Route.ContactBookSettings)
                                        },
                                        onProfileEdit = {
                                            navController.navigate(Route.ProfileEdit)
                                        },
                                        onProfileAvatarEdit = {
                                            navController.navigate(Route.ProfileAvatarEdit)
                                        },
                                        onProfileCard = {
                                            navController.navigate(Route.ProfileCard)
                                        }.takeIf { profileCardEnabled },
                                    ),
                                )
                            }
                        }

                        composable<Route.ProfileEdit> { entry ->
                            if (isAuthenticated) {
                                if (profileCardEnabled) {
                                    StartCardHostWhenSettled(
                                        koinViewModel(viewModelStoreOwner = rememberCardHostOwner(navController, entry)),
                                    )
                                }
                                ProfileEditScreen(
                                    viewModel = koinViewModel(),
                                    avatarViewModel = koinViewModel(),
                                    onBack = { navController.popBackStack() },
                                    onNavigateToCropper = { requestId ->
                                        navController.navigate(
                                            Route.Crop(requestId.toString(), lockedAspect = "square")
                                        )
                                    },
                                    onOpenCard = { navController.navigate(Route.ProfileCard) }
                                        .takeIf { profileCardEnabled },
                                )
                            }
                        }

                        composable<Route.ProfileCard> { entry ->
                            if (isAuthenticated) {
                                ProfileCardScreen(
                                    viewModel = koinViewModel(
                                        viewModelStoreOwner = rememberCardHostOwner(navController, entry),
                                    ),
                                    onBack = { navController.popBackStack() },
                                    onEdit = { navController.navigate(Route.ProfileCardEditor) },
                                )
                            }
                        }

                        composable<Route.ProfileCardEditor> { entry ->
                            if (isAuthenticated) {
                                ProfileCardEditorScreen(
                                    viewModel = koinViewModel(
                                        viewModelStoreOwner = rememberCardHostOwner(navController, entry),
                                    ),
                                    onBack = { navController.popBackStack() },
                                    onEditProfile = { navController.navigate(Route.ProfileEdit) },
                                )
                            }
                        }

                        composable<Route.ProfileAvatarEdit> {
                            if (isAuthenticated) {
                                ProfileAvatarEditScreen(
                                    viewModel = koinViewModel(),
                                    onBack = { navController.popBackStack() },
                                    onNavigateToCropper = { requestId ->
                                        navController.navigate(
                                            Route.Crop(requestId.toString(), lockedAspect = "square")
                                        )
                                    },
                                )
                            }
                        }

                        tab<Route.Moments>(tabRoot) {
                            val momentsActivated by momentsViewModel.isActivated.collectAsStateWithLifecycle()
                            if (isAuthenticated && !momentsActivated) {
                                MomentsOnboardingScreen(
                                    viewModel = momentsViewModel,
                                    onNavigateBack = { navController.popBackStack() },
                                )
                            } else if (isAuthenticated) {
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

                        tab<Route.Location>(tabRoot) {
                            val locationActivated by locationViewModel.isActivated.collectAsStateWithLifecycle()
                            if (isAuthenticated && !locationActivated) {
                                LocationOnboardingScreen(
                                    viewModel = locationViewModel,
                                    onNavigateBack = { navController.popBackStack() },
                                )
                            } else if (isAuthenticated) {
                                LocationScreen(
                                    viewModel = locationViewModel,
                                    onOpenEmergency = { navController.navigate(Route.LocationEmergency) },
                                    onOpenHistoryOverview = {
                                        navController.navigate(Route.LocationHistoryOverview)
                                    },
                                    onOpenLiveSharing = { navController.navigate(Route.LocationLiveSharing) },
                                    onOpenSettings = { navController.navigate(Route.LocationSettings) },
                                )
                            }
                        }

                        // The four detail screens share the home's LocationViewModel instance: a
                        // koinViewModel() here would build a second VM with its own verify loop.
                        composable<Route.LocationEmergency> {
                            if (isAuthenticated) {
                                LocationEmergencyScreen(
                                    viewModel = locationViewModel,
                                    onNavigateBack = { navController.popBackStack() },
                                    onManageEmergencyAccess = {
                                        navController.navigate(Route.LocationEmergencyContactAdd)
                                    },
                                )
                            }
                        }

                        composable<Route.LocationHistoryOverview> {
                            if (isAuthenticated) {
                                LocationHistoryOverviewScreen(
                                    viewModel = locationViewModel,
                                    onNavigateBack = { navController.popBackStack() },
                                    onOpenHistory = { navController.navigate(Route.LocationHistory) },
                                    onOpenDevice = { deviceId ->
                                        navController.navigate(
                                            Route.LocationFindDevice(deviceId.toString())
                                        )
                                    },
                                    onOpenSettings = { navController.navigate(Route.LocationSettings) },
                                )
                            }
                        }

                        composable<Route.LocationLiveSharing> {
                            if (isAuthenticated) {
                                LocationLiveSharingScreen(
                                    viewModel = locationViewModel,
                                    onNavigateBack = { navController.popBackStack() },
                                    onOpenLiveMap = { navController.navigate(Route.LocationLive) },
                                )
                            }
                        }

                        composable<Route.LocationSettings> {
                            if (isAuthenticated) {
                                LocationSettingsScreen(
                                    viewModel = locationViewModel,
                                    onNavigateBack = { navController.popBackStack() },
                                )
                            }
                        }

                        composable<Route.LocationEmergencyContactAdd> {
                            if (isAuthenticated) {
                                EmergencyContactPickerScreen(
                                    viewModel = koinViewModel(),
                                    onNavigateBack = { navController.popBackStack() },
                                )
                            }
                        }

                        composable<Route.LocationHistory> {
                            if (isAuthenticated) {
                                LocationHistoryScreen(
                                    viewModel = koinViewModel(),
                                    onNavigateBack = { navController.popBackStack() },
                                    // Empty-day "turn on location tracking" link → the history
                                    // overview, where the tracking toggle lives.
                                    onOpenTrackingToggle = {
                                        navController.navigate(Route.LocationHistoryOverview) {
                                            launchSingleTop = true
                                            popUpTo(Route.LocationHistoryOverview) { inclusive = false }
                                        }
                                    },
                                )
                            }
                        }

                        composable<Route.LocationPeerHistory> { backStackEntry ->
                            if (isAuthenticated) {
                                val route = backStackEntry.toRoute<Route.LocationPeerHistory>()
                                LocationHistoryScreen(
                                    // key: a fresh VM per peer (initial day + traces come from
                                    // that peer's retrieved data, resolved at construction).
                                    viewModel = koinViewModel(
                                        key = route.peerDomain,
                                        parameters = {
                                            org.koin.core.parameter.parametersOf(route.peerDomain)
                                        },
                                    ),
                                    onNavigateBack = { navController.popBackStack() },
                                    subjectName = route.peerName,
                                    allowDelete = false,
                                )
                            }
                        }

                        composable<Route.LocationLive> {
                            if (isAuthenticated) {
                                LiveLocationScreen(
                                    viewModel = koinViewModel(),
                                    onNavigateBack = { navController.popBackStack() },
                                    // Maps-off CTA → location/maps setup (Route.Location when
                                    // activated, else onboarding), reusing the shared nav lambda.
                                    onOpenSetup = openLocation,
                                )
                            }
                        }

                        composable<Route.LocationShare> { backStackEntry ->
                            if (isAuthenticated) {
                                val route = backStackEntry.toRoute<Route.LocationShare>()
                                ShareLocationScreen(
                                    viewModel = koinViewModel(
                                        key = route.conversationId,
                                        parameters = {
                                            org.koin.core.parameter.parametersOf(
                                                Uuid.parse(route.conversationId)
                                            )
                                        },
                                    ),
                                    onNavigateBack = { navController.popBackStack() },
                                    // Maps-off / enable-location CTA → location setup (dashboard or
                                    // onboarding), reusing the shared nav lambda.
                                    onOpenSetup = openLocation,
                                )
                            }
                        }

                        composable<Route.ShareContact> { backStackEntry ->
                            if (isAuthenticated) {
                                val route = backStackEntry.toRoute<Route.ShareContact>()
                                ShareContactPickerScreen(
                                    viewModel = koinViewModel(
                                        key = route.conversationId,
                                        parameters = {
                                            org.koin.core.parameter.parametersOf(
                                                Uuid.parse(route.conversationId)
                                            )
                                        },
                                    ),
                                    onNavigateBack = { navController.popBackStack() },
                                )
                            }
                        }

                        composable<Route.LocationFindDevice> { backStackEntry ->
                            if (isAuthenticated) {
                                val route = backStackEntry.toRoute<Route.LocationFindDevice>()
                                FindDeviceScreen(
                                    viewModel = koinViewModel(
                                        key = route.deviceId ?: "picker",
                                        parameters = {
                                            org.koin.core.parameter.parametersOf(
                                                route.deviceId?.let { Uuid.parse(it) }
                                            )
                                        },
                                    ),
                                    onNavigateBack = { navController.popBackStack() },
                                    onOpenDevice = { deviceId ->
                                        navController.navigate(
                                            Route.LocationFindDevice(deviceId.toString())
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

                        tab<Route.Vault>(tabRoot) { entry ->
                            if (isAuthenticated) {
                                val scrollVaultToTop by entry.savedStateHandle
                                    .getStateFlow(SCROLL_TO_TOP_KEY, false)
                                    .collectAsStateWithLifecycle()
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
                                            onNavigateToChats = openChats,
                                            onNavigateToNoteEditor = { sectionId, entryId ->
                                                navController.navigate(Route.VaultNoteEditor(sectionId, entryId))
                                            },
                                            onNavigateToWebDrop = openWebDrop,
                                            onNavigateToCropper = { requestId ->
                                                navController.navigate(Route.Crop(requestId.toString()))
                                            },
                                            onNavigateToDrawer = { requestId ->
                                                navController.navigate(Route.Draw(requestId.toString()))
                                            },
                                            scrollToTop = scrollVaultToTop,
                                            onScrollToTopHandled = {
                                                entry.savedStateHandle[SCROLL_TO_TOP_KEY] = false
                                            },
                                        )
                                    }
                                }
                            }
                        }

                        composable<Route.WebDrop> {
                            if (isAuthenticated) {
                                val webDropViewModel: WebDropViewModel = koinViewModel()
                                val webDropUiState by webDropViewModel.uiState.collectAsStateWithLifecycle()
                                when (webDropUiState.driveActivated) {
                                    null -> {
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            CircularProgressIndicator()
                                        }
                                    }
                                    false -> {
                                        WebDropOnboardingScreen(viewModel = webDropViewModel)
                                        LaunchedEffect(Unit) {
                                            webDropViewModel.events.collect { event ->
                                                if (event is WebDropUiEvent.CloseOnboarding) {
                                                    navController.popBackStack()
                                                }
                                            }
                                        }
                                    }
                                    true -> {
                                        WebDropScreen(
                                            viewModel = webDropViewModel,
                                            onNavigateBack = { navController.popBackStack() },
                                        )
                                    }
                                }
                            }
                        }

                        tab<Route.Email>(tabRoot) {
                            if (isAuthenticated) {
                                EmailScreen(
                                    viewModel = emailViewModel,
                                    setupViewModel = koinViewModel(),
                                    onNavigateBack = { navController.popBackStack() },
                                    onNavigateToSecrets = { navController.navigate(Route.EmailSecrets) },
                                    onNavigateToThunderbirdSetup = { navController.navigate(Route.EmailThunderbirdSetup) },
                                )
                            }
                        }

                        composable<Route.EmailThunderbirdSetup> {
                            if (isAuthenticated) {
                                EmailThunderbirdSetupScreen(
                                    viewModel = koinViewModel(),
                                    onBackClick = { navController.popBackStack() },
                                )
                            }
                        }

                        composable<Route.EmailSecrets> {
                            if (isAuthenticated) {
                                EmailSecretsScreen(
                                    viewModel = koinViewModel(),
                                    onBackClick = { navController.popBackStack() },
                                )
                            }
                        }

                        composable<Route.EmailSettings> {
                            if (isAuthenticated) {
                                EmailSettingsScreen(
                                    viewModel = koinViewModel(),
                                    onBackClick = { navController.popBackStack() },
                                    onOpenEmail = openEmail,
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

                        composable<Route.VaultNoteEditor> { backStackEntry ->
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
                                    onBackClick = { navController.popBackStack() },
                                    onNavigateToScheduledPushTest = {
                                        navController.navigate(Route.DevScheduledPushTest)
                                    },
                                )
                            }
                        }

                        composable<Route.DevScheduledPushTest> {
                            if (isAuthenticated) {
                                DeveloperScheduledPushTestScreen(
                                    viewModel = koinViewModel(),
                                    onBackClick = { navController.popBackStack() })
                            }
                        }

                        composable<Route.MediaSettings> {
                            if (isAuthenticated) {
                                MediaSettingsScreen(
                                    viewModel = koinViewModel(),
                                    onBackClick = { navController.popBackStack() })
                            }
                        }

                        composable<Route.KeyboardSettings> {
                            if (isAuthenticated) {
                                KeyboardSettingsScreen(
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


// Contacts are keyed by md5(odinId). An author who isn't in the contact book still lands on a usable screen —
// ContactDetailViewModel.syntheticEntry builds an entry from the route odinId.
private fun NavHostController.navigateToIdentity(odinId: String) {
    navigate(
        Route.ContactBookDetail(
            uniqueId = Md5.toGuidId(odinId.lowercase()).toString(),
            odinId = odinId,
        )
    )
}

// The card host lives with the Settings entry (else ProfileEdit's), so it is warm before the card opens and survives it.
@Composable
private fun rememberCardHostOwner(navController: NavHostController, entry: NavBackStackEntry): ViewModelStoreOwner =
    remember(entry) {
        val backStack = navController.currentBackStack.value
        backStack.lastOrNull { it.destination.hasRoute(Route.Settings::class) }
            ?: backStack.lastOrNull { it.destination.hasRoute(Route.ProfileEdit::class) }
            ?: backStack.lastOrNull { it.destination.hasRoute(Route.ProfileCard::class) }
            ?: entry
    }

private fun NavHostController.selectConversationOnChatList(
    conversationId: Uuid, scrollToBottom: Boolean = false, messageId: Uuid? = null,
    fromShareIntent: Boolean = false,
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
    entry.savedStateHandle["pendingScrollToMessageId"] = messageId?.toString()
    entry.savedStateHandle["pendingFromShareIntent"] = fromShareIntent
    return true
}

private const val OWNS_WINDOW_KEY = "ownsWindow"

// A screen whose own full-screen state (open conversation, media viewer) needs the whole window,
// read from the entry itself so it cannot outlive the screen that raised it.
@Composable
private fun entryOwnsWindow(
    entry: NavBackStackEntry,
    isExpanded: Boolean,
    isVaultOverlayOpen: Boolean,
): Boolean {
    val destination = entry.destination
    return when {
        destination.hasRoute(Route.ChatList::class) -> {
            val chat: ConversationListViewModel = koinViewModel(viewModelStoreOwner = entry)
            val ui by chat.uiState.collectAsStateWithLifecycle()
            val messages by chat.messagesUiState.collectAsStateWithLifecycle()
            val owns by remember(chat, isExpanded) {
                derivedStateOf { chatOwnsWindow(ui, messages, isExpanded) }
            }
            owns
        }

        destination.hasRoute(Route.Vault::class) -> isVaultOverlayOpen
        else -> entry.savedStateHandle.getStateFlow(OWNS_WINDOW_KEY, false)
            .collectAsStateWithLifecycle().value
    }
}

private inline fun <reified T : Any> NavGraphBuilder.tab(
    noinline root: @Composable (NavBackStackEntry, @Composable () -> Unit) -> Unit,
    noinline content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) {
    composable<T> { entry ->
        val scope = this
        root(entry) { scope.content(entry) }
    }
}

@Composable
private fun UpgradeRunningStrip(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
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

private fun NavDestination?.isCardRoute(): Boolean =
    this?.hasRoute(Route.ProfileCard::class) == true || this?.hasRoute(Route.ProfileCardEditor::class) == true

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
    data object Email : TopLevelRoute(Route.Email, MR.string.email_label, Icons.Outlined.MailOutline)
    data object Location : TopLevelRoute(Route.Location, MR.string.location_label, Icons.Outlined.LocationOn)
    data object ContactBook : TopLevelRoute(Route.ContactBook, MR.string.contactbook_label, Icons.Outlined.People)
}

/**
 * Bottom-nav / rail icon for a top-level destination. Draws a small dot badge
 * over the Moments icon when there are unseen moments ([showMomentsBadge]) —
 * a "there's something new" indicator, intentionally count-less to keep the
 * chip small (swap `Badge()` for `Badge { Text(stringResource(...)) }` with a
 * `%1$d` resource if a number is wanted later).
 */
@Composable
private fun RailItem(
    topLevelRoute: TopLevelRoute,
    selected: Boolean,
    showMomentsBadge: Boolean,
    showLocationBadge: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier.size(RailIndicatorSize).clip(NavigationIndicatorShape).background(
            if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
        ).selectable(selected = selected, role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        TopLevelNavIcon(
            topLevelRoute = topLevelRoute,
            showMomentsBadge = showMomentsBadge,
            showLocationBadge = showLocationBadge,
            size = RailIconSize,
            tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// Role.Button, not the Role.Tab the five destination items use: neither target is a
// destination the rail can be sitting on, so neither can ever read back as selected.
@Composable
private fun RailActionItem(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier.size(RailIndicatorSize).clip(NavigationIndicatorShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(RailIconSize),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TopLevelNavIcon(
    topLevelRoute: TopLevelRoute,
    showMomentsBadge: Boolean,
    showEmailBadge: Boolean = false,
    showLocationBadge: Boolean = false,
    size: Dp = 24.dp,
    tint: Color = LocalContentColor.current,
) {
    val icon: @Composable () -> Unit = {
        Icon(
            topLevelRoute.icon,
            contentDescription = stringResource(topLevelRoute.labelRes),
            modifier = Modifier.size(size),
            tint = tint,
        )
    }
    val badged = (topLevelRoute is TopLevelRoute.Moments && showMomentsBadge) ||
        // Count-less like Moments: the dot says "there is mail", and the number itself lives on
        // the Email setup screen where there is room to say what it means.
        (topLevelRoute is TopLevelRoute.Email && showEmailBadge) ||
        // A person I can locate has gone quiet for over 2 days (EmergencyContactService.hasStale).
        (topLevelRoute is TopLevelRoute.Location && showLocationBadge)

    if (badged) {
        val badgeDescription = if (topLevelRoute is TopLevelRoute.Location) {
            stringResource(MR.string.location_attention_cd)
        } else null
        BadgedBox(
            badge = {
                Badge(
                    modifier = if (badgeDescription != null) {
                        Modifier.semantics { contentDescription = badgeDescription }
                    } else Modifier,
                )
            },
        ) { icon() }
    } else {
        icon()
    }
}
