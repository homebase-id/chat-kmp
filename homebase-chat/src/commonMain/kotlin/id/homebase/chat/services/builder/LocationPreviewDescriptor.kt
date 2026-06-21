package id.homebase.chat.services.builder

import kotlinx.serialization.Serializable

/**
 * The coordinate metadata for a location message. New messages carry this in the message **header**
 * (`appData.content`) — location is a typed kind (`MessageContent.Location`), like Event — which makes
 * it the source of truth and lets a live-share toggle edit it via `updateMessage`. The map PNG lives
 * in the encrypted `chat_loc` payload bytes; a copy of this JSON is also kept on that payload's
 * `descriptorContent` (per-payload "image + its own GPS" sidecar) for the "See all → Locations" list
 * and older clients. The header copy is authoritative; the payload copy is not updated on live-share.
 */
@Serializable
data class LocationPreviewDescriptor(
    val lat: Double,
    val lon: Double,
    val address: String,
    val hasImage: Boolean,
    val imageWidth: Int?,
    val imageHeight: Int?,
    /**
     * Absolute UTC epoch-ms at which a live-location share window ends, or null for a plain static
     * location. The bubble derives its state from this: null = STATIC, now < value = LIVE,
     * now >= value = ENDED. Set/cleared by updating this message's descriptor (see the live-share UX).
     * Defaults null + `explicitNulls = false` ⇒ omitted from JSON for static shares and ignored by
     * older clients.
     */
    val liveShareUntilMs: Long? = null,
    /**
     * The user's optional caption typed alongside the location. Lives in the descriptor (like Event's
     * title/description) because a typed location message has no separate text body. Preserved across
     * live-share edits.
     */
    val caption: String? = null,
)
