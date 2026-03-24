package id.homebase.chat.conversationlist

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import co.touchlab.kermit.Logger
import com.mohamedrejeb.richeditor.model.RichTextState
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.OwnerSessionRepository
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.client.link.LinkPreview
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.util.truncateToCodePoints
import id.homebase.chat.conversationlist.ConversationListUiDialog.DeleteMessage
import id.homebase.chat.conversationlist.ConversationListUiDialog.DiscardDraft
import id.homebase.chat.conversationlist.ConversationListUiEvent.NavigateBack
import id.homebase.chat.conversationlist.ConversationListUiEvent.NavigateToContactInfo
import id.homebase.chat.conversationlist.ConversationListUiEvent.NavigateToConversationSettings
import id.homebase.chat.conversationlist.ConversationListUiEvent.NavigateToGroupSettings
import id.homebase.chat.conversationlist.ConversationListUiEvent.NavigateToMessageInfo
import id.homebase.chat.conversationlist.ConversationListUiEvent.NavigateToNewConversation
import id.homebase.chat.conversationlist.ConversationListUiEvent.OpenFile
import id.homebase.chat.conversationlist.ConversationListUiEvent.ShareFile
import id.homebase.chat.conversationlist.ConversationListUiEvent.ShareText
import id.homebase.chat.conversationlist.ConversationListUiEvent.ShowErrorMessage
import id.homebase.chat.conversationlist.ConversationListUiEvent.ShowInfoMessage
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.ThumbnailDescriptor
import id.homebase.api.image.ImageUtils
import id.homebase.api.video.FFmpegUtils
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.ChatMessageActionService
import id.homebase.chat.services.ChatMessageSenderService
import id.homebase.chat.services.ChatMessageStream
import id.homebase.chat.services.ChatMessagesData
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.ReplyPreview
import id.homebase.chat.services.builder.AttachmentInput
import id.homebase.chat.services.builder.LinkPreviewPayloadBuilder
import id.homebase.chat.services.builder.MessageAttachmentBuilder
import id.homebase.chat.services.convo.ConversationEnricher
import id.homebase.chat.services.convo.ConversationService
import id.homebase.chat.services.convo.ConversationStream
import id.homebase.chat.services.convo.EnrichedConversationUiModel
import id.homebase.chat.services.convo.contact.ContactService
import id.homebase.core.avatars.ConnectionStatus
import id.homebase.core.audio.AudioFileInfo
import id.homebase.core.audio.AudioRecorder
import id.homebase.core.audio.AudioWaveFormGenerator
import id.homebase.core.auth.AuthConnectionCoordinator
import id.homebase.core.config.chatTargetDrive
import id.homebase.core.settings.UserPreferences
import id.homebase.core.ui.navigation.Route
import id.homebase.core.util.ScrollPosition
import id.homebase.core.util.applyDefaultStyling
import id.homebase.core.util.buildConnectToIdentityUrl
import id.homebase.core.util.detectContentTypeFromExtensionOrHint
import id.homebase.resources.MR
import id.homebase.resources.chat_group_introduce_everyone_status
import id.homebase.resources.chat_message_audio_recording_help
import id.homebase.resources.chat_search_result_conversations
import id.homebase.resources.chat_search_result_messages
import id.homebase.resources.chat_search_result_pinned
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.cacheDir
import io.github.vinceglb.filekit.delete
import io.github.vinceglb.filekit.filesDir
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.write
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

@OptIn(FlowPreview::class)
class ConversationListViewModel(
    savedStateHandle: SavedStateHandle,
    private val conversationStream: ConversationStream,
    private val chatMessageStream: ChatMessageStream,
    private val chatMessageSenderService: ChatMessageSenderService,
    private val chatMessageActionService: ChatMessageActionService,
    private val conversationService: ConversationService,
    private val userPreferences: UserPreferences,
    private val fileOperationsProvider: FileOperationsProvider,
    private val ownerSessionRepository: OwnerSessionRepository,
    private val authConnectionCoordinator: AuthConnectionCoordinator,
    private val audioRecorder: AudioRecorder,
    private val audioWaveFormGenerator: AudioWaveFormGenerator,
    private val eventBus: EventBus,
    private val contactService: ContactService
) : ViewModel() {

    private val enricher = ConversationEnricher()
    val ownerSession = ownerSessionRepository.user

    val chatListRoute = savedStateHandle.toRoute<Route.ChatList>()

    private val _uiState = MutableStateFlow(ConversationListUiState())
    val uiState: StateFlow<ConversationListUiState> = _uiState.asStateFlow()

    private val _messagesUiState = MutableStateFlow(MessageListUiState())
    val messagesUiState: StateFlow<MessageListUiState> = _messagesUiState.asStateFlow()

    val conversationSearchTextState = TextFieldState()
    val messageInputTextState = RichTextState().applyDefaultStyling()
    var currentConversationJob: Job? = null
    var pendingMessageId: Uuid? = null

    init {
        viewModelScope.launch {
            ownerSessionRepository.user.collect { session ->
                _uiState.update { it.copy(ownerSession = session) }
                _messagesUiState.update { it.copy(ownerSession = session) }
            }
        }

        viewModelScope.launch {
            contactService.start()
            conversationStream.start()

            combine(
                conversationStream.conversations,
                contactService.contacts,
                ownerSessionRepository.user
            ) { conversationState, contacts, ownerSession ->

                if (ownerSession == null) return@combine Pair(false, emptyList())

                val contactMap = contacts.associateBy { it.odinId }

                Pair(conversationState.dataReady, conversationState.items.map {
                    enricher.enrich(it, contactMap, ownerSession)
                })
            }.collect { (dataReady: Boolean, enriched: List<EnrichedConversationUiModel>) ->
                if (dataReady) {
                    _uiState.update {
                        it.copy(
                            activeConversations = enriched
                                .sortedByDescending { conversation -> conversation.conversation.timestamp }
                                .toPersistentList()
                        )
                    }
                    updateListContent()
                }
            }
        }

        viewModelScope.launch {
            // TODO - restore any draft message stored for conversation here
            messageInputTextState.setMarkdown("")
        }

        // Load initial message if conversation is set
        viewModelScope.launch {
            chatListRoute.conversationId?.let { loadMessagesForConversation(Uuid.parse(it), null) }
        }

        // Listen for search query changes
        viewModelScope.launch {
            snapshotFlow { conversationSearchTextState.text.toString() }.debounce(300)
                .collectLatest {
                    if (uiState.value.conversationsContent is ConversationListContentState.Items) {
                        updateListContent()
                    }
                }
        }

        // Track upload progress via outbox and payload bundling events
        viewModelScope.launch {
            eventBus.events.filter { it is BackendEvent.PayloadBundlingEvent.Video.PhaseProgress }
                .collect { event ->
                    event as BackendEvent.PayloadBundlingEvent.Video.PhaseProgress
                    _messagesUiState.update { state ->
                        state.copy(
                            uploadProgress = (state.uploadProgress + (event.uniqueId to UploadStatus.Processing(event.progress))).toPersistentMap()
                        )
                    }
                }
        }

        viewModelScope.launch {
            eventBus.events.filter { it is BackendEvent.OutboxEvent.ItemProgress }
                .collect { event ->
                    event as BackendEvent.OutboxEvent.ItemProgress
                    _messagesUiState.update { state ->
                        state.copy(
                            uploadProgress = (state.uploadProgress + (event.uniqueId to UploadStatus.Uploading(event.progress / 100f))).toPersistentMap()
                        )
                    }
                }
        }

        viewModelScope.launch {
            eventBus.events.filter { it is BackendEvent.OutboxEvent.ItemCompleted }
                .collect { event ->
                    event as BackendEvent.OutboxEvent.ItemCompleted
                    viewModelScope.launch {
                        _messagesUiState.update { state ->
                            state.copy(
                                uploadProgress = (state.uploadProgress + (event.uniqueId to UploadStatus.Completed)).toPersistentMap()
                            )
                        }
                        delay(800)
                        _messagesUiState.update { state ->
                            state.copy(
                                uploadProgress = (state.uploadProgress - event.uniqueId).toPersistentMap()
                            )
                        }
                    }
                }
        }

        viewModelScope.launch {
            eventBus.events.filter { it is BackendEvent.OutboxEvent.ItemFailed }
                .collect { event ->
                    event as BackendEvent.OutboxEvent.ItemFailed
                    _messagesUiState.update { state ->
                        state.copy(
                            uploadProgress = (state.uploadProgress - event.uniqueId).toPersistentMap()
                        )
                    }
                }
        }

        // Set connected state
        viewModelScope.launch {
            authConnectionCoordinator.connectionState
                .collectLatest { state ->
                    val status = when {
                        state.isConnected -> ConnectionStatus.Connected
                        state.isDoingInitialConnection -> ConnectionStatus.Connecting
                        else -> ConnectionStatus.Disconnected
                    }
                    _uiState.update { it.copy(connectionStatus = status) }
                }
        }

        // Set isConnecting state
        viewModelScope.launch {
            eventBus.events
                .filter { it is BackendEvent.SyncAllStarted || it is BackendEvent.SyncAllCompleted || it is BackendEvent.SyncAllFailed }
                .collectLatest { event ->
                    when (event) {
                        is BackendEvent.SyncAllStarted   -> _uiState.update { it.copy(driveIsSyncing = true) }
                        is BackendEvent.SyncAllCompleted,
                        is BackendEvent.SyncAllFailed    -> _uiState.update { it.copy(driveIsSyncing = false) }
                        else -> Unit
                    }
                }
        }
    }

    fun eventConsumed() {
        _uiState.update { it.copy(uiEvent = null) }
    }

    fun dialogClosed() {
        _uiState.update { it.copy(uiDialog = null) }
    }

    fun onAction(action: ConversationListUiAction) {
        when (action) {
            is ConversationListUiAction.ConversationClicked -> {
                loadMessagesForConversation(action.conversationId, action.messageId)
            }

            is ConversationListUiAction.BackClicked -> {
                sendEvent(NavigateBack)
            }

            is ConversationListUiAction.SearchClicked -> {
                _uiState.update { it.copy(isSearchActive = true) }
            }

            is ConversationListUiAction.SearchBackClicked -> {
                _uiState.update { it.copy(isSearchActive = false) }
            }

            is ConversationListUiAction.NewConversationClicked -> {
                _uiState.value = _uiState.value.copy(
                    uiEvent = NavigateToNewConversation
                )
            }

            is ConversationListUiAction.ClearSelection -> {
                // Clear the selected conversation when user navigates back to list
                _uiState.update { it.copy(selectedConversationId = null) }
            }

            is ConversationListUiAction.FilterByUnreadClicked -> {
                _uiState.update { it.copy(filterByUnread = true) }
                updateListContent()
            }

            is ConversationListUiAction.ClearFilterByUnreadClicked -> {
                _uiState.update { it.copy(filterByUnread = false) }
                updateListContent()
            }

            is ConversationListUiAction.SendMessage -> {
                val hasMessage = !messageInputTextState.annotatedString.isBlank()
                if (hasMessage) {
                    val content = messageInputTextState.toMarkdown()
                    val replyTo = _messagesUiState.value.replyToMessage
                    if (replyTo != null) {
                        replyToMessage(
                            conversationId = action.conversationId,
                            replyTo = replyTo,
                            content = content,
                            linkPreview = action.linkPreview
                        )
                        _messagesUiState.update { it.copy(replyToMessage = null) }
                    } else {
                        addMessage(
                            conversationId = action.conversationId,
                            content = content,
                            linkPreview = action.linkPreview
                        )
                    }
                    messageInputTextState.clear()
                }
            }

            is ConversationListUiAction.SaveScrollPosition -> {
                _messagesUiState.update {
                    it.copy(
                        scrollPosition = ScrollPosition(
                            firstVisibleItemIndex = action.firstVisibleItemIndex,
                            firstVisibleItemScrollOffset = action.firstVisibleItemScrollOffset,
                        )
                    )
                }

                // Persist to user settings
                viewModelScope.launch {
                    userPreferences.setConversationScrollIndex(
                        action.conversationId.toString(), action.firstVisibleItemIndex
                    )
                    userPreferences.setConversationScrollOffset(
                        action.conversationId.toString(), action.firstVisibleItemScrollOffset
                    )
                }
            }

            is ConversationListUiAction.EditMessage -> {
                viewModelScope.launch {
                    try {
                        if (!action.ignoreDraft && messageInputTextState.annotatedString.isNotBlank()) {
                            _uiState.update {
                                it.copy(
                                    uiDialog = DiscardDraft(action.messageId, action.versionTag)
                                )
                            }
                            return@launch
                        }

                        chatMessageStream.getMessage(action.messageId)?.let { message ->
                            _messagesUiState.update {
                                it.copy(
                                    isEditingMessageId = action.messageId,
                                    isEditingVersionTag = action.versionTag,
                                    replyToMessage = null
                                )
                            }
                            messageInputTextState.setMarkdown(message.content)
                        }
                    } catch (e: Exception) {
                        Logger.e(throwable = e, tag = "ConversationListViewModel") {
                            "Failed to edit message: ${e.message}"
                        }
                        sendEvent(
                            ShowErrorMessage(
                                "Failed to edit message: ${e.message}"
                            )
                        )
                    }
                }
            }

            is ConversationListUiAction.EditMessageSave -> {
                _messagesUiState.value.isEditingMessageId?.let { messageId ->
                    editMessage(
                        messageId = messageId,
                        versionTag = _messagesUiState.value.isEditingVersionTag ?: Uuid.NIL,
                        content = messageInputTextState.toMarkdown(),
                    )
                }
            }

            is ConversationListUiAction.CancelEditMessage -> {
                messageInputTextState.clear()
                _messagesUiState.update {
                    it.copy(
                        isEditingMessageId = null,
                        isEditingVersionTag = null
                    )
                }
            }

            is ConversationListUiAction.DeleteMessage -> {
                val messages = _messagesUiState.value.messages.mapNotNull {
                    if (it is MessageListContentModel.Message) it.message else null
                }
                val message = messages.firstOrNull { it.id == action.messageId } ?: return
                val isCurrentUserMessage =
                    message.originalAuthor?.domainName == _uiState.value.ownerSession?.odinId?.domainName
                _uiState.update {
                    it.copy(
                        uiDialog = DeleteMessage(
                            messageId = action.messageId,
                            allowDeleteForEveryone = isCurrentUserMessage
                        )
                    )
                }
            }

            is ConversationListUiAction.ShareMedia -> {
                viewModelScope.launch {
                    try {
                        val messageModel =
                            _messagesUiState.value.messages.filterIsInstance<MessageListContentModel.Message>()
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
                            var extension = payload.contentType?.substringAfter("/") ?: "bin"
                            extension = when (extension) {
                                "jpeg" -> "jpg"
                                else -> extension
                            }
                            val tempPath = fileOperationsProvider.writeBytesToTempFile(
                                bytes, "share_", ".$extension"
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

            is ConversationListUiAction.ShareMessage -> {
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
                    onAction(ConversationListUiAction.ShareMedia(message.id, payload.key))
                } else {
                    // Text-only message
                    val text = message.content
                    if (text.isNotBlank()) {
                        sendEvent(ShareText(text))
                    }
                }
            }

            is ConversationListUiAction.DownloadMedia -> {
                val message =
                    _messagesUiState.value.messages.filterIsInstance<MessageListContentModel.Message>()
                        .map { it.message }.find { it.id == action.messageId } ?: return

                val fileKey = "${message.id}_${action.payloadKey}"

                // 1. Add to downloadingFiles set
                _uiState.update { it.copy(downloadingFiles = it.downloadingFiles + fileKey) }

                viewModelScope.launch {
                    try {

                        val payload =
                            message.payloads?.find { it.key == action.payloadKey } ?: return@launch
                        val payloadIv = Base64.decode(
                            payload.iv ?: throw IllegalStateException(
                                "encrypted payload requires key header"
                            )
                        )
                        val fileBytes = chatMessageActionService.getPayloadBytes(
                            message.fileId,
                            action.payloadKey,
                            KeyHeader(payloadIv, message.keyHeader.aesKey)
                        )

                        val fileName = payload.filename() ?: payload.key

                        if (fileBytes != null) {
                            var extension = payload.contentType?.substringAfter("/") ?: "bin"
                            extension = when (extension) {
                                "jpeg" -> "jpg"
                                else -> extension
                            }
                            val tempFile = fileOperationsProvider.writeBytesToTempFile(
                                fileBytes, fileName, ".$extension"
                            )
                            sendEvent(OpenFile(tempFile))
                        } else {
                            sendEvent(
                                ShowErrorMessage(
                                    "Could not download file"
                                )
                            )
                        }
                    } catch (e: Exception) {
                        sendEvent(
                            ShowErrorMessage(
                                "Error downloading file: ${e.message}"
                            )
                        )
                    } finally {
                        // 4. Remove from downloadingFiles set
                        _uiState.update {
                            it.copy(downloadingFiles = it.downloadingFiles - fileKey)
                        }
                    }
                }
            }

            is ConversationListUiAction.DecryptFile -> {
                val message =
                    _messagesUiState.value.messages.filterIsInstance<MessageListContentModel.Message>()
                        .map { it.message }.find { it.id == action.messageId } ?: return

                val fileKey = "${message.id}_${action.payloadKey}"

                viewModelScope.launch {
                    try {

                        val payload =
                            message.payloads?.find { it.key == action.payloadKey } ?: return@launch
                        val payloadIv = Base64.decode(
                            payload.iv ?: throw IllegalStateException(
                                "encrypted payload requires key header"
                            )
                        )
                        val fileBytes = chatMessageActionService.getPayloadBytes(
                            message.fileId,
                            action.payloadKey,
                            KeyHeader(payloadIv, message.keyHeader.aesKey)
                        )

                        val fileName = payload.filename() ?: payload.key

                        if (fileBytes != null) {
                            var extension = payload.contentType?.substringAfter("/") ?: "bin"
                            extension = when (extension) {
                                "jpeg" -> "jpg"
                                else -> extension
                            }
                            val tempFile = fileOperationsProvider.writeBytesToTempFile(
                                fileBytes, fileName, ".$extension"
                            )
                            val decryptedFiles =
                                _messagesUiState.value.decryptedFiles.toMutableMap()
                            decryptedFiles[DecryptedFileKey(message.fileId, action.payloadKey)] =
                                tempFile
                            _messagesUiState.update { it.copy(decryptedFiles = decryptedFiles.toPersistentMap()) }
                        } else {
                            sendEvent(
                                ShowErrorMessage(
                                    "Could not decrypt file"
                                )
                            )
                        }
                    } catch (e: Exception) {
                        sendEvent(
                            ShowErrorMessage(
                                "Error downloading file: ${e.message}"
                            )
                        )
                    } finally {
                        // 4. Remove from downloadingFiles set
                        _uiState.update {
                            it.copy(downloadingFiles = it.downloadingFiles - fileKey)
                        }
                    }
                }
            }

            is ConversationListUiAction.ScrollToMessageId -> {
                viewModelScope.launch {
                    try {
                        val indexOfMessageForScroll = messagesUiState.value.messages.indexOfLast {
                            it is MessageListContentModel.Message && it.message.id == action.messageId
                        }

                        if (indexOfMessageForScroll != -1) {
                            _messagesUiState.update {
                                it.copy(
                                    scrollPosition =
                                        ScrollPosition(
                                            firstVisibleItemIndex = indexOfMessageForScroll,
                                            triggerScroll = true
                                        )
                                )
                            }
                        }
                    } catch (e: Exception) {
                        sendEvent(ShowErrorMessage("Failed to scroll to message: ${e.message}"))
                    }
                }
            }

            is ConversationListUiAction.SaveFile -> {
                sendEvent(ShowErrorMessage("Not implemented yet"))
            }

            is ConversationListUiAction.DeleteMessageForEveryone -> {
                viewModelScope.launch {
                    try {
                        chatMessageActionService.deleteMessage(
                            action.messageId, deleteForEveryone = true
                        )
                    } catch (e: Exception) {
                        sendEvent(
                            ShowErrorMessage(
                                "Failed to delete message for everyone: ${e.message}"
                            )
                        )
                    }
                }
            }

            is ConversationListUiAction.DeleteMessageForMe -> {
                viewModelScope.launch {
                    try {
                        chatMessageActionService.deleteMessage(
                            action.messageId, deleteForEveryone = false
                        )
                    } catch (e: Exception) {
                        sendEvent(
                            ShowErrorMessage(
                                "Failed to delete message for me: ${e.message}"
                            )
                        )
                    }
                }
            }

            is ConversationListUiAction.MarkAsRead -> {
                viewModelScope.launch {
                    try {
                        if (action.messageIds == null) {
                            // TODO - mark all as read
                        } else {
                            chatMessageActionService.markAsReadLatestFileCreated(
                                action.conversationId,
                                action.messageIds
                            )
                        }
                    } catch (e: Exception) {
                        sendEvent(
                            ShowErrorMessage(
                                "Failed to mark message as read: ${e.message}"
                            )
                        )
                    }
                }
            }

            is ConversationListUiAction.TogglePinConversation -> {
                viewModelScope.launch {
                    try {
                      // TODO - toggle pinned convo
                    } catch (e: Exception) {
                        sendEvent(
                            ShowErrorMessage(
                                "Failed to toggle pinned conversation: ${e.message}"
                            )
                        )
                    }
                }
            }

            is ConversationListUiAction.ToggleReaction -> {
                viewModelScope.launch {
                    try {
                        chatMessageActionService.toggleReaction(
                            action.conversationId,
                            action.messageId,
                            action.reaction
                        )
                    } catch (e: Exception) {
                        sendEvent(
                            ShowErrorMessage(
                                "Failed to toggle reaction: ${e.message}"
                            )
                        )
                    }
                }
            }

            is ConversationListUiAction.SendFile -> {
                _messagesUiState.update { it.copy(scrollPosition = null, fullScreenOverlay = null) }

                addMessageWithFiles(
                    conversationId = action.conversationId,
                    content = action.message,
                    files = action.attachments,
                )
                messageInputTextState.clear()
            }

            is ConversationListUiAction.AttachPlatformFile -> {
                viewModelScope.launch {
                    try {
                        val newFiles = action.files.map {
                            val ct = detectContentTypeFromExtensionOrHint(it.name)
                            when {
                                ct.startsWith("video/") -> {
                                    val thumbnailBytes = try {
                                        val thumbPath = FFmpegUtils.grabThumbnail(it.toString())
                                        if (thumbPath != null) {
                                            val bytes = fileOperationsProvider.readFileBytes(thumbPath)
                                            fileOperationsProvider.deleteTempFile(thumbPath)
                                            bytes
                                        } else null
                                    } catch (_: Exception) { null }
                                    AttachmentPendingFile.FileVideo(Uuid.generateV7(), it, thumbnailBytes)
                                }
                                action.isImage || ct.startsWith("image/") -> AttachmentPendingFile.FileImage(Uuid.generateV7(), it)
                                else -> AttachmentPendingFile.File(Uuid.generateV7(), it)
                            }
                        }
                        val conversation = _uiState.value.activeConversations.find {
                            it.conversation.id == action.conversationId
                        }
                        if (newFiles.isEmpty() || conversation == null) return@launch

                        val overlay = _messagesUiState.value.fullScreenOverlay
                        val newOverlay = if (overlay is FullScreenOverlay.AttachmentData) {
                            overlay.copy(
                                attachments = overlay.attachments + newFiles,
                            )
                        } else {
                            FullScreenOverlay.AttachmentData(
                                conversationTitle = conversation.conversation.name,
                                conversationId = action.conversationId,
                                selected = newFiles.last().attachmentId,
                                attachments = newFiles,
                            )
                        }

                        _messagesUiState.update {
                            it.copy(
                                fullScreenOverlay = newOverlay,
                            )
                        }
                    } catch (e: Exception) {
                        Logger.e("Failed to attach file(s)", e)
                        sendEvent(
                            ShowErrorMessage(
                                "Failed to attach file(s): ${e.message}"
                            )
                        )
                    }
                }
            }

            is ConversationListUiAction.AttachGalleryItem -> {
                viewModelScope.launch {
                    try {
                        val newFiles = action.files.map {
                            AttachmentPendingFile.Gallery(Uuid.generateV7(), it)
                        }
                        val conversation = _uiState.value.activeConversations.find {
                            it.conversation.id == action.conversationId
                        }
                        if (newFiles.isEmpty() || conversation == null) return@launch

                        val overlay = _messagesUiState.value.fullScreenOverlay
                        val newOverlay = if (overlay is FullScreenOverlay.AttachmentData) {
                            overlay.copy(
                                attachments = overlay.attachments + newFiles,
                            )
                        } else {
                            FullScreenOverlay.AttachmentData(
                                conversationTitle = conversation.conversation.name,
                                conversationId = action.conversationId,
                                selected = newFiles.last().attachmentId,
                                attachments = newFiles,
                            )
                        }

                        _messagesUiState.update {
                            it.copy(
                                fullScreenOverlay = newOverlay,
                            )
                        }
                    } catch (e: Exception) {
                        Logger.e("Failed to attach file(s)", e)
                        sendEvent(
                            ShowErrorMessage(
                                "Failed to attach file(s): ${e.message}"
                            )
                        )
                    }
                }
            }

            is ConversationListUiAction.UnAttachFile -> {
                viewModelScope.launch {
                    try {
                        val fullScreenOverlay = _messagesUiState.value.fullScreenOverlay
                        if (fullScreenOverlay == null || fullScreenOverlay !is FullScreenOverlay.AttachmentData) return@launch

                        val newFiles = fullScreenOverlay.attachments.filter {
                            it.attachmentId != action.id
                        }
                        _messagesUiState.update {
                            it.copy(
                                fullScreenOverlay = fullScreenOverlay.copy(attachments = newFiles),
                            )
                        }
                    } catch (e: Exception) {
                        Logger.e("Failed to unattach file", e)
                        sendEvent(
                            ShowErrorMessage(
                                "Failed to unattach file: ${e.message}"
                            )
                        )
                    }
                }
            }

            is ConversationListUiAction.MediaClicked -> {
                viewModelScope.launch {
                    try {
                        val selectedPayload =
                            action.message.payloads?.firstOrNull { it.key == action.payloadKey }
                                ?: return@launch
                        val contentType = selectedPayload.contentType ?: ""
                        when {
                            contentType.startsWith("image/") -> {
                                Logger.d("Image clicked: ${action.message.id}:${action.payloadKey}")

                                _messagesUiState.update {
                                    it.copy(
                                        fullScreenOverlay = FullScreenOverlay.ViewMessageData(
                                            messageId = action.message.id,
                                            title = action.message.originalAuthor?.domainName
                                                ?: "null",
                                            created = action.message.created,
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
                                _messagesUiState.update {
                                    it.copy(
                                        fullScreenOverlay = FullScreenOverlay.VideoPlayerData(
                                            fileId = action.message.fileId,
                                            driveId = chatTargetDrive.alias,
                                            payloadKey = action.payloadKey,
                                            keyHeader = KeyHeader(
                                                iv = Base64.decode(selectedPayload.iv!!),
                                                aesKey = action.message.keyHeader.aesKey
                                            ),
                                            payload = selectedPayload,
                                        )
                                    )
                                }
                            }

                            contentType.startsWith("audio/") -> {}
                            contentType.startsWith("application/") || contentType.startsWith("text/") || contentType.startsWith(
                                "message/"
                            ) -> {
                                onAction(
                                    ConversationListUiAction.DownloadMedia(
                                        action.message.id, action.payloadKey
                                    )
                                )
                            }

                            else -> {
                                onAction(
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

            is ConversationListUiAction.ShowMoreClicked -> {
                viewModelScope.launch {

                    val full = chatMessageStream.loadFullMessage(
                        action.conversationId,
                        action.messageId
                    ) ?: return@launch

                    _messagesUiState.update { state ->
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

            is ConversationListUiAction.CloseFullScreenOverlay -> {
                _messagesUiState.update { it.copy(fullScreenOverlay = null) }
            }

            is ConversationListUiAction.ReplyToMessage -> {
                _messagesUiState.update { it.copy(replyToMessage = action.message) }
            }

            is ConversationListUiAction.CancelReplyToMessage -> {
                _messagesUiState.update { it.copy(replyToMessage = null) }
            }

            is ConversationListUiAction.ShowReactionDetails -> {
                loadReactionDetails(action.messageId)
            }

            is ConversationListUiAction.HideReactionDetails -> {
                _messagesUiState.update { it.copy(messageReactions = null) }
            }

            is ConversationListUiAction.ShowContactInfo -> {
                // ignore if click on own contact
                if (action.odinId == uiState.value.ownerSession?.odinId?.domainName) return
                _uiState.update {
                    it.copy(
                        uiEvent = NavigateToContactInfo((action.odinId))
                    )
                }
            }

            is ConversationListUiAction.ShowMessageInfo -> {
                _uiState.update {
                    it.copy(
                        uiEvent = NavigateToMessageInfo((action.message))
                    )
                }
            }

            is ConversationListUiAction.DismissSheet -> {
                _messagesUiState.update { it.copy(uiSheet = null) }
            }

            /* Group options */

            is ConversationListUiAction.ConnectIdentities -> {
                _messagesUiState.update {
                    it.copy(
                        uiSheet = ConversationListUiSheet.ConnectIdentities(
                            action.identities
                        )
                    )
                }
            }

            is ConversationListUiAction.ConnectToIdentity -> {
                uiState.value.ownerSession?.odinId?.let { currentUser ->
                    val url = currentUser.buildConnectToIdentityUrl(action.odinId)
                    _uiState.update { it.copy(uiEvent = ConversationListUiEvent.OpenUrl(url)) }
                }
            }

            /* Conversation options */
            is ConversationListUiAction.ShowConversationSettings -> {
                if (action.conversation.isGroupConversation) {
                    _uiState.update {
                        it.copy(
                            uiEvent = NavigateToGroupSettings(
                                (action.conversation.id.toString())
                            )
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            uiEvent = NavigateToConversationSettings(
                                (action.conversation.id.toString())
                            )
                        )
                    }
                }
            }

            is ConversationListUiAction.IntroduceEveryone -> {
                introduceEveryone(action.conversationId)
            }

            is ConversationListUiAction.ArchiveConversation -> {
                // TODO
                println("Unhandled: $action")
            }

            is ConversationListUiAction.ClearConversation -> {
                // TODO
                println("Unhandled: $action")
            }

            is ConversationListUiAction.DeleteConversation -> {
                // TODO
                println("Unhandled: $action")
            }

            /* Audio recording */
            is ConversationListUiAction.ShowRecordingHelp -> {
                sendEvent(ShowInfoMessage(MR.string.chat_message_audio_recording_help))
            }

            is ConversationListUiAction.StartRecording -> {
                viewModelScope.launch {
                    try {
                        val file = PlatformFile(
                            base = FileKit.filesDir,
                            child = "recording-${Uuid.random()}.${audioRecorder.getAudioFileExtension()}"
                        )
                        audioRecorder.startRecording(file.toString())
                        _messagesUiState.update {
                            it.copy(
                                recordingData = RecordingData(
                                    file = file,
                                    conversationId = action.conversationId
                                )
                            )
                        }
                    } catch (e: Exception) {
                        Logger.e("Failed to start recording", e)
                        sendEvent(ShowErrorMessage("Failed to start recording: $e"))
                    }
                }
            }

            is ConversationListUiAction.StopRecording -> {
                viewModelScope.launch {
                    try {
                        val recordingData = _messagesUiState.value.recordingData
                        _messagesUiState.update {
                            it.copy(recordingData = recordingData?.copy(isProcessing = true))
                        }

                        audioRecorder.stopRecording()
                        recordingData?.let { recordingData ->
                            var waveFormImageFile: PlatformFile? = null
                            var audioInfo: AudioFileInfo? = null
                            try {
                                audioInfo =
                                    audioWaveFormGenerator.generateWaveForm(recordingData.file)
                                val waveFormImageBytes = audioWaveFormGenerator.saveWaveformToPng(
                                    audioInfo.waveForm,
                                    1000,
                                    200
                                )
                                waveFormImageFile = PlatformFile(
                                    FileKit.cacheDir,
                                    "waveform-${Uuid.generateV4()}.png"
                                )
                                waveFormImageFile.write(waveFormImageBytes)
                            } catch (e: Exception) {
                                Logger.e("Failed to generate waveform", e)
                            }

                            addMessageWithFiles(
                                conversationId = recordingData.conversationId,
                                content = "",
                                files = listOf(
                                    AttachmentPendingFile.Audio(
                                        id = Uuid.random(),
                                        audioFile = recordingData.file,
                                        waveformFile = waveFormImageFile,
                                        lengthSeconds = audioInfo?.getDuration()?.inWholeSeconds?.toInt()
                                            ?: 0
                                    )
                                ),
                            )
                        }
                    } catch (e: Exception) {
                        Logger.e("Failed to send recording", e)
                        sendEvent(ShowErrorMessage("Failed to send recording: ${e.message}"))
                    }
                    _messagesUiState.update { it.copy(recordingData = null) }
                }
            }

            is ConversationListUiAction.CancelRecording -> {
                viewModelScope.launch {
                    try {
                        audioRecorder.stopRecording()
                        _messagesUiState.value.recordingData?.file?.delete(mustExist = false)
                    } catch (_: Exception) {
                        // ignore
                    }
                    _messagesUiState.update { it.copy(recordingData = null) }
                }
            }
        }
    }

    private fun introduceEveryone(conversationId: Uuid) {
        viewModelScope.launch {
            val defaultMessage =
                "${_uiState.value.ownerSession?.displayName ?: "Unknown"} has added you to group chat"
            conversationService.introduceEveryone(conversationId, defaultMessage)
            sendEvent(ShowInfoMessage(MR.string.chat_group_introduce_everyone_status))
        }
    }

    private fun updateListContent() {
        viewModelScope.launch {
            try {
                val searchQuery = conversationSearchTextState.text.toString()
                val filterByUnread = uiState.value.filterByUnread
                val conversationsPool =
                    if (filterByUnread) uiState.value.activeConversations.filter {
                        it.conversation.unreadCount > 0 || it.conversation.id == uiState.value.selectedConversationId
                    }
                    else uiState.value.activeConversations

                if (searchQuery.isEmpty()) {
                    val items = mutableListOf<ConversationListContentModel>()
                    val pinnedItems = conversationsPool
                        .filter { it.conversation.isPinned }
                        .map { conv -> ConversationListContentModel.Conversation(conv) }
                        .toPersistentList()
                    if (pinnedItems.isNotEmpty()) {
                        items.add(ConversationListContentModel.Header(MR.string.chat_search_result_pinned))
                        items.addAll(pinnedItems)
                    }

                    val normalItems = conversationsPool
                        .filter { !it.conversation.isPinned }
                        .map { conv -> ConversationListContentModel.Conversation(conv) }
                        .toPersistentList()
                    if (normalItems.isNotEmpty()) {
                        if (pinnedItems.isNotEmpty()) {
                            items.add(ConversationListContentModel.Header(MR.string.chat_search_result_conversations))
                        }
                        items.addAll(normalItems)
                    }

                    _uiState.update {
                        it.copy(
                            conversationsContent = if (items.isEmpty()) ConversationListContentState.Empty
                            else ConversationListContentState.Items(items.toPersistentList())
                        )
                    }
                } else {
                    val result = mutableListOf<ConversationListContentModel>()

                    val conversations = conversationsPool.filter { conversation ->
                        conversation.conversation.name.contains(searchQuery, ignoreCase = true)
                    }.toPersistentList()
                    if (conversations.isNotEmpty()) {
                        result.add(
                            ConversationListContentModel.Header(
                                MR.string.chat_search_result_conversations
                            )
                        )
                        result.addAll(
                            conversations.map { ConversationListContentModel.Conversation(it) })
                    }

                    // Only search for message if filter by unread conversations filter is not
                    // enabled
                    if (!filterByUnread) {
                        val messages = chatMessageStream.searchMessages(searchQuery).records
                        if (messages.isNotEmpty()) {
                            result.add(
                                ConversationListContentModel.Header(
                                    MR.string.chat_search_result_messages
                                )
                            )
                            result.addAll(messages.map { ConversationListContentModel.Message(it) })
                        }
                    }

                    _uiState.update {
                        it.copy(
                            conversationsContent = if (result.isEmpty()) ConversationListContentState.EmptySearch(
                                searchQuery
                            )
                            else ConversationListContentState.Items(
                                result.toPersistentList()
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                sendEvent(ShowErrorMessage("Failed to load conversations: ${e.message}"))
            }
        }
    }

    private fun loadReactionDetails(messageId: Uuid) {
        viewModelScope.launch {
            val messageReactions = chatMessageActionService.getReactions(messageId)
            _messagesUiState.update { it.copy(messageReactions = messageReactions) }
        }
    }

    private fun loadMessagesForConversation(conversationId: Uuid, messageIdForScroll: Uuid?) {
        _messagesUiState.update { it.copy(scrollPosition = null, isLoadingMessages = true) }

        // When loading message for newly selected conversation, cancel any previous job to
        // avoid observing multiple messageStreams
        currentConversationJob?.cancel()
        currentConversationJob = viewModelScope.launch {
            try {
                var messageIdForScrollNullable = messageIdForScroll
                var setInitialScroll = true

                chatMessageStream.loadConversation(conversationId)
                chatMessageStream.observeMessages(conversationId).collect { messageState ->
                    when (messageState) {
                        is ChatMessagesData.Initializing -> {
                            // ignore
                        }

                        is ChatMessagesData.Messages -> {
                            val messages = messageState.messages
                            // Group messages within day sections
                            val timezone = TimeZone.currentSystemDefault()
                            val groupedMessages =
                                messages.sortedBy { it.created }.groupBy { message ->
                                    val date = message.created.toLocalDateTime(timezone).date
                                    date
                                }
                            val messagesModels: MutableList<MessageListContentModel> =
                                mutableListOf(MessageListContentModel.Header)

                            messagesModels.addAll(groupedMessages.flatMap { (date, messages) ->
                                listOf(MessageListContentModel.Section(date)) + messages.map {
                                    if (it.isStatusMessage)
                                        MessageListContentModel.System(it.content, it.created)
                                    else
                                        MessageListContentModel.Message(it)
                                }
                            })

                            // Scroll handling, either use new message id, click message id or null
                            val newMessageId =
                                messages.firstOrNull { it.id == pendingMessageId }?.id
                            pendingMessageId = null
                            val indexOfMessageForScroll = if (newMessageId != null) {
                                Logger.i("Resetting scroll position, new message seen")
                                messagesModels.indexOfLast {
                                    it is MessageListContentModel.Message && it.message.id == newMessageId
                                }
                            } else {
                                if (messageIdForScrollNullable == null) null
                                else {
                                    val messageIndex = messagesModels.indexOfLast {
                                        it is MessageListContentModel.Message && it.message.id == messageIdForScrollNullable
                                    }
                                    messageIdForScrollNullable = null
                                    messageIndex
                                }
                            }

                            _uiState.value = _uiState.value.copy(
                                selectedConversationId = conversationId,
                            )

                            _messagesUiState.update {
                                it.copy(
                                    isLoadingMessages = false,
                                    messages = messagesModels.toPersistentList(),
                                    scrollPosition = if (indexOfMessageForScroll == null) {
                                        if (setInitialScroll) getScrollPosition(conversationId) else null
                                    } else {
                                        ScrollPosition(
                                            indexOfMessageForScroll,
                                            triggerScroll = true
                                        )
                                    },
                                )
                            }
                            setInitialScroll = false
                        }
                    }

                }
            } catch (_: CancellationException) {
                // ignore
            } catch (e: Exception) {
                sendEvent(ShowErrorMessage("Failed to load messages: ${e.message}"))
            }
        }
    }

    private fun getScrollPosition(conversationId: Uuid): ScrollPosition? {
        val firstVisibleItemIndex =
            userPreferences.getConversationScrollIndex(conversationId.toString())
        val firstVisibleItemScrollOffset =
            userPreferences.getConversationScrollOffset(conversationId.toString())

        if (firstVisibleItemIndex != null && firstVisibleItemScrollOffset != null) {
            return ScrollPosition(
                firstVisibleItemIndex = firstVisibleItemIndex,
                firstVisibleItemScrollOffset = firstVisibleItemScrollOffset
            )
        }
        return null
    }

    private fun sendEvent(event: ConversationListUiEvent) {
        _uiState.update { it.copy(uiEvent = event) }
    }

    private fun editMessage(messageId: Uuid, versionTag: Uuid, content: String) {
        viewModelScope.launch {
            try {
                chatMessageSenderService.updateMessage(
                    messageId = messageId,
                    versionTag = versionTag,
                    content = content
                )
                _messagesUiState.update {
                    it.copy(
                        isEditingMessageId = null,
                        isEditingVersionTag = null
                    )
                }
            } catch (e: Exception) {
                sendEvent(ShowErrorMessage("Failed to edit message: ${e.message}"))
            }
        }
    }

    private fun addMessage(
        conversationId: Uuid,
        content: String,
        linkPreview: LinkPreview? = null
    ) {
        viewModelScope.launch {
            try {
                val payloadBundle = linkPreview?.let {
                    LinkPreviewPayloadBuilder.build(it, fileOperationsProvider)
                }

                val newMessageId = Uuid.random()
                pendingMessageId = newMessageId
                chatMessageSenderService.sendNewMessage(
                    messageUniqueId = newMessageId,
                    conversationId = conversationId,
                    messageText = content,
                    previousMessageUniqueId = null,
                    payloadBundle = payloadBundle,
                )
            } catch (e: Exception) {
                sendEvent(ShowErrorMessage("Failed to send message: ${e.message}"))
            }
        }
    }

    private fun replyToMessage(
        conversationId: Uuid,
        replyTo: MessageUiModel,
        content: String,
        linkPreview: LinkPreview? = null
    ) {
        viewModelScope.launch {
            try {
                val payloadBundle = linkPreview?.let {
                    LinkPreviewPayloadBuilder.build(it, fileOperationsProvider)
                }

                val replyPreview = ReplyPreview(
                    replyUniqueId = replyTo.id,
                    authorOdinId = replyTo.originalAuthor?.domainName ?: "null",
                    message = replyTo.content.truncateToCodePoints(80),
                    previewThumbnail = replyTo.previewThumbnail
                )
                val newMessageId = Uuid.random()
                pendingMessageId = newMessageId
                chatMessageSenderService.replyToMessage(
                    messageUniqueId = newMessageId,
                    conversationId = conversationId,
                    replyTo = replyPreview,
                    messageText = content,
                    previousMessageUniqueId = null,
                    payloadBundle = payloadBundle
                )
            } catch (e: Exception) {
                sendEvent(ShowErrorMessage("Failed to send reply: ${e.message}"))
            }
        }
    }

    private fun addMessageWithFiles(
        conversationId: Uuid,
        content: String,
        files: List<AttachmentPendingFile>
    ) {
        viewModelScope.launch {
            val attachments = mutableListOf<AttachmentInput>()
            files.forEach { attachment ->
                when (attachment) {
                    is AttachmentPendingFile.File -> {
                        attachments.add(
                            AttachmentInput(
                                filePath = attachment.file.toString(),
                                contentType = detectContentTypeFromExtensionOrHint(
                                    attachment.file.name
                                ),
                                displayName = attachment.file.name,
                            )
                        )
                    }

                    is AttachmentPendingFile.FileImage -> {
                        attachments.add(
                            AttachmentInput(
                                filePath = attachment.file.toString(),
                                contentType = detectContentTypeFromExtensionOrHint(
                                    attachment.file.name
                                ),
                                displayName = attachment.file.name,
                            )
                        )
                    }

                    is AttachmentPendingFile.FileVideo -> {
                        attachments.add(
                            AttachmentInput(
                                filePath = attachment.file.toString(),
                                contentType = detectContentTypeFromExtensionOrHint(
                                    attachment.file.name
                                ),
                                displayName = attachment.file.name,
                            )
                        )
                    }

                    is AttachmentPendingFile.Gallery -> {
                        attachments.add(
                            AttachmentInput(
                                filePath = attachment.image.file.toString(),
                                contentType = detectContentTypeFromExtensionOrHint(
                                    attachment.image.fileName
                                ),
                                displayName = attachment.image.fileName,
                            )
                        )
                    }

                    is AttachmentPendingFile.Audio -> {
                        attachments.add(
                            AttachmentInput(
                                filePath = attachment.audioFile.toString(),
                                contentType = detectContentTypeFromExtensionOrHint(
                                    attachment.audioFile.name
                                ),
                                displayName = attachment.audioFile.name,
                                waveformFile = attachment.waveformFile?.toString(),
                                audioLengthSeconds = attachment.lengthSeconds,
                            )
                        )
                    }
                }
            }

            val newMessageId = Uuid.random()

            // Write a placeholder entry to the DB immediately so the message appears in the
            // list during the build+encrypt phase, before the real optimistic write fires.
            // For images, read pixel dimensions now so the aspect ratio — and therefore the
            // bubble size — is stable from the very first frame.
            val placeholderPayloads = attachments.mapIndexed { index, attachment ->
                val previewThumbnail = if (attachment.contentType.startsWith("image/")) {
                    try {
                        val bytes = fileOperationsProvider.readFileBytes(attachment.filePath)
                        val naturalSize = ImageUtils.getNaturalSize(bytes)
                        ThumbnailDescriptor(
                            pixelWidth = naturalSize.pixelWidth,
                            pixelHeight = naturalSize.pixelHeight,
                            contentType = attachment.contentType,
                        )
                    } catch (_: Exception) {
                        null
                    }
                } else null
                PayloadDescriptor(
                    key = "${ChatProtocol.PAYLOAD_KEY_MESSAGE_WEB}$index",
                    contentType = attachment.contentType,
                    iv = null,
                    descriptorContent = null,
                    previewThumbnail = previewThumbnail,
                )
            }.ifEmpty { null }

            chatMessageSenderService.writePlaceholderMessage(
                messageUniqueId = newMessageId,
                conversationId = conversationId,
                messageText = content,
                payloadDescriptors = placeholderPayloads,
            )

            _messagesUiState.update { state ->
                state.copy(
                    uploadProgress = (state.uploadProgress + (newMessageId to UploadStatus.Preparing)).toPersistentMap()
                )
            }

            pendingMessageId = newMessageId

            try {
                val bundle = MessageAttachmentBuilder.build(
                    attachments = attachments,
                    fileOperationsProvider = fileOperationsProvider,
                    payloadKeyFactory = { index, _ ->
                        "${ChatProtocol.PAYLOAD_KEY_MESSAGE_WEB}$index"
                    })

                chatMessageSenderService.sendNewMessage(
                    messageUniqueId = newMessageId,
                    conversationId = conversationId,
                    messageText = content,
                    previousMessageUniqueId = null,
                    payloadBundle = bundle,
                )
            } catch (e: Exception) {
                Logger.e("Failed to send file(s)", e)
                _messagesUiState.update { state ->
                    state.copy(uploadProgress = (state.uploadProgress - newMessageId).toPersistentMap())
                }
                sendEvent(
                    ShowErrorMessage(
                        "Failed to send file(s): ${e.message}"
                    )
                )
            }
        }
    }
}
