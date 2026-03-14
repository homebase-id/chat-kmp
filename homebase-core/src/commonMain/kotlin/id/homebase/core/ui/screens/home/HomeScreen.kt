package id.homebase.core.ui.screens.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import id.homebase.core.ui.assets.Homebase
import id.homebase.core.ui.assets.HomebaseIcons
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.core.util.getUriHandler
import id.homebase.core.widget.SquircleIcon
import id.homebase.resources.MR
import id.homebase.resources.export_log
import org.jetbrains.compose.resources.stringResource

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onNavigateToExamples: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val uriHandler = getUriHandler()

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
        }
    }


    HomeUi(
        uiState = uiState,
        onAction = viewModel::onAction
    )
}

@Composable
fun HomeUi(
    uiState: HomeUiState,
    onAction: (HomeUiAction) -> Unit
) {
    val scrollState = rememberScrollState()
    Scaffold { innerPadding ->
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
                contentDescription = "Homebase Logo",
                modifier = Modifier.size(72.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(uiState.appName)
            Text("Version ${uiState.appVersion}", style = MaterialTheme.typography.labelMedium)
            Spacer(modifier = Modifier.height(32.dp))
            NavigationButton(stringResource(MR.string.export_log)) { onAction(HomeUiAction.ExportLogClicked) }

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
