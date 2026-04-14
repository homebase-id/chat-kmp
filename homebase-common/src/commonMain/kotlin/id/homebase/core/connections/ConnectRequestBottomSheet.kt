package id.homebase.core.connections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import id.homebase.resources.connections_checking_identity
import id.homebase.resources.connections_invalid_identity
import id.homebase.resources.connections_message_label
import id.homebase.resources.connections_new_request
import id.homebase.resources.connections_recipient_label
import id.homebase.resources.connections_recipient_placeholder
import id.homebase.resources.connections_request_sent
import id.homebase.resources.connections_send_request
import id.homebase.resources.settings_open_owner_console
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectRequestBottomSheet(
    viewModel: ConnectRequestViewModel,
    snackbarHostState: SnackbarHostState,
    sendSuccessMessage: String = stringResource(MR.string.connections_request_sent),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
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
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

        ModalBottomSheet(
            onDismissRequest = {
                if (!state.isSending) viewModel.onAction(ConnectRequestAction.CloseDialog)
            },
            sheetState = sheetState,
        ) {
            ComposeRequestSheetContent(
                recipient = state.recipient,
                message = state.message,
                resolution = state.resolution,
                isSending = state.isSending,
                onRecipientChange = { viewModel.onAction(ConnectRequestAction.RecipientChanged(it)) },
                onMessageChange = { viewModel.onAction(ConnectRequestAction.MessageChanged(it)) },
                onSend = { viewModel.onAction(ConnectRequestAction.SendClicked) },
            )
        }
    }
}

@Composable
private fun ComposeRequestSheetContent(
    recipient: String,
    message: String,
    resolution: RecipientResolution,
    isSending: Boolean,
    onRecipientChange: (String) -> Unit,
    onMessageChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    val canSend = resolution is RecipientResolution.Resolved

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(MR.string.connections_new_request),
            style = MaterialTheme.typography.titleLarge,
        )

        val isError = resolution is RecipientResolution.NotFound
        OutlinedTextField(
            value = recipient,
            onValueChange = onRecipientChange,
            label = { Text(stringResource(MR.string.connections_recipient_label)) },
            placeholder = { Text(stringResource(MR.string.connections_recipient_placeholder)) },
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
            label = { Text(stringResource(MR.string.connections_message_label)) },
            enabled = !isSending,
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = onSend,
            enabled = !isSending && canSend,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (isSending) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(stringResource(MR.string.connections_send_request))
            }
        }
    }
}

@Composable
private fun RecipientResolutionIndicator(resolution: RecipientResolution) {
    when (resolution) {
        RecipientResolution.Idle,
        RecipientResolution.InvalidFormat -> {}
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
                    text = stringResource(MR.string.connections_checking_identity),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        RecipientResolution.NotFound -> {
            Text(
                text = stringResource(MR.string.connections_invalid_identity),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        is RecipientResolution.Resolved -> {
            val identity = resolution.identity
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(12.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ContactAvatar(
                        odinId = identity.odinId,
                        profileImageData = null,
                        initials = identity.initials(),
                        options = AvatarOptions(size = 36.dp),
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
}
