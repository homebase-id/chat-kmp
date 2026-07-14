package id.homebase.chat.services.convo.contact

import id.homebase.api.client.connections.CircleWithMembers
import id.homebase.api.client.connections.ConnectionNetworkProvider
import id.homebase.api.client.connections.ConnectionStatus
import id.homebase.api.client.connections.RedactedCircleDefinition
import id.homebase.api.client.connections.RedactedIdentityConnectionRegistration
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.OdinId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import co.touchlab.kermit.Logger
import kotlin.uuid.Uuid

/** Debounce window for push-driven refreshes — long enough to swallow a fan-out burst, short
 *  enough that the contact-detail / circles UI updates promptly after an external change. */
private const val REFRESH_DEBOUNCE_MS = 300L

/** How long [ConnectionService.findPendingMembers] waits for [ConnectionService.connections]/
 *  [ConnectionService.circles] to complete their first real load before reading them — bounds a
 *  cold-start caller against racing [ConnectionService.start]'s async hydrate+refresh. */
private const val CONNECTIONS_LOAD_WAIT_MS = 15_000L

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

    // Trailing-debounce for push-driven invalidation (ConnectionChanged / CircleDefinitionChanged).
    // Each event reschedules, so a burst (bulk circle edit, or the echo of our own mutation plus the
    // real change) collapses into a single refresh once the events go quiet.
    private var debouncedRefreshJob: Job? = null

    private fun launchRefresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = scope.launch { refresh() }
    }

    /** Coalesces a burst of push events into one refresh [REFRESH_DEBOUNCE_MS] after the last one. */
    private fun scheduleRefresh() {
        debouncedRefreshJob?.cancel()
        debouncedRefreshJob = scope.launch {
            delay(REFRESH_DEBOUNCE_MS)
            refresh()
        }
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
                    // Connection/circle state changed somewhere (another device, owner-console,
                    // server-side). These fan out to every session and echo our own mutations, so
                    // a single connection edit or a bulk circle change can arrive as a burst —
                    // collapse them into one re-fetch. refresh() already reloads connections AND
                    // circles, so both event kinds are covered by the same scheduled refresh.
                    is BackendEvent.CircleNetworkEvent.ConnectionChanged -> {
                        Logger.d { "ConnectionChanged: ${event.identity} ${event.change} ${event.circleId ?: ""}" }
                        scheduleRefresh()
                    }
                    is BackendEvent.CircleNetworkEvent.CircleDefinitionChanged -> {
                        Logger.d { "CircleDefinitionChanged: ${event.circleId} ${event.change}" }
                        scheduleRefresh()
                    }
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
        debouncedRefreshJob?.cancel()
        debouncedRefreshJob = null
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

    /**
     * Live (uncached) status read for [odinId] straight from the server — the only way to learn
     * whether a circle grant is still a sealed deposit (`accessGrant.pendingCircleIds`) rather
     * than a real [CircleWithMembers] entry, since there is no bulk "list pending" endpoint.
     */
    suspend fun getConnectionStatus(odinId: OdinId): RedactedIdentityConnectionRegistration? =
        provider.getConnectionStatus(odinId)

    /**
     * Grant [odinId] membership in [circleId]. May land as a real [CircleWithMembers] entry
     * immediately or as a sealed deposit (`pendingCircleIds` on their `/connections/status`) —
     * the caller decides how to represent that. Refreshes immediately after success so
     * [circles] reflects a landed grant without waiting on the debounced websocket refresh.
     */
    suspend fun addToCircle(circleId: Uuid, odinId: OdinId) {
        provider.addToCircle(circleId, odinId)
        refresh()
    }

    /** Revoke [odinId]'s membership in [circleId] — also drops any still-pending deposit. */
    suspend fun removeFromCircle(circleId: Uuid, odinId: OdinId) {
        provider.removeFromCircle(circleId, odinId)
        refresh()
    }

    /**
     * Live per-contact fan-out to find who currently has [circleId] sealed as a pending deposit
     * (`accessGrant.pendingCircleIds`) rather than a real member — there is no bulk "list
     * pending members of a circle" endpoint, so this is the only way to learn it, for ANY
     * circle. Never cached: every call re-derives the answer from the server. Scoped to
     * current, Connected identities that aren't already a real member of [circleId] (real
     * membership is cheap and authoritative from the already-loaded [circles] bulk read, so
     * there's no need to re-verify it here).
     *
     * Waits (bounded) for [connections]/[circles] to have completed at least one real load
     * before reading them. A caller invoked right on cold start — e.g. the Location dashboard's
     * resume-triggered check — otherwise races [start]'s async hydrate+refresh: [connections]
     * and [circles] still hold their empty initial `isLoaded = false` state, so every candidate
     * list comes back empty and this silently reports "nobody pending" for someone who
     * genuinely has a pending grant. That's not an exception, so nothing above this catches or
     * logs it — it just looks like an empty, correct answer. If the wait times out (e.g.
     * offline), proceeds best-effort against whatever's loaded rather than blocking forever.
     */
    suspend fun findPendingMembers(circleId: Uuid): List<OdinId> = coroutineScope {
        withTimeoutOrNull(CONNECTIONS_LOAD_WAIT_MS) {
            connections.first { it.isLoaded }
            circles.first { it.isLoaded }
        }

        val realMembers = circles.value.membersOf(circleId.toHexString())
        val candidates = connections.value.map.values
            .filter { it.status == ConnectionStatus.Connected }
            .map { it.odinId }
            .filterNot { realMembers.contains(it.domainName.lowercase()) }

        candidates.map { odinId ->
            async {
                val status = try {
                    getConnectionStatus(odinId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.w(e) { "ConnectionService: getConnectionStatus failed for $odinId while finding pending members of $circleId" }
                    null
                }
                odinId.takeIf { status?.accessGrant?.pendingCircleIds?.contains(circleId) == true }
            }
        }.awaitAll().filterNotNull()
    }
}
