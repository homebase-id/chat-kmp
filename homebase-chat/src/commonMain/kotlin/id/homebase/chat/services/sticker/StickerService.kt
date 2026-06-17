@file:OptIn(ExperimentalUuidApi::class, ExperimentalEncodingApi::class)

package id.homebase.chat.services.sticker

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.files.DeleteLocalFilesByFileIdRequest
import id.homebase.api.client.drives.files.DescriptorContent
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.files.ThumbnailDescriptor
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.image.convertHeicToJpeg
import id.homebase.api.lib.image.ImageFormatDetector
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.OutboxSync
import id.homebase.api.sync.database.enqueued
import id.homebase.chat.services.PayloadBundle
import id.homebase.chat.services.PayloadCacheSeeder
import id.homebase.chat.services.thumbnailDescriptorsFor
import id.homebase.chat.services.PayloadBundleEncryptionService
import id.homebase.chat.services.builder.MessageThumbnailGenerator
import id.homebase.chat.services.outbox.OptimisticWriter
import id.homebase.core.config.stickerLabeledDrive
import id.homebase.core.sync.OptionalDriveActivation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val TAG = "StickerService"

/**
 * Persists and resolves saved stickers on the dedicated Stickers drive.
 *
 * Structural copy of [id.homebase.core.ui.screens.vault.VaultUploaderService], reduced
 * to the single-payload sticker case:
 *  - [saveSticker] uploads one transparent image as a `STICKER_FILE_TYPE` file whose
 *    sole payload carries `descriptorContent = {"isSticker":true}` (PR #664 wire format).
 *  - [resolveForSend] downloads + decrypts a saved sticker's bytes to a temp file so the
 *    composer can re-stage it as a normal image attachment with `forceSticker = true`.
 *  - [deleteSticker] enqueues a local-file delete (mirror of VaultService.deleteEntry).
 *  - [activate] registers + mounts the optional Stickers drive on first-time grant.
 *
 * Reuses all existing crypto/outbox machinery — no hand-rolled encryption, no new table.
 */
class StickerService(
    private val outboxSync: OutboxSync,
    private val optimisticWriter: OptimisticWriter,
    private val payloadEncryptionService: PayloadBundleEncryptionService,
    private val fileOperationsProvider: FileOperationsProvider,
    private val driveFileProvider: DriveFileProvider,
    private val payloadCacheSeeder: PayloadCacheSeeder,
    private val optionalDriveActivation: OptionalDriveActivation,
    private val stickerStream: StickerStream,
) {
    private val driveId = stickerLabeledDrive.drive.alias

    /**
     * Activate the optional Stickers drive via [OptionalDriveActivation]: register it in
     * the cross-device [id.homebase.core.sync.DriveRegistry] and mount it, then cold-load
     * the saved-sticker stream. This is the SINGLE sanctioned mount path for stickers —
     * invoked once when the user first grants the Stickers permission. On every subsequent
     * app start the drive is already in the registry, so the login pre-mount loop mounts it
     * automatically before the WebSocket connects; feature code does not re-mount.
     *
     * Idempotent: [OptionalDriveActivation.activate] is a no-op (and skips the WS-refresh)
     * when the drive is already mounted, so a redundant call after the login pre-mount costs
     * nothing.
     */
    suspend fun activate() {
        try {
            optionalDriveActivation.activate(stickerLabeledDrive)
            stickerStream.start()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(e, TAG) { "Failed to activate Stickers drive" }
        }
    }

    /**
     * Save raw transparent image [bytes] as a new sticker. The single entry point a
     * future background-remover would also call. Returns the new sticker's uniqueId,
     * or null if the upload could not be enqueued.
     *
     * The alpha-gate (rejecting fully-opaque images) is enforced by the caller
     * ([id.homebase.chat.conversationlist.StickerHandler]) before this point, so this
     * method just persists whatever bytes it is handed.
     */
    suspend fun saveSticker(
        bytes: ByteArray,
        contentType: String,
        scope: CoroutineScope,
        name: String? = null,
        uniqueId: Uuid = Uuid.random(),
        /**
         * The chat-message file this sticker is being saved FROM. Persisted in the sticker
         * file's appData content ([StickerFileContent.sourceFileId]) so the sticker-tap bottom
         * sheet can later detect that the same received sticker is already saved. Null when the
         * sticker originates in-app (editor / background-remover) with no source message.
         */
        sourceFileId: Uuid? = null,
    ): Uuid? {
        // The Stickers drive is already mounted by the time a save runs — either
        // auto-mounted at login (registered) or activated when the user granted the
        // Stickers permission (StickerHandler.awaitDriveGranted gates on that). No mount here.

        // Mirror VaultUploaderService: convert HEIC/HEIF to JPEG so the drive and receivers
        // get a web image format. A directly-saved transparent HEIC would otherwise upload as
        // image/heic and fail thumbnail generation on platforms that can't decode it.
        val uploadBytes = if (ImageFormatDetector.isHeic(bytes)) convertHeicToJpeg(bytes) ?: bytes else bytes
        val uploadType = if (uploadBytes !== bytes) "image/jpeg" else contentType

        val ext = uploadType.substringAfter("/", "png").let { if (it == "jpeg") "jpg" else it }
        val tempPath = fileOperationsProvider.writeBytesToTempFile(uploadBytes, "sticker_", ".$ext")
        return try {
            val keyHeader = KeyHeader.newRandom16()
            val key = StickerProtocol.STICKER_PAYLOAD_KEY

            val thumbs = try {
                MessageThumbnailGenerator.generate(tempPath, key, fileOperationsProvider)
            } catch (e: Exception) {
                Logger.w(e, TAG) { "Sticker thumbnail generation failed" }
                null
            }

            val payload = PayloadFile(
                key = key,
                filePath = tempPath,
                contentType = uploadType,
                previewThumbnail = thumbs?.preview,
                // PR #664 wire format: marks the payload as a transparent cut-out so
                // receivers render it bare (no opaque bubble backdrop). The format lets a
                // receiver pick the right decoder and a downloaded file get the right extension.
                descriptorContent = DescriptorContent.descriptorContentFromImage(
                    isSticker = true,
                    format = ImageFormatDetector.detectFormat(uploadBytes),
                ),
            )

            val bundle = PayloadBundle(
                payloads = listOf(payload),
                thumbnails = thumbs?.thumbnails ?: emptyList(),
                previewThumbs = listOfNotNull(thumbs?.preview),
            )

            val encryptedBundle = payloadEncryptionService.encryptBundle(
                uniqueId, bundle, keyHeader.aesKey, scope,
            )

            val content = OdinSystemSerializer.serialize(
                StickerFileContent(name = name, sourceFileId = sourceFileId)
            )
            val unencryptedMetadata = UploadFileMetadata(
                allowDistribution = false,
                isEncrypted = true,
                appData = UploadAppFileMetaData(
                    uniqueId = uniqueId,
                    content = content,
                    fileType = StickerProtocol.STICKER_FILE_TYPE,
                    previewThumbnail = encryptedBundle.previewThumbs.firstOrNull(),
                ),
            )

            val payloadDescriptors = encryptedBundle.payloads.map { p ->
                PayloadDescriptor(
                    key = p.key,
                    contentType = p.contentType.ifEmpty { null },
                    // Native thumbnail sizes so the tray tile requests a native size
                    // (availableThumbSizes) and hits the seed below during upload.
                    thumbnails = encryptedBundle.thumbnailDescriptorsFor(p.key),
                    iv = p.iv?.let { Base64.encode(it) },
                    descriptorContent = p.descriptorContent,
                    previewThumbnail = p.previewThumbnail?.let {
                        ThumbnailDescriptor(
                            pixelWidth = it.pixelWidth,
                            pixelHeight = it.pixelHeight,
                            contentType = it.contentType,
                            content = it.content,
                        )
                    },
                )
            }.ifEmpty { null }

            val request = UploadFileRequest(
                driveId = driveId,
                keyHeader = keyHeader,
                metadata = unencryptedMetadata.encryptContent(keyHeader),
                payloads = encryptedBundle.payloads,
                thumbnails = encryptedBundle.thumbnails,
            )

            val enqueued = outboxSync.tryEnqueue(request)
            if (!enqueued.enqueued) return null

            // Optimistic local write + in-memory insert so the tray shows the sticker
            // immediately, before the outbox round-trips. The real fileId is assigned by
            // [OptimisticWriter] (and re-emitted by DriveSync); the in-memory optimistic
            // row keys on uniqueId, so [StickerStream.upsertSticker] swaps in the
            // server-confirmed SavedSticker (correct fileId) when it lands.
            // Pre-mint the optimistic fileId so the tray tile, the seeded cache, and
            // rekeyCacheAfterCreate all key on the same id — and so we can seed BEFORE
            // the write (which triggers the tray render → thumbnail read): otherwise
            // that read races the seed, misses, and 404s (non-retriable) → blur.
            val optimisticFileId = Uuid.random()
            try {
                payloadCacheSeeder.seed(driveId, optimisticFileId, encryptedBundle)
                optimisticWriter.writeNewFile(
                    driveId = driveId,
                    keyHeader = keyHeader,
                    unecryptedMetadata = unencryptedMetadata,
                    originalRecipientCount = 0,
                    fileSystemType = FileSystemType.Standard,
                    payloadDescriptors = payloadDescriptors,
                    fileId = optimisticFileId,
                )
            } catch (e: Exception) {
                Logger.e(e, TAG) { "Optimistic write failed (non-fatal) for sticker $uniqueId" }
            }

            stickerStream.insertOptimistic(
                SavedSticker(
                    // Pending-row fileId; the confirmed row (with the server fileId)
                    // replaces it via the stream's uniqueId-keyed upsert.
                    fileId = optimisticFileId,
                    uniqueId = uniqueId,
                    driveId = driveId,
                    payloadKey = key,
                    contentType = uploadType,
                    keyHeader = keyHeader,
                    previewThumbnail = encryptedBundle.previewThumbs.firstOrNull(),
                    payloadDescriptor = payloadDescriptors?.firstOrNull()
                        ?: PayloadDescriptor(key = key, contentType = uploadType),
                    createdAt = Clock.System.now().toEpochMilliseconds(),
                    sourceFileId = sourceFileId,
                    isPending = true,
                )
            )

            Logger.d(tag = TAG) { "Sticker saved uniqueId=$uniqueId" }
            uniqueId
        } catch (e: Exception) {
            Logger.e(e, TAG) { "Failed to enqueue sticker upload" }
            null
        } finally {
            fileOperationsProvider.deleteTempFile(tempPath)
        }
    }

    /**
     * Download + decrypt the sticker's image bytes to a temp file so the composer can
     * re-stage it as a normal image attachment. Returns the temp path or null.
     */
    suspend fun resolveForSend(sticker: SavedSticker): String? {
        return try {
            val iv = sticker.payloadDescriptor.iv
            val keyHeader = if (iv != null) {
                try {
                    KeyHeader(Base64.decode(iv), sticker.keyHeader.aesKey)
                } catch (_: Exception) {
                    sticker.keyHeader
                }
            } else {
                sticker.keyHeader
            }

            val bytes = driveFileProvider.getPayloadBytesDecrypted(
                driveId = sticker.driveId,
                fileId = sticker.fileId,
                key = sticker.payloadKey,
                keyHeader = keyHeader,
            )?.bytes ?: return null

            val ext = sticker.contentType.substringAfter("/", "png")
                .let { if (it == "jpeg") "jpg" else it }
            fileOperationsProvider.writeBytesToTempFile(bytes, "sticker_send_", ".$ext")
        } catch (e: Exception) {
            Logger.e(e, TAG) { "Failed to resolve sticker ${sticker.fileId} for send" }
            null
        }
    }

    /** Enqueue a delete for the saved sticker (mirror of VaultService.deleteEntry). */
    suspend fun deleteSticker(sticker: SavedSticker): Boolean {
        return try {
            stickerStream.removeOptimistic(sticker.uniqueId)
            outboxSync.tryEnqueue(
                request = DeleteLocalFilesByFileIdRequest(
                    driveId = driveId,
                    fileIds = listOf(sticker.fileId),
                ),
            ).enqueued
        } catch (e: Exception) {
            Logger.e(e, TAG) { "Failed to enqueue sticker delete: ${sticker.uniqueId}" }
            false
        }
    }
}
