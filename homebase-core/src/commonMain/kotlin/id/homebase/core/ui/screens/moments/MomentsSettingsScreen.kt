package id.homebase.core.ui.screens.moments

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.core.widget.SettingsRow
import id.homebase.core.widget.SettingsRowAction
import id.homebase.resources.MR
import id.homebase.resources.menu_back
import id.homebase.resources.moments_settings_open
import id.homebase.resources.moments_settings_section
import id.homebase.resources.moments_settings_show_icon
import org.jetbrains.compose.resources.stringResource

@Composable
fun MomentsSettingsScreen(
    viewModel: MomentsSettingsViewModel,
    onBackClick: () -> Unit,
    onOpenMoments: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    MomentsSettingsUi(
        uiState = uiState,
        onAction = { action ->
            if (action is MomentsSettingsUiAction.OpenMomentsClicked) onOpenMoments()
            else viewModel.onAction(action)
        },
        onBackClick = onBackClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MomentsSettingsUi(
    uiState: MomentsSettingsUiState,
    onAction: (MomentsSettingsUiAction) -> Unit,
    onBackClick: () -> Unit,
) {
    val scrollState = rememberScrollState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(MR.string.moments_settings_section)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(MR.string.menu_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
                .padding(innerPadding)
                .verticalScroll(scrollState),
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            SettingsRow(
                icon = Icons.Outlined.AutoAwesome,
                title = stringResource(MR.string.moments_settings_open),
                action = SettingsRowAction.Navigate {
                    onAction(MomentsSettingsUiAction.OpenMomentsClicked)
                },
            )
            SettingsRow(
                icon = Icons.Outlined.Visibility,
                title = stringResource(MR.string.moments_settings_show_icon),
                action = SettingsRowAction.Toggle(
                    checked = uiState.iconVisible,
                    onCheckedChange = { onAction(MomentsSettingsUiAction.SetIconVisible(it)) },
                ),
            )
            Spacer(modifier = Modifier
                .fillMaxWidth()
                .height(24.dp))
        }
    }
}
