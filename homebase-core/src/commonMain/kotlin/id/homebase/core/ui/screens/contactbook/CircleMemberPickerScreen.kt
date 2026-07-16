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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
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
import id.homebase.resources.chat_new_conversation_search_placeholder
import id.homebase.resources.circle_member_add_already_member
import id.homebase.resources.circle_member_add_drive_access_denied
import id.homebase.resources.circle_member_add_generic_failed
import id.homebase.resources.circle_member_add_none_eligible
import id.homebase.resources.circle_member_add_succeeded
import id.homebase.resources.circle_member_add_title
import id.homebase.resources.circle_member_add_unvetted_reason
import id.homebase.resources.menu_back
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable
fun CircleMemberPickerScreen(
    viewModel: CircleMemberPickerViewModel,
    onNavigateBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val addedLabel = stringResource(MR.string.circle_member_add_succeeded)
    val alreadyMemberLabel = stringResource(MR.string.circle_member_add_already_member)
    val driveAccessDeniedLabel = stringResource(MR.string.circle_member_add_drive_access_denied)
    val genericFailedLabel = stringResource(MR.string.circle_member_add_generic_failed)

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                CircleMemberPickerUiEvent.Back -> onNavigateBack()
                is CircleMemberPickerUiEvent.AddCompleted -> {
                    // Failures lead with the actual reason — a bare "Failed: 1" told the user
                    // nothing happened worth acting on. Only DriveAccessDenied and a real 400's
                    // message are genuinely explanatory; an opaque 403 gets a generic message
                    // rather than surfacing internal detail as if it were actionable.
                    val parts = buildList {
                        if (event.added > 0) add("$addedLabel: ${event.added}")
                        if (event.alreadyMember > 0) add("$alreadyMemberLabel: ${event.alreadyMember}")
                        event.failures.forEach { f ->
                            val reason = when (val r = f.reason) {
                                is CircleAddFailureReason.Raw -> r.message
                                CircleAddFailureReason.DriveAccessDenied -> driveAccessDeniedLabel
                                CircleAddFailureReason.OpaqueForbidden -> genericFailedLabel
                            }
                            add("${f.name}: $reason")
                        }
                    }
                    if (parts.isNotEmpty()) {
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                message = parts.joinToString(" · "),
                                duration = if (event.failures.isNotEmpty()) SnackbarDuration.Long
                                else SnackbarDuration.Short,
                            )
                        }
                    }
                }
            }
        }
    }

    CircleMemberPickerUi(
        snackbarHostState = snackbarHostState,
        uiState = uiState,
        searchTextState = viewModel.searchTextState,
        onUiAction = viewModel::onUiAction,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CircleMemberPickerUi(
    snackbarHostState: SnackbarHostState,
    uiState: CircleMemberPickerUiState,
    searchTextState: TextFieldState,
    onUiAction: (CircleMemberPickerUiAction) -> Unit,
) {
    Scaffold(
        modifier = Modifier.imePadding(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(MR.string.circle_member_add_title, uiState.circleName)) },
                navigationIcon = {
                    IconButton(onClick = { onUiAction(CircleMemberPickerUiAction.BackClicked) }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(MR.string.menu_back),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            if (uiState.selected.isNotEmpty()) {
                Button(
                    onClick = { onUiAction(CircleMemberPickerUiAction.AddClicked) },
                    modifier = Modifier.defaultMinSize(minWidth = 56.dp),
                    enabled = !uiState.submitting,
                    shape = CircleShape,
                ) {
                    if (uiState.submitting) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Check, contentDescription = stringResource(MR.string.circle_member_add_title, uiState.circleName))
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
                    Text(text = stringResource(MR.string.circle_member_add_none_eligible))
                }
            } else {
                val unvettedReason = stringResource(MR.string.circle_member_add_unvetted_reason)
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(uiState.candidates, key = { it.entry.uniqueId.toString() }) { candidate ->
                        val entry = candidate.entry
                        val isSelected = uiState.selected.contains(entry)
                        ContactBookRow(
                            entry = entry,
                            onClick = { onUiAction(CircleMemberPickerUiAction.ContactClicked(entry)) },
                            disabledReason = if (!candidate.eligible) unvettedReason else null,
                            trailing = if (isSelected) {
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
