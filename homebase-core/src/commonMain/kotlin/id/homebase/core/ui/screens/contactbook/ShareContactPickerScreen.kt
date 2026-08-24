package id.homebase.core.ui.screens.contactbook

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import id.homebase.core.ui.screens.contactbook.components.ContactBookRow
import id.homebase.core.widget.StyledSearchTextField
import id.homebase.resources.MR
import id.homebase.resources.chat_contact_share_no_contacts
import id.homebase.resources.chat_contact_share_title
import id.homebase.resources.chat_contact_share_unshareable
import id.homebase.resources.chat_new_conversation_search_placeholder
import id.homebase.resources.menu_back
import id.homebase.resources.send
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

@Composable
fun ShareContactPickerScreen(
    viewModel: ShareContactPickerViewModel,
    onNavigateBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                ShareContactPickerUiEvent.Back,
                ShareContactPickerUiEvent.MessageSent -> onNavigateBack()

                is ShareContactPickerUiEvent.ShowError -> {
                    val message = getString(event.res)
                    scope.launch { snackbarHostState.showSnackbar(message) }
                }
            }
        }
    }

    ShareContactPickerUi(
        snackbarHostState = snackbarHostState,
        uiState = uiState,
        searchTextState = viewModel.searchTextState,
        onUiAction = viewModel::onUiAction,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareContactPickerUi(
    snackbarHostState: SnackbarHostState,
    uiState: ShareContactPickerUiState,
    searchTextState: TextFieldState,
    onUiAction: (ShareContactPickerUiAction) -> Unit,
) {
    Scaffold(
        modifier = Modifier.imePadding(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(MR.string.chat_contact_share_title)) },
                navigationIcon = {
                    IconButton(onClick = { onUiAction(ShareContactPickerUiAction.BackClicked) }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(MR.string.menu_back),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            if (uiState.selectedId != null) {
                Button(
                    onClick = { onUiAction(ShareContactPickerUiAction.SendClicked) },
                    modifier = Modifier.defaultMinSize(minWidth = 56.dp),
                    enabled = !uiState.isSending,
                    shape = CircleShape,
                ) {
                    if (uiState.isSending) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(MR.string.send),
                        )
                    }
                }
            }
        },
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues)) {
            StyledSearchTextField(
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                textFieldState = searchTextState,
                showSearchIcon = false,
                placeHolderText = stringResource(MR.string.chat_new_conversation_search_placeholder),
            )
            if (uiState.candidates.isEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp, start = 16.dp, end = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(text = stringResource(MR.string.chat_contact_share_no_contacts))
                }
            } else {
                val unshareableReason = stringResource(MR.string.chat_contact_share_unshareable)
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(uiState.candidates, key = { it.entry.uniqueId.toString() }) { candidate ->
                        val entry = candidate.entry
                        ContactBookRow(
                            entry = entry,
                            onClick = { onUiAction(ShareContactPickerUiAction.ContactClicked(entry)) },
                            disabledReason = if (!candidate.shareable) unshareableReason else null,
                            trailing = if (uiState.selectedId == entry.uniqueId) {
                                {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            } else null,
                        )
                    }
                }
            }
        }
    }
}
