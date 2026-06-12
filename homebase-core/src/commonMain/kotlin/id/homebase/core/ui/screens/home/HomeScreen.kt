package id.homebase.core.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.touchlab.kermit.Logger
import id.homebase.core.ui.assets.Homebase
import id.homebase.core.ui.assets.HomebaseIcons
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.core.util.getUriHandler
import id.homebase.core.widget.SquircleIcon
import id.homebase.resources.MR
import id.homebase.resources.app_version
import id.homebase.resources.clear_log
import id.homebase.resources.contactbook_home_subtitle
import id.homebase.resources.contactbook_label
import id.homebase.resources.export_log
import id.homebase.resources.homebase_logo
import id.homebase.resources.location_home_subtitle
import id.homebase.resources.location_label
import id.homebase.resources.moments_home_subtitle
import id.homebase.resources.moments_label
import id.homebase.resources.vault_home_subtitle
import id.homebase.resources.vault_label
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onNavigateToVault: () -> Unit,
    onNavigateToMoments: () -> Unit,
    onNavigateToLocation: () -> Unit,
    onNavigateToContacts: () -> Unit,
    onNavigateToExamples: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val uriHandler = getUriHandler()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Pre-resolve StringResource events at composition time — stringResource() cannot
    // be called inside LaunchedEffect.
    val snackbarText = when (val event = uiState.uiEvent) {
        is HomeUiEvent.ShowInfoMessage -> stringResource(event.res)
        is HomeUiEvent.ShowErrorMessage -> stringResource(event.res)
        else -> ""
    }

    LaunchedEffect(uiState.uiEvent) {
        when (val event = uiState.uiEvent) {
            null -> {}
            is HomeUiEvent.ShareFile -> {
                viewModel.eventConsumed()
                uriHandler.shareFile(event.file) { error ->
                    Logger.e(error) { "Failed to share file" }
                }
            }

            is HomeUiEvent.OpenFileBrowser -> {
                viewModel.eventConsumed()
                uriHandler.openFileBrowser(event.file) { error ->
                    Logger.e(error) { "Failed to open file browser" }
                }
            }

            is HomeUiEvent.NavigateToExample -> {
                viewModel.eventConsumed()
                onNavigateToExamples()
            }

            is HomeUiEvent.ShowInfoMessage,
            is HomeUiEvent.ShowErrorMessage -> {
                viewModel.eventConsumed()
                scope.launch { snackbarHostState.showSnackbar(snackbarText) }
            }
        }
    }


    HomeUi(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onAction = viewModel::onAction,
        onNavigateToVault = onNavigateToVault,
        onNavigateToMoments = onNavigateToMoments,
        onNavigateToLocation = onNavigateToLocation,
        onNavigateToContacts = onNavigateToContacts,
    )
}

@Composable
fun HomeUi(
    uiState: HomeUiState,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onAction: (HomeUiAction) -> Unit,
    onNavigateToVault: () -> Unit = {},
    onNavigateToMoments: () -> Unit = {},
    onNavigateToLocation: () -> Unit = {},
    onNavigateToContacts: () -> Unit = {},
) {
    val scrollState = rememberScrollState()
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(32.dp))
            SquircleIcon(
                imageVector = HomebaseIcons.Homebase,
                contentDescription = stringResource(MR.string.homebase_logo),
                modifier = Modifier.size(72.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(uiState.appName)
            Text(stringResource(MR.string.app_version, uiState.appVersion), style = MaterialTheme.typography.labelMedium)
            Spacer(modifier = Modifier.height(32.dp))

            FeatureCard(
                icon = Icons.Outlined.Lock,
                label = stringResource(MR.string.vault_label),
                subtitle = stringResource(MR.string.vault_home_subtitle),
                onClick = onNavigateToVault,
            )

            Spacer(modifier = Modifier.height(12.dp))

            FeatureCard(
                icon = Icons.Outlined.AutoAwesome,
                label = stringResource(MR.string.moments_label),
                subtitle = stringResource(MR.string.moments_home_subtitle),
                onClick = onNavigateToMoments,
            )

            Spacer(modifier = Modifier.height(12.dp))

            FeatureCard(
                icon = Icons.Outlined.LocationOn,
                label = stringResource(MR.string.location_label),
                subtitle = stringResource(MR.string.location_home_subtitle),
                onClick = onNavigateToLocation,
            )

            Spacer(modifier = Modifier.height(12.dp))

            FeatureCard(
                icon = Icons.Outlined.Contacts,
                label = stringResource(MR.string.contactbook_label),
                subtitle = stringResource(MR.string.contactbook_home_subtitle),
                onClick = onNavigateToContacts,
            )

            Spacer(modifier = Modifier.height(48.dp))
            NavigationButton(stringResource(MR.string.export_log)) { onAction(HomeUiAction.ExportLogClicked) }
            NavigationButton(stringResource(MR.string.clear_log)) { onAction(HomeUiAction.ClearLogClicked) }
        }
    }
}

@Composable
private fun FeatureCard(
    icon: ImageVector,
    label: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
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
}

@Composable
private fun NavigationButton(text: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(text) }
}

@Preview
@Composable
fun HomeUiPreview() {
    HomebaseTheme {
        HomeUi(
            uiState = HomeUiState(appVersion = "1.0.0"),
            onAction = {}
        )
    }
}
