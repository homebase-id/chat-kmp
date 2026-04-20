package id.homebase.core.connections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import id.homebase.api.util.cleanDomain
import id.homebase.api.client.identity.displayNameOrDomain
import id.homebase.api.client.identity.initials
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.ContactAvatar
import id.homebase.core.util.getUriHandler
import id.homebase.core.widget.HomebaseIdField
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
import kotlin.uuid.Uuid
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectRequestBottomSheet(
    viewModel: ConnectRequestViewModel,
    snackbarHostState: SnackbarHostState,
    sendSuccessMessage: String = stringResource(MR.string.connections_request_sent),
    onNavigateToConversation: ((Uuid) -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val uriHandler = getUriHandler()
    // Separate snackbar state for errors shown while the sheet is open
    val sheetSnackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.uiEvent) {
        when (val event = state.uiEvent) {
            null -> {}
            ConnectRequestEvent.SendSuccess -> {
                // Sheet closes on success, so use the parent scaffold's snackbar
                snackbarHostState.showSnackbar(sendSuccessMessage)
                viewModel.onAction(ConnectRequestAction.EventConsumed)
            }
            is ConnectRequestEvent.SendError -> {
                // Sheet stays open on error, so use the sheet's own snackbar
                sheetSnackbarHostState.showSnackbar(event.message)
                viewModel.onAction(ConnectRequestAction.EventConsumed)
            }
            is ConnectRequestEvent.OpenUrl -> {
                viewModel.onAction(ConnectRequestAction.EventConsumed)
                uriHandler.openUrl(event.url)
            }
            is ConnectRequestEvent.NavigateToConversation -> {
                viewModel.onAction(ConnectRequestAction.EventConsumed)
                if (onNavigateToConversation != null) {
                    onNavigateToConversation(event.conversationId)
                } else {
                    // No navigation handler supplied; fall back to the standard success toast.
                    snackbarHostState.showSnackbar(sendSuccessMessage)
                }
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
            Box {
                ComposeRequestSheetContent(
                    recipient = state.recipient,
                    message = state.message,
                    resolution = state.resolution,
                    isSending = state.isSending,
                    onRecipientChange = { viewModel.onAction(ConnectRequestAction.RecipientChanged(it)) },
                    onMessageChange = { viewModel.onAction(ConnectRequestAction.MessageChanged(it)) },
                    onSend = { viewModel.onAction(ConnectRequestAction.SendClicked) },
                )
                SnackbarHost(
                    hostState = sheetSnackbarHostState,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
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
        // Local TextFieldValue stores space-encoded text; the visual transformation renders those
        // spaces as dots. The VM's canonical `recipient` is the dotted form. The sheet is
        // composed fresh on each open, so `remember` re-seeds from the VM's current value —
        // no ongoing sync needed.
        var fieldValue by remember {
            mutableStateOf(
                TextFieldValue(
                    text = recipient.replace('.', ' '),
                    selection = TextRange(recipient.length),
                )
            )
        }
        HomebaseIdField(
            value = fieldValue,
            onValueChange = { incoming ->
                val normalizedSpaces = incoming.text.cleanDomain().replace('.', ' ')
                fieldValue = incoming.copy(text = normalizedSpaces)
                val dotted = normalizedSpaces.cleanDomain(preserveTrailingDot = false)
                if (dotted != recipient) onRecipientChange(dotted)
            },
            label = { Text(stringResource(MR.string.connections_recipient_label)) },
            placeholder = { Text(stringResource(MR.string.connections_recipient_placeholder)) },
            isError = isError,
            enabled = !isSending,
            imeAction = ImeAction.Next,
        )

        RecipientResolutionIndicator(resolution = resolution)

        TextField(
            value = message,
            onValueChange = onMessageChange,
            label = { Text(stringResource(MR.string.connections_message_label)) },
            enabled = !isSending,
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
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
