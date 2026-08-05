package id.homebase.core.ui.screens.help

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Policy
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.core.util.getUriHandler
import id.homebase.core.widget.SettingsRow
import id.homebase.core.widget.SettingsRowAction
import id.homebase.core.widget.SettingsSectionHeader
import id.homebase.resources.MR
import id.homebase.resources.about_homebase
import id.homebase.resources.dev_menu_title
import id.homebase.resources.help_contact_us
import id.homebase.resources.help_copyright
import id.homebase.resources.help_debug_log_description
import id.homebase.resources.help_enable_error_collection
import id.homebase.resources.help_ffmpeg_version
import id.homebase.resources.help_submit_debug_log
import id.homebase.resources.help_support_center
import id.homebase.resources.help_terms_privacy
import id.homebase.resources.help_version
import id.homebase.resources.logging
import id.homebase.resources.menu_back
import id.homebase.resources.settings_help
import id.homebase.resources.update_available
import id.homebase.resources.update_check_now
import id.homebase.resources.update_checking
import id.homebase.resources.update_get_update
import id.homebase.resources.update_not_supported
import id.homebase.resources.update_using_latest_version
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable
fun HelpScreen(
    viewModel: HelpViewModel,
    onBackClick: () -> Unit,
    onNavigateToDeveloperMenu: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val uriHandler = getUriHandler()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(uiState.uiEvent) {
        when (val event = uiState.uiEvent) {
            null -> {}
            is HelpUiEvent.OpenUrl -> {
                viewModel.eventConsumed()
                uriHandler.openUrl(event.url)
            }

            is HelpUiEvent.ShareFile -> {
                viewModel.eventConsumed()
                uriHandler.shareFile(
                    file = event.filePath,
                    onError = { error ->
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                message = "Failed to share log: ${error.message}"
                            )
                        }
                    },
                )
            }

            is HelpUiEvent.ShowError -> {
                viewModel.eventConsumed()
                scope.launch { snackbarHostState.showSnackbar(message = event.message) }
            }

            is HelpUiEvent.OpenDeveloperMenu -> {
                viewModel.eventConsumed()
                onNavigateToDeveloperMenu()
            }
        }
    }

    HelpUi(
        snackbarHostState = snackbarHostState,
        uiState = uiState,
        onAction = viewModel::onAction,
        onBackClick = onBackClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpUi(
    snackbarHostState: SnackbarHostState,
    uiState: HelpUiState,
    onAction: (HelpUiAction) -> Unit,
    onBackClick: () -> Unit,
) {
    val scrollState = rememberScrollState()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(MR.string.settings_help),
                        modifier = Modifier.testTag("helpTitle"),
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier.testTag("helpBackButton"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(MR.string.menu_back)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .testTag("helpList"),
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            SettingsRow(
                modifier = Modifier.testTag("supportCenterRow"),
                icon = Icons.AutoMirrored.Outlined.HelpOutline,
                title = stringResource(MR.string.help_support_center),
                action = SettingsRowAction.External {
                    onAction(HelpUiAction.SupportCenterClicked)
                },
            )
            SettingsRow(
                modifier = Modifier.testTag("contactUsRow"),
                icon = Icons.Outlined.MailOutline,
                title = stringResource(MR.string.help_contact_us),
                action = SettingsRowAction.External { onAction(HelpUiAction.ContactUsClicked) },
            )

            HelpSectionHeader(stringResource(MR.string.logging))
            SettingsRow(
                modifier = Modifier.testTag("submitDebugLogRow"),
                icon = Icons.Outlined.Share,
                title = stringResource(MR.string.help_submit_debug_log),
                action = SettingsRowAction.Invoke {
                    onAction(HelpUiAction.SubmitDebugLogClicked)
                },
            )
            SettingsRow(
                modifier = Modifier.testTag("errorCollectionRow"),
                icon = Icons.Outlined.BugReport,
                title = stringResource(MR.string.help_enable_error_collection),
                action = SettingsRowAction.Toggle(
                    checked = uiState.errorCollectionEnabled,
                    onCheckedChange = { onAction(HelpUiAction.ToggleErrorCollection) },
                ),
            )
            HelpFootnote(stringResource(MR.string.help_debug_log_description))

            HelpSectionHeader(stringResource(MR.string.about_homebase))
            // Five taps here unlock the developer menu row below.
            SettingsRow(
                modifier = Modifier.testTag("versionRow"),
                icon = Icons.Outlined.Info,
                title = stringResource(MR.string.help_version),
                supportingText = uiState.appVersion,
                action = SettingsRowAction.Invoke { onAction(HelpUiAction.DeveloperClicked) },
            )
            uiState.ffmpegVersion?.let { ffmpegVersion ->
                HelpInfoRow(
                    modifier = Modifier.testTag("ffmpegVersionRow"),
                    icon = Icons.Outlined.Movie,
                    title = stringResource(MR.string.help_ffmpeg_version),
                    supportingText = ffmpegVersion,
                )
            }
            UpdateRow(uiState = uiState, onAction = onAction)
            SettingsRow(
                modifier = Modifier.testTag("termsPrivacyRow"),
                icon = Icons.Outlined.Policy,
                title = stringResource(MR.string.help_terms_privacy),
                action = SettingsRowAction.External { onAction(HelpUiAction.TermsPrivacyClicked) },
            )
            if (uiState.showDeveloperMenu) {
                SettingsRow(
                    modifier = Modifier.testTag("developerMenuRow"),
                    icon = Icons.Outlined.Code,
                    title = stringResource(MR.string.dev_menu_title),
                    action = SettingsRowAction.Navigate { onAction(HelpUiAction.DeveloperMenu) },
                )
            }

            HelpFootnote(stringResource(MR.string.help_copyright))
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun UpdateRow(uiState: HelpUiState, onAction: (HelpUiAction) -> Unit) {
    val status = when {
        !uiState.isUpdateSupported -> stringResource(MR.string.update_not_supported)
        uiState.isUpdateAvailable -> stringResource(MR.string.update_available)
        else -> stringResource(MR.string.update_using_latest_version)
    }

    when {
        uiState.isCheckingForUpdate -> HelpInfoRow(
            modifier = Modifier.testTag("updateCheckingRow"),
            icon = Icons.Outlined.SystemUpdateAlt,
            title = stringResource(MR.string.update_checking),
            trailing = {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            },
        )

        // Not External: only the store-backed platforms leave the app, Desktop updates in place.
        uiState.isUpdateAvailable -> SettingsRow(
            modifier = Modifier.testTag("getUpdateRow"),
            icon = Icons.Outlined.SystemUpdateAlt,
            title = stringResource(MR.string.update_get_update),
            supportingText = status,
            action = SettingsRowAction.Invoke { onAction(HelpUiAction.DownloadUpdateClicked) },
        )

        else -> SettingsRow(
            modifier = Modifier.testTag("checkForUpdateRow"),
            icon = Icons.Outlined.SystemUpdateAlt,
            title = stringResource(MR.string.update_check_now),
            supportingText = status,
            action = SettingsRowAction.Invoke { onAction(HelpUiAction.CheckForUpdatedClicked) },
        )
    }
}

@Composable
private fun HelpSectionHeader(title: String) {
    SettingsSectionHeader(
        title = title,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun HelpFootnote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
    )
}

// Read-only counterpart to SettingsRow: same metrics, no click target, so it can't imply an action.
@Composable
private fun HelpInfoRow(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    ListItem(
        modifier = modifier,
        headlineContent = { Text(text = title) },
        supportingContent = supportingText?.let { { Text(text = it) } },
        leadingContent = {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(24.dp))
        },
        trailingContent = trailing,
    )
}
