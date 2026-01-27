package id.homebase.chat.data

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.drives.query.QueryBatchCursor
import id.homebase.api.client.drives.upload.DriveUploadProvider
import id.homebase.api.client.drives.upload.PushNotificationOptions
import id.homebase.api.client.drives.upload.TransitOptions
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.BatchResult
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.QueryBatch
import id.homebase.chat.config.chatTargetDrive
import id.homebase.homebasekmppoc.prototype.lib.serialization.OdinSystemSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.uuid.Uuid

class ChatMessageSenderService(
    private val credentialsManager: CredentialsManager,
    private val driveUploadProvider: DriveUploadProvider,
    private val eventBus: EventBus,
    private val scope: CoroutineScope
) {

    private val chatDrive = chatTargetDrive.alias

    suspend fun sendMessage(
        conversationId: Uuid,
        messageText: String,
        recipients: List<String>,
        chatId: Uuid? = null,
        userDate: Long? = null,
        tags: List<String>? = null
    ): SendMessageResult {

        val credentials = credentialsManager.requireActiveCredentials()

        // 1️⃣ uniqueId (retry-safe if provided)
        val uniqueId = chatId ?: Uuid.random()

        // 2️⃣ Chat message content (matches ChatMessageContent)
        val content = ChatMessageContent(
            replyId = null,
            replyPreview = null,
            message = messageText,
            deliveryStatus = ChatDeliveryStatus.Sent.value
        )

        // 3️⃣ Metadata (UploadFileMetadata + UploadAppFileMetaData)
        val metadata =
            UploadFileMetadata(
                allowDistribution = true,
                isEncrypted = true,
                appData =
                    UploadAppFileMetaData(
                        uniqueId = uniqueId.toString(),
                        groupId = conversationId.toString(),
                        fileType = CHAT_MESSAGE_FILE_TYPE,
                        userDate = userDate ?: Unix,
                        content = OdinSystemSerializer.serialize(content),
                        tags = tags,
                        dataType = null,
                        archivalStatus = null,
                        previewThumbnail = null
                    )
            )

        // 4️⃣ Upload request
        val request =
            UploadFileRequest(
                driveId = chatDrive,
                keyHeader = KeyHeader.newRandom16(),
                metadata = metadata.encryptContent(KeyHeader.newRandom16()),
                transitOptions =
                    TransitOptions(
                        recipients = recipients,
                        useAppNotification = true,
                        appNotificationOptions =
                            PushNotificationOptions(
                                appId = CHAT_APP_ID.toString(),
                                typeId = conversationId.toString(),
                                tagId = uniqueId.toString(),
                                silent = false,
                                unEncryptedMessage = "You have a new message"
                            )
                    )
            )

        // 5️⃣ Upload
        val result =
            driveUploadProvider.uploadFile(request)
                ?: error("Failed to upload chat message")

        // 6️⃣ Return exactly what the original service returns
        return SendMessageResult(
            fileId = result.fileId,
            uniqueId = uniqueId,
            versionTag = result.newVersionTag
        )
    }

}
