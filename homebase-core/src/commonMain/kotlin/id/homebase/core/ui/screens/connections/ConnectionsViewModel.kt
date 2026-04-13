package id.homebase.core.ui.screens.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.ClientException
import id.homebase.api.client.OdinClientErrorCode
import id.homebase.api.client.auth.OwnerSessionRepository
import id.homebase.api.client.connections.ConnectionRequestHeader
import id.homebase.api.client.connections.ConnectionRequestProvider
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

class ConnectionsViewModel(
    private val connectionRequestProvider: ConnectionRequestProvider,
    private val publicIdentityRepository: PublicIdentityRepository,
    private val ownerSessionRepository: OwnerSessionRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConnectionsUiState())
    val uiState: StateFlow<ConnectionsUiState> = _uiState.asStateFlow()

    private var recipientResolveJob: Job? = null

    init {
        refresh()
    }

    fun onAction(action: ConnectionsUiAction) {
        when (action) {
            ConnectionsUiAction.Refresh -> refresh()

            is ConnectionsUiAction.SetShowIntroductionOutgoing ->
                _uiState.update { it.copy(showIntroductionOutgoing = action.show) }

            ConnectionsUiAction.OpenComposeDialog ->
                _uiState.update { it.copy(showComposeDialog = true) }

            ConnectionsUiAction.CloseComposeDialog -> {
                recipientResolveJob?.cancel()
                _uiState.update {
                    it.copy(
                        showComposeDialog = false,
                        composeRecipient = "",
                        composeMessage = "",
                        recipientResolution = RecipientResolution.Idle,
                    )
                }
            }

            is ConnectionsUiAction.ComposeRecipientChanged -> {
                _uiState.update { it.copy(composeRecipient = action.value) }
                startRecipientResolution(action.value)
            }

            is ConnectionsUiAction.ComposeMessageChanged ->
                _uiState.update { it.copy(composeMessage = action.value) }

            ConnectionsUiAction.SendClicked -> sendRequest()

            is ConnectionsUiAction.AcceptIncoming -> acceptIncoming(action.senderOdinId)
            is ConnectionsUiAction.RejectIncoming -> rejectIncoming(action.senderOdinId)
            is ConnectionsUiAction.CancelOutgoing -> cancelOutgoing(action.recipient)

            ConnectionsUiAction.DismissAlreadySentDialog ->
                _uiState.update { it.copy(alreadySentRecipient = null) }

            ConnectionsUiAction.OpenOwnerConsoleClicked -> {
                val owner = ownerSessionRepository.user.value ?: return
                _uiState.update { it.copy(alreadySentRecipient = null) }
                _uiState.update {
                    it.copy(uiEvent = ConnectionsUiEvent.OpenUrl(
                        "https://${owner.odinId.domainName}/owner/connections"
                    ))
                }
            }
        }
    }

    fun eventConsumed() {
        _uiState.update { it.copy(uiEvent = null) }
    }

    private fun refresh() {
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            try {
                val incoming = connectionRequestProvider.getIncomingRequests(1, 1000)
                val outgoing = connectionRequestProvider.getOutgoingRequests(1, 1000)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        incomingRequests = incoming.results,
                        outgoingRequests = outgoing.results,
                    )
                }
                val needed = buildSet {
                    incoming.results.forEach { add(it.senderOdinId) }
                    outgoing.results.forEach { add(it.recipient) }
                }
                loadIdentities(needed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(e) { "Failed to load connection requests" }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        uiEvent = ConnectionsUiEvent.ActionError(e.message ?: "Failed to load requests"),
                    )
                }
            }
        }
    }

    private fun startRecipientResolution(rawValue: String) {
        recipientResolveJob?.cancel()
        val trimmed = rawValue.trim()

        if (trimmed.isEmpty()) {
            _uiState.update { it.copy(recipientResolution = RecipientResolution.Idle) }
            return
        }
        if (!OdinId.isValid(trimmed)) {
            _uiState.update { it.copy(recipientResolution = RecipientResolution.InvalidFormat) }
            return
        }

        _uiState.update { it.copy(recipientResolution = RecipientResolution.Resolving) }
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

            // Bail out if the user kept typing after we fired.
            if (_uiState.value.composeRecipient.trim() != trimmed) return@launch

            _uiState.update {
                val resolution = if (identity != null) {
                    RecipientResolution.Resolved(identity)
                } else {
                    RecipientResolution.NotFound
                }
                val identities = if (identity != null) it.identities + (odinId to identity) else it.identities
                it.copy(recipientResolution = resolution, identities = identities)
            }
        }
    }

    private fun loadIdentities(odinIds: Set<OdinId>) {
        val missing = odinIds - _uiState.value.identities.keys
        missing.forEach { odinId ->
            viewModelScope.launch {
                try {
                    val identity = publicIdentityRepository.get(odinId)
                    _uiState.update { it.copy(identities = it.identities + (odinId to identity)) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.w(e) { "Failed to load public identity for $odinId" }
                }
            }
        }
    }

    private fun sendRequest() {
        val state = _uiState.value
        val recipient = state.composeRecipient.trim()
        if (recipient.isBlank()) {
            _uiState.update { it.copy(uiEvent = ConnectionsUiEvent.SendError("Recipient is required")) }
            return
        }
        if (!OdinId.isValid(recipient)) {
            _uiState.update { it.copy(uiEvent = ConnectionsUiEvent.SendError("Recipient is not a valid OdinId")) }
            return
        }

        val header = ConnectionRequestHeader(
            id = Uuid.random(),
            recipient = OdinId(recipient),
            message = state.composeMessage.trim().takeIf { it.isNotEmpty() },
            introducerOdinId = null,
            connectionRequestOrigin = "identityOwnerApp",
        )

        _uiState.update { it.copy(isSending = true) }
        viewModelScope.launch {
            try {
                connectionRequestProvider.sendConnectionRequest(header)
                _uiState.update {
                    it.copy(
                        isSending = false,
                        showComposeDialog = false,
                        composeRecipient = "",
                        composeMessage = "",
                        recipientResolution = RecipientResolution.Idle,
                        uiEvent = ConnectionsUiEvent.SendSuccess,
                    )
                }
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ClientException) {
                Logger.e(e) { "Connection request rejected by server: ${e.errorCode}" }
                val title = e.problem?.title.orEmpty()
                val alreadySent = e.errorCode == OdinClientErrorCode.ConnectionRequestAlreadySent ||
                        title.contains("existing", ignoreCase = true) &&
                        title.contains("outgoing", ignoreCase = true)
                _uiState.update {
                    if (alreadySent) {
                        it.copy(
                            isSending = false,
                            showComposeDialog = false,
                            composeRecipient = "",
                            composeMessage = "",
                            recipientResolution = RecipientResolution.Idle,
                            alreadySentRecipient = header.recipient,
                        )
                    } else {
                        it.copy(
                            isSending = false,
                            uiEvent = ConnectionsUiEvent.SendError(
                                e.message ?: "Failed to send request"
                            ),
                        )
                    }
                }
            } catch (e: Exception) {
                Logger.e(e) { "Failed to send connection request" }
                _uiState.update {
                    it.copy(
                        isSending = false,
                        uiEvent = ConnectionsUiEvent.SendError(e.message ?: "Failed to send request"),
                    )
                }
            }
        }
    }

    private fun acceptIncoming(senderOdinId: OdinId) {
        runItemAction(senderOdinId, "accept") {
            connectionRequestProvider.acceptIncomingRequest(senderOdinId)
        }
    }

    private fun rejectIncoming(senderOdinId: OdinId) {
        runItemAction(senderOdinId, "reject") {
            connectionRequestProvider.rejectIncomingRequest(senderOdinId)
        }
    }

    private fun cancelOutgoing(recipient: OdinId) {
        runItemAction(recipient, "cancel") {
            connectionRequestProvider.cancelOutgoingRequest(recipient)
        }
    }

    private fun runItemAction(
        odinId: OdinId,
        actionName: String,
        block: suspend () -> Unit,
    ) {
        _uiState.update { it.copy(pendingOdinIds = it.pendingOdinIds + odinId) }
        viewModelScope.launch {
            try {
                block()
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(e) { "Failed to $actionName connection request for $odinId" }
                _uiState.update {
                    it.copy(
                        uiEvent = ConnectionsUiEvent.ActionError(
                            e.message ?: "Failed to $actionName request"
                        ),
                    )
                }
            } finally {
                _uiState.update { it.copy(pendingOdinIds = it.pendingOdinIds - odinId) }
            }
        }
    }
}
