@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package id.homebase.core.ui.screens.contactbook

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddReaction
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.chat.widget.ExtendPermissionDialog
import id.homebase.core.ui.theme.withEmojiFont
import id.homebase.core.widget.AdaptiveSheet
import id.homebase.core.widget.ReportOverlay
import id.homebase.core.widget.EmojiSelectorDialog
import id.homebase.resources.MR
import id.homebase.resources.circle_create_access_check_failed
import id.homebase.resources.circle_create_action
import id.homebase.resources.circle_create_approve
import id.homebase.resources.circle_create_choose_emoji
import id.homebase.resources.circle_create_creating
import id.homebase.resources.ok
import id.homebase.resources.circle_create_description
import id.homebase.resources.circle_create_remove_emoji
import id.homebase.resources.circle_create_failed
import id.homebase.resources.circle_create_name
import id.homebase.resources.circle_create_needs_permission
import id.homebase.resources.circle_create_title
import id.homebase.resources.circle_new
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.Uuid
import org.koin.compose.viewmodel.koinViewModel

private val EMOJI_BADGE_SIZE = 64.dp

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
        ReportOverlay()
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
            onApprove = viewModel::onApproveClicked,
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
    onApprove: () -> Unit,
    onDismiss: () -> Unit,
) {
    var pickingEmoji by remember { mutableStateOf(false) }
    // The error banner can grow it past the half-height stop, which would hide Create below the screen.
    AdaptiveSheet(onDismiss = onDismiss, expandFully = true) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(MR.string.circle_create_title),
                style = MaterialTheme.typography.titleLargeEmphasized,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                EmojiBadge(
                    emoji = state.emoji,
                    enabled = !state.isCreating,
                    onPick = { pickingEmoji = true },
                    onRemove = { onEmojiChange("") },
                )
                CircleField(
                    value = state.name,
                    onValueChange = onNameChange,
                    label = stringResource(MR.string.circle_create_name),
                    enabled = !state.isCreating,
                    singleLine = true,
                    imeAction = ImeAction.Next,
                    modifier = Modifier.weight(1f),
                )
            }
            CircleField(
                value = state.description,
                onValueChange = onDescriptionChange,
                label = stringResource(MR.string.circle_create_description),
                enabled = !state.isCreating,
                modifier = Modifier.fillMaxWidth(),
            )
            state.error?.let { CreateErrorBanner(it, onApprove) }
            Button(
                onClick = onCreate,
                enabled = state.canCreate,
                shapes = ButtonDefaults.shapes(),
                // Disabled by the in-flight create, but it is working rather than unavailable, so it keeps the primary colour.
                colors = if (state.isCreating) {
                    ButtonDefaults.buttonColors(
                        disabledContainerColor = MaterialTheme.colorScheme.primary,
                        disabledContentColor = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    ButtonDefaults.buttonColors()
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                if (state.isCreating) {
                    LoadingIndicator(modifier = Modifier.size(28.dp), color = LocalContentColor.current)
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(MR.string.circle_create_creating))
                } else {
                    Text(stringResource(MR.string.circle_create_action))
                }
            }
        }
    }
    if (pickingEmoji) {
        EmojiSelectorDialog(
            onDismiss = { pickingEmoji = false },
            onEmojiSelected = {
                pickingEmoji = false
                onEmojiChange(it)
            },
        )
    }
}

@Composable
private fun EmojiBadge(emoji: String, enabled: Boolean, onPick: () -> Unit, onRemove: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val choose = stringResource(MR.string.circle_create_choose_emoji)
    val shape = MaterialShapes.Cookie9Sided.toShape()
    Box(contentAlignment = Alignment.BottomEnd) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(EMOJI_BADGE_SIZE)
                .clip(shape)
                .background(colors.primaryContainer)
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = choose, onClick = onPick)
                .semantics { contentDescription = choose },
        ) {
            if (emoji.isEmpty()) {
                Icon(
                    Icons.Outlined.AddReaction,
                    contentDescription = null,
                    tint = colors.onPrimaryContainer,
                    modifier = Modifier.size(28.dp),
                )
            } else {
                Text(text = emoji.withEmojiFont(), style = MaterialTheme.typography.headlineLarge, maxLines = 1)
            }
        }
        if (emoji.isNotEmpty() && enabled) {
            FilledTonalIconButton(onClick = onRemove, modifier = Modifier.size(24.dp)) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = stringResource(MR.string.circle_create_remove_emoji),
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@Composable
private fun CircleField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    imeAction: ImeAction = ImeAction.Default,
) {
    val container = MaterialTheme.colorScheme.surfaceContainerHighest
    TextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        maxLines = if (singleLine) 1 else 3,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = imeAction),
        shape = MaterialTheme.shapes.large,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = container,
            unfocusedContainerColor = container,
            disabledContainerColor = container,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
        modifier = modifier,
    )
}

@Composable
private fun CreateErrorBanner(error: CreateCircleError, onApprove: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val needsPermission = error == CreateCircleError.NeedsPermission
    Surface(color = colors.errorContainer, contentColor = colors.onErrorContainer, shape = MaterialTheme.shapes.large) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Icon(Icons.Outlined.ErrorOutline, contentDescription = null)
            Text(
                text = stringResource(if (needsPermission) MR.string.circle_create_needs_permission else MR.string.circle_create_failed),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            )
            if (needsPermission) {
                FilledTonalButton(onClick = onApprove, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(MR.string.circle_create_approve), maxLines = 1)
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
