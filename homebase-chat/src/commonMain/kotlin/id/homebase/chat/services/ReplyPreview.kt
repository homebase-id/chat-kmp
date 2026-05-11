package id.homebase.chat.services

import androidx.compose.runtime.Immutable
import id.homebase.api.client.drives.upload.EmbeddedThumb
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
@Immutable
data class ReplyPreview(
    val replyUniqueId: Uuid, // FileId of the message that was replied to
    val authorOdinId: String, // frodo.baggins.demo.rocks
    val message: String, // chopped chars (IDK how many you use? 40? 80? use truncateToCodePoints(80)
    val previewThumbnail: EmbeddedThumb? =
        null, // Real thumb via replyUniqueId, null for text-only messages
    // When the replied-to message is an Event, the start instant (UTC) lets
    // the reply chip render the viewer's local month/day without looking up
    // the parent message (which may be paged out, deleted, or never
    // received). Null for non-Event replies and for replies sent by older
    // clients — consumer falls back to parent-message lookup in that case.
    // Authoring zone is intentionally not carried: the chip displays in the
    // viewer's local zone, so UTC alone is sufficient.
    val eventStartUtcMs: Long? = null,
) // Tiny tiny thumb, can be even smaller than tinyThumb even a 1px color