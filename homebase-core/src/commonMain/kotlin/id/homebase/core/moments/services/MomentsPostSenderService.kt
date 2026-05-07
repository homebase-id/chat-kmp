package id.homebase.core.moments.services

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.upload.PushNotificationOptions
import id.homebase.api.client.drives.upload.SendContents
import id.homebase.api.client.drives.upload.TransitOptions
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.common.OdinId
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.OutboxSync
import id.homebase.chat.services.PayloadBundleEncryptor
import id.homebase.chat.services.builder.AttachmentInput
import id.homebase.chat.services.builder.MessageAttachmentBuilder
import id.homebase.core.config.momentsLabeledDrive
import kotlinx.coroutines.CoroutineScope
import kotlin.uuid.Uuid

class MomentsPostSenderService(
    private val outboxSync: OutboxSync,
    private val payloadBundleEncryptor: PayloadBundleEncryptor,
    private val fileOps: FileOperationsProvider,
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
        return PostMomentResult(uniqueId = momentUniqueId)
    }
}

data class PostMomentResult(val uniqueId: Uuid)
