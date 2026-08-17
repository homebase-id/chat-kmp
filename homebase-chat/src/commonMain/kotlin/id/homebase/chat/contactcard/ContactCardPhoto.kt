package id.homebase.chat.contactcard

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.chat.services.ChatProtocol
import id.homebase.core.image.HomebaseImageData
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * A contact card's photo rides as an ordinary encrypted image payload on the message, never in the
 * descriptor — a picture cannot fit the 7 KB header budget that lets the card render on scroll with
 * no fetch. Presence is therefore *inferred* from the payload list rather than declared, so a card
 * from a sender that never attached one simply has none, and an older client is unaffected.
 *
 * It reuses the Event cover's `chat_web` key, which every generic media surface already filters out
 * — a bespoke key would have to be added to each of those filters to stop the photo turning up as a
 * loose image in the conversation's shared-media tab.
 */
internal fun contactCardPhotoPayload(payloads: List<PayloadDescriptor>?): PayloadDescriptor? =
    payloads?.firstOrNull {
        it.contentType?.startsWith("image/") == true &&
            it.key.startsWith(ChatProtocol.PAYLOAD_KEY_MESSAGE_WEB)
    }

/**
 * The [HomebaseImageData] for a card's photo, or null when there is no usable one. The AES key is
 * the message's and the IV is the payload's, the same split every other chat image uses.
 */
@OptIn(ExperimentalEncodingApi::class, ExperimentalUuidApi::class)
internal fun contactCardPhotoData(
    payloads: List<PayloadDescriptor>?,
    driveId: Uuid,
    fileId: Uuid,
    keyHeader: KeyHeader,
    previewThumbnail: EmbeddedThumb?,
): HomebaseImageData? {
    val payload = contactCardPhotoPayload(payloads) ?: return null
    val iv = payload.iv?.let { runCatching { Base64.decode(it) }.getOrNull() } ?: return null
    return HomebaseImageData(
        driveId = driveId,
        fileId = fileId,
        payloadKey = payload.key,
        previewThumbnail = payload.previewThumbnail?.toEmbeddedThumb() ?: previewThumbnail,
        loadFullPayload = false,
        isEncrypted = true,
        lastModified = payload.lastModified,
        payloadContentType = payload.contentType,
        keyHeader = KeyHeader(iv = iv, aesKey = keyHeader.aesKey),
    )
}
