package id.homebase.core.connections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import id.homebase.api.client.identity.displayNameOrDomain
import id.homebase.api.client.identity.initials
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.ContactAvatar
import id.homebase.core.util.getUriHandler
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.connections_already_sent_text
import id.homebase.resources.connections_already_sent_title
import id.homebase.resources.settings_open_owner_console
import org.jetbrains.compose.resources.stringResource

@Composable
fun ConnectRequestDialogs(
    viewModel: ConnectRequestViewModel,
    snackbarHostState: SnackbarHostState,
    sendSuccessMessage: String = "Connection request sent",
) {
    val state by viewModel.state.collectAsState()
    val uriHandler = getUriHandler()

    LaunchedEffect(state.uiEvent) {
        when (val event = state.uiEvent) {
            null -> {}
            ConnectRequestEvent.SendSuccess -> {
                snackbarHostState.showSnackbar(sendSuccessMessage)
                viewModel.onAction(ConnectRequestAction.EventConsumed)
            }
            is ConnectRequestEvent.SendError -> {
                snackbarHostState.showSnackbar(event.message)
                viewModel.onAction(ConnectRequestAction.EventConsumed)
            }
            is ConnectRequestEvent.OpenUrl -> {
                viewModel.onAction(ConnectRequestAction.EventConsumed)
                uriHandler.openUrl(event.url)
            }
        }
    }

    state.alreadySentRecipient?.let { recipient ->
        AlertDialog(
            onDismissRequest = { viewModel.onAction(ConnectRequestAction.DismissAlreadySentDialog) },
            title = { Text(stringResource(MR.string.connections_already_sent_title)) },
            text = {
                Text(
                    stringResource(
                        MR.string.connections_already_sent_text,
                        recipient.domainName,
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.onAction(ConnectRequestAction.OpenOwnerConsoleClicked) }) {
                    Text(stringResource(MR.string.settings_open_owner_console))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.onAction(ConnectRequestAction.DismissAlreadySentDialog) }) {
                    Text(stringResource(MR.string.cancel))
                }
            }
        )
    }

    if (state.showDialog) {
        ComposeRequestDialog(
            recipient = state.recipient,
            message = state.message,
            resolution = state.resolution,
            isSending = state.isSending,
            onRecipientChange = { viewModel.onAction(ConnectRequestAction.RecipientChanged(it)) },
            onMessageChange = { viewModel.onAction(ConnectRequestAction.MessageChanged(it)) },
            onSend = { viewModel.onAction(ConnectRequestAction.SendClicked) },
            onDismiss = { viewModel.onAction(ConnectRequestAction.CloseDialog) },
        )
    }
}

@Composable
private fun ComposeRequestDialog(
    recipient: String,
    message: String,
    resolution: RecipientResolution,
    isSending: Boolean,
    onRecipientChange: (String) -> Unit,
    onMessageChange: (String) -> Unit,
    onSend: () -> Unit,
    onDismiss: () -> Unit,
) {
    val canSend = resolution is RecipientResolution.Resolved
    AlertDialog(
        onDismissRequest = { if (!isSending) onDismiss() },
        title = { Text("New connection request") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                val isError = resolution is RecipientResolution.NotFound
                OutlinedTextField(
                    value = recipient,
                    onValueChange = onRecipientChange,
                    label = { Text("Recipient OdinId") },
                    placeholder = { Text("example.dotyou.cloud") },
                    singleLine = true,
                    isError = isError,
                    enabled = !isSending,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        imeAction = ImeAction.Next,
                    ),
                )
                RecipientResolutionIndicator(resolution = resolution)
                OutlinedTextField(
                    value = message,
                    onValueChange = onMessageChange,
                    label = { Text("Message (optional)") },
                    enabled = !isSending,
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onSend,
                enabled = !isSending && canSend,
            ) {
                if (isSending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Send")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSending) { Text("Cancel") }
        }
    )
}

@Composable
private fun RecipientResolutionIndicator(resolution: RecipientResolution) {
    when (resolution) {
        RecipientResolution.Idle,
        RecipientResolution.InvalidFormat -> {
            // Stay quiet while the user is still typing a partial OdinId.
        }
        RecipientResolution.Resolving -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                )
                Text(
                    text = "Checking identity…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        RecipientResolution.NotFound -> {
            Text(
                text = "This isn't a valid Homebase identity",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        is RecipientResolution.Resolved -> {
            val identity = resolution.identity
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ContactAvatar(
                    odinId = identity.odinId,
                    profileImageData = null,
                    initials = identity.initials(),
                    options = AvatarOptions(size = 40.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = identity.displayNameOrDomain(),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (identity.displayName?.isNotBlank() == true) {
                        Text(
                            text = identity.odinId.domainName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    identity.status?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
