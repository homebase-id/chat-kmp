package id.homebase.core.ui.screens.settings

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.client.auth.initials
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.ContactAvatar
import id.homebase.core.ui.theme.ExtendedColors
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.core.util.getUriHandler
import id.homebase.core.widget.DialogButtons
import id.homebase.core.widget.DialogCard
import id.homebase.core.widget.DialogText
import id.homebase.core.widget.DialogTitle
import id.homebase.core.widget.SettingsItemAction
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.menu_back
import id.homebase.resources.settings
import id.homebase.resources.settings_appearance
import id.homebase.resources.settings_connections
import id.homebase.resources.settings_delete_account
import id.homebase.resources.settings_delete_account_dialog_text
import id.homebase.resources.settings_delete_account_dialog_title
import id.homebase.resources.settings_help
import id.homebase.resources.settings_logout
import id.homebase.resources.settings_logout_in_progress
import id.homebase.resources.settings_notifications
import id.homebase.resources.settings_notifications_active
import id.homebase.resources.settings_notifications_issue
import id.homebase.resources.settings_open_owner_console
import id.homebase.resources.settings_profile_info
import id.homebase.resources.settings_security_setup
import id.homebase.resources.cd_open_externally
import id.homebase.resources.settings_section_danger_zone
import id.homebase.resources.settings_section_general
import id.homebase.resources.settings_storage
import org.jetbrains.compose.resources.stringResource

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBackClick: () -> Unit,
    onNavigateToConnections: () -> Unit,
    onNavigateToNotifications: () -> Unit,
    onNavigateToAppearance: () -> Unit,
    onNavigateToStorage: () -> Unit,
    onNavigateToHelp: () -> Unit,
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
            onBackClick = onBackClick,
            onNavigateToConnections = onNavigateToConnections,
            onNavigateToNotifications = onNavigateToNotifications,
            onNavigateToAppearance = onNavigateToAppearance,
            onNavigateToStorage = onNavigateToStorage,
            onNavigateToHelp = onNavigateToHelp
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
    onBackClick: () -> Unit,
    onNavigateToConnections: () -> Unit,
    onNavigateToNotifications: () -> Unit,
    onNavigateToAppearance: () -> Unit,
    onNavigateToStorage: () -> Unit,
    onNavigateToHelp: () -> Unit,
) {
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(MR.string.settings), modifier = Modifier.testTag("settingsTitle")) }, navigationIcon = {
                IconButton(onClick = onBackClick) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(MR.string.menu_back)
                    )
                }
            })
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .consumeWindowInsets(innerPadding)
                .padding(innerPadding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Avatar
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                uiState.ownerSession?.let { ownerSession ->
                    ContactAvatar(
                        odinId = ownerSession.odinId,
                        profileImageData = null,
                        initials = ownerSession.initials(),
                        options = AvatarOptions(
                            size = 96.dp,
                        ),
                        sharedTransitionScope = null,
                        animatedVisibilityScope = null
                    )
                    Spacer(modifier = Modifier.width(24.dp))
                    Column {
                        Text(
                            text = ownerSession.displayName ?: "",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = ownerSession.odinId.domainName,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            SettingsSectionHeader(stringResource(MR.string.settings_section_general))
            SettingsItemAction(
                imageVector = Icons.Outlined.People,
                text = stringResource(MR.string.settings_connections),
                onClick = onNavigateToConnections
            )
            Spacer(modifier = Modifier.height(8.dp))
            SettingsItemAction(
                imageVector = Icons.Outlined.Person,
                text = stringResource(MR.string.settings_profile_info),
                onClick = { onAction(SettingsUiAction.ProfileInfoClicked) },
                trailingContent = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                        contentDescription = stringResource(MR.string.cd_open_externally),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            )
            Spacer(modifier = Modifier.height(8.dp))
            SettingsItemAction(
                modifier = Modifier.testTag("notificationsButton"),
                imageVector = Icons.Outlined.Notifications,
                text = stringResource(MR.string.settings_notifications),
                onClick = onNavigateToNotifications,
                trailingContent = {
                    when (uiState.notificationStatus) {
                        NotificationVerificationStatus.CHECKING -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        }
                        NotificationVerificationStatus.OK -> {
                            Icon(
                                imageVector = Icons.Outlined.CheckCircle,
                                contentDescription = stringResource(MR.string.settings_notifications_active),
                                tint = ExtendedColors.Success,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        NotificationVerificationStatus.ERROR -> {
                            Icon(
                                imageVector = Icons.Outlined.Error,
                                contentDescription = stringResource(MR.string.settings_notifications_issue),
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            )
            Spacer(modifier = Modifier.height(8.dp))
            SettingsItemAction(
                modifier = Modifier.testTag("securitySetupButton"),
                imageVector = Icons.Outlined.Security,
                text = stringResource(MR.string.settings_security_setup),
                onClick = { onAction(SettingsUiAction.SecuritySetupClicked) },
                trailingContent = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                        contentDescription = stringResource(MR.string.cd_open_externally),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            )
            Spacer(modifier = Modifier.height(8.dp))
            SettingsItemAction(
                modifier = Modifier.testTag("appearanceButton"),
                imageVector = Icons.Outlined.Brightness6,
                text = stringResource(MR.string.settings_appearance),
                onClick = onNavigateToAppearance
            )
            Spacer(modifier = Modifier.height(8.dp))
            SettingsItemAction(
                modifier = Modifier.testTag("storageButton"),
                imageVector = Icons.Outlined.Storage,
                text = stringResource(MR.string.settings_storage),
                onClick = onNavigateToStorage
            )
            Spacer(modifier = Modifier.height(8.dp))
            SettingsItemAction(
                modifier = Modifier.testTag("helpButton"),
                imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
                text = stringResource(MR.string.settings_help),
                onClick = onNavigateToHelp
            )
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))
            SettingsSectionHeader(stringResource(MR.string.settings_section_danger_zone))
            SettingsItemAction(
                modifier = Modifier.testTag("deleteAccountButton"),
                imageVector = Icons.Outlined.Delete,
                text = stringResource(MR.string.settings_delete_account),
                tint = MaterialTheme.colorScheme.error,
                onClick = { onAction(SettingsUiAction.DeleteAccount) }
            )
            SettingsItemAction(
                modifier = Modifier.testTag("logoutButton"),
                imageVector = Icons.AutoMirrored.Outlined.Logout,
                text = stringResource(MR.string.settings_logout),
                tint = MaterialTheme.colorScheme.error,
                onClick = { onAction(SettingsUiAction.LogoutClicked) }
            )
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
    )
}

@Preview
@Composable
fun SettingsUiPreview() {
    HomebaseTheme {
        SettingsUi(
            uiState = SettingsUiState(),
            onAction = {},
            onBackClick = {},
            onNavigateToConnections = {},
            onNavigateToNotifications = {},
            onNavigateToAppearance = {},
            onNavigateToStorage = {},
            onNavigateToHelp = {}
        )
    }
}
