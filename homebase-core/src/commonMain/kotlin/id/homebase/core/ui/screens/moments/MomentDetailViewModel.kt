package id.homebase.core.ui.screens.moments

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import id.homebase.api.client.KeyHeader
import id.homebase.chat.conversationlist.FullScreenOverlay
import id.homebase.core.moments.services.MomentsFeedService
import id.homebase.core.ui.navigation.Route
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Resolves a single moment by id from the live feed flow. We deliberately
 * source from [MomentsFeedService] rather than re-querying the DB:
 *  - The user lands here from the feed, where the moment is already in
 *    memory — no extra read needed for the happy path.
 *  - When new sync batches arrive (e.g. a description edit replays from
 *    another device), [MomentsFeedService] re-emits and the detail screen
 *    re-renders automatically.
 *  - On cold start before the feed has loaded, `moment` stays null and the
 *    screen shows a loading state until the feed populates.
 */
class MomentDetailViewModel(
    savedStateHandle: SavedStateHandle,
    feedService: MomentsFeedService,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<Route.MomentDetail>()
    private val momentId: Uuid = Uuid.parse(route.momentId)
    private val initialPayloadKey: String? = route.initialPayloadKey

    private val _overlay = MutableStateFlow<FullScreenOverlay?>(null)

    val uiState: StateFlow<MomentDetailUiState> = combine(
        feedService.feed,
        _overlay,
    ) { list, overlay ->
        val match = list.firstOrNull { it.id == momentId }
        MomentDetailUiState(
            moment = match,
            isLoading = match == null,
            fullScreenOverlay = overlay,
            initialPayloadKey = initialPayloadKey,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        MomentDetailUiState(initialPayloadKey = initialPayloadKey),
    )

    @OptIn(ExperimentalEncodingApi::class)
    fun onAction(action: MomentDetailUiAction) {
        when (action) {
            is MomentDetailUiAction.MediaClicked -> {
                val moment = uiState.value.moment ?: return
                val payload = moment.payloads.firstOrNull { it.key == action.payloadKey } ?: return
                val contentType = payload.contentType ?: ""

                when {
                    contentType.startsWith("image/") -> {
                        _overlay.value = FullScreenOverlay.ViewMessageData(
                            messageId = moment.id,
                            // Empty title for moments — the chat viewer renders this
                            // in its top bar; we don't have an author display name to
                            // surface here.
                            title = "",
                            userDate = Instant.fromEpochMilliseconds(moment.userDateMs),
                            // The chat viewer treats `content` as markdown for the
                            // caption — moment description is plain text but markdown
                            // tolerates it.
                            content = moment.description,
                            fileId = moment.fileId,
                            driveId = moment.driveId,
                            payloads = moment.payloads,
                            keyHeader = moment.keyHeader,
                            selectedPayloadKey = action.payloadKey,
                        )
                    }

                    contentType.startsWith("video/") ||
                            contentType == "application/vnd.apple.mpegurl" -> {
                        val ivBytes = payload.iv?.let { Base64.decode(it) }
                        // The video player needs a per-payload KeyHeader (the
                        // payload's IV + the moment's master AES key). If the IV
                        // is somehow missing, fall back to a 16-byte zero IV so
                        // the surface still renders the thumbnail; playback will
                        // fail loudly which is better than a silent no-op.
                        _overlay.value = FullScreenOverlay.VideoPlayerData(
                            fileId = moment.fileId,
                            driveId = moment.driveId,
                            payloadKey = action.payloadKey,
                            keyHeader = KeyHeader(
                                iv = ivBytes ?: ByteArray(16),
                                aesKey = moment.keyHeader.aesKey,
                            ),
                            payload = payload,
                            localFilePath = null,
                            uploadMessageId = null,
                        )
                    }

                    // Audio / document / other content types currently no-op.
                    // Add branches here if/when moments grow to support them.
                    else -> Unit
                }
            }

            MomentDetailUiAction.CloseFullScreenOverlay -> {
                _overlay.value = null
            }
        }
    }
}
