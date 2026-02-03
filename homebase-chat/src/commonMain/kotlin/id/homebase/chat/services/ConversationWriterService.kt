package id.homebase.chat.services

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.upload.DriveUploadProvider
import id.homebase.api.client.drives.upload.PushNotificationOptions
import id.homebase.api.client.drives.upload.TransitOptions
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.util.truncateToCodePoints
import id.homebase.chat.data.ConversationUiModel
import id.homebase.chat.data.MessageUiModel
import id.homebase.core.config.chatTargetDrive
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

class ConversationWriterService(
    private val credentialsManager: CredentialsManager,
    private val driveUploadProvider: DriveUploadProvider
) {

    private val chatDrive = chatTargetDrive.alias

    suspend fun createConversation(
        recipients: List<String>,
        title: String?,
        imagePayload: ByteArray?
    ): Pair<Uuid, ConversationUiModel?> {

        val credentials = credentialsManager.requireActiveCredentials()
        val domain = credentials.domain

        val newConversationId: Uuid =
            if (recipients.size == 1) {
                XorIdUtil.getNewXorId(domain, recipients.first())
            } else {
                Uuid.random()
            }

        // 1-to-1 conversation: return existing if found
        if (recipients.size == 1) {
            val existing = getConversationById(newConversationId)
            if (existing != null) {
                return newConversationId to existing
            }
        }

        val updatedRecipients =
            (recipients + domain).distinct()

        val keyHeader = KeyHeader.newRandom16()
        val recipients = conversationService.getRecipients(conversationId)

        val encryptedPayloads =
            payloadBundle?.payloads?.map { payload ->

                val encryptedFile =
                    if (payload.contentType.startsWith("video/")) {
                        encodeAndEncryptVideo(
                            inputFile = payload.filePath,
                            keyHeader = keyHeader
                        )
                    } else {
                        encryptFile(
                            inputFile = payload.filePath,
                            keyHeader = keyHeader
                        )
                    }

                payload.copy(
                    filePath = encryptedFile.filePath,
                    iv = encryptedFile.iv,
                    isPreEncrypted = true
                )
            } ?:

        val payloadIvByKey: Map<String, ByteArray> =
            encryptedPayloads.associate { payload ->
                payload.key to (payload.iv ?: error("Missing IV for payload ${payload.key}"))
            }

        val encryptedThumbnails =
            payloadBundle?.thumbnails?.map { thumb ->

                if (thumb.skipEncryption) {
                    thumb
                } else {
                    val payloadIv =
                        payloadIvByKey[thumb.key]
                            ?: error("No payload IV found for thumbnail key=${thumb.key}")

                    val encryptedBytes =
                        keyHeader.encryptDataAes(
                            data = thumb.thumbnailBytes,
                            customIv = payloadIv
                        )

                    thumb.copy(
                        thumbnailBytes = encryptedBytes,
                        skipEncryption = true
                    )
                }
            } ?: emptyList()


        val metadata =
            UploadFileMetadata(
                allowDistribution = true,
                isEncrypted = true,
                appData =
                    UploadAppFileMetaData(
                        uniqueId = newConversationId.toString(),
                        fileType = ChatProtocol.ConversationFileType,
                        content = OdinSystemSerializer.serialize(content),
                        previewThumbnail =
                            payloadBundle
                                ?.previewThumbs
                                ?.minByOrNull { it.pixelWidth }
                    )
            )

        val request =
            UploadFileRequest(
                driveId = chatDrive,
                keyHeader = keyHeader,
                metadata = metadata.encryptContent(keyHeader),
                transitOptions =
                    TransitOptions(
                        recipients = recipients,
                        useAppNotification = true,
                        appNotificationOptions =
                            PushNotificationOptions(
                                appId = ChatProtocol.ChatAppId.toString(),
                                typeId = conversationId.toString(),
                                tagId = uniqueId.toString(),
                                silent = false,
                                unEncryptedMessage = notificationText
                            )
                    ),
                payloads = encryptedPayloads,
                thumbnails = encryptedThumbnails
            )

        val convoUi =
            ConversationService.mapToConversation(uploaded, null)

        insertNewConversation(convoUi)

        return newConversationId to convoUi
    }

}
