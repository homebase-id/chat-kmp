package id.homebase.chat.services

import androidx.compose.runtime.Immutable
import id.homebase.api.client.drives.upload.EmbeddedThumb
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlin.uuid.Uuid

@Serializable
@Immutable
data class ReplyPreview(
    val replyUniqueId: Uuid, // FileId of the message that was replied to
    val authorOdinId: String, // frodo.baggins.demo.rocks
    val message: String, // chopped chars (IDK how many you use? 40? 80? use truncateToCodePoints(80)
    val previewThumbnail: EmbeddedThumb? =
        null, // Real thumb via replyUniqueId, null for text-only messages
    // Kind-specific extras the reply preview renderer can use without
    // looking up the parent message. Small JsonObject with a "kind"
    // discriminator; see [ReplyContext] for known shapes. Null for plain
    // text/media replies and for replies sent by clients that pre-date
    // this field — consumers fall back to default rendering in that case.
    val context: JsonElement? = null,
) // Tiny tiny thumb, can be even smaller than tinyThumb even a 1px color
