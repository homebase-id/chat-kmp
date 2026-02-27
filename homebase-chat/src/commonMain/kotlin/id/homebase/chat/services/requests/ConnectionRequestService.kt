package id.homebase.chat.services.requests

import id.homebase.api.client.connections.ConnectionRequestProvider
import id.homebase.api.client.connections.IncomingConnectionRequestResponse
import id.homebase.api.client.connections.OutgoingConnectionRequestResponse
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.chat.data.IncomingConnectionRequestUiModel
import id.homebase.chat.data.OutgoingConnectionRequestUiModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

class ConnectionRequestService(
    private val connectionRequestProvider: ConnectionRequestProvider,
    private val eventBus: EventBus,
    scope: CoroutineScope
) {
    private val _incomingRequests =
        MutableStateFlow<List<IncomingConnectionRequestUiModel>>(emptyList())
    private val _outgoingRequests =
        MutableStateFlow<List<OutgoingConnectionRequestUiModel>>(emptyList())

    val incomingRequests: StateFlow<List<IncomingConnectionRequestUiModel>> =
        _incomingRequests.asStateFlow()
    val outgoingRequests: StateFlow<List<OutgoingConnectionRequestUiModel>> =
        _outgoingRequests.asStateFlow()

    suspend fun start() {
        refresh()
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
        _incomingRequests.update { fetchIncomingRequests() }
        _outgoingRequests.update { fetchOutgoingRequests() }
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
        //TODO: Paging
        val outgoing = connectionRequestProvider.getOutgoingRequests(
            pageNumber = 1,
            pageSize = 1000
        )

        val requests = outgoing.results.map { mapToOutgoingModel(it) }
        return requests
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
        if (serverResponse.direction != "outgoig") {
            throw IllegalStateException("this mapper only handles incoming requests")
        }

        val ui =
            OutgoingConnectionRequestUiModel(
                recipientName = "TODO " + serverResponse.recipient.domainName,
                recipientOdinId = serverResponse.senderOdinId,
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
