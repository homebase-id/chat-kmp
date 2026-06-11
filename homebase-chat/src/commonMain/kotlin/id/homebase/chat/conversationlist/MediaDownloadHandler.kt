package id.homebase.chat.conversationlist

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.DescriptorContent
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.coroutines.ioDispatcher
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.video.VideoCompressionService
import id.homebase.core.util.extensionForMimeType
import id.homebase.api.video.VideoMetadata
import id.homebase.chat.conversationlist.ConversationListUiEvent.SaveFileToDevice
import id.homebase.chat.conversationlist.ConversationListUiEvent.ShareFile
import id.homebase.chat.conversationlist.ConversationListUiEvent.ShareText
import id.homebase.chat.conversationlist.ConversationListUiEvent.ShowErrorMessage
import id.homebase.chat.services.ChatMessageActionService
import id.homebase.chat.services.ChatMessageStream
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.LocalAttachmentContextStore
import id.homebase.core.config.chatTargetDrive
import id.homebase.core.util.extensionForMimeType
import io.github.vinceglb.filekit.name
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

/**
 * Handles media-related action arms (share / download / decrypt / open in
 * full-screen) extracted from `ConversationListViewModel.onAction`. Behavior
 * is byte-identical to the previous in-VM implementation; the move is purely
 * structural — see PR 4 in `lucky-chasing-valley.md` for context.
 */
internal class MediaDownloadHandler(
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<ConversationListUiState>,
    private val messagesUiState: MutableStateFlow<MessageListUiState>,
    private val driveFileProvider: DriveFileProvider,
    private val fileOperationsProvider: FileOperationsProvider,
    private val chatMessageActionService: ChatMessageActionService,
    private val chatMessageStream: ChatMessageStream,
    private val localVideoContextStore: LocalAttachmentContextStore,
    private val sendEvent: (ConversationListUiEvent) -> Unit,
    private val dispatch: (ConversationListUiAction) -> Unit,
) {

    fun handleShareMedia(action: ConversationListUiAction.ShareMedia) {
        scope.launch {
            try {
                val messageModel =
                    messagesUiState.value.messages.filterIsInstance<MessageListContentModel.Message>()
                        .find { it.message.id == action.messageId } ?: return@launch
                val message = messageModel.message
                val payload =
                    message.payloads?.find { it.key == action.payloadKey } ?: return@launch
                val payloadIv = Base64.decode(
                    payload.iv ?: throw IllegalStateException(
                        "encrypted payload requires key header"
                    )
                )
                val bytes = chatMessageActionService.getPayloadBytes(
                    message.fileId,
                    action.payloadKey,
                    KeyHeader(payloadIv, message.keyHeader.aesKey)
                )
                if (bytes != null) {
                    val extension = payload.contentType?.let { extensionForMimeType(it) }
                        ?: payload.contentType?.substringAfter("/")
                        ?: "bin"
                    // Cleartext copy of an end-to-end-encrypted Homebase
                    // payload — sequestered into the share_outbound subdir so
                    // the cold-start + foreground sweepers can reap it as a
                    // single unit, bounding its on-disk lifetime.
                    val tempPath = fileOperationsProvider.writeBytesToShareOutboundFile(
                        bytes, ".$extension"
                    )
                    sendEvent(ShareFile(tempPath))
                } else {
                    sendEvent(
                        ShowErrorMessage(
                            "Failed to download file for sharing"
                        )
                    )
                }
            } catch (e: Exception) {
                Logger.e(throwable = e, tag = "ConversationListViewModel") {
                    "Failed to share media: ${e.message}"
                }
                sendEvent(
                    ShowErrorMessage(
                        "Failed to share: ${e.message}"
                    )
                )
            }
        }
    }

    fun handleShareMessage(action: ConversationListUiAction.ShareMessage) {
        val message = action.message
        val filteredPayloads = message.payloads?.filter {
            !listOf(
                ChatProtocol.PAYLOAD_KEY_MESSAGE_WEB,
                ChatProtocol.DefaultPayloadKey,
                ChatProtocol.DEFAULT_PAYLOAD_DESCRIPTOR_KEY
            ).contains(it.key)
        }
        val hasMedia = !filteredPayloads.isNullOrEmpty()
        if (hasMedia) {
            // Share the first media payload as a file
            val payload = filteredPayloads.first()
            dispatch(ConversationListUiAction.ShareMedia(message.id, payload.key))
        } else {
            // Text-only message
            val text = message.content
            if (text.isNotBlank()) {
                sendEvent(ShareText(text))
            }
        }
    }

    fun handleDownloadMedia(action: ConversationListUiAction.DownloadMedia) {
        val message =
            messagesUiState.value.messages.filterIsInstance<MessageListContentModel.Message>()
                .map { it.message }.find { it.id == action.messageId } ?: return

        val fileKey = "${message.id}_${action.payloadKey}"

        // 1. Add to downloadingFiles set
        messagesUiState.update { it.copy(downloadingFiles = it.downloadingFiles + fileKey) }

        scope.launch {
            try {
                val payload =
                    message.payloads?.find { it.key == action.payloadKey } ?: return@launch
                val payloadIv = Base64.decode(
                    payload.iv ?: throw IllegalStateException(
                        "encrypted payload requires key header"
                    )
                )

                val fullName = resolveDownloadFileName(
                    payload.filename(), payload.key, payload.contentType
                )
                val filePath =
                    "${fileOperationsProvider.getCacheDirectory()}/$fullName"

                val success = withContext(ioDispatcher) {
                    driveFileProvider.streamPayloadDecryptedToPath(
                        driveId = chatTargetDrive.alias,
                        fileId = message.fileId,
                        key = action.payloadKey,
                        keyHeader = KeyHeader(payloadIv, message.keyHeader.aesKey),
                        outputPath = filePath,
                        fileOps = fileOperationsProvider,
                    )
                }

                if (success) {
                    sendEvent(SaveFileToDevice(filePath, fullName))
                } else {
                    sendEvent(ShowErrorMessage("Could not download file"))
                }
            } catch (e: Exception) {
                sendEvent(
                    ShowErrorMessage(
                        "Error downloading file: ${e.message}"
                    )
                )
            } finally {
                messagesUiState.update {
                    it.copy(downloadingFiles = it.downloadingFiles - fileKey)
                }
            }
        }
    }

    fun handleDownloadVideoMedia(action: ConversationListUiAction.DownloadVideoMedia) {
        val fileKey = "${action.fileId}_${action.payloadKey}"
        messagesUiState.update { it.copy(downloadingFiles = it.downloadingFiles + fileKey) }

        scope.launch {
            try {
                val hlsMetadata = resolveHlsVideoMetadata(
                    descriptorContent = action.payload.descriptorContent,
                    fileId = action.fileId,
                    keyHeader = action.keyHeader,
                )

                if (hlsMetadata != null) {
                    val (mp4Path, mp4Name) = withContext(ioDispatcher) {
                        downloadAndRemuxHlsToMp4(
                            fileId = action.fileId,
                            payloadKey = action.payloadKey,
                            keyHeader = action.keyHeader,
                            metadata = hlsMetadata,
                            suggestedBaseName = action.payload.filename(),
                        )
                    } ?: run {
                        sendEvent(ShowErrorMessage("Could not convert video"))
                        return@launch
                    }
                    sendEvent(SaveFileToDevice(mp4Path, mp4Name))
                } else {
                    val fullName = resolveDownloadFileName(
                        action.payload.filename(), action.payloadKey, action.payload.contentType
                    )
                    val filePath =
                        "${fileOperationsProvider.getCacheDirectory()}/$fullName"

                    val success = withContext(ioDispatcher) {
                        driveFileProvider.streamPayloadDecryptedToPath(
                            driveId = chatTargetDrive.alias,
                            fileId = action.fileId,
                            key = action.payloadKey,
                            keyHeader = action.keyHeader,
                            outputPath = filePath,
                            fileOps = fileOperationsProvider,
                        )
                    }

                    if (success) {
                        sendEvent(SaveFileToDevice(filePath, fullName))
                    } else {
                        sendEvent(ShowErrorMessage("Could not download file"))
                    }
                }
            } catch (e: Exception) {
                sendEvent(ShowErrorMessage("Error downloading file: ${e.message}"))
            } finally {
                messagesUiState.update { it.copy(downloadingFiles = it.downloadingFiles - fileKey) }
            }
        }
    }

    fun handleDecryptFile(action: ConversationListUiAction.DecryptFile) {
        val message =
            messagesUiState.value.messages.filterIsInstance<MessageListContentModel.Message>()
                .map { it.message }.find { it.id == action.messageId } ?: return

        val fileKey = "${message.id}_${action.payloadKey}"

        scope.launch {
            try {

                val payload =
                    message.payloads?.find { it.key == action.payloadKey } ?: return@launch
                val payloadIv = Base64.decode(
                    payload.iv ?: throw IllegalStateException(
                        "encrypted payload requires key header"
                    )
                )

                val rawName = payload.filename() ?: payload.key
                val filePath = if (rawName.contains('.')) {
                    "${fileOperationsProvider.getCacheDirectory()}/$rawName"
                } else {
                    val extension = payload.contentType?.let { extensionForMimeType(it) }
                        ?: payload.contentType?.substringAfter("/")
                        ?: "bin"
                    "${fileOperationsProvider.getCacheDirectory()}/$rawName.$extension"
                }

                val success = driveFileProvider.streamPayloadDecryptedToPath(
                    driveId = chatTargetDrive.alias,
                    fileId = message.fileId,
                    key = action.payloadKey,
                    keyHeader = KeyHeader(payloadIv, message.keyHeader.aesKey),
                    outputPath = filePath,
                    fileOps = fileOperationsProvider,
                )

                if (success) {
                    val decryptedFiles =
                        messagesUiState.value.decryptedFiles.toMutableMap()
                    decryptedFiles[DecryptedFileKey(message.fileId, action.payloadKey)] =
                        filePath
                    messagesUiState.update { it.copy(decryptedFiles = decryptedFiles.toPersistentMap()) }
                } else {
                    sendEvent(ShowErrorMessage("Error downloading file"))
                }
            } catch (e: Exception) {
                sendEvent(
                    ShowErrorMessage("Error downloading file: ${e.message}")
                )
            } finally {
                // 4. Remove from downloadingFiles set
                uiState.update {
                    it.copy(downloadingFiles = it.downloadingFiles - fileKey)
                }
            }
        }
    }

    fun handleSaveFile(action: ConversationListUiAction.SaveFile) {
        val (filePath, fileName) = when (val f = action.file) {
            is AttachmentPendingFile.FileImage -> f.file.toString() to f.file.name
            is AttachmentPendingFile.FileVideo -> f.file.toString() to f.file.name
            is AttachmentPendingFile.File -> f.file.toString() to f.file.name
            is AttachmentPendingFile.Gallery -> f.image.file.toString() to f.image.fileName
            is AttachmentPendingFile.Audio -> f.audioFile.toString() to f.audioFile.name
        }
        sendEvent(SaveFileToDevice(filePath, fileName))
    }

    fun handleMediaClicked(action: ConversationListUiAction.MediaClicked) {
        scope.launch {
            try {
                val selectedPayload =
                    action.message.payloads?.firstOrNull { it.key == action.payloadKey }
                        ?: return@launch
                val contentType = selectedPayload.contentType ?: ""
                // A sticker tap (WhatsApp-style) opens the sticker-options bottom sheet — add/
                // remove from the user's library + save to device — instead of the fullscreen
                // viewer. Detected the same way the bubble decides to render bare (PR #664
                // descriptorContent {"isSticker":true}). Regular images fall through to the
                // fullscreen path unchanged.
                val isSticker =
                    (selectedPayload.descriptorInfo() as? DescriptorContent.ImageFile)?.isSticker == true
                when {
                    contentType.startsWith("image/") && isSticker -> {
                        dispatch(
                            ConversationListUiAction.ShowStickerOptions(
                                message = action.message,
                                payloadKey = action.payloadKey,
                            )
                        )
                    }

                    contentType.startsWith("image/") -> {
                        Logger.d("Image clicked: ${action.message.id}:${action.payloadKey}")

                        messagesUiState.update {
                            it.copy(
                                fullScreenOverlay = FullScreenOverlay.ViewMessageData(
                                    messageId = action.message.id,
                                    title = action.message.originalAuthor?.domainName
                                        ?: "null",
                                    userDate = action.message.userDate,
                                    content = action.message.content,
                                    fileId = action.message.fileId,
                                    driveId = chatTargetDrive.alias,
                                    payloads = action.message.payloads,
                                    selectedPayloadKey = action.payloadKey,
                                    keyHeader = action.message.keyHeader,
                                )
                            )
                        }
                    }

                    contentType.startsWith("video/") || contentType == "application/vnd.apple.mpegurl" -> {
                        val localContext = localVideoContextStore.get(action.message.id, selectedPayload.key)
                        val ivBytes = selectedPayload.iv?.let { Base64.decode(it) }

                        if (ivBytes != null || localContext != null) {
                            messagesUiState.update {
                                it.copy(
                                    fullScreenOverlay = FullScreenOverlay.VideoPlayerData(
                                        fileId = action.message.fileId,
                                        driveId = chatTargetDrive.alias,
                                        payloadKey = action.payloadKey,
                                        keyHeader = KeyHeader(
                                            iv = ivBytes ?: ByteArray(16),
                                            aesKey = action.message.keyHeader.aesKey
                                        ),
                                        payload = selectedPayload,
                                        localFilePath = localContext?.localFilePath,
                                        uploadMessageId = if (localContext != null) action.message.id else null,
                                    )
                                )
                            }
                        }
                    }

                    contentType.startsWith("audio/") -> {}
                    contentType.startsWith("application/") || contentType.startsWith("text/") || contentType.startsWith(
                        "message/"
                    ) -> {
                        dispatch(
                            ConversationListUiAction.DownloadMedia(
                                action.message.id, action.payloadKey
                            )
                        )
                    }

                    else -> {
                        dispatch(
                            ConversationListUiAction.DownloadMedia(
                                action.message.id, action.payloadKey
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Logger.e("Failed to handle media click", e)
                sendEvent(
                    ShowErrorMessage(
                        "Failed to handle media click: ${e.message}"
                    )
                )
            }
        }
    }

    fun handleShowMoreClicked(action: ConversationListUiAction.ShowMoreClicked) {
        scope.launch {

            val full = chatMessageStream.loadFullMessage(
                action.conversationId,
                action.messageId
            ) ?: return@launch

            messagesUiState.update { state ->
                state.copy(
                    messages = state.messages.map { item ->

                        if (item is MessageListContentModel.Message &&
                            item.message.id == action.messageId
                        ) {

                            val updatedMessage =
                                item.message.copy(
                                    content = full,
                                    hasMore = false,
                                    messageAppData = item.message.messageAppData.copy(
                                        message = JsonPrimitive(full)
                                    )
                                )

                            item.copy(message = updatedMessage)

                        } else item

                    }.toPersistentList()
                )
            }
        }
    }

    fun handleCloseFullScreenOverlay() {
        // Dismissing the attachment editor: release any video playable handles (web blob: URLs;
        // no-op on native) so the browser can drop the File references.
        (messagesUiState.value.fullScreenOverlay as? FullScreenOverlay.AttachmentData)?.attachments
            ?.forEach { if (it is AttachmentPendingFile.FileVideo) it.playablePath?.let(::revokePlayableUrl) }
        messagesUiState.update { it.copy(fullScreenOverlay = null) }
    }

    private suspend fun resolveHlsVideoMetadata(
        descriptorContent: String?,
        fileId: Uuid,
        keyHeader: KeyHeader,
    ): VideoMetadata? {
        val stub = descriptorContent?.let {
            try {
                OdinSystemSerializer.deserialize<VideoMetadata>(it)
            } catch (_: Exception) {
                null
            }
        } ?: return null

        if (!stub.isSegmented) return null

        val full = if (stub.isDescriptorContentComplete) {
            stub
        } else {
            val json = driveFileProvider.getPayloadBytesDecrypted(
                driveId = chatTargetDrive.alias,
                fileId = fileId,
                key = stub.key,
                keyHeader = keyHeader,
            )?.bytes?.decodeToString() ?: return null
            try {
                OdinSystemSerializer.deserialize<VideoMetadata>(json)
            } catch (_: Exception) {
                return null
            }
        }

        return if (full.isSegmented && !full.hlsPlaylist.isNullOrBlank()) full else null
    }

    /**
     * Decrypts the HLS segment payload + synthesizes a local playlist, then remuxes into an MP4
     * using stream copy (no re-encoding). Returns (mp4Path, suggestedName) or null on failure.
     */
    private suspend fun downloadAndRemuxHlsToMp4(
        fileId: Uuid,
        payloadKey: String,
        keyHeader: KeyHeader,
        metadata: VideoMetadata,
        suggestedBaseName: String?,
    ): Pair<String, String>? {
        val cacheDir = fileOperationsProvider.getCacheDirectory()
        val uid = Uuid.random().toString().take(8)
        val tsFileName = "input_hlsdl_${uid}.ts"
        val tsPath = "$cacheDir/$tsFileName"
        val mp4Path = "$cacheDir/hlsdl_${uid}.mp4"

        val tsOk = driveFileProvider.streamPayloadDecryptedToPath(
            driveId = chatTargetDrive.alias,
            fileId = fileId,
            key = payloadKey,
            keyHeader = keyHeader,
            outputPath = tsPath,
            fileOps = fileOperationsProvider,
        )
        if (!tsOk) return null

        // Strip EXT-X-KEY (segments are already decrypted on disk) and rewrite segment
        // references to point at the local .ts file we just wrote.
        val rewrittenPlaylist = metadata.hlsPlaylist!!.lines()
            .filter { !it.startsWith("#EXT-X-KEY") }
            .joinToString("\n") { line ->
                if (line.isNotBlank() && !line.startsWith("#")) tsFileName else line
            }

        // cacheInputVideo writes to "<cacheDir>/input_<fileName>" on all platforms, which is the
        // same directory as tsPath — the playlist's relative segment reference resolves correctly.
        val playlistPath = VideoCompressionService.cacheInputVideo(
            fileName = "hlsdl_${uid}.m3u8",
            data = rewrittenPlaylist.encodeToByteArray(),
        )

        val ok = VideoCompressionService.remuxHlsToMp4(playlistPath = playlistPath, outputPath = mp4Path)

        // Clean up intermediates regardless of success
        runCatching { fileOperationsProvider.deleteTempFile(tsPath) }
        runCatching { fileOperationsProvider.deleteTempFile(playlistPath) }

        if (!ok) return null

        val base = suggestedBaseName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "video"
        val safeBase = base.replace('/', '_').replace('\\', '_').replace('\u0000', '_')
        return mp4Path to "$safeBase.mp4"
    }

    /**
     * Returns a safe filename for saving a downloaded file.
     * If the original filename (from descriptorContent) has an extension, uses it as-is.
     * Only derives an extension from contentType when there's no original filename or no extension.
     */
    private fun resolveDownloadFileName(
        originalName: String?,
        fallbackKey: String,
        contentType: String?,
    ): String {
        val safeName = originalName
            ?.replace('/', '_')
            ?.replace('\\', '_')
            ?.replace('\u0000', '_')

        // If the original filename has an extension, trust it completely
        if (safeName != null && safeName.contains('.')) return safeName

        // No extension in name — derive one from contentType
        val name = safeName ?: fallbackKey
        val ext = contentType?.let { extensionForMimeType(it) }
            ?: contentType?.substringAfter("/")
                ?.takeIf { it != "octet-stream" && !it.contains('.') && !it.contains('+') }
            ?: "bin"
        return "$name.$ext"
    }
}
