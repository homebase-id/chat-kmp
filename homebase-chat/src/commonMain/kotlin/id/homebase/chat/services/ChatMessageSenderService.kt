package id.homebase.chat.services

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.readFileBytes
import id.homebase.api.client.drives.upload.DriveUploadProvider
import id.homebase.api.client.drives.upload.PushNotificationOptions
import id.homebase.api.client.drives.upload.TransitOptions
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.client.drives.writeBytesToTempFile
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.core.config.chatTargetDrive
import id.homebase.api.serialization.OdinSystemSerializer
import kotlin.uuid.Uuid

class ChatMessageSenderService(
    private val driveUploadProvider: DriveUploadProvider,
    private val conversationService: ConversationService
) {
    private val chatDrive = chatTargetDrive.alias

    suspend fun sendNewMessage(
        conversationId: Uuid,
        messageText: String,
        payloadBundle: PayloadBundle?
    ): SendMessageResult =
        sendMessageInternal(
            conversationId = conversationId,
            content =
                MessageAppData(
                    replyId = null,
                    replyPreview = null,
                    message = messageText,
                    deliveryStatus = ChatDeliveryStatus.Sent.value
                ),
            notificationText = "You have a new message",
            payloadBundle = payloadBundle
        )

    suspend fun replyToMessage(
        conversationId: Uuid,
        replyTo: ReplyPreview,
        messageText: String,
        payloadBundle: PayloadBundle?
    ): SendMessageResult =
        sendMessageInternal(
            conversationId = conversationId,
            content =
                MessageAppData(
                    replyId = replyTo.replyUniqueId,
                    replyPreview = replyTo,
                    message = messageText,
                    deliveryStatus = ChatDeliveryStatus.Sent.value
                ),
            notificationText = "You have a new reply",
            payloadBundle = payloadBundle
        )

    private suspend fun sendMessageInternal(
        conversationId: Uuid,
        content: MessageAppData,
        notificationText: String,
        payloadBundle: PayloadBundle?
    ): SendMessageResult {

        val uniqueId = Uuid.random()
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
            } ?: emptyList()

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
                        uniqueId = uniqueId.toString(),
                        groupId = conversationId.toString(),
                        fileType = ChatProtocol.MessageFileType,
                        userDate = UnixTimeUtc.now().milliseconds,
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
        try {
            val result =
                driveUploadProvider.uploadFile(request) ?: error("Failed to upload chat message")
            return SendMessageResult(
                fileId = result.fileId,
                uniqueId = uniqueId,
                versionTag = result.newVersionTag
            )
        } catch (t: Throwable) {
            Logger.e("ChatMessageSenderService", t)
        }

        error("Failed to send chat message")
    }

    private suspend fun encryptFile(
        inputFile: String,
        keyHeader: KeyHeader
    ): EncryptedFileResult {

        // Read full file from disk
        val plainBytes = readFileBytes(inputFile)

        // Per-payload IV
        val payloadIv = ByteArrayUtil.getRndByteArray(16)

        // Encrypt with shared AES key + payload IV
        val encryptedBytes =
            keyHeader.encryptDataAes(
                data = plainBytes,
                customIv = payloadIv
            )

        // Write encrypted payload to temp file
        val encryptedPath =
            writeBytesToTempFile(
                bytes = encryptedBytes,
                prefix = "enc",
                suffix = ".jpg.encrypted"
            )

        return EncryptedFileResult(
            filePath = encryptedPath,
            iv = payloadIv
        )
    }


    private suspend fun encodeAndEncryptVideo(
        inputFile: String,
        keyHeader: KeyHeader
    ): EncryptedFileResult {

        //TODO: BIshwa, we call encoding and encryption here
//        val encodedVideoPath = videoEncoder.encodeToFile(inputFile) // your existing encoder
//
//        return encryptFile(
//            inputFile = encodedVideoPath,
//            keyHeader = keyHeader
//        )


        // for now
        return encryptFile(inputFile, keyHeader);
    }

}

data class EncryptedFileResult(
    val filePath: String,
    val iv: ByteArray
)

