package id.homebase.chat.services.requests

import co.touchlab.kermit.Logger
import id.homebase.api.client.connections.ConnectionRequestHeader
import id.homebase.api.client.connections.ConnectionRequestProvider
import id.homebase.api.client.connections.IncomingConnectionRequestResponse
import id.homebase.api.client.connections.OutgoingConnectionRequestResponse
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.OdinId
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.chat.data.IncomingConnectionRequestUiModel
import id.homebase.chat.data.OutgoingConnectionRequestUiModel
import id.homebase.chat.services.convo.contact.ConnectionService
import id.homebase.chat.services.convo.contact.DriveContactService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

class ConnectionRequestService(
    private val connectionRequestProvider: ConnectionRequestProvider,
    private val driveContactService: DriveContactService,
    private val connectionService: ConnectionService,
    private val eventBus: EventBus,
    private val scope: CoroutineScope
) {
    private val _incomingRequests =
        MutableStateFlow<List<IncomingConnectionRequestUiModel>>(emptyList())
    private val _outgoingRequests =
        MutableStateFlow<List<OutgoingConnectionRequestUiModel>>(emptyList())

    val incomingRequests: StateFlow<List<IncomingConnectionRequestUiModel>> =
        _incomingRequests.asStateFlow()
    val outgoingRequests: StateFlow<List<OutgoingConnectionRequestUiModel>> =
        _outgoingRequests.asStateFlow()

    fun start() {
        scope.launch { refresh() }
    }

    init {
        scope.launch {
            eventBus.events.collect { event ->
                if (event is BackendEvent.CircleNetworkEvent.ConnectionRequestReceived) {
//                    event.sender
                    refresh()
                }

                if (event is BackendEvent.CircleNetworkEvent.ConnectionRequestAccepted) {
//                    event.acceptedBy
                    refresh()
                }

                if (event is BackendEvent.CircleNetworkEvent.ConnectionRequestFinalized) {
//                    event.identity
                    refresh()
                }

//                if (event is BackendEvent.CircleNetworkEvent.IntroductionAccepted) {
//                    refresh()
//                }
//
//                if (event is BackendEvent.CircleNetworkEvent.IntroductionsReceived) {
//                    refresh()
//                }
            }
        }
    }

    private suspend fun refresh() {
        val incoming = fetchIncomingRequests()
        val outgoing = fetchOutgoingRequests()
        Logger.d(tag = "ConnectionRequestService") {
            "refresh loaded incoming=${incoming.size} outgoing=${outgoing.size} " +
                    "outgoingRecipients=${outgoing.map { it.recipientOdinId }}"
        }
        _incomingRequests.update { incoming }
        _outgoingRequests.update { outgoing }
    }

    suspend fun fetchIncomingRequests(): List<IncomingConnectionRequestUiModel> {
        return try {

            val incomingRequests = connectionRequestProvider.getIncomingRequests(
                pageNumber = 1,
                pageSize = 1000
            )

            incomingRequests.results.map { mapToIncomingModel(it) }

        } catch (e: CancellationException) {
            // Never swallow coroutine cancellation
            throw e
        } catch (e: Exception) {
            // Log it properly
            println("Failed to fetch incoming requests: ${e.message}")
            emptyList() // or rethrow depending on your architecture
        }
    }

    suspend fun fetchOutgoingRequests(): List<OutgoingConnectionRequestUiModel> {
        return try {
            //TODO: Paging
            val outgoing = connectionRequestProvider.getOutgoingRequests(
                pageNumber = 1,
                pageSize = 1000
            )

            outgoing.results.map { mapToOutgoingModel(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("Failed to fetch outgoing requests: ${e.message}")
            emptyList()
        }
    }

    /**
     * Sends a connection request and immediately refreshes the outgoing-requests list so the
     * list UI can reflect the new pending state without waiting for a websocket event (no
     * server event fires for our own outbound send).
     */
    suspend fun sendConnectionRequest(header: ConnectionRequestHeader) {
        connectionRequestProvider.sendConnectionRequest(header)
        refresh()
    }

    /**
     * Accepts an incoming connection request, best-effort saves the sender as a contact, and
     * refreshes both the pending-request list and the connected-identities list so the list
     * UI flips "Invited" → (removed) and the conversation's 1:1 status flips to Connected
     * immediately. The server also emits a websocket event for both sides, but we don't want
     * the UI to wait on round-trip latency.
     */
    suspend fun acceptIncomingRequest(senderId: OdinId) {
        connectionRequestProvider.acceptIncomingRequest(senderId)
        driveContactService.saveContactForOdinId(senderId)
        refresh()
        connectionService.refresh()
    }

    fun mapToIncomingModel(serverResponse: IncomingConnectionRequestResponse): IncomingConnectionRequestUiModel {
        val ui =
            IncomingConnectionRequestUiModel(
                senderName = "TODO " + serverResponse.senderOdinId,
                senderOdinId = serverResponse.senderOdinId,
                receivedTimestampMilliseconds = UnixTimeUtc(serverResponse.receivedTimestampMilliseconds),
            )

        return ui
    }

    fun mapToOutgoingModel(serverResponse: OutgoingConnectionRequestResponse): OutgoingConnectionRequestUiModel {
        val ui =
            OutgoingConnectionRequestUiModel(
                recipientName = "TODO " + serverResponse.recipient.domainName,
                recipientOdinId = serverResponse.recipient,
                message = serverResponse.message,
                introducerOdinId = serverResponse.introducerOdinId,
                receivedTimestampMilliseconds = UnixTimeUtc(serverResponse.receivedTimestampMilliseconds),
//                contactData = TODO(),
//                senderOdinId = TODO(),
//                circleIds = TODO(),
//                connectionRequestOrigin = TODO(),
            )

        return ui
    }

}
