package id.homebase.chat.widget

/**
 * Stable `testTag` strings shared by the chat-bubble composables and their layout
 * regression suite (BubbleLayoutInvariantTest). Kept in one object so production and
 * test never drift apart.
 */
object ChatBubbleTestTags {
    /** Outer bubble Surface — its bounds are the bubble's. */
    const val BUBBLE = "chat.bubble"

    /** The media/gallery container (single image, or 2/3/4+ gallery). */
    const val MEDIA = "chat.bubble.media"

    /** The rendered caption text (inside its padded row). */
    const val CAPTION = "chat.bubble.caption"
}
