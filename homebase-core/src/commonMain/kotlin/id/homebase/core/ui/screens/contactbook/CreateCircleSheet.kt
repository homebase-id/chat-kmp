@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package id.homebase.core.ui.screens.contactbook

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.chat.widget.ExtendPermissionDialog
import id.homebase.core.widget.AdaptiveSheet
import id.homebase.resources.MR
import id.homebase.resources.circle_create_access_check_failed
import id.homebase.resources.circle_create_action
import id.homebase.resources.ok
import id.homebase.resources.circle_create_description
import id.homebase.resources.circle_create_emoji
import id.homebase.resources.circle_create_failed
import id.homebase.resources.circle_create_name
import id.homebase.resources.circle_create_needs_permission
import id.homebase.resources.circle_create_title
import id.homebase.resources.circle_new
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.Uuid
import org.koin.compose.viewmodel.koinViewModel

/**
 * Hosts the new-circle sheet and the permission prompt that gates it, and returns the action every
 * "New circle" entry point calls. [onCreated] gets the new circle's id once the server has it.
 */
@Composable
fun rememberNewCircleAction(onCreated: (String) -> Unit): () -> Unit {
    // One instance per host: two hosts on a screen would otherwise share a sheet and both react to a create.
    val viewModel = koinViewModel<CreateCircleViewModel>(key = rememberSaveable { Uuid.random().toString() })
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val latest by rememberUpdatedState(onCreated)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is CreateCircleEvent.Created -> latest(event.circleId)
            }
        }
    }
    viewModel.permission?.let { ExtendPermissionDialog(viewModel = it) }
    if (state.accessCheckFailed) {
        AlertDialog(
            onDismissRequest = viewModel::onAccessFailureAcknowledged,
            text = { Text(stringResource(MR.string.circle_create_access_check_failed)) },
            confirmButton = {
                TextButton(onClick = viewModel::onAccessFailureAcknowledged) { Text(stringResource(MR.string.ok)) }
            },
        )
    }
    if (state.open) {
        CreateCircleSheet(
            state = state,
            onNameChange = viewModel::onNameChanged,
            onEmojiChange = viewModel::onEmojiChanged,
            onDescriptionChange = viewModel::onDescriptionChanged,
            onCreate = viewModel::onCreateClicked,
            onDismiss = viewModel::onDismiss,
        )
    }
    return viewModel::onNewCircleClicked
}

@Composable
internal fun CreateCircleSheet(
    state: CreateCircleUiState,
    onNameChange: (String) -> Unit,
    onEmojiChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit,
) {
    AdaptiveSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(MR.string.circle_create_title),
                style = MaterialTheme.typography.titleLargeEmphasized,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = state.name,
                    onValueChange = onNameChange,
                    label = { Text(stringResource(MR.string.circle_create_name)) },
                    singleLine = true,
                    enabled = !state.isCreating,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = state.emoji,
                    onValueChange = onEmojiChange,
                    label = { Text(stringResource(MR.string.circle_create_emoji)) },
                    singleLine = true,
                    enabled = !state.isCreating,
                    modifier = Modifier.weight(0.6f),
                )
            }
            OutlinedTextField(
                value = state.description,
                onValueChange = onDescriptionChange,
                label = { Text(stringResource(MR.string.circle_create_description)) },
                maxLines = 3,
                enabled = !state.isCreating,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            state.error?.let { error ->
                Text(
                    text = stringResource(
                        if (error == CreateCircleError.NeedsPermission) MR.string.circle_create_needs_permission
                        else MR.string.circle_create_failed,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(onClick = onCreate, enabled = state.canCreate, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) {
                if (state.isCreating) {
                    LoadingIndicator(modifier = Modifier.size(ButtonDefaults.IconSize), color = LocalContentColor.current)
                } else {
                    Text(stringResource(MR.string.circle_create_action))
                }
            }
        }
    }
}

@Composable
fun NewCircleRow(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    ListItem(
        modifier = modifier.clickable(enabled = enabled, onClick = onClick),
        leadingContent = { Icon(Icons.Outlined.Add, contentDescription = null) },
        headlineContent = { Text(stringResource(MR.string.circle_new)) },
    )
}
