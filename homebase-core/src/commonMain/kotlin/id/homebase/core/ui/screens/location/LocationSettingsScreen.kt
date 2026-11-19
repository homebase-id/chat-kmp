package id.homebase.core.ui.screens.location

import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.core.widget.SettingsTopBar
import id.homebase.resources.MR
import id.homebase.resources.location_settings_section
import id.homebase.resources.location_settings_title
import org.jetbrains.compose.resources.stringResource

/** Location's settings, in the app and as its page in Settings → Apps, where [onOpenLocation] leads into the app. */
@Composable
fun LocationSettingsScreen(
    viewModel: LocationViewModel,
    onNavigateBack: () -> Unit,
    onOpenLocation: (() -> Unit)? = null,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // The Apps page is named for the app; inside the app, for its settings.
    val title = if (onOpenLocation != null) MR.string.location_settings_section else MR.string.location_settings_title

    LocationPermissionHost(viewModel = viewModel, uiState = uiState) { dispatch ->
        Scaffold(
            topBar = { SettingsTopBar(title = stringResource(title), onBack = onNavigateBack) },
        ) { innerPadding ->
            LocationContent(
                uiState = uiState,
                innerPadding = innerPadding,
                onAction = dispatch,
                onOpenLocation = onOpenLocation,
            )
        }
    }
}
