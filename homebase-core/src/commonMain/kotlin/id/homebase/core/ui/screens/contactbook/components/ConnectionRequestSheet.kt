package id.homebase.core.ui.screens.contactbook.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.client.identity.displayNameOrDomain
import id.homebase.chat.services.convo.contact.ConnectionService
import id.homebase.core.connections.ConnectCircleError
import id.homebase.core.connections.ConnectRequestAction
import id.homebase.core.connections.ConnectRequestBottomSheet
import id.homebase.core.connections.ConnectRequestViewModel
import id.homebase.core.ui.screens.contactbook.reviewCircleGroups
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.connections_circle_not_found
import id.homebase.resources.connections_circle_not_grantable
import kotlin.uuid.Uuid
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/**
 * The one place a connection request is sent from. Sending counts as the review, so once the
 * recipient resolves the sheet asks the review's own question with the review's own content.
 */
@Composable
fun ConnectionRequestSheet(
    viewModel: ConnectRequestViewModel,
    snackbarHostState: SnackbarHostState,
    onNavigateToConversation: ((Uuid) -> Unit)? = null,
) {
    val circles by koinInject<ConnectionService>().circles.collectAsStateWithLifecycle()
    val groups = remember(circles) { circles.reviewCircleGroups() }

    ConnectRequestBottomSheet(
        viewModel = viewModel,
        snackbarHostState = snackbarHostState,
        onNavigateToConversation = onNavigateToConversation,
        review = { identity, state, onAction ->
            ReviewConnectionContent(
                displayName = identity.displayNameOrDomain(),
                odinId = identity.odinId.domainName,
                avatar = null,
                introducedBy = null,
                connectedAtMs = null,
                groups = groups,
                alreadyHeldCircleIds = emptySet(),
                isSubmitting = state.isSending,
                errorText = state.circleError?.let {
                    stringResource(
                        when (it) {
                            ConnectCircleError.NotGrantable -> MR.string.connections_circle_not_grantable
                            ConnectCircleError.NotFound -> MR.string.connections_circle_not_found
                        }
                    )
                },
                onSubmit = { onAction(ConnectRequestAction.SendClicked(it)) },
                showIdentity = false,
                secondaryAction = {
                    TextButton(
                        onClick = { onAction(ConnectRequestAction.CloseDialog) },
                        enabled = !state.isSending,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(MR.string.cancel))
                    }
                },
            )
        },
    )
}
