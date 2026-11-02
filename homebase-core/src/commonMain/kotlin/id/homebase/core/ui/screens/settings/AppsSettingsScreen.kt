package id.homebase.core.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.DynamicFeed
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Redeem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.core.ui.assets.Filesync
import id.homebase.core.ui.assets.HomebaseIcons
import id.homebase.core.widget.SettingsRow
import id.homebase.core.widget.SettingsRowAction
import id.homebase.core.widget.SettingsTopBar
import id.homebase.resources.MR
import id.homebase.resources.contactbook_settings_section
import id.homebase.resources.email_settings_section
import id.homebase.resources.filesync_label
import id.homebase.resources.location_settings_section
import id.homebase.resources.moments_settings_section
import id.homebase.resources.settings_calls_beta
import id.homebase.resources.settings_calls_beta_desc
import id.homebase.resources.settings_contactbook_desc
import id.homebase.resources.settings_email_desc
import id.homebase.resources.settings_filesync_icon_desc
import id.homebase.resources.settings_location_desc
import id.homebase.resources.settings_moments_desc
import id.homebase.resources.settings_native_feed
import id.homebase.resources.settings_section_apps
import id.homebase.resources.settings_vault_desc
import id.homebase.resources.vault_settings_section
import id.homebase.resources.webdrop_home_subtitle
import id.homebase.resources.webdrop_label
import org.jetbrains.compose.resources.stringResource

internal val AppsSettingsRowTags = listOf(
    "nativeFeedToggle",
    "filesyncIconToggle",
    "callsBetaToggle",
    "momentsSettingsButton",
    "vaultSettingsButton",
    "emailSettingsButton",
    "webDropButton",
    "locationSettingsButton",
    "contactBookSettingsButton",
)

@Composable
fun AppsSettingsScreen(
    viewModel: SettingsViewModel,
    actions: AppsSettingsActions,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    AppsSettingsUi(uiState = uiState, onAction = viewModel::onAction, actions = actions)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsSettingsUi(
    uiState: SettingsUiState,
    onAction: (SettingsUiAction) -> Unit,
    actions: AppsSettingsActions,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            SettingsTopBar(
                title = stringResource(MR.string.settings_section_apps),
                navigationIconModifier = Modifier.testTag("appsSettingsBackButton"),
                onBack = actions.onBack,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .testTag("appsSettingsList"),
        ) {
            SettingsRow(
                modifier = Modifier.testTag("nativeFeedToggle"),
                icon = Icons.Outlined.DynamicFeed,
                title = stringResource(MR.string.settings_native_feed),
                action = SettingsRowAction.Toggle(
                    checked = uiState.useNativeFeed,
                    onCheckedChange = { onAction(SettingsUiAction.SetUseNativeFeed(it)) },
                ),
            )
            SettingsRow(
                modifier = Modifier.testTag("filesyncIconToggle"),
                icon = HomebaseIcons.Filesync,
                title = stringResource(MR.string.filesync_label),
                supportingText = stringResource(MR.string.settings_filesync_icon_desc),
                action = SettingsRowAction.Toggle(
                    checked = uiState.filesyncIconVisible,
                    onCheckedChange = { onAction(SettingsUiAction.SetFilesyncIconVisible(it)) },
                ),
            )
            SettingsRow(
                modifier = Modifier.testTag("callsBetaToggle"),
                icon = Icons.Outlined.Call,
                title = stringResource(MR.string.settings_calls_beta),
                supportingText = stringResource(MR.string.settings_calls_beta_desc),
                action = SettingsRowAction.Toggle(
                    checked = uiState.callsEnabled,
                    onCheckedChange = { onAction(SettingsUiAction.SetCallsEnabled(it)) },
                ),
            )
            SettingsRow(
                modifier = Modifier.testTag("momentsSettingsButton"),
                icon = Icons.Outlined.AutoAwesome,
                title = stringResource(MR.string.moments_settings_section),
                supportingText = stringResource(MR.string.settings_moments_desc),
                action = SettingsRowAction.Navigate(actions.onMomentsSettings),
            )
            SettingsRow(
                modifier = Modifier.testTag("vaultSettingsButton"),
                icon = Icons.Outlined.Lock,
                title = stringResource(MR.string.vault_settings_section),
                supportingText = stringResource(MR.string.settings_vault_desc),
                action = SettingsRowAction.Navigate(actions.onVaultSettings),
            )
            SettingsRow(
                modifier = Modifier.testTag("emailSettingsButton"),
                icon = Icons.Outlined.MailOutline,
                title = stringResource(MR.string.email_settings_section),
                supportingText = stringResource(MR.string.settings_email_desc),
                action = SettingsRowAction.Navigate(actions.onEmailSettings),
            )
            SettingsRow(
                modifier = Modifier.testTag("webDropButton"),
                icon = Icons.Outlined.Redeem,
                title = stringResource(MR.string.webdrop_label),
                supportingText = stringResource(MR.string.webdrop_home_subtitle),
                action = SettingsRowAction.Navigate(actions.onOpenWebDrop),
            )
            SettingsRow(
                modifier = Modifier.testTag("locationSettingsButton"),
                icon = Icons.Outlined.LocationOn,
                title = stringResource(MR.string.location_settings_section),
                supportingText = stringResource(MR.string.settings_location_desc),
                action = SettingsRowAction.Navigate(actions.onLocation),
            )
            SettingsRow(
                modifier = Modifier.testTag("contactBookSettingsButton"),
                icon = Icons.Outlined.People,
                title = stringResource(MR.string.contactbook_settings_section),
                supportingText = stringResource(MR.string.settings_contactbook_desc),
                action = SettingsRowAction.Navigate(actions.onContactBookSettings),
            )
        }
    }
}
