package id.homebase.chat.services.convo

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.upload.DriveUploadProvider
import id.homebase.api.client.drives.upload.TransitOptions
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.PayloadBundle
import id.homebase.chat.services.PayloadBundleEncryptionService
import id.homebase.chat.services.XorIdUtil
import id.homebase.core.config.chatTargetDrive
import kotlin.uuid.Uuid

class ConversationWriterService(
    private val credentialsManager: CredentialsManager,
    private val driveUploadProvider: DriveUploadProvider,
    private val payloadBundleEncryptionService: PayloadBundleEncryptionService
) {
    private val chatDrive = chatTargetDrive.alias

    suspend fun createConversation(
        recipients: List<String>,
        title: String?,
        payloadBundle: PayloadBundle?
    ): Uuid {

        val credentials = credentialsManager.requireActiveCredentials()
        val domain = credentials.domain
        val keyHeader = KeyHeader.newRandom16()

        val newConversationId: Uuid =
            if (recipients.size == 1) {
                XorIdUtil.getNewXorId(domain, recipients.first())
            } else {
                Uuid.random()
            }

        val content = ConversationAppDataJson(
            title = title ?: "",
            recipients = (recipients + domain).distinct(),
            version = 1
        )

        val encryptedBundle = payloadBundleEncryptionService.encryptBundle(payloadBundle, keyHeader)

        val metadata =
            UploadFileMetadata(
                allowDistribution = false,
                isEncrypted = true,
                appData =
                    UploadAppFileMetaData(
                        uniqueId = newConversationId.toString(),
                        fileType = ChatProtocol.ConversationFileType,
                        content = OdinSystemSerializer.serialize(content),
                        previewThumbnail = encryptedBundle
                            ?.previewThumbs
                            ?.minByOrNull { it.pixelWidth }
                    ),
            )

        val request =
            UploadFileRequest(
                driveId = chatDrive,
                keyHeader = keyHeader,
                metadata = metadata.encryptContent(keyHeader),
                transitOptions =
                    TransitOptions(
                        recipients = recipients,
                        useAppNotification = false
                    ),
                payloads = encryptedBundle.payloads,
                thumbnails = encryptedBundle.thumbnails
            )

        driveUploadProvider.uploadFile(request)
        return newConversationId;
    }
}
