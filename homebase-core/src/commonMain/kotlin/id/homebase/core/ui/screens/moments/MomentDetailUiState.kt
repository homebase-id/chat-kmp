package id.homebase.core.ui.screens.moments

import id.homebase.chat.conversationlist.FullScreenOverlay
import id.homebase.core.moments.services.MomentFeedItem

data class MomentDetailUiState(
    val moment: MomentFeedItem? = null,
    val isLoading: Boolean = true,
    val fullScreenOverlay: FullScreenOverlay? = null,
    /**
     * Payload key the detail carousel should land on when first opened —
     * forwarded from the route. Null (or unmatched at render time) starts
     * the pager at page 0.
     */
    val initialPayloadKey: String? = null,
)

sealed interface MomentDetailUiAction {
    /**
     * Tap on a payload in the carousel. Routes by contentType to either
     * [FullScreenOverlay.ViewMessageData] (image) or
     * [FullScreenOverlay.VideoPlayerData] (video). Other types currently
     * fall through with no action.
     */
    data class MediaClicked(val payloadKey: String) : MomentDetailUiAction

    /** Dismiss whichever full-screen viewer is showing. */
    data object CloseFullScreenOverlay : MomentDetailUiAction
}
