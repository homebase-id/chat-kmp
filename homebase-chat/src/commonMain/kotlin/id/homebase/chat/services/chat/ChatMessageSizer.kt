package id.homebase.chat.services.chat

import id.homebase.chat.services.ChatProtocol
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64

object ChatMessageSizer {

    fun shouldEmbedInHeader(markdown: String): Boolean {
        val payload = ChatMessagePayload(markdown)
        val bytes = Json.encodeToString(payload).encodeToByteArray()
        val base64Length = Base64.encode(bytes).length
        return base64Length < ChatProtocol.MaxHeaderContentBytes
    }

    fun payloadBytes(markdown: String): ByteArray {
        val payload = ChatMessagePayload(markdown)
        return Json.encodeToString(payload).encodeToByteArray()
    }

    fun preview(markdown: String): String {
        val plain =
            markdown
                .replace(Regex("[*_`#>\\-]"), "")
                .replace("\n", " ")

        return plain.take(400) + if (plain.length > 400) "…" else ""
    }
}

@Serializable
data class ChatMessagePayload(
    val message: String
)