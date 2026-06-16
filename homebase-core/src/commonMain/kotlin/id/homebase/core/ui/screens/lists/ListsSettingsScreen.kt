package id.homebase.core.ui.screens.lists

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
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.core.widget.SettingsItemAction
import id.homebase.resources.MR
import id.homebase.resources.lists_settings_open
import id.homebase.resources.lists_settings_section
import id.homebase.resources.lists_settings_show_icon
import id.homebase.resources.menu_back
import org.jetbrains.compose.resources.stringResource

@Composable
fun ListsSettingsScreen(
    viewModel: ListsSettingsViewModel,
    onBackClick: () -> Unit,
    onOpenLists: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ListsSettingsUi(
        uiState = uiState,
        onAction = { action ->
            if (action is ListsSettingsUiAction.OpenListsClicked) onOpenLists()
            else viewModel.onAction(action)
        },
        onBackClick = onBackClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListsSettingsUi(
    uiState: ListsSettingsUiState,
    onAction: (ListsSettingsUiAction) -> Unit,
    onBackClick: () -> Unit,
) {
    val scrollState = rememberScrollState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(MR.string.lists_settings_section)) },
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
            SettingsItemAction(
                imageVector = Icons.AutoMirrored.Outlined.ListAlt,
                text = stringResource(MR.string.lists_settings_open),
                onClick = { onAction(ListsSettingsUiAction.OpenListsClicked) },
            )
            SettingsItemAction(
                imageVector = Icons.Outlined.Visibility,
                text = stringResource(MR.string.lists_settings_show_icon),
                onClick = { onAction(ListsSettingsUiAction.SetIconVisible(!uiState.iconVisible)) },
                trailingContent = {
                    Switch(
                        checked = uiState.iconVisible,
                        onCheckedChange = { onAction(ListsSettingsUiAction.SetIconVisible(it)) },
                    )
                },
            )
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp),
            )
        }
    }
}
