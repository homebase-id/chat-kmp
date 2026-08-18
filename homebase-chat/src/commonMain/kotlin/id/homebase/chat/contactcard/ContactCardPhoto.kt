package id.homebase.chat.contactcard

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.chat.services.ChatProtocol
import id.homebase.core.image.HomebaseImageData
import id.homebase.core.image.ImageSize
import id.homebase.core.image.thumbSizesFrom
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// Shares the Event cover's key because every generic media surface already filters `chat_web*`; a
// new key would have to be added to each of those filters.
internal fun contactCardPhotoPayload(payloads: List<PayloadDescriptor>?): PayloadDescriptor? =
    payloads?.firstOrNull {
        it.contentType?.startsWith("image/") == true &&
            it.key.startsWith(ChatProtocol.PAYLOAD_KEY_MESSAGE_WEB)
    }

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
        // Left empty, the loader guesses a size the server never stored and takes a 404.
        requestedSize = ImageSize.THUMB_SMALL,
        availableThumbSizes = thumbSizesFrom(payload.thumbnails),
        loadFullPayload = false,
        isEncrypted = true,
        lastModified = payload.lastModified,
        payloadContentType = payload.contentType,
        keyHeader = KeyHeader(iv = iv, aesKey = keyHeader.aesKey),
    )
}
