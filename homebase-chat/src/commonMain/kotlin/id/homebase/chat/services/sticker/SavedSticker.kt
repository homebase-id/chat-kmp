@file:OptIn(ExperimentalUuidApi::class, ExperimentalEncodingApi::class)

package id.homebase.chat.services.sticker

import androidx.compose.runtime.Immutable
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.core.image.HomebaseImageData
import id.homebase.core.image.ImageSize
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * UI/domain model for one saved sticker in the "My Stickers" tray.
 *
 * Mirrors [id.homebase.core.ui.screens.vault.model.VaultEntry] but single-payload:
 * a saved sticker is exactly one transparent image. [groupId] is reserved (nullable)
 * for future sticker packs and is read straight off the envelope's appData.groupId,
 * so packs become a pure UI grouping later with no migration.
 */
@Immutable
data class SavedSticker(
    val fileId: Uuid,
    val uniqueId: Uuid,
    val driveId: Uuid,
    val payloadKey: String,
    val contentType: String,
    val keyHeader: KeyHeader,
    val previewThumbnail: EmbeddedThumb?,
    val payloadDescriptor: PayloadDescriptor,
    val createdAt: Long,
    val groupId: Uuid? = null,
    /**
     * The chat-message file this sticker was saved FROM, or null if it was created in-app
     * (editor / background-remover). Read off [StickerFileContent.sourceFileId] in the
     * envelope's appData.content. Lets the sticker-tap bottom sheet detect that a received
     * sticker is already in the user's library (`sourceFileId == message.fileId`).
     */
    val sourceFileId: Uuid? = null,
    /** True until the outbox confirms the upload (optimistic local entry). */
    val isPending: Boolean = false,
)

/**
 * Maps a [HomebaseFile] from the Stickers drive to a [SavedSticker], or returns
 * null if the file has no payload (mirror of `HomebaseFile.toVaultEntry`).
 */
@OptIn(ExperimentalUuidApi::class)
fun HomebaseFile.toSavedSticker(): SavedSticker? {
    val payloads = fileMetadata.payloads
    val payload = payloads?.firstOrNull() ?: return null

    // appData.content carries an optional [StickerFileContent]. The v1 tray ignores the
    // label (name); we read it only for [StickerFileContent.sourceFileId] — the back-pointer
    // to the chat message a sticker was saved from, used by the sticker-tap bottom sheet to
    // detect an already-saved sticker. A blank/legacy/corrupt content parses to null source.
    val savedContent = fileMetadata.appData.content?.let { json ->
        try {
            OdinSystemSerializer.deserialize<StickerFileContent>(json)
        } catch (_: Exception) {
            null
        }
    }

    return SavedSticker(
        fileId = fileId,
        uniqueId = fileMetadata.appData.uniqueId ?: fileId,
        driveId = driveId,
        payloadKey = payload.key,
        contentType = payload.contentType ?: "image/png",
        keyHeader = keyHeader,
        previewThumbnail = fileMetadata.appData.previewThumbnail,
        payloadDescriptor = payload,
        createdAt = fileMetadata.created.milliseconds,
        groupId = fileMetadata.appData.groupId,
        sourceFileId = savedContent?.sourceFileId,
    )
}

/**
 * Builds the [HomebaseImageData] for rendering this sticker's transparent thumbnail in
 * the tray, or null if the payload has no decodable IV yet (e.g. still uploading).
 * Mirror of `VaultEntry.imageDataFor` — single source of truth for IV decode + KeyHeader
 * assembly so the tray tile addresses the same Coil cache entry as the eventual bubble.
 */
@OptIn(ExperimentalEncodingApi::class)
fun SavedSticker.toImageData(requestedSize: ImageSize? = ImageSize.THUMB_SMALL): HomebaseImageData? {
    val ivBytes = payloadDescriptor.iv?.let {
        try { Base64.decode(it) } catch (_: Exception) { null }
    } ?: return null
    return HomebaseImageData(
        driveId = driveId,
        fileId = fileId,
        payloadKey = payloadKey,
        previewThumbnail = previewThumbnail,
        requestedSize = requestedSize,
        loadFullPayload = false,
        isEncrypted = true,
        lastModified = payloadDescriptor.lastModified,
        keyHeader = KeyHeader(iv = ivBytes, aesKey = keyHeader.aesKey),
    )
}
