package id.homebase.core.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import id.homebase.api.client.auth.initials
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.ContactAvatar
import id.homebase.core.ui.assets.Homebase
import id.homebase.core.ui.assets.HomebaseIcons
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.core.util.getUriHandler
import id.homebase.core.widget.DialogButtons
import id.homebase.core.widget.DialogCard
import id.homebase.core.widget.DialogText
import id.homebase.core.widget.DialogTitle
import id.homebase.core.widget.SettingsItemAction
import id.homebase.core.widget.SquircleIcon
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.menu_back
import id.homebase.resources.settings
import id.homebase.resources.settings_appearance
import id.homebase.resources.settings_delete_account
import id.homebase.resources.settings_delete_account_dialog_text
import id.homebase.resources.settings_delete_account_dialog_title
import id.homebase.resources.settings_logout
import id.homebase.resources.settings_notifications
import id.homebase.resources.settings_open_owner_console
import org.jetbrains.compose.resources.stringResource

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBackClick: () -> Unit,
    onNavigateToNotifications: () -> Unit,
    onNavigateToAppearance: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
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

    SettingsUi(
        uiState = uiState,
        onAction = viewModel::onAction,
        onBackClick = onBackClick,
        onNavigateToNotifications = onNavigateToNotifications,
        onNavigateToAppearance = onNavigateToAppearance
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsUi(
    uiState: SettingsUiState,
    onAction: (SettingsUiAction) -> Unit,
    onBackClick: () -> Unit,
    onNavigateToNotifications: () -> Unit,
    onNavigateToAppearance: () -> Unit,
) {
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(MR.string.settings)) }, navigationIcon = {
                IconButton(onClick = onBackClick) {
                    Icon(
                        imageVector = Icons.Default.ChevronLeft,
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
            SettingsItemAction(
                imageVector = Icons.Outlined.Notifications,
                text = stringResource(MR.string.settings_notifications),
                onClick = onNavigateToNotifications
            )
            Spacer(modifier = Modifier.height(8.dp))
            SettingsItemAction(
                imageVector = Icons.Outlined.Brightness6,
                text = stringResource(MR.string.settings_appearance),
                onClick = onNavigateToAppearance
            )
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))
            SettingsItemAction(
                imageVector = Icons.Outlined.Delete,
                text = stringResource(MR.string.settings_delete_account),
                onClick = { onAction(SettingsUiAction.DeleteAccount) }
            )
            SettingsItemAction(
                imageVector = Icons.AutoMirrored.Outlined.Logout,
                text = stringResource(MR.string.settings_logout),
                onClick = { onAction(SettingsUiAction.LogoutClicked) }
            )
            Spacer(modifier = Modifier.height(32.dp))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                SquircleIcon(
                    imageVector = HomebaseIcons.Homebase,
                    contentDescription = "Homebase Logo",
                    modifier = Modifier.size(72.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(uiState.appName)
                Text("Version ${uiState.appVersion}")
            }
        }
    }
}

@Preview
@Composable
fun SettingsUiPreview() {
    HomebaseTheme {
        SettingsUi(
            uiState = SettingsUiState(appVersion = "1.0.0"),
            onAction = {},
            onBackClick = {},
            onNavigateToNotifications = {},
            onNavigateToAppearance = {}
        )
    }
}
