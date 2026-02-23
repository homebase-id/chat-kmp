package id.homebase.chat.services.requests

import id.homebase.api.client.connections.ConnectionRequestProvider
import id.homebase.api.client.connections.ConnectionRequestResponse
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.time.UnixTimeUtc
import kotlinx.coroutines.CoroutineScope
import id.homebase.chat.data.IncomingConnectionRequestUiModel
import id.homebase.chat.data.OutgoingConnectionRequestUiModel
import id.homebase.chat.services.convo.ContactService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch


class ConnectionRequestService(
    private val connectionRequestProvider: ConnectionRequestProvider,
    private val eventBus: EventBus,
    private val contactService: ContactService,
    private val scope: CoroutineScope
) {
    private val _incomingRequests = MutableStateFlow<List<IncomingConnectionRequestUiModel>>(emptyList())
    private val _outgoingRequests = MutableStateFlow<List<OutgoingConnectionRequestUiModel>>(emptyList())

    val incomingRequests: StateFlow<List<IncomingConnectionRequestUiModel>> = _incomingRequests.asStateFlow()
    val outgoingRequests: StateFlow<List<OutgoingConnectionRequestUiModel>> = _outgoingRequests.asStateFlow()

    fun start() {
        scope.launch {
            refresh()
        }
    }

    init {
        scope.launch {
            eventBus.events.collect { event ->
                if (event is BackendEvent.CircleNetworkEvent.ConnectionRequestReceived) {
                    refresh()
                }

                if (event is BackendEvent.CircleNetworkEvent.ConnectionRequestAccepted) {
                    refresh()
                }

                if (event is BackendEvent.CircleNetworkEvent.ConnectionRequestFinalized) {
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
        _incomingRequests.value = fetchIncomingRequests()
        _outgoingRequests.value = fetchOutgoingRequests()
    }

    suspend fun fetchIncomingRequests(): List<IncomingConnectionRequestUiModel> {
        //TODO: Paging
        val incomingRequests = connectionRequestProvider.getRequests(
            "incoming",
            pageNumber = 1,
            pageSize = 1000
        )

        val requests = incomingRequests.results.map { mapToIncomingModel(it) }
        return requests
    }

    suspend fun fetchOutgoingRequests(): List<OutgoingConnectionRequestUiModel> {
        //TODO: Paging
        val outgoing = connectionRequestProvider.getRequests(
            "outgoing",
            pageNumber = 1,
            pageSize = 1000
        )

        val requests = outgoing.results.map { mapToOutgoingModel(it) }
        return requests
    }

    suspend fun mapToIncomingModel(serverResponse: ConnectionRequestResponse): IncomingConnectionRequestUiModel {
        if (serverResponse.direction != "incoming") {
            throw IllegalStateException("this mapper only handles incoming requests")
        }

        val ui =
            IncomingConnectionRequestUiModel(
                senderName = "TODO " + serverResponse.senderOdinId,
                senderOdinId = serverResponse.senderOdinId,
                message = serverResponse.message,
                introducerOdinId = serverResponse.introducerOdinId,
                receivedTimestampMilliseconds = UnixTimeUtc(serverResponse.receivedTimestampMilliseconds),
//                contactData = TODO(),
//                circleIds = TODO(),
//                connectionRequestOrigin = TODO(),
            )

        return ui
    }

    suspend fun mapToOutgoingModel(serverResponse: ConnectionRequestResponse): OutgoingConnectionRequestUiModel {
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
