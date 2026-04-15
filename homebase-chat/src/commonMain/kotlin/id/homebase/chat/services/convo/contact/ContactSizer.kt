package id.homebase.chat.services.convo.contact

import id.homebase.chat.services.ChatProtocol
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Decides whether a serialized contact JSON fits in the file header (appData.content) or must
 * spill into a separate payload. Mirrors the size check in TypeScript `saveContact`
 * (`uint8ArrayToBase64(payloadBytes).length < MAX_HEADER_CONTENT_BYTES`).
 */
object ContactSizer {

    @OptIn(ExperimentalEncodingApi::class)
    fun shouldEmbedInHeader(json: String): Boolean {
        val base64Length = Base64.encode(json.encodeToByteArray()).length
        return base64Length < ChatProtocol.MaxHeaderContentBytes
    }
}
