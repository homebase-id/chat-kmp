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
        null // Real thumb via replyUniqueId, null for text-only messages
) // Tiny tiny thumb, can be even smaller than tinyThumb even a 1px color