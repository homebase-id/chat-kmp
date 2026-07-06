package id.homebase.core.location.emergency

import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.OdinId
import id.homebase.core.ui.screens.location.model.LocationTrackHour
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One completed emergency retrieval: the peer's location hour-files pulled over the
 * temporal API, decoded and held as-is (day filtering/assembly happens at view time).
 */
data class EmergencyLocateResult(
    val peer: OdinId,
    val displayName: String,
    /** How far back the requester asked for, ms (window end = [fetchedAtMs]). */
    val windowMs: Long,
    val fetchedAtMs: Long,
    val hours: List<LocationTrackHour>,
)

/**
 * In-memory store of emergency-retrieved peer location history, keyed by peer domain.
 *
 * **Memory-only by decision** — never a drive, never the DB, never a file: persisting
 * someone else's location trail would outlive the owner's revocable temporal access
 * window. A retrieval exists only until logout/process death; re-opening the feature
 * re-fetches over the temporal API (which is also what keeps the owner's server-side
 * access notification honest). Last-fetch-wins per peer. Cleared in-stream on
 * [BackendEvent.SessionEnded] (collector runs for the app lifetime on the app scope,
 * mirroring LiveLocationReceiveStore) and from the post-auth bootstrap.
 */
class EmergencyLocateStore(
    private val eventBus: EventBus,
    scope: CoroutineScope,
) {
    private val _results = MutableStateFlow<Map<String, EmergencyLocateResult>>(emptyMap())
    val results: StateFlow<Map<String, EmergencyLocateResult>> = _results.asStateFlow()

    init {
        scope.launch {
            eventBus.events.collect { event ->
                if (event is BackendEvent.SessionEnded) reset()
            }
        }
    }

    fun put(result: EmergencyLocateResult) {
        _results.update { it + (result.peer.domainName to result) }
    }

    operator fun get(peerDomain: String): EmergencyLocateResult? = _results.value[peerDomain]

    /** Drop everything (logout / new identity). */
    fun reset() {
        _results.value = emptyMap()
    }
}
