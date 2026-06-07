package id.homebase.chat.services.chat

import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.util.markdownToPlainPreview
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.MessageAppData
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.encoding.Base64

object ChatMessageSizer {

    fun shouldEmbedInHeader(markdown: String): Boolean {
        val payload = ChatMessagePayload(JsonPrimitive(markdown))
        val bytes = Json.encodeToString(payload).encodeToByteArray()
        val base64Length = Base64.encode(bytes).length
        return base64Length < ChatProtocol.MaxHeaderContentBytes
    }

    fun payloadBytes(messageData: MessageAppData): ByteArray {
        val payload = ChatMessagePayload(messageData.message)
        return OdinSystemSerializer.serialize(payload).encodeToByteArray()
    }

    fun preview(markdown: String): String =
        // Shared AST strip — mirrors the renderer's grammar and, unlike the old
        // `[*_`#>\-]` regex, leaves mid-word hyphens/underscores intact.
        markdownToPlainPreview(markdown, maxCodePoints = 400)
}

@Serializable
data class ChatMessagePayload(
    val message: JsonElement = JsonPrimitive("")
)