package id.homebase.core.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import id.homebase.resources.export_log
import id.homebase.resources.homebase_logo
import id.homebase.resources.vault_label
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onNavigateToVault: () -> Unit,
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
    )
}

@Composable
fun HomeUi(
    uiState: HomeUiState,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onAction: (HomeUiAction) -> Unit,
    onNavigateToVault: () -> Unit = {},
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

            Surface(
                onClick = onNavigateToVault,
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 1.dp,
                modifier = Modifier.size(96.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(40.dp),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(MR.string.vault_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            Spacer(modifier = Modifier.height(48.dp))
            NavigationButton(stringResource(MR.string.export_log)) { onAction(HomeUiAction.ExportLogClicked) }
            NavigationButton(stringResource(MR.string.clear_log)) { onAction(HomeUiAction.ClearLogClicked) }
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
