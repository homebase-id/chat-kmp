package id.homebase.core.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.ClientException
import id.homebase.api.client.OdinClientErrorCode
import id.homebase.api.client.auth.OwnerSessionRepository
import id.homebase.api.client.connections.AutoConnectOutcome
import id.homebase.api.client.connections.ConnectionRequestResult
import id.homebase.api.client.identity.PublicIdentity
import id.homebase.api.client.identity.PublicIdentityRepository
import id.homebase.api.common.OdinId
import id.homebase.chat.services.ChatMessageSenderService
import id.homebase.chat.services.StatusMessage
import id.homebase.chat.services.StatusMessageData
import id.homebase.chat.services.convo.ConversationService
import id.homebase.chat.services.convo.contact.ConnectionService
import id.homebase.chat.services.requests.CirclesRefusedException
import id.homebase.chat.services.requests.ConnectionRequestService
import id.homebase.chat.services.requests.RefusedCircles
import id.homebase.core.ui.screens.contactbook.ReviewCircleGroups
import id.homebase.core.ui.screens.contactbook.reviewCircleGroups
import id.homebase.core.ui.screens.contactbook.toCircleUuids
import id.homebase.resources.MR
import id.homebase.resources.auto_connect_blocked
import id.homebase.resources.auto_connect_failed_generic
import id.homebase.resources.auto_connect_invalid_request
import id.homebase.resources.auto_connect_invalid_request_with_detail
import id.homebase.resources.auto_connect_recipient_not_configured
import id.homebase.resources.auto_connect_recipient_rejected
import id.homebase.resources.auto_connect_recipient_requires_upgrade
import id.homebase.resources.auto_connect_recipient_unreachable
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid
import org.jetbrains.compose.resources.StringResource

class ConnectRequestViewModel(
    private val connectionRequestService: ConnectionRequestService,
    private val publicIdentityRepository: PublicIdentityRepository,
    private val ownerSessionRepository: OwnerSessionRepository,
    private val conversationService: ConversationService,
    private val chatMessageSenderService: ChatMessageSenderService,
    connectionService: ConnectionService,
) : ViewModel() {

    private val _state = MutableStateFlow(ConnectRequestState())
    val state: StateFlow<ConnectRequestState> = _state.asStateFlow()

    val reviewCircleGroups: StateFlow<ReviewCircleGroups> = connectionService.circles
        .map { it.reviewCircleGroups() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReviewCircleGroups())

    private var recipientResolveJob: Job? = null

    fun onAction(action: ConnectRequestAction) {
        when (action) {
            ConnectRequestAction.OpenDialog ->
                _state.update { it.copy(showDialog = true) }

            is ConnectRequestAction.OpenDialogWithRecipient -> {
                val recipient = action.odinId.domainName
                _state.update {
                    it.copy(
                        showDialog = true,
                        recipient = recipient,
                        message = "",
                    )
                }
                startRecipientResolution(recipient)
            }

            ConnectRequestAction.CloseDialog -> {
                recipientResolveJob?.cancel()
                _state.update { it.closed() }
            }

            is ConnectRequestAction.RecipientChanged -> {
                _state.update { it.copy(recipient = action.value, circleError = null) }
                startRecipientResolution(action.value)
            }

            is ConnectRequestAction.MessageChanged ->
                _state.update { it.copy(message = action.value) }

            is ConnectRequestAction.SendClicked -> sendRequest(action.circleIds)

            ConnectRequestAction.DismissAlreadySentDialog ->
                _state.update { it.copy(alreadySentRecipient = null) }

            ConnectRequestAction.OpenOwnerConsoleClicked -> {
                val owner = ownerSessionRepository.user.value ?: return
                _state.update {
                    it.copy(
                        alreadySentRecipient = null,
                        uiEvent = ConnectRequestEvent.OpenUrl(
                            "https://${owner.odinId.domainName}/owner/connections"
                        ),
                    )
                }
            }

            ConnectRequestAction.EventConsumed ->
                _state.update { it.copy(uiEvent = null) }
        }
    }

    /**
     * Creates (or reuses) the 1:1 conversation with [recipient] so the sender can jump into it
     * before the connection request is accepted. The conversation file and status message are
     * enqueued for transit delivery to [recipient] — while they remain un-connected the server
     * retries until acceptance flips them to connected, at which point they sync through.
     *
     * Only posts the "ConversationStarted" status when the conversation file is freshly
     * written; a revive of a previously-deleted thread is intentionally silent so the sender
     * doesn't see a spurious "You started the conversation" entry above existing history.
     *
     * Returns the conversation id on success, or null if creation failed (logged, not
     * surfaced — the connection request itself already succeeded and shouldn't be rolled back).
     */
    private suspend fun startConversationWithRecipient(recipient: OdinId): Uuid? {
        return try {
            val result = conversationService.createConversation(
                recipients = listOf(recipient),
                title = null,
                payloadBundle = null,
            )

            if (result.wasNewlyCreated) {
                chatMessageSenderService.sendStatusMessage(
                    messageUniqueId = Uuid.random(),
                    conversationId = result.conversationId,
                    previousMessageUniqueId = result.conversationId,
                    statusMessage = StatusMessageData(
                        statusMessage = StatusMessage.ConversationStarted,
                        subject = null,
                    ),
                )
            }

            result.conversationId
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(e) { "Failed to start conversation with $recipient" }
            null
        }
    }

    private fun startRecipientResolution(rawValue: String) {
        recipientResolveJob?.cancel()
        val trimmed = rawValue.trim()

        if (trimmed.isEmpty()) {
            _state.update { it.copy(resolution = RecipientResolution.Idle) }
            return
        }
        if (!OdinId.isValid(trimmed)) {
            _state.update { it.copy(resolution = RecipientResolution.InvalidFormat) }
            return
        }

        _state.update { it.copy(resolution = RecipientResolution.Resolving) }
        recipientResolveJob = viewModelScope.launch {
            delay(450)
            val odinId = OdinId(trimmed)
            val identity = try {
                publicIdentityRepository.resolve(odinId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(e) { "Failed to resolve $odinId" }
                null
            }

            if (_state.value.recipient.trim() != trimmed) return@launch

            _state.update {
                val resolution = if (identity != null) {
                    RecipientResolution.Resolved(identity)
                } else {
                    RecipientResolution.NotFound
                }
                it.copy(resolution = resolution)
            }
        }
    }

    private fun sendRequest(circleIds: Set<String>) {
        val current = _state.value
        val recipientId = (current.resolution as? RecipientResolution.Resolved)?.identity?.odinId
            ?: return
        val message = current.message.trim().takeIf { it.isNotEmpty() }

        _state.update { it.copy(isSending = true, circleError = null) }
        viewModelScope.launch {
            try {
                val result = connectionRequestService.sendReviewed(
                    recipientId,
                    message,
                    circleIds.toCircleUuids(),
                )
                when (result.outcome) {
                    AutoConnectOutcome.Connected,
                    AutoConnectOutcome.AcceptedFromExistingIncoming,
                    AutoConnectOutcome.AlreadyConnected,
                    AutoConnectOutcome.PendingManualApproval -> {
                        val conversationId = startConversationWithRecipient(recipientId)
                        _state.update {
                            it.closed().copy(
                                uiEvent = conversationId
                                    ?.let { id -> ConnectRequestEvent.NavigateToConversation(id) }
                                    ?: ConnectRequestEvent.SendSuccess,
                            )
                        }
                    }

                    AutoConnectOutcome.OutgoingRequestAlreadyExists,
                    AutoConnectOutcome.DuplicateIntroductoryRequest ->
                        _state.update { it.closed().copy(alreadySentRecipient = recipientId) }

                    else -> failed(result.failureMessage(recipientId))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: CirclesRefusedException) {
                Logger.w(e) { "Send refused circles: ${e.reason}" }
                _state.update { it.copy(isSending = false, circleError = e.reason) }
            } catch (e: ClientException) {
                Logger.w(e) { "Connection request rejected by server: ${e.errorCode}" }
                when (e.errorCode) {
                    OdinClientErrorCode.ConnectionRequestAlreadySent ->
                        _state.update { it.closed().copy(alreadySentRecipient = recipientId) }
                    else -> failed(e.failureMessage(recipientId))
                }
            } catch (e: Exception) {
                Logger.e(e) { "Failed to send connection request" }
                failed(ConnectFailure(MR.string.auto_connect_failed_generic))
            }
        }
    }

    private fun failed(failure: ConnectFailure) {
        _state.update { it.copy(isSending = false, uiEvent = ConnectRequestEvent.SendError(failure)) }
    }
}

data class ConnectRequestState(
    val showDialog: Boolean = false,
    val recipient: String = "",
    val message: String = "",
    val resolution: RecipientResolution = RecipientResolution.Idle,
    val isSending: Boolean = false,
    val alreadySentRecipient: OdinId? = null,
    val circleError: RefusedCircles? = null,
    val uiEvent: ConnectRequestEvent? = null,
)

data class ConnectFailure(val res: StringResource, val args: List<Any> = emptyList())

private fun ConnectRequestState.closed() = copy(
    isSending = false,
    showDialog = false,
    recipient = "",
    message = "",
    resolution = RecipientResolution.Idle,
    circleError = null,
)

private fun ConnectionRequestResult.failureMessage(recipient: OdinId): ConnectFailure {
    val who = listOf(recipient.domainName)
    return when (outcome) {
        AutoConnectOutcome.Blocked -> ConnectFailure(MR.string.auto_connect_blocked, who)
        AutoConnectOutcome.RecipientUnreachable ->
            ConnectFailure(MR.string.auto_connect_recipient_unreachable, who)
        AutoConnectOutcome.RecipientRejected ->
            ConnectFailure(MR.string.auto_connect_recipient_rejected, who)
        AutoConnectOutcome.RecipientIdentityNotConfigured ->
            ConnectFailure(MR.string.auto_connect_recipient_not_configured, who)
        AutoConnectOutcome.RecipientRequiresUpgrade ->
            ConnectFailure(MR.string.auto_connect_recipient_requires_upgrade, who)
        AutoConnectOutcome.InvalidRequest -> detail
            ?.let { ConnectFailure(MR.string.auto_connect_invalid_request_with_detail, listOf(it)) }
            ?: ConnectFailure(MR.string.auto_connect_invalid_request)
        else -> ConnectFailure(MR.string.auto_connect_failed_generic)
    }
}

private fun ClientException.failureMessage(recipient: OdinId): ConnectFailure = when (errorCode) {
    OdinClientErrorCode.BlockedConnection ->
        ConnectFailure(MR.string.auto_connect_blocked, listOf(recipient.domainName))
    OdinClientErrorCode.ConnectionRequestToYourself ->
        ConnectFailure(MR.string.auto_connect_invalid_request)
    else -> ConnectFailure(MR.string.auto_connect_failed_generic)
}

sealed interface ConnectRequestAction {
    data object OpenDialog : ConnectRequestAction
    /** Open the dialog with the recipient field pre-populated (e.g. from a chat disclaimer
     *  where we already know who the other party is). */
    data class OpenDialogWithRecipient(val odinId: OdinId) : ConnectRequestAction
    data object CloseDialog : ConnectRequestAction
    data class RecipientChanged(val value: String) : ConnectRequestAction
    data class MessageChanged(val value: String) : ConnectRequestAction
    data class SendClicked(val circleIds: Set<String>) : ConnectRequestAction
    data object DismissAlreadySentDialog : ConnectRequestAction
    data object OpenOwnerConsoleClicked : ConnectRequestAction
    data object EventConsumed : ConnectRequestAction
}

sealed interface RecipientResolution {
    data object Idle : RecipientResolution
    data object InvalidFormat : RecipientResolution
    data object Resolving : RecipientResolution
    data class Resolved(val identity: PublicIdentity) : RecipientResolution
    data object NotFound : RecipientResolution
}

sealed interface ConnectRequestEvent {
    data object SendSuccess : ConnectRequestEvent
    data class SendError(val failure: ConnectFailure) : ConnectRequestEvent
    data class OpenUrl(val url: String) : ConnectRequestEvent
    data class NavigateToConversation(val conversationId: Uuid) : ConnectRequestEvent
}