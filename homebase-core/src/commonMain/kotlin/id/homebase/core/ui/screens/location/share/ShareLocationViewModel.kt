package id.homebase.core.ui.screens.location.share

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.location.LocationPreviewProvider
import id.homebase.api.client.location.WebMercator
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.util.truncateToCodePoints
import id.homebase.chat.services.ChatMessageSenderService
import id.homebase.chat.services.builder.LocationPreviewPayloadBuilder
import id.homebase.chat.services.content.MessageContent
import id.homebase.chat.services.convo.ConversationStream
import id.homebase.chat.services.livelocation.LiveLocationShareService
import id.homebase.chat.services.livelocation.LiveShareReadiness
import id.homebase.core.location.GpsRequestReason
import id.homebase.core.location.LocationMapProvider
import id.homebase.core.location.LocationPreferences
import id.homebase.core.location.LocationService
import id.homebase.core.location.tracking.DemandReason
import id.homebase.core.location.tracking.GpsFixResult
import id.homebase.resources.MR
import id.homebase.resources.chat_location_unavailable
import id.homebase.resources.error_unknown
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Drives the full-screen share-location screen (#966). The map owns the viewport; this VM owns
 * the pin (viewport center → lat/lon), the debounced address resolve, and the two send paths:
 *
 *  - **Static pin** — the panned-to coordinates + resolved address + comment, sent as a typed
 *    location message with the static-map payload (same shape the old attachment flow produced).
 *  - **Live share** — the user's ACTUAL position (a fresh fix; the panned pin is deliberately
 *    ignored), sent as an own location message that is live already at creation
 *    (`liveShareUntilMs` set — no post-hoc updateMessage), plus the relay roster armed with the
 *    SAME absolute end-time (the {recipient, endTime} pair is the stop key).
 */
class ShareLocationViewModel(
    private val conversationId: Uuid,
    private val previewProvider: LocationPreviewProvider,
    private val locationService: LocationService,
    private val locationPreferences: LocationPreferences,
    private val liveShareReadiness: LiveShareReadiness,
    private val liveLocationShareService: LiveLocationShareService,
    private val chatMessageSenderService: ChatMessageSenderService,
    private val conversationStream: ConversationStream,
    private val fileOperationsProvider: FileOperationsProvider,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ShareLocationUiState(
            showMapTiles = locationPreferences.mapProvider.value == LocationMapProvider.OpenStreetMap,
        )
    )
    val uiState: StateFlow<ShareLocationUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<ShareLocationUiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<ShareLocationUiEvent> = _events.asSharedFlow()

    // Hold GPS while the screen is open (same transient demand the live map uses) so the entry
    // fix and the re-center button have a warm provider. No-op without permission.
    private val demandToken = locationService.acquireDemand(DemandReason.LiveMapOpen)

    private var geocodeJob: Job? = null
    private var lastResolvedLat: Double? = null
    private var lastResolvedLon: Double? = null
    private var recenterSeq = 0
    /** Set once the user pans/zooms — a late first fix must not yank the map away anymore. */
    private var userMoved = false

    init {
        // Seed the map immediately: the last known position when we have one, else the whole
        // world — a null bbox leaves the map BLANK (no viewport → no tiles fetched) until the
        // first fix lands, which on a cold GPS can take many seconds or never come. Then refine
        // with a fresh fix — but only recenter while the user hasn't taken over.
        val last = locationService.lastKnown.value
        if (last != null) {
            seedBbox(last.lat, last.lon)
        } else {
            _uiState.update {
                it.copy(initialBbox = WORLD_BBOX, initialBboxKey = it.initialBboxKey + 1)
            }
        }
        viewModelScope.launch {
            val fix = locationService.requestLatestGps(GpsRequestReason.LiveMap)
            if (fix is GpsFixResult.Success && !userMoved) seedBbox(fix.point.lat, fix.point.lon)
        }
    }

    override fun onCleared() {
        demandToken.release()
        super.onCleared()
    }

    /**
     * Screen callback when location permission lands — re-arms the GPS hold (LiveMap pattern)
     * and retries the initial seed: on a first-ever open the init-time fix request fails with
     * PermissionDenied (the prompt is still up), which used to leave the map on the fallback
     * view until the screen was reopened.
     */
    fun onPermissionGranted() {
        locationService.refreshGpsHold()
        viewModelScope.launch {
            val fix = locationService.requestLatestGps(GpsRequestReason.LiveMap)
            if (fix is GpsFixResult.Success && !userMoved) seedBbox(fix.point.lat, fix.point.lon)
        }
    }

    private fun seedBbox(lat: Double, lon: Double) {
        val (x, y) = WebMercator.latLonToUnit(lat, lon)
        _uiState.update {
            it.copy(
                initialBbox = listOf(x, y, x, y),
                initialBboxKey = it.initialBboxKey + 1,
            )
        }
    }

    /**
     * The map's viewport center moved (gesture or re-fit). Updates the pin instantly; the address
     * resolve is debounced (Nominatim budget) and skipped when the pin settled within ~30 m of
     * the last resolved point.
     */
    fun onMapCenterChanged(unitX: Double, unitY: Double, byUser: Boolean) {
        if (byUser) userMoved = true
        val (lat, lon) = WebMercator.unitToLatLon(unitX, unitY)
        _uiState.update { it.copy(pinLat = lat, pinLon = lon) }

        val last = lastResolvedLat
        if (last != null && lastResolvedLon != null &&
            approxDistanceMeters(last, lastResolvedLon!!, lat, lon) < RESOLVE_MIN_MOVE_METERS
        ) {
            return
        }
        _uiState.update { it.copy(isResolvingAddress = true) }
        geocodeJob?.cancel()
        geocodeJob = viewModelScope.launch {
            delay(GEOCODE_DEBOUNCE_MS)
            val address = runCatching { previewProvider.reverseGeocode(lat, lon) }.getOrNull()
            lastResolvedLat = lat
            lastResolvedLon = lon
            _uiState.update {
                it.copy(
                    address = address ?: formatLatLon(lat, lon),
                    isResolvingAddress = false,
                )
            }
        }
    }

    fun onCommentChanged(text: String) = _uiState.update { it.copy(comment = text) }

    /** GPS re-center button: fresh fix → one-shot camera target the screen applies. */
    fun onRecenter() {
        if (_uiState.value.isAcquiringFix) return
        _uiState.update { it.copy(isAcquiringFix = true) }
        viewModelScope.launch {
            val fix = locationService.requestLatestGps(GpsRequestReason.LiveMap)
            if (fix is GpsFixResult.Success) {
                val (x, y) = WebMercator.latLonToUnit(fix.point.lat, fix.point.lon)
                _uiState.update {
                    it.copy(isAcquiringFix = false, recenterTarget = RecenterTarget(x, y, ++recenterSeq))
                }
            } else {
                _uiState.update { it.copy(isAcquiringFix = false) }
                _events.emit(ShareLocationUiEvent.ShowError(MR.string.chat_location_unavailable))
            }
        }
    }

    fun recenterConsumed() = _uiState.update { it.copy(recenterTarget = null) }

    fun dismissEnableLocationDialog() = _uiState.update { it.copy(showEnableLocationDialog = false) }

    /** Send the panned-to pin (+ comment) as a static location message, then pop. */
    fun sendStaticPin() {
        val state = _uiState.value
        val lat = state.pinLat ?: return
        val lon = state.pinLon ?: return
        if (state.isSending) return
        _uiState.update { it.copy(isSending = true) }
        viewModelScope.launch {
            try {
                // The pan-resolved address rides along so the provider skips a second geocode.
                val preview = previewProvider.getLocationPreview(
                    lat = lat,
                    lon = lon,
                    knownAddress = state.address.takeIf { !state.isResolvingAddress },
                )
                val descriptor = LocationPreviewPayloadBuilder.descriptorFor(preview)
                    .copy(caption = captionOrNull())
                chatMessageSenderService.sendNewTypedMessage(
                    messageUniqueId = Uuid.random(),
                    conversationId = conversationId,
                    content = MessageContent.Location(descriptor),
                    previousMessageUniqueId = null,
                    payloadBundle = LocationPreviewPayloadBuilder.build(preview, fileOperationsProvider),
                )
                _events.emit(ShareLocationUiEvent.MessageSent)
            } catch (e: Exception) {
                Logger.e(throwable = e, tag = TAG) { "static pin send failed" }
                _uiState.update { it.copy(isSending = false) }
                _events.emit(ShareLocationUiEvent.ShowError(MR.string.error_unknown))
            }
        }
    }

    /**
     * Start a live share for [durationMs]: own live-at-creation message (from the ACTUAL current
     * position) + relay roster, both on the same absolute end-time. Gated on share readiness.
     */
    fun startLiveShare(durationMs: Long) {
        if (_uiState.value.isSending) return
        _uiState.update { it.copy(isSending = true) }
        viewModelScope.launch {
            try {
                if (!liveShareReadiness.isReady()) {
                    _uiState.update { it.copy(isSending = false, showEnableLocationDialog = true) }
                    return@launch
                }
                val fix = locationService.requestLatestGps(GpsRequestReason.LiveMap)
                if (fix !is GpsFixResult.Success) {
                    _uiState.update { it.copy(isSending = false) }
                    _events.emit(ShareLocationUiEvent.ShowError(MR.string.chat_location_unavailable))
                    return@launch
                }
                val untilMs = Clock.System.now().toEpochMilliseconds() + durationMs
                val preview = previewProvider.getLocationPreview(fix.point.lat, fix.point.lon)
                val descriptor = LocationPreviewPayloadBuilder.descriptorFor(preview)
                    .copy(caption = captionOrNull(), liveShareUntilMs = untilMs)
                val recipients = conversationStream.getRecipients(conversationId, emptyList(), null)
                chatMessageSenderService.sendNewTypedMessage(
                    messageUniqueId = Uuid.random(),
                    conversationId = conversationId,
                    content = MessageContent.Location(descriptor),
                    previousMessageUniqueId = null,
                    payloadBundle = LocationPreviewPayloadBuilder.build(preview, fileOperationsProvider),
                )
                liveLocationShareService.start(recipients, untilMs)
                _events.emit(ShareLocationUiEvent.MessageSent)
            } catch (e: Exception) {
                Logger.e(throwable = e, tag = TAG) { "live share start failed" }
                _uiState.update { it.copy(isSending = false) }
                _events.emit(ShareLocationUiEvent.ShowError(MR.string.error_unknown))
            }
        }
    }

    private fun captionOrNull(): String? =
        _uiState.value.comment.trim().ifBlank { null }?.truncateToCodePoints(CAPTION_MAX_CODEPOINTS)

    /** Small-distance approximation — plenty for a "did the pin really move" check. */
    private fun approxDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = abs(lat2 - lat1) * METERS_PER_DEGREE
        val dLon = abs(lon2 - lon1) * METERS_PER_DEGREE * cos(lat1 * PI / 180.0)
        return dLat + dLon
    }

    private fun formatLatLon(lat: Double, lon: Double): String {
        val latStr = ((lat * 1e5).toLong() / 1e5).toString()
        val lonStr = ((lon * 1e5).toLong() / 1e5).toString()
        return "$latStr, $lonStr"
    }

    private companion object {
        const val TAG = "ShareLocationViewModel"
        /** Unit-space bbox of the whole world — the no-fix fallback so the map never opens blank. */
        val WORLD_BBOX = listOf(0.0, 0.0, 1.0, 1.0)
        const val GEOCODE_DEBOUNCE_MS = 700L
        const val RESOLVE_MIN_MOVE_METERS = 30.0
        const val METERS_PER_DEGREE = 111_320.0
        // Same cap the composer-caption path uses (7 KB header budget).
        const val CAPTION_MAX_CODEPOINTS = 2000
    }
}
