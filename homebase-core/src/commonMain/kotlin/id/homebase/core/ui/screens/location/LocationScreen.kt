package id.homebase.core.ui.screens.location

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.resources.MR
import id.homebase.resources.location_label
import org.jetbrains.compose.resources.stringResource

/** Location home: the four tiles. A top-level destination (bottom nav stays visible, no back arrow). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationScreen(
    viewModel: LocationViewModel,
    onOpenEmergency: () -> Unit,
    onOpenHistoryOverview: () -> Unit,
    onOpenLiveSharing: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LocationPermissionHost(
        viewModel = viewModel,
        uiState = uiState,
        onVisible = {
            viewModel.refresh()
            viewModel.onAction(LocationUiAction.TileHomeVisible)
        },
    ) { _ ->
        Scaffold(
            topBar = {
                TopAppBar(title = { Text(stringResource(MR.string.location_label)) })
            },
        ) { innerPadding ->
            LocationTileHomeContent(
                uiState = uiState,
                innerPadding = innerPadding,
                onOpenEmergency = onOpenEmergency,
                onOpenHistory = onOpenHistoryOverview,
                onOpenLiveSharing = onOpenLiveSharing,
                onOpenSettings = onOpenSettings,
            )
        }
    }
}
