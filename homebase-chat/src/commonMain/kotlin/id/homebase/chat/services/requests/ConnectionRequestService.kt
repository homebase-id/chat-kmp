package id.homebase.chat.services.requests

import co.touchlab.kermit.Logger
import id.homebase.api.client.connections.AutoConnectOutcome
import id.homebase.api.client.connections.ConnectionRequestResult
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
import id.homebase.chat.services.convo.contact.ConnectionCacheRepository
import id.homebase.chat.services.convo.contact.ConnectionService
import id.homebase.chat.services.convo.contact.DriveContactService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

class ConnectionRequestService(
    private val connectionRequestProvider: ConnectionRequestProvider,
    private val driveContactService: DriveContactService,
    private val connectionService: ConnectionService,
    private val eventBus: EventBus,
    private val scope: CoroutineScope,
    private val cache: ConnectionCacheRepository,
) {
    private val _incomingRequests =
        MutableStateFlow<List<IncomingConnectionRequestUiModel>>(emptyList())
    private val _outgoingRequests =
        MutableStateFlow<List<OutgoingConnectionRequestUiModel>>(emptyList())
    private val _isLoaded = MutableStateFlow(false)

    val incomingRequests: StateFlow<List<IncomingConnectionRequestUiModel>> =
        _incomingRequests.asStateFlow()
    val outgoingRequests: StateFlow<List<OutgoingConnectionRequestUiModel>> =
        _outgoingRequests.asStateFlow()

    /** True once we've loaded pending-request data (either from the cache or from the
     *  network). Consumers combine this with [ConnectionService.connections]'s isLoaded
     *  flag to decide whether to show "Unknown" vs. "Not connected" in the UI. */
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    // One-shot — prevents the AppModule preload and the ConversationListViewModel
    // init from each running hydrate+refresh on cold boot. WS-reconnect refreshes
    // go through the BackendEvent.ConnectionOnline handler in init (calls
    // launchRefresh() below), not through start() — this flag does not block them.
    private var started = false

    // Coalesces the two automatic refresh triggers (start()'s post-hydrate path
    // and BackendEvent.ConnectionOnline). On cold boot they fire ~ms apart and
    // would otherwise both launch the same incoming + outgoing HTTP fetches.
    // Direct refresh() calls (user actions, CircleNetworkEvents) bypass this
    // guard so they always re-fetch.
    private var refreshJob: Job? = null

    private fun launchRefresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = scope.launch { refresh() }
    }

    fun start() {
        if (started) return
        started = true
        scope.launch {
            hydrateFromCache()
            launchRefresh()
        }
    }

    init {
        scope.launch {
            eventBus.events.collect { event ->
                // Never do blocking IO inside a SharedFlow collect body: on partial
                // connectivity `refresh()` hangs in an HTTP call, which parks the
                // 11-slot EventBus buffer and cascades to stall the chat Send path
                // (OutboxSync.tryEnqueue emits ItemEnqueued on the same bus).
                when (event) {
                    is BackendEvent.CircleNetworkEvent.ConnectionRequestReceived -> {
                        scope.launch {
                            markIncomingOptimistically(event.sender)
                            refresh()
                        }
                    }
                    is BackendEvent.CircleNetworkEvent.ConnectionRequestAccepted -> {
                        // This outgoing request was accepted — drop it from the pending list
                        scope.launch {
                            removeFromOutgoing(event.acceptedBy)
                            refresh()
                        }
                    }
                    is BackendEvent.CircleNetworkEvent.ConnectionRequestFinalized -> {
                        // The incoming request we accepted is now finalized — clear it.
                        scope.launch {
                            removeFromIncoming(event.identity)
                            refresh()
                        }
                    }
                    is BackendEvent.ConnectionOnline -> launchRefresh()
                    else -> {}
                }
            }
        }
    }

    private suspend fun hydrateFromCache() {
        try {
            val cachedIncoming = cache.hydrateIncomingRequests()
            val cachedOutgoing = cache.hydrateOutgoingRequests()
            if (cachedIncoming == null && cachedOutgoing == null) return
            cachedIncoming?.let { incoming ->
                if (_incomingRequests.value.isEmpty()) _incomingRequests.value = incoming
            }
            cachedOutgoing?.let { outgoing ->
                if (_outgoingRequests.value.isEmpty()) _outgoingRequests.value = outgoing
            }
            _isLoaded.value = true
            Logger.d(tag = "ConnectionRequestService") {
                "hydrated cache incoming=${cachedIncoming?.size ?: 0} outgoing=${cachedOutgoing?.size ?: 0}"
            }
        } catch (e: Exception) {
            Logger.w(e) { "ConnectionRequestService: cache hydration failed" }
        }
    }

    private suspend fun refresh() {
        val incoming = fetchIncomingRequests()
        val outgoing = fetchOutgoingRequests()
        val incomingOk = incoming != null
        val outgoingOk = outgoing != null
        Logger.d(tag = "ConnectionRequestService") {
            "refresh loaded incoming=${incoming?.size} outgoing=${outgoing?.size} " +
                    "outgoingRecipients=${outgoing?.map { it.recipientOdinId }}"
        }
        if (incoming != null) {
            _incomingRequests.update { incoming }
            runCatching {
                cache.persistIncomingRequests(incoming.map { it.senderOdinId })
            }.onFailure { Logger.w(it) { "ConnectionRequestService: cache persist incoming failed" } }
        }
        if (outgoing != null) {
            _outgoingRequests.update { outgoing }
            runCatching {
                cache.persistOutgoingRequests(outgoing.map { it.recipientOdinId })
            }.onFailure { Logger.w(it) { "ConnectionRequestService: cache persist outgoing failed" } }
        }
        if (incomingOk || outgoingOk) _isLoaded.value = true
    }

    /** Returns null on network failure so we don't clobber StateFlow/cache with empty lists
     *  when the user is offline. */
    suspend fun fetchIncomingRequests(): List<IncomingConnectionRequestUiModel>? {
        return try {
            val incomingRequests = connectionRequestProvider.getIncomingRequests(
                pageNumber = 1,
                pageSize = 1000
            )

            incomingRequests.results.map { mapToIncomingModel(it) }

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(e) { "Failed to fetch incoming requests" }
            null
        }
    }

    suspend fun fetchOutgoingRequests(): List<OutgoingConnectionRequestUiModel>? {
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
            Logger.w(e) { "Failed to fetch outgoing requests" }
            null
        }
    }

    /**
     * Sends a connection request and immediately refreshes the outgoing-requests list so the
     * list UI can reflect the new pending state without waiting for a websocket event (no
     * server event fires for our own outbound send).
     */
    suspend fun sendConnectionRequest(header: ConnectionRequestHeader) {
        connectionRequestProvider.sendConnectionRequest(header)
        markOutgoingOptimistically(header.recipient)
        refresh()
    }

    /**
     * App-origin auto-connect: the server may fully establish the ICR in a single round trip
     * (if the recipient auto-accepts). Applies the right local-state side effects for each
     * outcome so the UI updates immediately:
     *  - Connected / AcceptedFromExistingIncoming: drop from outgoing (if present) and refresh
     *    both pending-request and connected-identity lists.
     *  - AlreadyConnected: refresh connected-identity list (cheap, keeps UI in sync).
     *  - PendingManualApproval / OutgoingRequestAlreadyExists / DuplicateIntroductoryRequest:
     *    show the outgoing request optimistically.
     *  - All other outcomes (Blocked, Rejected, Unreachable, InvalidRequest, Failed, Unknown):
     *    no local state change — the caller decides how to surface them.
     *
     * Transport/auth failures propagate as exceptions; they are never returned as an outcome.
     */
    suspend fun autoConnect(header: ConnectionRequestHeader): ConnectionRequestResult {
        val result = connectionRequestProvider.autoConnect(header)
        when (result.outcome) {
            AutoConnectOutcome.Connected,
            AutoConnectOutcome.AcceptedFromExistingIncoming -> {
                removeFromOutgoing(header.recipient)
                refresh()
                connectionService.refresh()
                driveContactService.saveContactForOdinId(header.recipient)
            }
            AutoConnectOutcome.AlreadyConnected -> {
                connectionService.refresh()
                driveContactService.saveContactForOdinId(header.recipient)
            }
            AutoConnectOutcome.PendingManualApproval -> {
                markOutgoingOptimistically(header.recipient)
                refresh()
                // Save contact so they appear in the contact list immediately — matches
                // the legacy sendConnectionRequest flow, which saved on HTTP-200.
                driveContactService.saveContactForOdinId(header.recipient)
            }
            AutoConnectOutcome.OutgoingRequestAlreadyExists,
            AutoConnectOutcome.DuplicateIntroductoryRequest -> {
                markOutgoingOptimistically(header.recipient)
                refresh()
            }
            AutoConnectOutcome.Blocked,
            AutoConnectOutcome.RecipientUnreachable,
            AutoConnectOutcome.RecipientRejected,
            AutoConnectOutcome.RecipientIdentityNotConfigured,
            AutoConnectOutcome.RecipientRequiresUpgrade,
            AutoConnectOutcome.InvalidRequest,
            AutoConnectOutcome.Failed,
            AutoConnectOutcome.Unknown -> Unit
        }
        return result
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
        removeFromIncoming(senderId)
        refresh()
        connectionService.refresh()
    }

    private suspend fun markIncomingOptimistically(sender: OdinId) {
        _incomingRequests.update { current ->
            if (current.any { it.senderOdinId == sender }) current
            else current + IncomingConnectionRequestUiModel(
                senderName = "TODO $sender",
                senderOdinId = sender,
                receivedTimestampMilliseconds = UnixTimeUtc(
                    Clock.System.now().toEpochMilliseconds()
                ),
            )
        }
        runCatching {
            cache.upsert(sender, ConnectionCacheRepository.STATUS_INCOMING_PENDING)
        }.onFailure { Logger.w(it) { "ConnectionRequestService: cache upsert incoming failed" } }
    }

    private suspend fun markOutgoingOptimistically(recipient: OdinId) {
        _outgoingRequests.update { current ->
            if (current.any { it.recipientOdinId == recipient }) current
            else current + OutgoingConnectionRequestUiModel(
                recipientName = "TODO ${recipient.domainName}",
                recipientOdinId = recipient,
                message = null,
                introducerOdinId = null,
                receivedTimestampMilliseconds = UnixTimeUtc(
                    Clock.System.now().toEpochMilliseconds()
                ),
            )
        }
        runCatching {
            cache.upsert(recipient, ConnectionCacheRepository.STATUS_OUTGOING_PENDING)
        }.onFailure { Logger.w(it) { "ConnectionRequestService: cache upsert outgoing failed" } }
    }

    private suspend fun removeFromIncoming(sender: OdinId) {
        _incomingRequests.update { current -> current.filterNot { it.senderOdinId == sender } }
        runCatching { cache.remove(sender) }
            .onFailure { Logger.w(it) { "ConnectionRequestService: cache remove incoming failed" } }
    }

    private suspend fun removeFromOutgoing(recipient: OdinId) {
        _outgoingRequests.update { current -> current.filterNot { it.recipientOdinId == recipient } }
        runCatching { cache.remove(recipient) }
            .onFailure { Logger.w(it) { "ConnectionRequestService: cache remove outgoing failed" } }
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
