package id.homebase.core.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material.icons.outlined.DynamicFeed
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.client.auth.OwnerSession
import id.homebase.api.client.auth.initials
import id.homebase.common.util.formatBytes
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.ContactAvatar
import id.homebase.core.ui.screens.appearance.getStringResourceForTheme
import id.homebase.core.ui.theme.ExtendedColors
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.core.util.getUriHandler
import id.homebase.core.widget.DialogButtons
import id.homebase.core.widget.DialogCard
import id.homebase.core.widget.DialogText
import id.homebase.core.widget.DialogTitle
import id.homebase.core.widget.SettingsRow
import id.homebase.core.widget.SettingsRowAction
import id.homebase.core.widget.SettingsSectionHeader
import id.homebase.resources.MR
import id.homebase.resources.app_version
import id.homebase.resources.cancel
import id.homebase.resources.cd_profile_avatar_change_photo
import id.homebase.resources.contactbook_settings_section
import id.homebase.resources.location_settings_section
import id.homebase.resources.menu_back
import id.homebase.resources.moments_settings_section
import id.homebase.resources.settings
import id.homebase.resources.settings_appearance
import id.homebase.resources.settings_appearance_theme
import id.homebase.resources.settings_contactbook_desc
import id.homebase.resources.settings_delete_account
import id.homebase.resources.settings_delete_account_desc
import id.homebase.resources.settings_delete_account_dialog_text
import id.homebase.resources.settings_delete_account_dialog_title
import id.homebase.resources.settings_edit_profile
import id.homebase.resources.settings_help
import id.homebase.resources.settings_help_desc
import id.homebase.resources.settings_location_desc
import id.homebase.resources.settings_logout
import id.homebase.resources.settings_logout_desc
import id.homebase.resources.settings_logout_in_progress
import id.homebase.resources.settings_moments_desc
import id.homebase.resources.settings_native_feed
import id.homebase.resources.settings_notifications
import id.homebase.resources.settings_notifications_status_checking
import id.homebase.resources.settings_notifications_status_error
import id.homebase.resources.settings_notifications_status_on
import id.homebase.resources.settings_open_owner_console
import id.homebase.resources.settings_section_account_actions
import id.homebase.resources.settings_section_apps
import id.homebase.resources.settings_section_device
import id.homebase.resources.settings_section_preferences
import id.homebase.resources.settings_security_setup
import id.homebase.resources.settings_security_setup_desc
import id.homebase.resources.settings_storage
import id.homebase.resources.settings_storage_desc
import id.homebase.resources.settings_storage_used
import id.homebase.resources.settings_vault_desc
import id.homebase.resources.vault_settings_section
import org.jetbrains.compose.resources.stringResource

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    actions: SettingsActions,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val uriHandler = getUriHandler()

    LaunchedEffect(uiState.uiEvent) {
        when (val event = uiState.uiEvent) {
            null -> {}
            SettingsUiEvent.LoggedOut -> {
                viewModel.eventConsumed()
                // navigation handled at AppNavHost / auth gate
            }

            is SettingsUiEvent.OpenUrl -> {
                viewModel.eventConsumed()
                uriHandler.openUrl(event.url)
            }

            SettingsUiEvent.NavigateToProfileEdit -> {
                viewModel.eventConsumed()
                actions.onProfileEdit()
            }

            SettingsUiEvent.NavigateToProfileAvatarEdit -> {
                viewModel.eventConsumed()
                actions.onProfileAvatarEdit()
            }
        }
    }

    when (uiState.uiDialog) {
        null -> {}
        is SettingsUiDialog.DeleteAccount -> {
            Dialog(onDismissRequest = { viewModel.dialogClosed() }) {
                DialogCard(
                    buttons = {
                        DialogButtons(
                            primaryText = stringResource(MR.string.cancel),
                            onPrimaryClick = { viewModel.dialogClosed() },
                            secondaryText = stringResource(MR.string.settings_open_owner_console),
                            onSecondaryClick = {
                                viewModel.dialogClosed()
                                viewModel.onAction(SettingsUiAction.OpenOwnerConsoleClicked)
                            },
                        )
                    }) {
                    DialogTitle(
                        text = stringResource(MR.string.settings_delete_account_dialog_title),
                    )
                    DialogText(
                        text = stringResource(MR.string.settings_delete_account_dialog_text),
                    )
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        SettingsUi(
            uiState = uiState,
            onAction = viewModel::onAction,
            actions = actions,
        )

        if (uiState.isLoggingOut) {
            LogoutOverlay()
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun LogoutOverlay() {
    // Swallow the system back gesture so the user can't navigate mid-wipe.
    @Suppress("DEPRECATION")
    BackHandler(enabled = true) { /* no-op */ }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f))
            // Absorb every tap so the underlying Settings row never receives it.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) { awaitPointerEvent() }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 8.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CircularProgressIndicator()
                Text(
                    text = stringResource(MR.string.settings_logout_in_progress),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsUi(
    uiState: SettingsUiState,
    onAction: (SettingsUiAction) -> Unit,
    actions: SettingsActions,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(MR.string.settings),
                        modifier = Modifier.testTag("settingsTitle")
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = actions.onBack,
                        modifier = Modifier.testTag("settingsBackButton"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(MR.string.menu_back)
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
                .testTag("settingsList"),
            contentPadding = innerPadding,
        ) {
            item {
                IdentityHeader(
                    session = uiState.ownerSession,
                    onEditProfile = { onAction(SettingsUiAction.ProfileInfoClicked) },
                    onEditAvatar = { onAction(SettingsUiAction.AvatarClicked) },
                )
            }

            item {
                SettingsRow(
                    modifier = Modifier.testTag("securitySetupButton"),
                    icon = Icons.Outlined.Security,
                    title = stringResource(MR.string.settings_security_setup),
                    supportingText = stringResource(MR.string.settings_security_setup_desc),
                    action = SettingsRowAction.External {
                        onAction(SettingsUiAction.SecuritySetupClicked)
                    },
                )
            }

            item { HubSectionHeader(stringResource(MR.string.settings_section_preferences)) }
            item {
                SettingsRow(
                    modifier = Modifier.testTag("notificationsButton"),
                    icon = Icons.Outlined.Notifications,
                    title = stringResource(MR.string.settings_notifications),
                    supportingText = when (uiState.notificationStatus) {
                        NotificationVerificationStatus.CHECKING ->
                            stringResource(MR.string.settings_notifications_status_checking)

                        NotificationVerificationStatus.OK ->
                            stringResource(MR.string.settings_notifications_status_on)

                        NotificationVerificationStatus.ERROR ->
                            stringResource(MR.string.settings_notifications_status_error)
                    },
                    action = SettingsRowAction.Navigate(actions.onNotifications),
                    status = { NotificationStatusIndicator(uiState.notificationStatus) },
                )
            }
            item {
                SettingsRow(
                    modifier = Modifier.testTag("appearanceButton"),
                    icon = Icons.Outlined.Brightness6,
                    title = stringResource(MR.string.settings_appearance),
                    supportingText = stringResource(
                        MR.string.settings_appearance_theme,
                        uiState.theme.getStringResourceForTheme(),
                    ),
                    action = SettingsRowAction.Navigate(actions.onAppearance),
                )
            }

            item { HubSectionHeader(stringResource(MR.string.settings_section_apps)) }
            item {
                SettingsRow(
                    modifier = Modifier.testTag("nativeFeedToggle"),
                    icon = Icons.Outlined.DynamicFeed,
                    title = stringResource(MR.string.settings_native_feed),
                    action = SettingsRowAction.Toggle(
                        checked = uiState.useNativeFeed,
                        onCheckedChange = { onAction(SettingsUiAction.SetUseNativeFeed(it)) },
                    ),
                )
            }
            item {
                SettingsRow(
                    modifier = Modifier.testTag("momentsSettingsButton"),
                    icon = Icons.Outlined.AutoAwesome,
                    title = stringResource(MR.string.moments_settings_section),
                    supportingText = stringResource(MR.string.settings_moments_desc),
                    action = SettingsRowAction.Navigate(actions.onMomentsSettings),
                )
            }
            item {
                SettingsRow(
                    modifier = Modifier.testTag("vaultSettingsButton"),
                    icon = Icons.Outlined.Lock,
                    title = stringResource(MR.string.vault_settings_section),
                    supportingText = stringResource(MR.string.settings_vault_desc),
                    action = SettingsRowAction.Navigate(actions.onVaultSettings),
                )
            }
            item {
                SettingsRow(
                    modifier = Modifier.testTag("locationSettingsButton"),
                    icon = Icons.Outlined.LocationOn,
                    title = stringResource(MR.string.location_settings_section),
                    supportingText = stringResource(MR.string.settings_location_desc),
                    action = SettingsRowAction.Navigate(actions.onLocation),
                )
            }
            item {
                SettingsRow(
                    modifier = Modifier.testTag("contactBookSettingsButton"),
                    icon = Icons.Outlined.People,
                    title = stringResource(MR.string.contactbook_settings_section),
                    supportingText = stringResource(MR.string.settings_contactbook_desc),
                    action = SettingsRowAction.Navigate(actions.onContactBookSettings),
                )
            }

            item { HubSectionHeader(stringResource(MR.string.settings_section_device)) }
            item {
                SettingsRow(
                    modifier = Modifier.testTag("storageButton"),
                    icon = Icons.Outlined.Storage,
                    title = stringResource(MR.string.settings_storage),
                    supportingText = uiState.storageUsedBytes
                        ?.let { stringResource(MR.string.settings_storage_used, formatBytes(it)) }
                        ?: stringResource(MR.string.settings_storage_desc),
                    action = SettingsRowAction.Navigate(actions.onStorage),
                )
            }
            item {
                SettingsRow(
                    modifier = Modifier.testTag("helpButton"),
                    icon = Icons.AutoMirrored.Outlined.HelpOutline,
                    title = stringResource(MR.string.settings_help),
                    supportingText = stringResource(MR.string.settings_help_desc),
                    action = SettingsRowAction.Navigate(actions.onHelp),
                )
            }
            item {
                Text(
                    text = stringResource(MR.string.app_version, uiState.appVersion),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 56.dp, end = 16.dp, top = 8.dp),
                )
            }

            item { HubSectionHeader(stringResource(MR.string.settings_section_account_actions)) }
            item {
                SettingsRow(
                    modifier = Modifier.testTag("logoutButton"),
                    icon = Icons.AutoMirrored.Outlined.Logout,
                    title = stringResource(MR.string.settings_logout),
                    supportingText = stringResource(MR.string.settings_logout_desc),
                    action = SettingsRowAction.Invoke {
                        onAction(SettingsUiAction.LogoutClicked)
                    },
                )
            }
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            item {
                SettingsRow(
                    modifier = Modifier.testTag("deleteAccountButton"),
                    icon = Icons.Outlined.Delete,
                    title = stringResource(MR.string.settings_delete_account),
                    supportingText = stringResource(MR.string.settings_delete_account_desc),
                    isDestructive = true,
                    action = SettingsRowAction.Invoke {
                        onAction(SettingsUiAction.DeleteAccount)
                    },
                )
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun IdentityHeader(
    session: OwnerSession?,
    onEditProfile: () -> Unit,
    onEditAvatar: () -> Unit,
) {
    val editProfile = stringResource(MR.string.settings_edit_profile)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("profileHeader")
            .clickable(onClickLabel = editProfile, onClick = onEditProfile)
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // IconButton reports a 48.dp minimum touch target, so the box is oversized to let the
        // badge ride the avatar rim instead of covering its face. The visible disc is the icon.
        // 85.5 = 36 (avatar radius) + 36/sqrt(2) (rim at 45 degrees) + 24 (half the button).
        Box(modifier = Modifier.size(85.5.dp)) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .size(72.dp)
                    // ContactAvatar hardcodes its own contentDescription; unsilenced it would
                    // be read before the person's name.
                    .clearAndSetSemantics {},
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                )
                session?.let {
                    ContactAvatar(
                        odinId = it.odinId,
                        profileImageData = null,
                        initials = it.initials(),
                        options = AvatarOptions(size = 72.dp),
                        sharedTransitionScope = null,
                        animatedVisibilityScope = null,
                        cacheBustKey = it.profileImageLastModified,
                    )
                }
            }
            IconButton(
                onClick = onEditAvatar,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .testTag("avatarEditButton"),
            ) {
                Icon(
                    imageVector = Icons.Outlined.PhotoCamera,
                    contentDescription = stringResource(MR.string.cd_profile_avatar_change_photo),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                        .padding(5.dp),
                )
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = session?.displayName ?: "",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = session?.odinId?.domainName ?: "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HubSectionHeader(title: String) {
    SettingsSectionHeader(
        title = title,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
    )
}

// Decorative: the row's supporting text already states the status in words.
@Composable
private fun NotificationStatusIndicator(status: NotificationVerificationStatus) {
    when (status) {
        NotificationVerificationStatus.CHECKING -> CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
        )

        NotificationVerificationStatus.OK -> Icon(
            imageVector = Icons.Outlined.CheckCircle,
            contentDescription = null,
            tint = ExtendedColors.Success,
            modifier = Modifier.size(20.dp),
        )

        NotificationVerificationStatus.ERROR -> Icon(
            imageVector = Icons.Outlined.Error,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Preview
@Composable
fun SettingsUiPreview() {
    HomebaseTheme {
        SettingsUi(
            uiState = SettingsUiState(),
            onAction = {},
            actions = SettingsActions(
                onBack = {},
                onNotifications = {},
                onAppearance = {},
                onStorage = {},
                onHelp = {},
                onMomentsSettings = {},
                onVaultSettings = {},
                onLocation = {},
                onContactBookSettings = {},
                onProfileEdit = {},
                onProfileAvatarEdit = {},
            ),
        )
    }
}
