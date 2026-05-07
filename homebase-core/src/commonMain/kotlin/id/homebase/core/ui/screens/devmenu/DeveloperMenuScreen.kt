package id.homebase.core.ui.screens.devmenu

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat_kmp.homebase_common.BuildConfig
import id.homebase.core.ui.screens.help.HelpClickableRow
import id.homebase.core.ui.screens.help.HelpSectionHeader
import id.homebase.resources.MR
import id.homebase.resources.menu_back
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable
fun DeveloperMenuScreen(
    viewModel: DeveloperMenuViewModel,
    onBackClick: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(uiState.uiEvent) {
        when (val event = uiState.uiEvent) {
            null -> {}
            is DeveloperMenuUiEvent.Back -> {
                viewModel.eventConsumed()
                onBackClick()
            }

            is DeveloperMenuUiEvent.Error -> {
                viewModel.eventConsumed()
                scope.launch { snackbarHostState.showSnackbar(message = event.errorMessage) }
            }

            is DeveloperMenuUiEvent.Success -> {
                viewModel.eventConsumed()
                scope.launch { snackbarHostState.showSnackbar(message = event.message) }
            }
        }
    }

    DeveloperMenuUi(
        snackbarHostState = snackbarHostState,
        uiState = uiState,
        onAction = viewModel::onUiAction,
        onBackClick = onBackClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperMenuUi(
    snackbarHostState: SnackbarHostState,
    uiState: DeveloperMenuUiState,
    onAction: (DeveloperMenuUiAction) -> Unit,
    onBackClick: () -> Unit,
) {
    val scrollState = rememberScrollState()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Developer menu") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
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
                .padding(horizontal = 16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            HelpSectionHeader(title = "Misc info")
            Text(
                text = BuildConfig.APP_BUILD_TIME,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Sync & Connection Section
            HelpSectionHeader(title = "Sync & Connection")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    HelpClickableRow(
                        label = "Force Sync All",
                        showChevron = false,
                        onClick = { onAction(DeveloperMenuUiAction.ForceSyncAll) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            // Database Section
            HelpSectionHeader(title = "Database & Storage")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    HelpClickableRow(
                        label = "Clear All Data",
                        showChevron = false,
                        onClick = { onAction(DeveloperMenuUiAction.ClearAllData) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            // Testing Section
            HelpSectionHeader(title = "Testing Tools")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    HelpClickableRow(
                        label = "Test Rich Notification",
                        showChevron = false,
                        onClick = { onAction(DeveloperMenuUiAction.TestRichNotification) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}