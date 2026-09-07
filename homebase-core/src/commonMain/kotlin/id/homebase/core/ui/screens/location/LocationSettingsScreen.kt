package id.homebase.core.ui.screens.location

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.resources.MR
import id.homebase.resources.location_settings_title
import id.homebase.resources.menu_back
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationSettingsScreen(
    viewModel: LocationViewModel,
    onNavigateBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LocationPermissionHost(viewModel = viewModel, uiState = uiState) { dispatch ->
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(MR.string.location_settings_title)) },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(MR.string.menu_back),
                            )
                        }
                    },
                )
            },
        ) { innerPadding ->
            LocationContent(
                uiState = uiState,
                innerPadding = innerPadding,
                onAction = dispatch,
            )
        }
    }
}
