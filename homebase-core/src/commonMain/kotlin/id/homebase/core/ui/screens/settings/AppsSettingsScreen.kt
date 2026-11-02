package id.homebase.core.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.core.ui.assets.Filesync
import id.homebase.core.ui.assets.HomebaseIcons
import id.homebase.core.widget.SettingsRow
import id.homebase.core.widget.SettingsRowAction
import id.homebase.core.widget.SettingsSectionHeader
import id.homebase.core.widget.SettingsTopBar
import id.homebase.resources.MR
import id.homebase.resources.contactbook_settings_section
import id.homebase.resources.email_settings_section
import id.homebase.resources.filesync_label
import id.homebase.resources.location_settings_section
import id.homebase.resources.moments_settings_section
import id.homebase.resources.settings_apps_section_features
import id.homebase.resources.settings_apps_section_pages
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
        val rowColor = MaterialTheme.colorScheme.surfaceContainer
        Column(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .testTag("appsSettingsList"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = AppsContentMaxWidth)
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            ) {
                AppsGroup(stringResource(MR.string.settings_apps_section_features)) {
                    SettingsRow(
                        modifier = Modifier.testTag("nativeFeedToggle"),
                        icon = Icons.Outlined.DynamicFeed,
                        title = stringResource(MR.string.settings_native_feed),
                        containerColor = rowColor,
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
                        containerColor = rowColor,
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
                        containerColor = rowColor,
                        action = SettingsRowAction.Toggle(
                            checked = uiState.callsEnabled,
                            onCheckedChange = { onAction(SettingsUiAction.SetCallsEnabled(it)) },
                        ),
                    )
                }
                Spacer(Modifier.height(8.dp))
                AppsGroup(stringResource(MR.string.settings_apps_section_pages)) {
                    SettingsRow(
                        modifier = Modifier.testTag("momentsSettingsButton"),
                        icon = Icons.Outlined.AutoAwesome,
                        title = stringResource(MR.string.moments_settings_section),
                        supportingText = stringResource(MR.string.settings_moments_desc),
                        containerColor = rowColor,
                        action = SettingsRowAction.Navigate(actions.onMomentsSettings),
                    )
                    SettingsRow(
                        modifier = Modifier.testTag("vaultSettingsButton"),
                        icon = Icons.Outlined.Lock,
                        title = stringResource(MR.string.vault_settings_section),
                        supportingText = stringResource(MR.string.settings_vault_desc),
                        containerColor = rowColor,
                        action = SettingsRowAction.Navigate(actions.onVaultSettings),
                    )
                    SettingsRow(
                        modifier = Modifier.testTag("emailSettingsButton"),
                        icon = Icons.Outlined.MailOutline,
                        title = stringResource(MR.string.email_settings_section),
                        supportingText = stringResource(MR.string.settings_email_desc),
                        containerColor = rowColor,
                        action = SettingsRowAction.Navigate(actions.onEmailSettings),
                    )
                    SettingsRow(
                        modifier = Modifier.testTag("webDropButton"),
                        icon = Icons.Outlined.Redeem,
                        title = stringResource(MR.string.webdrop_label),
                        supportingText = stringResource(MR.string.webdrop_home_subtitle),
                        containerColor = rowColor,
                        action = SettingsRowAction.Navigate(actions.onOpenWebDrop),
                    )
                    SettingsRow(
                        modifier = Modifier.testTag("locationSettingsButton"),
                        icon = Icons.Outlined.LocationOn,
                        title = stringResource(MR.string.location_settings_section),
                        supportingText = stringResource(MR.string.settings_location_desc),
                        containerColor = rowColor,
                        action = SettingsRowAction.Navigate(actions.onLocation),
                    )
                    SettingsRow(
                        modifier = Modifier.testTag("contactBookSettingsButton"),
                        icon = Icons.Outlined.People,
                        title = stringResource(MR.string.contactbook_settings_section),
                        supportingText = stringResource(MR.string.settings_contactbook_desc),
                        containerColor = rowColor,
                        action = SettingsRowAction.Navigate(actions.onContactBookSettings),
                    )
                }
            }
        }
    }
}

private val AppsContentMaxWidth = 640.dp

// The heading's top inset centres the first one on the pane sidebar's first row.
@Composable
private fun AppsGroup(title: String, rows: @Composable ColumnScope.() -> Unit) {
    SettingsSectionHeader(
        title = title,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 26.dp, bottom = 8.dp),
    )
    Column(
        modifier = Modifier.clip(MaterialTheme.shapes.large),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        content = rows,
    )
}
