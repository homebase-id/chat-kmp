package id.homebase.chat.addgroupmembers

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch

@Composable
fun AddGroupMembersScreen(
    viewModel: AddGroupMembersViewModel,
    onNavigateBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(uiState.uiEvent) {
        when (val event = uiState.uiEvent) {
            null -> {}
            is AddGroupMembersUiEvent.Back -> {
                viewModel.eventConsumed()
                onNavigateBack()
            }

            is AddGroupMembersUiEvent.Error -> {
                viewModel.eventConsumed()
                scope.launch { snackbarHostState.showSnackbar(message = event.errorMessage) }
            }
        }
    }

    when (uiState.uiDialog) {
        null -> {}
        AddGroupMembersUiDialog.TestDialog -> {
            Dialog(onDismissRequest = { viewModel.dialogConsumed() }) {
                Card {
                    Column(
                        modifier = Modifier.padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Test dialog")
                        Button(onClick = { viewModel.dialogConsumed() }) {
                            Text("Close dialog")
                        }
                    }
                }
            }
        }
    }

    AddGroupMembersUi(
        snackbarHostState = snackbarHostState,
        uiState = uiState,
        onUiAction = viewModel::onUiAction
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddGroupMembersUi(
    snackbarHostState: SnackbarHostState,
    uiState: AddGroupMembersUiState,
    onUiAction: (AddGroupMembersUiAction) -> Unit,
) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = { onUiAction(AddGroupMembersUiAction.BackClicked) }) {
                        Icon(
                            imageVector = Icons.Default.ChevronLeft,
                            contentDescription = "Back"
                        )
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .consumeWindowInsets(padding)
                .padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (uiState.isLoading) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else {
                Text("Display content here")
                Button(onClick = { onUiAction(AddGroupMembersUiAction.ShowDialog) }) {
                    Text("Show dialog")
                }
            }
        }
    }
}