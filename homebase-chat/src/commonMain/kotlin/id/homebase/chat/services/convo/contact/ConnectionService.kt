package id.homebase.chat.services.convo.contact

import id.homebase.api.client.connections.CircleWithMembers
import id.homebase.api.client.connections.ConnectionNetworkProvider
import id.homebase.api.client.connections.ConnectionStatus
import id.homebase.api.client.connections.RedactedCircleDefinition
import id.homebase.api.client.connections.RedactedIdentityConnectionRegistration
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.OdinId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import co.touchlab.kermit.Logger

data class ConnectionState(
    val isLoaded: Boolean,
    val map: Map<OdinId, RedactedIdentityConnectionRegistration>
)

/**
 * Owner circles (including system circles) with their members, fetched alongside the
 * connection map on every [ConnectionService.refresh]. Powers circle-membership reads:
 * the contact-list "Confirmed" / "Introduced" pills (via the two system-circle ids) and
 * the contact-detail "circles this person is in" list. Not cached — it repopulates on the
 * next refresh, so on a cold start consumers fall back until the first refresh lands.
 */
data class CircleMembershipState(
    val isLoaded: Boolean = false,
    val circles: List<CircleWithMembers> = emptyList(),
) {
    /** Lowercased member domains of the circle whose id matches [circleId] (32-char N-format). */
    fun membersOf(circleId: String): Set<String> =
        circles.firstOrNull { it.circle.id.equals(circleId, ignoreCase = true) }
            ?.members?.map { it.domainName.lowercase() }?.toSet()
            ?: emptySet()

    /** Circle definitions the identity [odinId] is a member of. */
    fun circlesFor(odinId: String): List<RedactedCircleDefinition> {
        val domain = odinId.lowercase()
        return circles
            .filter { cwm -> cwm.members.any { it.domainName.lowercase() == domain } }
            .map { it.circle }
    }
}

class ConnectionService(
    private val provider: ConnectionNetworkProvider,
    private val eventBus: EventBus,
    private val scope: CoroutineScope,
    private val cache: ConnectionCacheRepository,
) {

    private val _connections =
        MutableStateFlow(ConnectionState(isLoaded = false, map = emptyMap()))

    val connections: StateFlow<ConnectionState> =
        _connections.asStateFlow()

    private val _circles = MutableStateFlow(CircleMembershipState())
    val circles: StateFlow<CircleMembershipState> = _circles.asStateFlow()

    // One-shot — prevents the AppModule preload and the ConversationListViewModel
    // init from each running hydrate+refresh on cold boot. WS-reconnect refreshes
    // go through the BackendEvent.ConnectionOnline handler in init (calls
    // launchRefresh() below), not through start() — this flag does not block them.
    private var started = false

    // Coalesces the two automatic refresh triggers (start()'s post-hydrate path
    // and BackendEvent.ConnectionOnline). On cold boot they fire ~500ms apart
    // and would otherwise both hit the network. Direct refresh() calls (user
    // actions, CircleNetworkEvents) bypass this guard so they always re-fetch.
    private var refreshJob: Job? = null

    private fun launchRefresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = scope.launch { refresh() }
    }

    init {
        // Keep the connected-identity map in sync with websocket events so downstream UI
        // (1:1 connection chips, conversation disclaimers) flips as soon as the server
        // reports an accepted/finalized connection — we don't wait for the next manual
        // refresh or app-foreground event.
        scope.launch {
            eventBus.events.collect { event ->
                // Never do blocking IO inside a SharedFlow collect body: refresh() does
                // HTTP calls that hang on partial connectivity, parking the 11-slot
                // EventBus buffer and cascading to stall the chat Send path.
                when (event) {
                    is BackendEvent.CircleNetworkEvent.ConnectionRequestAccepted -> {
                        scope.launch {
                            markConnectedOptimistically(event.acceptedBy)
                            refresh()
                        }
                    }
                    is BackendEvent.CircleNetworkEvent.ConnectionRequestFinalized -> {
                        scope.launch {
                            markConnectedOptimistically(event.identity)
                            refresh()
                        }
                    }
                    // When the websocket comes back after an offline window, reconcile
                    // against the server — covers the airplane-mode-off case.
                    is BackendEvent.ConnectionOnline -> launchRefresh()
                    // Logout: drop the previous identity's connection map.
                    is BackendEvent.SessionEnded -> reset()
                    else -> {}
                }
            }
        }
    }

    fun start() {
        if (started) return
        started = true
        scope.launch {
            hydrateFromCache()
            launchRefresh()
        }
    }

    /**
     * Logout: cancel any in-flight refresh and drop the connection map back to
     * the not-loaded state. Clearing `started` lets the next login's [start]
     * re-hydrate from cache and re-fetch. The init eventBus collector is left
     * running (it's app-scoped, single, and gates on the new session's events).
     */
    fun reset() {
        refreshJob?.cancel()
        refreshJob = null
        started = false
        _connections.value = ConnectionState(isLoaded = false, map = emptyMap())
        _circles.value = CircleMembershipState()
    }

    private suspend fun hydrateFromCache() {
        try {
            val hydrated = cache.hydrateConnections() ?: return
            _connections.update { current ->
                // Cache hydration is a non-authoritative fallback: only apply it if we
                // haven't already loaded fresh data from the network.
                if (current.isLoaded) current
                else ConnectionState(isLoaded = true, map = hydrated.connectionMap)
            }
            Logger.d { "ConnectionService hydrated ${hydrated.connectionMap.size} cached rows" }
        } catch (e: Exception) {
            Logger.w(e) { "ConnectionService cache hydration failed" }
        }
    }

    suspend fun refresh() {
        try {
            coroutineScope {
                Logger.d { "Fetching connected and blocked connections in parallel..." }
                val connectedDeferred = async { provider.getConnected(1000, null) }
                val blockedDeferred = async { provider.getBlocked(1000, null) }
                // Circles ride along on the same refresh. A failure here must not break the
                // connection map, so it is caught independently and leaves prior circles intact.
                val circlesDeferred = async {
                    runCatching { provider.getCirclesWithMembers(includeSystemCircle = true) }
                        .getOrElse { e ->
                            Logger.w(e) { "ConnectionService: getCirclesWithMembers failed" }
                            null
                        }
                }
                val connected = connectedDeferred.await()
                val blocked = blockedDeferred.await()
                Logger.d { "Loaded connections ${connected.results.size} connected, ${blocked.results.size} blocked" }
                _connections.value = ConnectionState(
                    isLoaded = true,
                    map = (connected.results + blocked.results).associateBy { it.odinId }
                )
                circlesDeferred.await()?.let { circles ->
                    _circles.value = CircleMembershipState(isLoaded = true, circles = circles)
                    // Verifies the Confirmed (bb2683fa…) / Auto (9e22b429…) system-circle ids
                    // actually come back here so the membership-driven pills are reliable.
                    Logger.d {
                        "ConnectionService circles: " +
                            circles.joinToString { "${it.circle.id}(${it.circle.name})=${it.members.size}" }
                    }
                }
                runCatching {
                    cache.persistConnections(
                        connected = connected.results.map { it.odinId },
                        blocked = blocked.results.map { it.odinId },
                    )
                }.onFailure { Logger.w(it) { "ConnectionService: cache persist failed" } }
            }
        } catch (e: Exception) {
            Logger.e(e) {
                "ConnectionService.refresh failed: ${e.message}"
            }
            // Leave any previously-loaded or cache-hydrated state in place — clobbering it
            // with an empty map would misleadingly flip every 1:1 chip to "Not connected"
            // on every network hiccup (including airplane mode on a cold start).
        }
    }

    private suspend fun markConnectedOptimistically(odinId: OdinId) {
        _connections.update { current ->
            val synthesized = RedactedIdentityConnectionRegistration(
                odinId = odinId,
                status = ConnectionStatus.Connected,
                accessGrant = null,
                created = 0L,
                lastUpdated = 0L,
                originalContactData = null,
                introducerOdinId = null,
                connectionRequestOrigin = id.homebase.api.client.connections.ConnectionRequestOrigin.None,
                hasVerificationHash = false,
                rku = false,
            )
            ConnectionState(
                isLoaded = true,
                map = current.map + (odinId to synthesized),
            )
        }
        runCatching {
            cache.upsert(odinId, ConnectionCacheRepository.STATUS_CONNECTED)
        }.onFailure { Logger.w(it) { "ConnectionService: optimistic cache upsert failed" } }
    }

    fun get(odinId: OdinId): RedactedIdentityConnectionRegistration? {
        return _connections.value.map[odinId]
    }
}
