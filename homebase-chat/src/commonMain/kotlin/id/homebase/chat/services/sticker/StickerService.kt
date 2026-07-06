@file:OptIn(ExperimentalUuidApi::class, ExperimentalEncodingApi::class)

package id.homebase.chat.services.sticker

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.DeleteLocalFilesByFileIdRequest
import id.homebase.api.client.drives.files.DescriptorContent
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.image.convertHeicToJpeg
import id.homebase.api.lib.image.ImageFormatDetector
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.OutboxSync
import id.homebase.api.sync.database.enqueued
import id.homebase.upload.MediaUploadSpec
import id.homebase.upload.PayloadBundle
import id.homebase.upload.UploadOutcome
import id.homebase.upload.UploadService
import id.homebase.chat.services.builder.MessageThumbnailGenerator
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
    private val uploadService: UploadService,
    private val fileOperationsProvider: FileOperationsProvider,
    private val driveFileProvider: DriveFileProvider,
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

            val content = OdinSystemSerializer.serialize(
                StickerFileContent(name = name, sourceFileId = sourceFileId)
            )
            // previewThumbs pass through encryption unchanged, so derive the preview from the
            // plaintext bundle (UploadService owns the encrypt).
            val unencryptedMetadata = UploadFileMetadata(
                allowDistribution = false,
                isEncrypted = true,
                appData = UploadAppFileMetaData(
                    uniqueId = uniqueId,
                    content = content,
                    fileType = StickerProtocol.STICKER_FILE_TYPE,
                    previewThumbnail = bundle.previewThumbs.firstOrNull(),
                ),
            )

            // Pre-mint the optimistic fileId so the tray tile, the seeded cache, and
            // rekeyCacheAfterCreate all key on the same id.
            val optimisticFileId = Uuid.random()
            val outcome = uploadService.upload(
                MediaUploadSpec(
                    driveId = driveId,
                    uniqueId = uniqueId,
                    keyHeader = keyHeader,
                    bundle = bundle,
                    metadata = unencryptedMetadata,
                    // Local-only (allowDistribution=false): no transit recipients.
                    originalRecipientCount = 0,
                    optimisticFileId = optimisticFileId,
                ),
                scope = scope,
            )
            if (outcome !is UploadOutcome.Enqueued) {
                Logger.w(tag = TAG) { "Sticker not enqueued uniqueId=$uniqueId outcome=$outcome" }
                return null
            }

            // In-memory tray insert so the tray shows the sticker immediately, before the
            // outbox round-trips. The optimistic DB write + cache seed already ran inside
            // UploadService (keyed on the same optimisticFileId); this is the tray mirror,
            // keyed on uniqueId, swapped for the server-confirmed row when it lands. It reuses
            // the payload descriptor UploadService built from the encrypted bundle.
            stickerStream.insertOptimistic(
                SavedSticker(
                    fileId = optimisticFileId,
                    uniqueId = uniqueId,
                    driveId = driveId,
                    payloadKey = key,
                    contentType = uploadType,
                    keyHeader = keyHeader,
                    previewThumbnail = bundle.previewThumbs.firstOrNull(),
                    payloadDescriptor = outcome.payloadDescriptors?.firstOrNull()
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
