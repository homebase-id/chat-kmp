package id.homebase.core.ui.screens.moments.widget

/**
 * Stable `testTag` strings shared by the Moments feed composables and their layout
 * regression suite (`MomentMediaLayoutInvariantTest`). Kept in one object so
 * production and test never drift apart — same convention as `ChatBubbleTestTags`.
 */
object MomentMediaTestTags {
    /** Outer moment post card. */
    const val CARD = "moments.card"

    /** Full-width region a media cell may occupy — the card's content width. */
    const val MEDIA_SLOT = "moments.card.mediaSlot"

    /** The laid-out media cell inside [MEDIA_SLOT] (single photo/video, or carousel frame). */
    const val MEDIA = "moments.card.media"
}
