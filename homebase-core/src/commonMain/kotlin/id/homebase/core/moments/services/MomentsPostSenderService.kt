package id.homebase.core.moments.services

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.ThumbnailDescriptor
import id.homebase.api.client.drives.upload.FileUpdateInstructionSet
import id.homebase.api.client.drives.upload.PushNotificationOptions
import id.homebase.api.client.drives.upload.SendContents
import id.homebase.api.client.drives.upload.TransitOptions
import id.homebase.api.client.drives.upload.UpdateFileByUniqueIdRequest
import id.homebase.api.client.drives.upload.UpdateLocale
import id.homebase.api.client.drives.upload.UpdateManifest
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.common.OdinId
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.OutboxSync
import id.homebase.chat.services.PayloadBundleEncryptor
import id.homebase.chat.services.builder.AttachmentInput
import id.homebase.chat.services.builder.MessageAttachmentBuilder
import id.homebase.chat.services.outbox.OptimisticWriter
import id.homebase.core.config.momentsLabeledDrive
import kotlinx.coroutines.CoroutineScope
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.Uuid

class MomentsPostSenderService(
    private val outboxSync: OutboxSync,
    private val payloadBundleEncryptor: PayloadBundleEncryptor,
    private val fileOps: FileOperationsProvider,
    private val driveFileProvider: DriveFileProvider,
    private val optimisticWriter: OptimisticWriter,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "MomentsPostSenderService"
    }

    private val drive = momentsLabeledDrive.drive.alias

    suspend fun postMoment(
        attachments: List<AttachmentInput>,
        description: String,
        recipients: List<OdinId>,
        momentUniqueId: Uuid = Uuid.random(),
        userDate: UnixTimeUtc? = null,
    ): PostMomentResult {
        Logger.d(tag = TAG) {
            "postMoment: starting moment=$momentUniqueId attachments=${attachments.size} recipients=${recipients.size}"
        }

        // Server constraint: payload keys must match ^[a-z0-9_]{8,10}$. The
        // padStart keeps the key fixed at 8 chars across single- and multi-digit
        // indices (mmt_0000 .. mmt_9999).
        val bundle = MessageAttachmentBuilder.build(
            attachments = attachments,
            fileOperationsProvider = fileOps,
        ) { index, _ -> "mmt_${index.toString().padStart(4, '0')}" }

        val keyHeader = KeyHeader.newRandom16()
        val encrypted = payloadBundleEncryptor.encryptBundle(
            uniqueId = momentUniqueId,
            bundle = bundle,
            aesKey = keyHeader.aesKey,
            scope = scope,
        )

        val isLocalOnly = recipients.isEmpty()
        val effectiveUserDate = userDate ?: UnixTimeUtc.now()

        val content = OdinSystemSerializer.serialize(
            MomentPostContent(
                version = MomentsProtocol.MomentPostVersionNumberOne,
                description = description,
            )
        )

        val unencryptedMetadata = UploadFileMetadata(
            allowDistribution = !isLocalOnly,
            isEncrypted = true,
            appData = UploadAppFileMetaData(
                uniqueId = momentUniqueId,
                fileType = MomentsProtocol.MomentPostFileType,
                userDate = effectiveUserDate.milliseconds,
                content = content,
                previewThumbnail = encrypted.previewThumbs.minByOrNull { it.pixelWidth },
            ),
        )

        val request = UploadFileRequest(
            driveId = drive,
            keyHeader = keyHeader,
            metadata = unencryptedMetadata.encryptContent(keyHeader),
            transitOptions = TransitOptions(
                recipients = recipients,
                sendContents = SendContents.All,
                useAppNotification = !isLocalOnly,
                appNotificationOptions = if (isLocalOnly) null else PushNotificationOptions(
                    appId = MomentsProtocol.MomentsAppId.toString(),
                    typeId = momentUniqueId.toString(),
                    tagId = momentUniqueId.toString(),
                    silent = false,
                    unEncryptedMessage = "New moment posted",
                ),
            ),
            payloads = encrypted.payloads,
            thumbnails = encrypted.thumbnails,
        )

        val enqueued = outboxSync.tryEnqueue(
            request,
            priority = 1,
            dependencyUniqueId = null,
        )

        if (!enqueued) {
            error("Failed to enqueue moment for upload")
        }

        Logger.d(tag = TAG) { "postMoment: outbox enqueued moment=$momentUniqueId" }

        // Best-effort optimistic write — surfaces the moment in the feed
        // immediately so the user sees their own post without waiting for
        // outbox drain → server → sync replay. If this fails the outbox
        // delivery + sync will still bring it back.
        @OptIn(ExperimentalEncodingApi::class)
        val payloadDescriptors = encrypted.payloads.map { payload ->
            PayloadDescriptor(
                key = payload.key,
                contentType = payload.contentType.ifEmpty { null },
                iv = payload.iv?.let { Base64.encode(it) },
                descriptorContent = payload.descriptorContent,
                previewThumbnail = payload.previewThumbnail?.let {
                    ThumbnailDescriptor(
                        pixelWidth = it.pixelWidth,
                        pixelHeight = it.pixelHeight,
                        contentType = it.contentType,
                        content = it.content,
                    )
                },
            )
        }.ifEmpty { null }
        try {
            optimisticWriter.writeNewFile(
                driveId = drive,
                keyHeader = keyHeader,
                unecryptedMetadata = unencryptedMetadata,
                originalRecipientCount = recipients.size,
                fileSystemType = FileSystemType.Standard,
                payloadDescriptors = payloadDescriptors,
            )
            Logger.d(tag = TAG) { "postMoment: optimistic write complete moment=$momentUniqueId" }
        } catch (e: Exception) {
            Logger.e(throwable = e, tag = TAG) {
                "postMoment: optimistic write failed (non-fatal) moment=$momentUniqueId"
            }
        }

        return PostMomentResult(uniqueId = momentUniqueId)
    }

    /**
     * Edit the description of an existing moment. Mirrors
     * [id.homebase.chat.services.ChatMessageSenderService.updateMessage]: the
     * AES key from the original moment is reused (only the IV is regenerated)
     * so the existing media payloads remain decryptable, the manifest carries
     * no payload changes (media is preserved), and the request goes through
     * `replaceEnqueue` so a still-pending earlier post/edit for the same
     * moment is superseded rather than racing.
     *
     * Reads the current file header from the drive to recover the AES key and
     * to seed `userDate` / `previewThumbnail` so the edit doesn't blank them.
     */
    suspend fun updateMoment(
        momentUniqueId: Uuid,
        versionTag: Uuid,
        description: String,
        recipients: List<OdinId>,
    ): UpdateMomentResult {
        Logger.d(tag = TAG) {
            "updateMoment: starting moment=$momentUniqueId recipients=${recipients.size}"
        }

        val existing = driveFileProvider.getFileHeaderByUid(drive, momentUniqueId)
            ?: throw IllegalArgumentException("moment not found: $momentUniqueId")

        if (existing.fileMetadata.versionTag != versionTag) {
            error("VersionTag mismatch")
        }

        val keyHeader = KeyHeader(
            iv = ByteArrayUtil.getRndByteArray(16),
            aesKey = existing.keyHeader.aesKey,
        )

        val isLocalOnly = recipients.isEmpty()

        val content = OdinSystemSerializer.serialize(
            MomentPostContent(
                version = MomentsProtocol.MomentPostVersionNumberOne,
                description = description,
            )
        )

        val unencryptedMetadata = UploadFileMetadata(
            allowDistribution = !isLocalOnly,
            isEncrypted = true,
            versionTag = versionTag,
            appData = UploadAppFileMetaData(
                uniqueId = momentUniqueId,
                fileType = MomentsProtocol.MomentPostFileType,
                userDate = existing.fileMetadata.appData.userDate,
                content = content,
                previewThumbnail = existing.fileMetadata.appData.previewThumbnail,
            ),
        )

        // Empty manifest: no payload appends, no payload deletes — the
        // existing media payloads on the file are left intact.
        val manifest = UpdateManifest.build(
            payloads = null,
            toDeletePayloads = null,
            thumbnails = null,
            generatePayloadIv = false,
        )

        val request = UpdateFileByUniqueIdRequest(
            driveId = drive,
            uniqueId = momentUniqueId,
            keyHeader = keyHeader,
            instructions = FileUpdateInstructionSet(
                transferIv = ByteArrayUtil.getRndByteArray(16),
                locale = UpdateLocale.Local,
                recipients = recipients,
                manifest = manifest,
                useAppNotification = false,
                appNotificationOptions = null,
            ),
            metadata = unencryptedMetadata.encryptContent(keyHeader),
            payloads = emptyList(),
            thumbnails = emptyList(),
        )

        val enqueued = outboxSync.replaceEnqueue(
            request,
            priority = 1,
            dependencyUniqueId = null,
        )

        if (!enqueued) {
            error("Failed to enqueue moment update")
        }

        Logger.d(tag = TAG) { "updateMoment: outbox enqueued moment=$momentUniqueId" }

        // Best-effort optimistic write — applies the description edit to the
        // local copy so the detail screen reflects it immediately. Media
        // payloads on the existing file are preserved (we don't touch them).
        try {
            optimisticWriter.writeUpdate(
                driveId = drive,
                keyHeader = keyHeader,
                unecryptedMetadata = unencryptedMetadata,
            )
            Logger.d(tag = TAG) { "updateMoment: optimistic write complete moment=$momentUniqueId" }
        } catch (e: Exception) {
            Logger.e(throwable = e, tag = TAG) {
                "updateMoment: optimistic write failed (non-fatal) moment=$momentUniqueId"
            }
        }

        return UpdateMomentResult(uniqueId = momentUniqueId)
    }
}

data class PostMomentResult(val uniqueId: Uuid)
data class UpdateMomentResult(val uniqueId: Uuid)
