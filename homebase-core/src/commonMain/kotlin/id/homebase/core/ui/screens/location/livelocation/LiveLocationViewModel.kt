package id.homebase.core.ui.screens.location.livelocation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.common.OdinId
import id.homebase.chat.services.convo.contact.ContactService
import id.homebase.chat.services.livelocation.LiveLocationShareService
import id.homebase.core.location.LocationMapProvider
import id.homebase.core.location.LocationPreferences
import id.homebase.core.location.tracking.LocationPointStore
import id.homebase.core.util.initials
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * Drives the live-location map: turns the in-memory [LiveLocationReceiveStore] into a list of
 * [LiveMarker]s (others + an optional "you"), filtering out positions older than [LIVE_STALE_MS] and
 * resolving each sender's avatar. Recomputes on every store emission and on a 30 s ticker (so dots
 * age-label and drop even with no new packets).
 */
class LiveLocationViewModel(
    private val receiveStore: LiveLocationReceiveStore,
    private val contactService: ContactService,
    private val locationPreferences: LocationPreferences,
    private val liveShareService: LiveLocationShareService,
    private val pointStore: LocationPointStore,
    private val credentialsManager: CredentialsManager,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : ViewModel() {

    private val ownOdinId = MutableStateFlow<OdinId?>(null)

    init {
        viewModelScope.launch {
            ownOdinId.value = runCatching { credentialsManager.getActiveCredentials()?.domain }.getOrNull()
        }
    }

    // Re-emits so age labels update and stale dots drop without a new store packet.
    private val ticker = flow {
        while (true) {
            emit(Unit)
            delay(TICK_MS)
        }
    }

    val uiState: StateFlow<LiveLocationUiState> =
        combine(receiveStore.positions, ownOdinId, ticker) { positions, ownId, _ ->
            val now = nowMs()
            val others = positions.values
                .filter { now - it.receivedAtMs <= LIVE_STALE_MS }
                .map { lp ->
                    val contact = contactService.resolveByOdinId(lp.senderOdinId)
                    LiveMarker(
                        key = lp.senderOdinId.domainName,
                        lat = lp.point.lat,
                        lon = lp.point.lon,
                        avatarUrl = contact?.avatarUrl?.ifEmpty { null },
                        initials = contact?.avatarInitials?.ifEmpty { null }
                            ?: lp.senderOdinId.domainName.initials(),
                        ageMs = now - lp.receivedAtMs,
                    )
                }
            // Include myself when I'm actively sharing and have a fix — a distinct "you" dot.
            val self = selfMarker(ownId, now)
            LiveLocationUiState(
                markers = listOfNotNull(self) + others,
                showMapTiles = locationPreferences.mapProvider.value == LocationMapProvider.OpenStreetMap,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LiveLocationUiState())

    private fun selfMarker(ownId: OdinId?, now: Long): LiveMarker? {
        if (!liveShareService.isActive()) return null
        val p = pointStore.lastPoint.value ?: return null
        val contact = ownId?.let { contactService.resolveByOdinId(it) }
        return LiveMarker(
            key = "self",
            lat = p.lat,
            lon = p.lon,
            avatarUrl = contact?.avatarUrl?.ifEmpty { null },
            initials = contact?.avatarInitials?.ifEmpty { null }
                ?: ownId?.domainName?.initials().orEmpty(),
            ageMs = now - p.t,
            isSelf = true,
        )
    }

    private companion object {
        const val TICK_MS = 30_000L
    }
}
