package id.homebase.core.ui.screens.location.livelocation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.common.OdinId
import id.homebase.chat.services.convo.contact.ContactService
import id.homebase.chat.services.livelocation.LiveLocationReceiveStore
import id.homebase.core.location.LocationMapProvider
import id.homebase.core.location.LocationPreferences
import id.homebase.core.location.LocationService
import id.homebase.core.location.tracking.DemandReason
import id.homebase.core.location.tracking.LocationPointStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToLong
import kotlin.time.Clock

/**
 * Drives the live-location map: turns the in-memory [LiveLocationReceiveStore] into a list of
 * [LiveMarker]s (others + an optional "you"), resolving each sender's avatar. Every received point
 * is shown — the server is the source of truth for what's worth displaying (it won't flush an
 * evicted point), so the client doesn't second-guess it with a staleness cutoff (#1072); freshness
 * is conveyed by the [LiveMarker.ageMs] label instead. Recomputes on every store emission and on a
 * 30 s ticker (so age labels stay current even with no new packets).
 */
class LiveLocationViewModel(
    private val receiveStore: LiveLocationReceiveStore,
    private val contactService: ContactService,
    private val locationPreferences: LocationPreferences,
    private val pointStore: LocationPointStore,
    private val credentialsManager: CredentialsManager,
    private val locationService: LocationService,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : ViewModel() {

    private val logger = Logger.withTag("LiveRelay")

    private val ownOdinId = MutableStateFlow<OdinId?>(null)

    // Hold GPS while the live map is open so the user's own dot appears — without enabling location
    // history (the #841 coupling fix). No-op where GPS is unavailable / permission not yet granted;
    // released in onCleared when the screen is left.
    private val demandToken = locationService.acquireDemand(DemandReason.LiveMapOpen)

    init {
        viewModelScope.launch {
            ownOdinId.value = runCatching { credentialsManager.getActiveCredentials()?.domain }.getOrNull()
        }
    }

    override fun onCleared() {
        demandToken.release()
        super.onCleared()
    }

    /**
     * Called by the screen when location permission is granted. The map's [DemandReason.LiveMapOpen]
     * hold is already held; this re-evaluates the GPS hold so the now-permitted tracker arms and the
     * user's own dot appears — without enabling history or leaving the screen.
     */
    fun onPermissionGranted() = locationService.refreshGpsHold()

    // Re-emits so age labels update and stale dots drop without a new store packet.
    private val ticker = flow {
        while (true) {
            emit(Unit)
            delay(TICK_MS)
        }
    }

    val uiState: StateFlow<LiveLocationUiState> =
        combine(receiveStore.positions, ownOdinId, pointStore.lastPoint, ticker) { positions, ownId, myFix, _ ->
            val now = nowMs()
            val others = positions.values
                .map { lp ->
                    val contact = contactService.resolveByOdinId(lp.senderOdinId)
                    // Every received sender is shown; log its age so upstream sparsity (peer stopped
                    // transmitting) is visible in the log even though the point is no longer dropped.
                    logger.i {
                        "map sender=${lp.senderOdinId.domainName} ageMs=${now - lp.receivedAtMs} -> SHOW"
                    }
                    LiveMarker(
                        key = lp.senderOdinId.domainName,
                        lat = lp.point.lat,
                        lon = lp.point.lon,
                        odinId = lp.senderOdinId,
                        initials = contact.avatarInitials,
                        ageMs = now - lp.receivedAtMs,
                    )
                }
            // Always show myself (a distinct "you" dot) whenever this device has a known position —
            // not only while sharing. Listed first so it stays slot 0 of any co-located cluster.
            val self = selfMarker(ownId, myFix?.lat, myFix?.lon, myFix?.t, now)
            LiveLocationUiState(
                markers = assignClusters(listOfNotNull(self) + others),
                showMapTiles = locationPreferences.mapProvider.value == LocationMapProvider.OpenStreetMap,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LiveLocationUiState())

    /**
     * Tag markers that sit at (near-)identical coordinates so the map can fan them out instead of
     * stacking them invisibly. Co-location is keyed on lat/lon rounded to ~1.1 m, so this only fires
     * for genuine overlap (e.g. two people testing from the same spot), not for friends a street
     * apart. Preserves input order (self stays first).
     */
    private fun assignClusters(markers: List<LiveMarker>): List<LiveMarker> {
        fun key(m: LiveMarker): Pair<Long, Long> =
            (m.lat * 1e5).roundToLong() to (m.lon * 1e5).roundToLong()
        val sizes = markers.groupingBy { key(it) }.eachCount()
        val nextIndex = HashMap<Pair<Long, Long>, Int>()
        return markers.map { m ->
            val k = key(m)
            val idx = nextIndex.getOrElse(k) { 0 }
            nextIndex[k] = idx + 1
            m.copy(clusterIndex = idx, clusterSize = sizes[k] ?: 1)
        }
    }

    /**
     * Your own dot — shown whenever this device has a known position ([lat]/[lon] non-null),
     * regardless of whether you're actively sharing. Returns null when there's no fix (e.g. location
     * permission not granted, or a viewer device with no GPS), so the map just omits it.
     */
    private fun selfMarker(ownId: OdinId?, lat: Double?, lon: Double?, fixTimeMs: Long?, now: Long): LiveMarker? {
        if (lat == null || lon == null) return null
        val contact = ownId?.let { contactService.resolveByOdinId(it) }
        return LiveMarker(
            key = "self",
            lat = lat,
            lon = lon,
            odinId = ownId,
            initials = contact?.avatarInitials.orEmpty(),
            ageMs = if (fixTimeMs != null) now - fixTimeMs else 0L,
            isSelf = true,
        )
    }

    private companion object {
        const val TICK_MS = 30_000L
    }
}
