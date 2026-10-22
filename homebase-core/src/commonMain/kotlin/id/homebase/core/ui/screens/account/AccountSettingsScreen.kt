package id.homebase.core.ui.screens.account

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.client.storage.HostingProvider
import id.homebase.core.ui.screens.settings.SettingsUiAction
import id.homebase.core.ui.screens.settings.SettingsUiDialog
import id.homebase.core.ui.screens.settings.SettingsUiEvent
import id.homebase.core.ui.screens.settings.SettingsViewModel
import id.homebase.core.ui.screens.storage.AccountStorageCard
import id.homebase.core.ui.screens.storage.ErrorCard
import id.homebase.core.util.getUriHandler
import id.homebase.core.widget.DialogButtons
import id.homebase.core.widget.DialogCard
import id.homebase.core.widget.DialogText
import id.homebase.core.widget.DialogTitle
import id.homebase.core.widget.SettingsRow
import id.homebase.core.widget.SettingsRowAction
import id.homebase.core.widget.SettingsSectionHeader
import id.homebase.core.widget.SettingsTopBar
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.plan_free_up_body
import id.homebase.resources.plan_free_up_button
import id.homebase.resources.plan_free_up_title
import id.homebase.resources.ok
import id.homebase.resources.plan_more_body
import id.homebase.resources.plan_more_body_provider
import id.homebase.resources.plan_more_button
import id.homebase.resources.plan_more_title
import id.homebase.resources.plan_upgrade_continue
import id.homebase.resources.plan_upgrade_dialog_body
import id.homebase.resources.plan_upgrade_dialog_no_provider
import id.homebase.resources.plan_upgrade_dialog_title
import id.homebase.resources.plan_title
import id.homebase.resources.plan_unavailable
import id.homebase.resources.settings_account
import id.homebase.resources.settings_danger_zone
import id.homebase.resources.settings_delete_account
import id.homebase.resources.settings_delete_account_desc
import id.homebase.resources.settings_delete_account_dialog_text
import id.homebase.resources.settings_delete_account_dialog_title
import id.homebase.resources.settings_logout
import id.homebase.resources.settings_logout_desc
import id.homebase.resources.settings_logout_in_progress
import id.homebase.resources.settings_open_owner_console
import id.homebase.resources.storage_account_full
import id.homebase.resources.storage_full_upload_failed
import org.jetbrains.compose.resources.stringResource

/**
 * Settings > Account: the plan and storage, what to do when it is full, log out, and delete account. The
 * storage-full banner and the Storage page's Homebase storage card lead here.
 *
 * Log out and delete account are [SettingsViewModel]'s: the hub and this screen share it in the settings pane,
 * and on phones this screen borrows the hub's when it is on the back stack.
 */
@Composable
fun AccountSettingsScreen(
    viewModel: AccountSettingsViewModel,
    settingsViewModel: SettingsViewModel,
    onBackClick: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
    val uriHandler = getUriHandler()

    LaunchedEffect(uiState.uiEvent) {
        when (val event = uiState.uiEvent) {
            null -> {}
            is AccountSettingsUiEvent.OpenUrl -> {
                viewModel.eventConsumed()
                uriHandler.openUrl(event.url)
            }
        }
    }

    // Only the events this screen's actions raise; the hub's own (profile editing) stay for the hub
    LaunchedEffect(settingsState.uiEvent) {
        when (val event = settingsState.uiEvent) {
            is SettingsUiEvent.OpenUrl -> {
                settingsViewModel.eventConsumed()
                uriHandler.openUrl(event.url)
            }

            SettingsUiEvent.LoggedOut -> {
                settingsViewModel.eventConsumed()
                // navigation handled at AppNavHost / auth gate
            }

            else -> {}
        }
    }

    if (settingsState.uiDialog is SettingsUiDialog.DeleteAccount) {
        DeleteAccountDialog(
            onDismiss = settingsViewModel::dialogClosed,
            onOpenOwnerConsole = {
                settingsViewModel.dialogClosed()
                settingsViewModel.onAction(SettingsUiAction.OpenOwnerConsoleClicked)
            },
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AccountSettingsUi(
            uiState = uiState,
            onAction = viewModel::onAction,
            onLogout = { settingsViewModel.onAction(SettingsUiAction.LogoutClicked) },
            onDeleteAccount = { settingsViewModel.onAction(SettingsUiAction.DeleteAccount) },
            onBackClick = onBackClick,
        )

        AnimatedVisibility(
            visible = settingsState.isLoggingOut,
            enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
            exit = fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec()),
        ) {
            LogoutOverlay()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSettingsUi(
    uiState: AccountSettingsUiState,
    onAction: (AccountSettingsUiAction) -> Unit,
    onLogout: () -> Unit,
    onDeleteAccount: () -> Unit,
    onBackClick: () -> Unit,
) {
    if (uiState.showUpgradeDialog) {
        UpgradeDialog(
            hostingProvider = uiState.usage?.hostingProvider,
            onContinue = { onAction(AccountSettingsUiAction.UpgradeContinueClicked) },
            onDismiss = { onAction(AccountSettingsUiAction.UpgradeDialogDismissed) },
        )
    }

    Scaffold(
        topBar = { SettingsTopBar(title = stringResource(MR.string.settings_account), onBack = onBackClick) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .testTag("accountSettingsList"),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SettingsSectionHeader(
                    title = stringResource(MR.string.plan_title),
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
                PlanAndStorage(uiState = uiState, onAction = onAction)
            }

            Spacer(modifier = Modifier.height(16.dp))
            SettingsRow(
                modifier = Modifier.testTag("logoutButton"),
                icon = Icons.AutoMirrored.Outlined.Logout,
                title = stringResource(MR.string.settings_logout),
                supportingText = stringResource(MR.string.settings_logout_desc),
                action = SettingsRowAction.Invoke(onLogout),
            )

            SettingsSectionHeader(
                title = stringResource(MR.string.settings_danger_zone),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
            )
            SettingsRow(
                modifier = Modifier.testTag("deleteAccountButton"),
                icon = Icons.Outlined.Delete,
                title = stringResource(MR.string.settings_delete_account),
                supportingText = stringResource(MR.string.settings_delete_account_desc),
                isDestructive = true,
                action = SettingsRowAction.Invoke(onDeleteAccount),
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// Loads on its own, so log out and delete account are there at once
@Composable
private fun PlanAndStorage(uiState: AccountSettingsUiState, onAction: (AccountSettingsUiAction) -> Unit) {
    if (uiState.isLoading) {
        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val usage = uiState.usage
    if (usage == null) {
        Text(
            text = stringResource(MR.string.plan_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        return
    }

    AccountStorageCard(account = usage, showPublicPage = true)
    if (uiState.isFull) {
        ErrorCard(
            title = stringResource(MR.string.storage_full_upload_failed),
            body = stringResource(MR.string.storage_account_full),
        )
    }

    PlanSection(
        title = stringResource(MR.string.plan_free_up_title),
        body = stringResource(MR.string.plan_free_up_body),
        button = stringResource(MR.string.plan_free_up_button),
        onClick = { onAction(AccountSettingsUiAction.SeeWhatEachAppStoresClicked) },
    )
    val provider = usage.hostingProvider?.takeIf { it.upgradeUrl != null }
    PlanSection(
        title = stringResource(MR.string.plan_more_title),
        body = provider?.let { stringResource(MR.string.plan_more_body_provider, it.displayName) }
            ?: stringResource(MR.string.plan_more_body),
        button = stringResource(MR.string.plan_more_button),
        onClick = { onAction(AccountSettingsUiAction.HowToUpgradeClicked) },
    )
}

@Composable
private fun PlanSection(title: String, body: String, button: String, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        OutlinedButton(onClick = onClick) { Text(button) }
    }
}

/**
 * Before leaving the app for the hosting provider's upgrade page, say where the owner is going. With no hosting
 * provider (or no upgrade page) there is nowhere to send them: say who to ask instead.
 */
@Composable
private fun UpgradeDialog(hostingProvider: HostingProvider?, onContinue: () -> Unit, onDismiss: () -> Unit) {
    val provider = hostingProvider?.takeIf { it.upgradeUrl != null }
    Dialog(onDismissRequest = onDismiss) {
        DialogCard(
            modifier = Modifier.testTag("upgradeDialog"),
            buttons = {
                if (provider != null) {
                    DialogButtons(
                        primaryText = stringResource(MR.string.plan_upgrade_continue),
                        onPrimaryClick = onContinue,
                        secondaryText = stringResource(MR.string.cancel),
                        onSecondaryClick = onDismiss,
                    )
                } else {
                    DialogButtons(primaryText = stringResource(MR.string.ok), onPrimaryClick = onDismiss)
                }
            }) {
            DialogTitle(text = stringResource(MR.string.plan_upgrade_dialog_title))
            DialogText(
                text = provider?.let { stringResource(MR.string.plan_upgrade_dialog_body, it.displayName) }
                    ?: stringResource(MR.string.plan_upgrade_dialog_no_provider)
            )
        }
    }
}

@Composable
private fun DeleteAccountDialog(onDismiss: () -> Unit, onOpenOwnerConsole: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        DialogCard(
            buttons = {
                DialogButtons(
                    primaryText = stringResource(MR.string.cancel),
                    onPrimaryClick = onDismiss,
                    secondaryText = stringResource(MR.string.settings_open_owner_console),
                    onSecondaryClick = onOpenOwnerConsole,
                )
            }) {
            DialogTitle(text = stringResource(MR.string.settings_delete_account_dialog_title))
            DialogText(text = stringResource(MR.string.settings_delete_account_dialog_text))
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
