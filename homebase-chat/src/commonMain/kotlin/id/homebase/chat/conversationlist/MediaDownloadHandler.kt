package id.homebase.chat.conversationlist

import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.payload.PayloadLocation
import id.homebase.api.client.payload.PayloadReader
import id.homebase.api.video.isHlsVideo
import id.homebase.api.video.playableContentType
import id.homebase.api.video.playableFileName
import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.coroutines.ioDispatcher
import id.homebase.api.file.AppCacheDirs
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.file.FileTooLargeForMemoryException
import id.homebase.api.file.extensionForContentType
import id.homebase.chat.agentui.agentUiImagePayloads
import id.homebase.chat.data.isImage
import id.homebase.chat.conversationlist.ConversationListUiEvent.FileSaved
import id.homebase.chat.conversationlist.ConversationListUiEvent.OfferRefusedSave
import id.homebase.chat.conversationlist.ConversationListUiEvent.SaveFileToDevice
import id.homebase.chat.conversationlist.ConversationListUiEvent.ShareFile
import id.homebase.chat.conversationlist.ConversationListUiEvent.ShareText
import id.homebase.chat.conversationlist.ConversationListUiEvent.ShowErrorMessage
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.ChatMessageActionService
import id.homebase.chat.services.ChatMessageStream
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.LocalAttachmentContextStore
import id.homebase.chat.services.content.MessageContent
import id.homebase.chat.viewonce.ViewOnceActions
import id.homebase.chat.viewonce.viewOnceViewerFor
import id.homebase.chat.widget.preview.chatChipPreviewKind
import id.homebase.chat.widget.preview.decryptedCopyName
import id.homebase.core.config.chatTargetDrive
import id.homebase.core.localization.TranslationUtil
import id.homebase.core.util.AlbumSaveResult
import id.homebase.core.util.AlbumSaver
import id.homebase.core.util.saveFileName
import id.homebase.core.util.isMobile
import id.homebase.core.util.saveReceived
import id.homebase.core.vault.VaultFileSaver
import id.homebase.resources.MR
import id.homebase.resources.chat_error_convert_video
import id.homebase.resources.chat_error_copy_image
import id.homebase.resources.chat_error_download_file
import id.homebase.resources.chat_error_download_for_sharing
import id.homebase.resources.chat_error_downloading_file
import id.homebase.resources.chat_error_downloading_file_detail
import id.homebase.resources.chat_error_file_too_large_here
import id.homebase.resources.chat_error_media_click
import id.homebase.resources.chat_error_share
import id.homebase.resources.chat_image_copied
import id.homebase.resources.contactbook_self_you
import id.homebase.resources.vault_save_failed
import id.homebase.resources.vault_saved
import io.github.vinceglb.filekit.name
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.compose.resources.StringResource

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
    private val payloadReader: PayloadReader,
    private val fileOperationsProvider: FileOperationsProvider,
    private val chatMessageActionService: ChatMessageActionService,
    private val viewOnceActions: ViewOnceActions,
    private val chatMessageStream: ChatMessageStream,
    private val localVideoContextStore: LocalAttachmentContextStore,
    private val albumSaver: AlbumSaver,
    private val vaultFileSaver: VaultFileSaver? = null,
    private val sendEvent: (ConversationListUiEvent) -> Unit,
    private val dispatch: (ConversationListUiAction) -> Unit,
    private val onMobile: () -> Boolean = { isMobile() },
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
                val payloadIv = (payload.iv ?: throw IllegalStateException(
                        "encrypted payload requires key header"
                    )).bytes
                val extension = extensionForContentType(payload.contentType)
                val tempPath = chatMessageActionService.exportToShareOutbound(
                    message.fileId,
                    payload,
                    KeyHeader(payloadIv, message.keyHeader.aesKey),
                    ".$extension",
                )
                if (tempPath != null) {
                    sendEvent(ShareFile(tempPath))
                } else {
                    sendEvent(
                        ShowErrorMessage(
                            MR.string.chat_error_download_for_sharing,
                        )
                    )
                }
            } catch (e: Exception) {
                Logger.e(throwable = e, tag = "ConversationListViewModel") {
                    "Failed to share media: ${e.message}"
                }
                sendEvent(
                    ShowErrorMessage(
                        MR.string.chat_error_share,
                        e.message ?: "",
                    )
                )
            }
        }
    }

    fun handleSaveMediaToVault(action: ConversationListUiAction.SaveMediaToVault) {
        val saver = vaultFileSaver ?: return
        scope.launch {
            val saved = try {
                val message =
                    messagesUiState.value.messageById(action.messageId) ?: return@launch
                val payload =
                    message.payloads?.find { it.key == action.payloadKey } ?: return@launch
                val contentType = payload.playableContentType ?: return@launch
                val payloadIv = (payload.iv ?: throw IllegalStateException("encrypted payload requires key header")).bytes
                val extension = extensionForContentType(payload.contentType)
                val tempPath = chatMessageActionService.exportToUploadTemp(
                    message.fileId,
                    payload,
                    KeyHeader(payloadIv, message.keyHeader.aesKey),
                    "vault_",
                    ".$extension",
                )
                tempPath != null && saver.save(
                    tempPath,
                    contentType,
                    saveFileName(payload.playableFileName(payload.filename()), contentType, message.userDate.toEpochMilliseconds()),
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(throwable = e, tag = "ConversationListViewModel") {
                    "Failed to save media to Vault: ${e.message}"
                }
                false
            }
            sendEvent(
                if (saved) ConversationListUiEvent.ShowInfoMessage(MR.string.vault_saved)
                else ShowErrorMessage(MR.string.vault_save_failed)
            )
        }
    }

    fun handleCopyImage(action: ConversationListUiAction.CopyImage) {
        val state = messagesUiState.value
        val loaded = state.messageById(action.messageId)
        val viewer = (state.fullScreenOverlay as? FullScreenOverlay.ViewMessageData)?.takeIf { it.messageId == action.messageId }
        val source = when {
            loaded != null ->
                if (loaded.canCopyImage(action.payloadKey)) {
                    loaded.payloads?.find { it.key == action.payloadKey }?.let { Triple(loaded.fileId, it, loaded.keyHeader) }
                } else null
            viewer != null ->
                viewer.payloads.find { it.key == action.payloadKey && it.isImage }?.let { Triple(viewer.fileId, it, viewer.keyHeader) }
            else -> null
        }
        if (source == null) {
            sendEvent(ShowErrorMessage(MR.string.chat_error_copy_image))
            return
        }
        val (fileId, payload, keyHeader) = source
        // Undispatched: the web clipboard write must start inside the click (Safari).
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val copied = chatMessageActionService.copyImagePayloadToClipboard(fileId, payload, keyHeader)
            sendEvent(
                if (copied) ConversationListUiEvent.ShowInfoMessage(MR.string.chat_image_copied)
                else ShowErrorMessage(MR.string.chat_error_copy_image)
            )
        }
    }

    fun handleShareMessage(action: ConversationListUiAction.ShareMessage) {
        val message = action.message
        val filteredPayloads = message.payloads?.filter {
            !listOf(ChatProtocol.PAYLOAD_KEY_MESSAGE_WEB, ChatProtocol.DefaultPayloadKey).contains(it.key) &&
                !it.isInternalDescriptor()
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
            messagesUiState.value.messageById(action.messageId) ?: return

        val fileKey = "${message.id}_${action.payloadKey}"

        // 1. Add to downloadingFiles set
        messagesUiState.update { it.copy(downloadingFiles = it.downloadingFiles + fileKey) }

        scope.launch {
            try {
                val payload =
                    message.payloads?.find { it.key == action.payloadKey } ?: return@launch
                val payloadIv = (payload.iv ?: throw IllegalStateException(
                        "encrypted payload requires key header"
                    )).bytes

                val saved = withContext(ioDispatcher) {
                    albumSaver.saveReceived(
                        driveFileProvider, fileOperationsProvider, chatTargetDrive.alias, message.fileId, payload,
                        KeyHeader(payloadIv, message.keyHeader.aesKey),
                        message.userDate.toEpochMilliseconds(),
                    )
                }
                report(saved, if (payload.isHlsVideo) MR.string.chat_error_convert_video else MR.string.chat_error_download_file)
            } catch (e: FileTooLargeForMemoryException) {
                sendEvent(ShowErrorMessage(MR.string.chat_error_file_too_large_here))
            } catch (e: Exception) {
                sendEvent(
                    ShowErrorMessage(
                        MR.string.chat_error_downloading_file_detail,
                        e.message ?: "",
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

        val dateMs = messagesUiState.value.messages.filterIsInstance<MessageListContentModel.Message>()
            .firstOrNull { it.message.fileId == action.fileId }?.message?.userDate?.toEpochMilliseconds() ?: Clock.System.now().toEpochMilliseconds()
        scope.launch {
            try {
                val saved = withContext(ioDispatcher) {
                    albumSaver.saveReceived(
                        driveFileProvider, fileOperationsProvider, chatTargetDrive.alias, action.fileId, action.payload, action.keyHeader, dateMs,
                    )
                }
                report(saved, if (action.payload.isHlsVideo) MR.string.chat_error_convert_video else MR.string.chat_error_download_file)
            } catch (e: FileTooLargeForMemoryException) {
                sendEvent(ShowErrorMessage(MR.string.chat_error_file_too_large_here))
            } catch (e: Exception) {
                sendEvent(ShowErrorMessage(MR.string.chat_error_downloading_file_detail, e.message ?: ""))
            } finally {
                messagesUiState.update { it.copy(downloadingFiles = it.downloadingFiles - fileKey) }
            }
        }
    }

    private fun report(saved: AlbumSaveResult, failed: StringResource) = when (saved) {
        is AlbumSaveResult.Saved -> sendEvent(FileSaved(saved.location))
        AlbumSaveResult.Cancelled -> Unit
        AlbumSaveResult.WriteFailed -> sendEvent(ShowErrorMessage(failed))
        is AlbumSaveResult.Refused -> sendEvent(OfferRefusedSave(saved))
    }

    private fun closePreviewOf(fileId: Uuid, payloadKey: String) {
        messagesUiState.update {
            val open = it.fullScreenOverlay as? FullScreenOverlay.FilePreviewData
            if (open != null && open.fileId == fileId && open.payloadKey == payloadKey) it.closeMediaViewer() else it
        }
    }

    private val decryptsInFlight = mutableSetOf<String>()

    fun handleDecryptFile(action: ConversationListUiAction.DecryptFile) {
        val message =
            messagesUiState.value.messageById(action.messageId) ?: return

        val fileKey = "${message.id}_${action.payloadKey}"
        if (!decryptsInFlight.add(fileKey)) return

        scope.launch {
            try {

                val payload =
                    message.payloads?.find { it.key == action.payloadKey } ?: return@launch
                val payloadIv = (payload.iv ?: throw IllegalStateException(
                        "encrypted payload requires key header"
                    )).bytes

                val filePath = "${AppCacheDirs.scratchPath(fileOperationsProvider.getCacheDirectory(), AppCacheDirs.DOWNLOADS)}/" +
                    decryptedCopyName(message.fileId, action.payloadKey, payload.contentType)

                val success = payloadReader.toFile(
                    at = PayloadLocation.Own(chatTargetDrive.alias, message.fileId),
                    key = action.payloadKey,
                    keyHeader = KeyHeader(payloadIv, message.keyHeader.aesKey),
                    outputPath = filePath,
                    cache = false,
                )

                if (success) {
                    val decryptedFiles =
                        messagesUiState.value.decryptedFiles.toMutableMap()
                    decryptedFiles[DecryptedFileKey(message.fileId, action.payloadKey)] =
                        filePath
                    messagesUiState.update { it.copy(decryptedFiles = decryptedFiles.toPersistentMap()) }
                } else {
                    Logger.e(tag = "DecryptFile") {
                        "stream returned false file=${message.fileId} key=${action.payloadKey} " +
                            "contentType=${payload.contentType} bytesWritten=${payload.bytesWritten} path=$filePath"
                    }
                    closePreviewOf(message.fileId, action.payloadKey)
                    sendEvent(ShowErrorMessage(MR.string.chat_error_downloading_file))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                closePreviewOf(message.fileId, action.payloadKey)
                if (e is FileTooLargeForMemoryException) {
                    sendEvent(ShowErrorMessage(MR.string.chat_error_file_too_large_here))
                    return@launch
                }
                Logger.e(throwable = e, tag = "DecryptFile") {
                    val payload = message.payloads?.find { it.key == action.payloadKey }
                    val aesKey = message.keyHeader.aesKey.unsafeBytes
                    "failed file=${message.fileId} key=${action.payloadKey} contentType=${payload?.contentType} " +
                        "bytesWritten=${payload?.bytesWritten} hasIv=${payload?.iv != null} " +
                        "aesKeySize=${aesKey.size} aesKeyZeroed=${aesKey.all { it == 0.toByte() }}"
                }
                sendEvent(
                    ShowErrorMessage(MR.string.chat_error_downloading_file_detail, e.message ?: "")
                )
            } finally {
                decryptsInFlight.remove(fileKey)
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

    fun handleViewOnceViewerClosed(action: ConversationListUiAction.ViewOnceViewerClosed) {
        // A remounted viewer on the same overlay (a rotation across the expanded-layout gate) must not reopen a spent item.
        messagesUiState.update {
            val overlay = it.fullScreenOverlay
            if (overlay is FullScreenOverlay.ViewOnceViewer && overlay.messageId == action.messageId) {
                it.copy(fullScreenOverlay = null)
            } else it
        }
        // The viewer's own scope is already gone by now; the view model's outlives it.
        scope.launch { viewOnceActions.onViewerClosed(action.conversationId, action.messageId) }
    }

    fun handleViewOnceReact(action: ConversationListUiAction.ViewOnceReact) {
        scope.launch { viewOnceActions.onReact(action.conversationId, action.messageId, action.emoji) }
    }

    fun handleViewOnceReply(action: ConversationListUiAction.ViewOnceReply) {
        val message = messagesUiState.value.messages
            .filterIsInstance<MessageListContentModel.Message>()
            .firstOrNull { it.message.id == action.messageId }
            ?.message
            ?.takeIf { it.messageContent is MessageContent.ViewOnce }
            ?: return
        messagesUiState.update { it.copy(replyToMessage = message, replyToPayloadKey = null) }
    }

    fun handleViewOnceScreenshot(action: ConversationListUiAction.ViewOnceScreenshot) {
        scope.launch { viewOnceActions.onScreenshot(action.conversationId, action.messageId) }
    }

    fun handleMediaClicked(action: ConversationListUiAction.MediaClicked) {
        scope.launch {
            try {
                // A view-once item never takes the generic viewer: it would cache, share and save.
                if (action.message.messageContent is MessageContent.ViewOnce) {
                    if (viewOnceActions.isConsumed(action.message.id)) return@launch
                    viewOnceViewerFor(
                        message = action.message,
                        nowMs = Clock.System.now().toEpochMilliseconds(),
                        myOdinId = uiState.value.ownerSession?.odinId,
                        mobile = onMobile(),
                    )?.let { viewer -> messagesUiState.update { it.copy(fullScreenOverlay = viewer) } }
                    return@launch
                }
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
                    selectedPayload.imageFile()?.isSticker == true
                when {
                    selectedPayload.isImage() && isSticker -> {
                        dispatch(
                            ConversationListUiAction.ShowStickerOptions(
                                message = action.message,
                                payloadKey = action.payloadKey,
                            )
                        )
                    }

                    selectedPayload.isImage() -> {
                        Logger.d("Image clicked: ${action.message.id}:${action.payloadKey}")

                        // Header title = the sender's resolved display name (same as the
                        // bubble), not the raw odinId. Append "(you)" when the sender is us.
                        val authorDomain = action.message.originalAuthor?.domainName
                        val isSelf = authorDomain != null &&
                                authorDomain == uiState.value.ownerSession?.odinId?.domainName
                        val title = if (isSelf) {
                            TranslationUtil.getString(
                                MR.string.contactbook_self_you,
                                action.message.displayName,
                            )
                        } else {
                            action.message.displayName
                        }

                        messagesUiState.update {
                            it.copy(fullScreenOverlay = imageViewerFor(action.message, title, action.payloadKey))
                        }
                    }

                    selectedPayload.isVideo() -> {
                        val localContext = localVideoContextStore.get(action.message.id, selectedPayload.key)
                        val ivBytes = selectedPayload.iv?.bytes

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

                    selectedPayload.isAudio() -> {}
                    chatChipPreviewKind(contentType, selectedPayload.filename() ?: selectedPayload.key, selectedPayload.bytesWritten) != null -> {
                        val name = selectedPayload.filename() ?: selectedPayload.key
                        val kind = chatChipPreviewKind(contentType, name, selectedPayload.bytesWritten) ?: return@launch
                        val key = DecryptedFileKey(action.message.fileId, action.payloadKey)
                        val known = messagesUiState.value.decryptedFiles[key]
                        val needDecrypt = known == null || !fileOperationsProvider.sourceExists(known)
                        messagesUiState.update {
                            it.copy(
                                fullScreenOverlay = FullScreenOverlay.FilePreviewData(
                                    messageId = action.message.id,
                                    fileId = action.message.fileId,
                                    payloadKey = action.payloadKey,
                                    title = name,
                                    name = name,
                                    kind = kind,
                                    userDate = action.message.userDate,
                                )
                            )
                        }
                        if (needDecrypt) {
                            if (known != null) {
                                messagesUiState.update {
                                    it.copy(decryptedFiles = it.decryptedFiles.toMutableMap().apply { remove(key) }.toPersistentMap())
                                }
                            }
                            dispatch(
                                ConversationListUiAction.DecryptFile(
                                    action.message.id, action.payloadKey
                                )
                            )
                        }
                    }
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
                        MR.string.chat_error_media_click,
                        e.message ?: "",
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
}

/** An agent card's header is its descriptor and its payloads include the tree, so its viewer pages only the card's images, uncaptioned. */
internal fun imageViewerFor(message: MessageUiModel, title: String, selectedPayloadKey: String): FullScreenOverlay.ViewMessageData {
    val agentCard = message.messageContent is MessageContent.AgentUi
    return FullScreenOverlay.ViewMessageData(
        messageId = message.id,
        title = title,
        userDate = message.userDate,
        content = if (agentCard) "" else message.content,
        fileId = message.fileId,
        driveId = chatTargetDrive.alias,
        payloads = if (agentCard) message.agentUiImagePayloads() else message.payloads.orEmpty(),
        selectedPayloadKey = selectedPayloadKey,
        keyHeader = message.keyHeader,
    )
}
