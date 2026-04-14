package id.homebase.core.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.ClientException
import id.homebase.api.client.OdinClientErrorCode
import id.homebase.api.client.auth.OwnerSessionRepository
import id.homebase.api.client.connections.ConnectionRequestHeader
import id.homebase.api.client.connections.ConnectionRequestProvider
import id.homebase.api.client.identity.PublicIdentity
import id.homebase.api.client.identity.PublicIdentityRepository
import id.homebase.api.common.OdinId
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid

class ConnectRequestViewModel(
    private val connectionRequestProvider: ConnectionRequestProvider,
    private val publicIdentityRepository: PublicIdentityRepository,
    private val ownerSessionRepository: OwnerSessionRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ConnectRequestState())
    val state: StateFlow<ConnectRequestState> = _state.asStateFlow()

    private var recipientResolveJob: Job? = null

    fun onAction(action: ConnectRequestAction) {
        when (action) {
            ConnectRequestAction.OpenDialog ->
                _state.update { it.copy(showDialog = true) }

            ConnectRequestAction.CloseDialog -> {
                recipientResolveJob?.cancel()
                _state.update {
                    it.copy(
                        showDialog = false,
                        recipient = "",
                        message = "",
                        resolution = RecipientResolution.Idle,
                    )
                }
            }

            is ConnectRequestAction.RecipientChanged -> {
                _state.update { it.copy(recipient = action.value) }
                startRecipientResolution(action.value)
            }

            is ConnectRequestAction.MessageChanged ->
                _state.update { it.copy(message = action.value) }

            ConnectRequestAction.SendClicked -> sendRequest()

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

    private fun sendRequest() {
        val current = _state.value
        val recipient = current.recipient.trim()
        if (recipient.isBlank()) {
            _state.update { it.copy(uiEvent = ConnectRequestEvent.SendError("Recipient is required")) }
            return
        }
        if (!OdinId.isValid(recipient)) {
            _state.update { it.copy(uiEvent = ConnectRequestEvent.SendError("Recipient is not a valid OdinId")) }
            return
        }

        val header = ConnectionRequestHeader(
            id = Uuid.random(),
            recipient = OdinId(recipient),
            message = current.message.trim().takeIf { it.isNotEmpty() },
            introducerOdinId = null,
            connectionRequestOrigin = "identityOwnerApp",
        )

        _state.update { it.copy(isSending = true) }
        viewModelScope.launch {
            try {
                connectionRequestProvider.sendConnectionRequest(header)
                _state.update {
                    it.copy(
                        isSending = false,
                        showDialog = false,
                        recipient = "",
                        message = "",
                        resolution = RecipientResolution.Idle,
                        uiEvent = ConnectRequestEvent.SendSuccess,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ClientException) {
                Logger.e(e) { "Connection request rejected by server: ${e.errorCode}" }
                val title = e.problem?.title.orEmpty()
                val alreadySent = e.errorCode == OdinClientErrorCode.ConnectionRequestAlreadySent ||
                        title.contains("existing", ignoreCase = true) &&
                        title.contains("outgoing", ignoreCase = true)
                _state.update {
                    if (alreadySent) {
                        it.copy(
                            isSending = false,
                            showDialog = false,
                            recipient = "",
                            message = "",
                            resolution = RecipientResolution.Idle,
                            alreadySentRecipient = header.recipient,
                        )
                    } else {
                        it.copy(
                            isSending = false,
                            uiEvent = ConnectRequestEvent.SendError(
                                e.message ?: "Failed to send request"
                            ),
                        )
                    }
                }
            } catch (e: Exception) {
                Logger.e(e) { "Failed to send connection request" }
                _state.update {
                    it.copy(
                        isSending = false,
                        uiEvent = ConnectRequestEvent.SendError(e.message ?: "Failed to send request"),
                    )
                }
            }
        }
    }
}

data class ConnectRequestState(
    val showDialog: Boolean = false,
    val recipient: String = "",
    val message: String = "",
    val resolution: RecipientResolution = RecipientResolution.Idle,
    val isSending: Boolean = false,
    val alreadySentRecipient: OdinId? = null,
    val uiEvent: ConnectRequestEvent? = null,
)

sealed interface ConnectRequestAction {
    data object OpenDialog : ConnectRequestAction
    data object CloseDialog : ConnectRequestAction
    data class RecipientChanged(val value: String) : ConnectRequestAction
    data class MessageChanged(val value: String) : ConnectRequestAction
    data object SendClicked : ConnectRequestAction
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
    data class SendError(val message: String) : ConnectRequestEvent
    data class OpenUrl(val url: String) : ConnectRequestEvent
}