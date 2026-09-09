package id.homebase.chat.services.convo.contact

import id.homebase.api.client.BlockingCircle
import id.homebase.api.client.ClientException
import id.homebase.api.client.OdinClientErrorCode
import id.homebase.api.client.blockingCircles
import id.homebase.api.client.connections.CircleWithMembers
import id.homebase.api.client.connections.PendingCircleMember
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import co.touchlab.kermit.Logger
import kotlin.uuid.Uuid

/** Debounce window for push-driven refreshes — long enough to swallow a fan-out burst, short
 *  enough that the contact-detail / circles UI updates promptly after an external change. */
private const val REFRESH_DEBOUNCE_MS = 300L

/**
 * A review could not be cleared because the contact still holds circles that keep them reviewed.
 * [circles] is the complete set the server named, never just the first one.
 */
class UnreviewBlockedException(
    val circles: List<BlockingCircle>,
    cause: Throwable? = null,
) : RuntimeException("Un-review blocked by ${circles.size} circle(s)", cause)

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
    /** Pending deposits on the circle whose id matches [circleId], never merged into members. */
    fun pendingMembersOf(circleId: String): List<PendingCircleMember> =
        circles.firstOrNull { it.circle.id.equals(circleId, ignoreCase = true) }
            ?.pendingMembers
            .orEmpty()

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
            processEnrollments()
        }
    }

    /**
     * Drain any circle enrolments queued for this app, over HTTP.
     *
     * The socket carries the same command and is sent on every connect, but it answers nothing —
     * so it cannot tell us whether an enrolment was actually claimed. This one returns counts.
     * Idempotent and a no-op without the permission, so running both is harmless.
     */
    suspend fun processEnrollments() {
        // Logged before the call as well as after: without this, silence is ambiguous — never
        // reached, still in flight, and threw all look the same.
        Logger.i { "ENROLL-DIAG calling POST /connections/enrollments/process" }
        val result = try {
            provider.processEnrollments()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 403 here is the answer, not an incident: it means this app token may not process
            // enrolments at all, which no count could have told us.
            Logger.w(e) { "ENROLL-DIAG failed (${e::class.simpleName})" }
            return
        }
        Logger.i {
            "ENROLL-DIAG processed connections=${result.connectionsProcessed} " +
                "enrollments=${result.enrollmentsCompleted}"
        }
        if (result.enrollmentsCompleted > 0) refresh()
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
                // TODO(pending-diagnosis): remove once the pending chip is confirmed working.
                // The list endpoint is the only source for accessGrant on this screen; if it
                // arrives null here, every pending chip and key warning downstream is dead.
                Logger.i {
                    val withGrant = connected.results.count { it.accessGrant != null }
                    val withPending = connected.results.count {
                        it.accessGrant?.pendingCircleIds?.isNotEmpty() == true
                    }
                    "PENDING-DIAG list: ${connected.results.size} connected, " +
                        "$withGrant with accessGrant, $withPending with pendingCircleIds"
                }
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
                    // TODO(pending-diagnosis): remove once the pending chip is confirmed working.
                    Logger.i {
                        "PENDING-DIAG circles: " + circles.joinToString {
                            "${it.circle.name}[grantOn=${it.circle.grantOn}," +
                                "designation=${it.circle.designation}," +
                                "members=${it.members.size},pending=${it.pendingMembers.size}]"
                        }
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
     * Record the owner's review of [odinId] and enrol [circleIds], in one server call.
     *
     * Additive and idempotent — nothing is revoked and a circle already held is a no-op, so a
     * failed call is safe to retry whole. An empty [circleIds] is the "chat only" outcome, not a
     * skipped review.
     *
     * Refreshes after, so the stamp and the new memberships land together rather than the state
     * flickering through a half-applied review while the debounced websocket refresh catches up.
     */
    suspend fun reviewConnection(odinId: OdinId, circleIds: List<Uuid> = emptyList()) {
        provider.reviewConnection(odinId, circleIds)
        refresh()
    }

    /**
     * Clear [odinId]'s review stamp, dropping them back to New.
     *
     * Withdraws the vouching only — every circle and grant they hold survives.
     *
     * @throws UnreviewBlockedException naming every circle standing in the way. The server returns
     *   the whole set in one response, so the caller can list them rather than have the user
     *   discover them one rejection at a time.
     */
    suspend fun clearConnectionReview(odinId: OdinId) {
        try {
            provider.clearConnectionReview(odinId)
        } catch (e: ClientException) {
            if (e.errorCode == OdinClientErrorCode.CannotClearReviewWhilePersonalCircleMember) {
                throw UnreviewBlockedException(e.problem?.blockingCircles().orEmpty(), e)
            }
            throw e
        }
        refresh()
    }
}
