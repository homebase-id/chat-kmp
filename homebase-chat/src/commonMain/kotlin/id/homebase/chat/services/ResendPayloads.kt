package id.homebase.chat.services

import co.touchlab.kermit.Logger
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.files.ResendPayloadByteSource
import id.homebase.api.client.drives.files.ThumbnailFile
import id.homebase.api.file.FileOperationsProvider
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.Uuid

/**
 * Result of [resendablePayloads]: the payloads/thumbnails that could be
 * recovered as pre-encrypted [PayloadFile]/[ThumbnailFile]s, plus the keys of
 * descriptors whose bytes could NOT be recovered (cache-evicted and not on the
 * server, or missing iv). Callers use [unavailableKeys] to decide whether a
 * resend is safe — e.g. refuse to retry a media message whose image bytes are
 * gone rather than silently sending a text-only message.
 */
internal data class ResendablePayloads(
    val payloads: List<PayloadFile>,
    val thumbnails: List<ThumbnailFile>,
    val unavailableKeys: List<String>,
)

/**
 * Re-reads a file's existing payloads as pre-encrypted [PayloadFile]s so they
 * can be re-attached to a new upload/update request. For each payload
 * descriptor the still-encrypted bytes are pulled via [byteSource] (disk cache
 * first, then the server), dropped into a temp file, and wrapped with
 * `isPreEncrypted = true` + the descriptor's original IV — the file's
 * [id.homebase.api.client.KeyHeader.aesKey] must be preserved by the caller so
 * the bytes decrypt cleanly on the peer side.
 *
 * Per-payload thumbnails are re-attached too: an update ships an
 * AppendOrOverwrite per payload key, which REPLACES the payload AND its
 * thumbnail set on the server — shipping the payload without its thumbnails
 * wipes them server-side and every thumb load 404s afterwards. Thumbnail
 * recovery is best-effort and does not mark the payload unavailable.
 *
 * Temp files are written with [FileOperationsProvider.writeBytesToTempFile]
 * and left for the OS / app cleanup pass to remove once the outbox drains,
 * mirroring how `MessageAttachmentBuilder` handles attachments.
 *
 * Used by the conversation heal-redistribute path and by chat message retry.
 *
 * @param excludeKeys descriptor keys to skip entirely (not recovered, not
 *   reported unavailable) — e.g. the text-overflow payload, which retry
 *   rebuilds from plaintext instead.
 */
internal suspend fun resendablePayloads(
    file: HomebaseFile,
    driveId: Uuid,
    byteSource: ResendPayloadByteSource,
    fileOps: FileOperationsProvider,
    excludeKeys: Set<String> = emptySet(),
    tempPrefix: String = "resend",
    logTag: String = "Resend",
): ResendablePayloads {
    val existing = file.fileMetadata.payloads.orEmpty()
        .filter { it.key !in excludeKeys }
    if (existing.isEmpty()) {
        Logger.d(tag = logTag) {
            "resendablePayloads: fileId=${file.fileId} has no existing payloads — nothing to re-attach"
        }
        return ResendablePayloads(emptyList(), emptyList(), emptyList())
    }

    val payloads = mutableListOf<PayloadFile>()
    val thumbnails = mutableListOf<ThumbnailFile>()
    val unavailableKeys = mutableListOf<String>()
    for (descriptor in existing) {
        try {
            val bytes = byteSource.getPayloadBytesEncrypted(driveId, file.fileId, descriptor.key)
            if (bytes == null || bytes.isEmpty()) {
                Logger.w(tag = logTag) {
                    "resendablePayloads: payload key=${descriptor.key} unavailable — getPayloadBytesEncrypted returned ${if (bytes == null) "null" else "empty"} (fileId=${file.fileId})"
                }
                unavailableKeys += descriptor.key
                continue
            }
            val ivBytes = descriptor.iv?.let {
                @OptIn(ExperimentalEncodingApi::class)
                Base64.Default.decode(it)
            }
            if (ivBytes == null) {
                Logger.w(tag = logTag) {
                    "resendablePayloads: payload key=${descriptor.key} unavailable — descriptor has no iv (fileId=${file.fileId})"
                }
                unavailableKeys += descriptor.key
                continue
            }
            val tempPath = fileOps.writeBytesToTempFile(
                bytes = bytes,
                prefix = "${tempPrefix}_${descriptor.key}_",
                suffix = ".enc",
            )
            payloads += PayloadFile(
                key = descriptor.key,
                filePath = tempPath,
                // Keep the payload's embedded preview thumbnail on the descriptor too.
                previewThumbnail = descriptor.previewThumbnail?.toEmbeddedThumb(),
                contentType = descriptor.contentType ?: "",
                isPreEncrypted = true,
                iv = ivBytes,
                descriptorContent = descriptor.descriptorContent,
            )

            // The thumbnails are encrypted with the SAME payload key + iv, so we
            // ship the pre-encrypted bytes as-is (no re-encryption needed).
            var reattachedThumbs = 0
            for (thumb in descriptor.thumbnails.orEmpty()) {
                val w = thumb.pixelWidth
                val h = thumb.pixelHeight
                if (w == null || h == null) {
                    Logger.w(tag = logTag) {
                        "resendablePayloads: skipping thumbnail for key=${descriptor.key} — missing pixel dims (w=$w h=$h fileId=${file.fileId})"
                    }
                    continue
                }
                val thumbBytes = byteSource.getThumbBytesEncrypted(
                    driveId = driveId,
                    fileId = file.fileId,
                    payloadKey = descriptor.key,
                    width = w,
                    height = h,
                    lastModified = descriptor.lastModified,
                )
                if (thumbBytes == null || thumbBytes.isEmpty()) {
                    Logger.w(tag = logTag) {
                        "resendablePayloads: skipping thumbnail key=${descriptor.key} ${w}x$h — getThumbBytesEncrypted returned ${if (thumbBytes == null) "null" else "empty"} (fileId=${file.fileId})"
                    }
                    continue
                }
                thumbnails += ThumbnailFile(
                    pixelWidth = w,
                    pixelHeight = h,
                    thumbnailBytes = thumbBytes,
                    key = descriptor.key,
                    contentType = thumb.contentType ?: "image/webp",
                )
                reattachedThumbs++
            }

            Logger.i(tag = logTag) {
                "resendablePayloads: re-attached payload key=${descriptor.key} bytes=${bytes.size} thumbs=$reattachedThumbs/${descriptor.thumbnails?.size ?: 0} tempPath=$tempPath fileId=${file.fileId}"
            }
        } catch (e: Exception) {
            Logger.w(throwable = e, tag = logTag) {
                "resendablePayloads: failed to re-attach payload key=${descriptor.key} fileId=${file.fileId}"
            }
            unavailableKeys += descriptor.key
        }
    }
    return ResendablePayloads(payloads, thumbnails, unavailableKeys)
}
