package id.homebase.core.ui.screens.help

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.core.util.getUriHandler
import id.homebase.core.widget.CheckboxRow
import id.homebase.resources.MR
import id.homebase.resources.about_homebase
import id.homebase.resources.help_contact_us
import id.homebase.resources.help_copyright
import id.homebase.resources.help_debug_log_description
import id.homebase.resources.help_enable_error_collection
import id.homebase.resources.help_submit_debug_log
import id.homebase.resources.help_support_center
import id.homebase.resources.help_terms_privacy
import id.homebase.resources.help_version
import id.homebase.resources.logging
import id.homebase.resources.menu_back
import id.homebase.resources.settings_help
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
                uriHandler.shareFile(event.filePath)
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
                title = { Text(stringResource(MR.string.settings_help)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.Default.ChevronLeft,
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
                .padding(horizontal = 16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // ── Support Section ──
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    HelpClickableRow(
                        label = stringResource(MR.string.help_support_center),
                        onClick = { onAction(HelpUiAction.SupportCenterClicked) }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    HelpClickableRow(
                        label = stringResource(MR.string.help_contact_us),
                        onClick = { onAction(HelpUiAction.ContactUsClicked) }
                    )
                }
            }

            // ── Logging Section ──
            HelpSectionHeader(title = stringResource(MR.string.logging))
            Card(modifier = Modifier.fillMaxWidth()) {
                HelpClickableRow(
                    label = stringResource(MR.string.help_submit_debug_log),
                    showChevron = false,
                    onClick = { onAction(HelpUiAction.SubmitDebugLogClicked) }
                )
            }
            CheckboxRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                label = stringResource(MR.string.help_enable_error_collection),
                checked = uiState.errorCollectionEnabled,
                onCheckedChange = { onAction(HelpUiAction.ToggleErrorCollection) }
            )

            Text(
                text = stringResource(MR.string.help_debug_log_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            // ── About Section ──
            HelpSectionHeader(title = stringResource(MR.string.about_homebase))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    // Version row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onAction(HelpUiAction.DeveloperClicked)
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(MR.string.help_version),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = uiState.appVersion,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    HelpClickableRow(
                        label = stringResource(MR.string.help_terms_privacy),
                        onClick = { onAction(HelpUiAction.TermsPrivacyClicked) }
                    )
                    if (uiState.showDeveloperMenu) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        HelpClickableRow(
                            label = "Developer menu",
                            onClick = { onAction(HelpUiAction.DeveloperMenu) }
                        )
                    }
                }
            }
            Text(
                text = stringResource(MR.string.help_copyright),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// ── Reusable Components ──

@Composable
fun HelpSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}

@Composable
fun HelpClickableRow(
    label: String,
    showChevron: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
        )
        if (showChevron) {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
