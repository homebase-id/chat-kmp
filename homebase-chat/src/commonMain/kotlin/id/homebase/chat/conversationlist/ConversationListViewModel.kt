package id.homebase.chat.conversationlist

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.mohamedrejeb.richeditor.model.RichTextState
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.OwnerSessionRepository
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.ThumbnailDescriptor
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.client.link.LinkPreview
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.image.ImageUtils
import id.homebase.api.image.convertHeicToJpeg
import id.homebase.api.util.truncateToCodePoints
import id.homebase.api.video.FFmpegUtils
import id.homebase.chat.conversationlist.ConversationListUiDialog.DeleteMessage
import id.homebase.chat.conversationlist.ConversationListUiDialog.DiscardDraft
import id.homebase.chat.conversationlist.ConversationListUiEvent.NavigateBack
import id.homebase.chat.conversationlist.ConversationListUiEvent.NavigateToContactInfo
import id.homebase.chat.conversationlist.ConversationListUiEvent.NavigateToConversationSettings
import id.homebase.chat.conversationlist.ConversationListUiEvent.NavigateToGroupSettings
import id.homebase.chat.conversationlist.ConversationListUiEvent.NavigateToMessageInfo
import id.homebase.chat.conversationlist.ConversationListUiEvent.NavigateToNewConversation
import id.homebase.chat.conversationlist.ConversationListUiEvent.SaveFileToDevice
import id.homebase.chat.conversationlist.ConversationListUiEvent.ShareFile
import id.homebase.chat.conversationlist.ConversationListUiEvent.ShareText
import id.homebase.chat.conversationlist.ConversationListUiEvent.ShowErrorMessage
import id.homebase.chat.conversationlist.ConversationListUiEvent.ShowInfoMessage
import id.homebase.chat.data.ConversationState
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.ChatMessageActionService
import id.homebase.chat.services.ChatMessageSenderService
import id.homebase.chat.services.ChatMessageStream
import id.homebase.chat.services.ChatMessagesData
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.LocalVideoContext
import id.homebase.chat.services.LocalVideoContextStore
import id.homebase.chat.services.ReplyPreview
import id.homebase.chat.services.builder.AttachmentInput
import id.homebase.chat.services.builder.LinkPreviewPayloadBuilder
import id.homebase.chat.services.builder.MessageAttachmentBuilder
import id.homebase.chat.services.convo.ConversationEnricher
import id.homebase.chat.services.convo.ConversationService
import id.homebase.chat.services.convo.ConversationStream
import id.homebase.chat.services.convo.EnrichedConversationUiModel
import id.homebase.chat.services.convo.contact.ConnectionService
import id.homebase.chat.services.convo.contact.ContactService
import id.homebase.chat.services.requests.ConnectionRequestService
import id.homebase.core.audio.AudioFileInfo
import id.homebase.core.audio.AudioRecorder
import id.homebase.core.audio.AudioWaveFormGenerator
import id.homebase.core.auth.AuthConnectionCoordinator
import id.homebase.core.auth.toConnectionStatus
import id.homebase.core.clipboard.platformFileFromPath
import id.homebase.core.config.AppConfig
import id.homebase.core.config.chatTargetDrive
import id.homebase.core.navigation.ActiveConversation
import id.homebase.core.settings.UserPreferences
import id.homebase.core.share.ShareContentProcessor
import id.homebase.core.util.ScrollPosition
import id.homebase.core.util.applyDefaultStyling
import id.homebase.core.util.buildBlockUrl
import id.homebase.core.util.buildConnectToIdentityUrl
import id.homebase.core.util.detectContentTypeFromExtensionOrHint
import id.homebase.core.util.extensionForMimeType
import id.homebase.core.util.resolveContentType
import id.homebase.resources.MR
import id.homebase.resources.chat_group_introduce_everyone_status
import id.homebase.resources.chat_message_audio_recording_help
import id.homebase.resources.chat_message_forwarded
import id.homebase.resources.chat_search_result_conversations
import id.homebase.resources.chat_search_result_messages
import id.homebase.resources.chat_search_result_pinned
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.cacheDir
import io.github.vinceglb.filekit.delete
import io.github.vinceglb.filekit.filesDir
import io.github.vinceglb.filekit.mimeType
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.write
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

@OptIn(FlowPreview::class)
class ConversationListViewModel(
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
    private val contactService: ContactService,
    private val connectionService: ConnectionService,
    private val connectionRequestService: ConnectionRequestService,
    private val driveFileProvider: DriveFileProvider,
    private val shareContentProcessor: ShareContentProcessor,
    private val localVideoContextStore: LocalVideoContextStore,
) : ViewModel() {

    companion object {
        private const val TAG = "ConversationListViewModel"
    }

    private val enricher = ConversationEnricher()
    val ownerSession = ownerSessionRepository.user

    private val _uiState = MutableStateFlow(ConversationListUiState())
    val uiState: StateFlow<ConversationListUiState> = _uiState.asStateFlow()

    private val _messagesUiState = MutableStateFlow(
        MessageListUiState(
            userDefaultReactions = userPreferences.preferredUserReactions.toPersistentList()
        )
    )
    val messagesUiState: StateFlow<MessageListUiState> = _messagesUiState.asStateFlow()

    val conversationSearchTextState = TextFieldState()

    val messagesSearchTextState = TextFieldState()
    val messageInputTextState = RichTextState().applyDefaultStyling()
    private var currentConversationJob: Job? = null
    private var pendingMessageId: Uuid? = null

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
            connectionService.start()
            connectionRequestService.start()

            val connectionStatusFlow = combine(
                connectionService.connections,
                connectionRequestService.incomingRequests,
                connectionRequestService.outgoingRequests,
            ) { connections, incoming, outgoing ->
                Triple(
                    connections.map,
                    incoming.map { it.senderOdinId }.toSet(),
                    outgoing.map { it.recipientOdinId }.toSet(),
                )
            }

            combine(
                conversationStream.conversations,
                contactService.contacts,
                ownerSessionRepository.user,
                connectionStatusFlow,
            ) { conversationState, contacts, ownerSession, connectionCtx ->

                if (ownerSession == null) return@combine Pair(false, emptyList())

                val contactMap = contacts.associateBy { it.odinId }
                val (connectionMap, incomingSenders, outgoingRecipients) = connectionCtx

                Pair(conversationState.dataReady, conversationState.items.map {
                    enricher.enrich(
                        convo = it,
                        contactMap = contactMap,
                        ownerSession = ownerSession,
                        connectionMap = connectionMap,
                        incomingRequestSenders = incomingSenders,
                        outgoingRequestRecipients = outgoingRecipients,
                    )
                })
            }.collect { (dataReady: Boolean, enriched: List<EnrichedConversationUiModel>) ->
                if (dataReady) {
                    _uiState.update {
                        it.copy(
                            activeConversations = enriched
                                .sortedByDescending { conversation -> conversation.conversation.latestMessageTimestamp }
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

        viewModelScope.launch {
            conversationStream.conversations.first { it.dataReady }
            conversationService.ensureNoteToSelfExists()
        }

        // Listen for search query changes
        viewModelScope.launch {
            snapshotFlow { conversationSearchTextState.text.toString() }.debounce(300)
                .collectLatest {
                    if (uiState.value.conversationsContent is ConversationListContentState.Items
                        || uiState.value.conversationsContent is ConversationListContentState.EmptySearch
                    ) {
                        updateListContent()
                    }
                }
        }

        // Listen for message search query changes
        viewModelScope.launch {
            snapshotFlow { messagesSearchTextState.text.toString() }.debounce(200)
                .collectLatest { query ->
                    updateMessageSearchResults(query)
                }
        }

        // Track upload progress via outbox and payload bundling events
        viewModelScope.launch {
            eventBus.events.filter { it is BackendEvent.PayloadBundlingEvent.Video.PhaseProgress }
                .collect { event ->
                    event as BackendEvent.PayloadBundlingEvent.Video.PhaseProgress
                    _messagesUiState.update { state ->
                        state.copy(
                            uploadProgress = (state.uploadProgress + (event.uniqueId to UploadStatus.Processing(
                                progress = event.progress,
                                phase = event.phase
                            ))).toPersistentMap()
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
                            uploadProgress = (state.uploadProgress + (event.uniqueId to UploadStatus.Uploading(
                                event.progress / 100f
                            ))).toPersistentMap()
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
                        localVideoContextStore.remove(event.uniqueId)
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
                    localVideoContextStore.remove(event.uniqueId)
                }
        }

        // Set connected state
        viewModelScope.launch {
            authConnectionCoordinator.connectionState
                .collectLatest { state ->
                    _uiState.update { it.copy(connectionStatus = state.toConnectionStatus()) }
                }
        }

        // Set isConnecting state
        viewModelScope.launch {
            eventBus.events
                .filter {
                    it is BackendEvent.SyncAllStarted ||
                            it is BackendEvent.SyncAllStopped
                }
                .collectLatest { event ->
                    when (event) {
                        is BackendEvent.SyncAllStarted -> _uiState.update {
                            it.copy(
                                driveIsSyncing = true,
                                hasDriveError = false
                            )
                        }

                        is BackendEvent.SyncAllStopped -> _uiState.update {
                            it.copy(
                                driveIsSyncing = false,
                                hasDriveError = event.result is BackendEvent.SyncAllResult.Failure
                            )
                        }

                        else -> Unit
                    }
                }
        }
    }

    fun selectConversation(
        conversationId: Uuid,
        messageId: Uuid? = null,
        scrollToBottom: Boolean = false
    ) {
        // Check for pending shared content (from iOS share extension or other handoff)
        viewModelScope.launch {
            processPendingSharedContent(conversationId)
        }

        ActiveConversation.selectConversation(conversationId)
        loadMessagesForConversation(conversationId, messageId, scrollToBottom)
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
                ActiveConversation.selectConversation(action.conversationId)
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

            is ConversationListUiAction.SearchMessagesClicked -> {
                _messagesUiState.update { it.copy(isSearchActive = true) }
            }

            is ConversationListUiAction.SearchMessagesBackClicked -> {
                _messagesUiState.update {
                    it.copy(
                        isSearchActive = false,
                        searchQuery = "",
                        searchResultMessageIds = emptyList(),
                        currentSearchResultIndex = -1,
                    )
                }
            }

            is ConversationListUiAction.SearchMessagesNavigateNext -> {
                val state = _messagesUiState.value
                val size = state.searchResultMessageIds.size
                if (size > 0 && state.currentSearchResultIndex < size - 1) {
                    _messagesUiState.update {
                        it.copy(currentSearchResultIndex = it.currentSearchResultIndex + 1)
                    }
                }
            }

            is ConversationListUiAction.SearchMessagesNavigatePrevious -> {
                val state = _messagesUiState.value
                if (state.currentSearchResultIndex > 0) {
                    _messagesUiState.update {
                        it.copy(currentSearchResultIndex = it.currentSearchResultIndex - 1)
                    }
                }
            }

            is ConversationListUiAction.NewConversationClicked -> {
                _uiState.value = _uiState.value.copy(
                    uiEvent = NavigateToNewConversation
                )
            }

            is ConversationListUiAction.ClearSelection -> {
                ActiveConversation.selectConversation(null)
                currentConversationJob?.cancel()
                _uiState.update { it.copy(selectedConversationId = null) }
                _messagesUiState.update {
                    it.copy(
                        messages = persistentListOf(),
                        isLoadingMessages = false
                    )
                }
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
                    _messagesUiState.update { it.copy(isSendingMessage = true) }
                    val content = messageInputTextState.toMarkdown().trimEnd()
                    val replyTo = _messagesUiState.value.replyToMessage
                    if (replyTo != null) {
                        replyToMessage(
                            conversationId = action.conversationId,
                            replyTo = replyTo,
                            content = content,
                            linkPreview = action.linkPreview
                        )
                    } else {
                        addMessage(
                            conversationId = action.conversationId,
                            content = content,
                            linkPreview = action.linkPreview
                        )
                    }
                    // Input is cleared inside addMessage/replyToMessage after
                    // the send is successfully queued.
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
                _messagesUiState.update { it.copy(isSendingMessage = true) }
                _messagesUiState.value.isEditingMessageId?.let { messageId ->
                    editMessage(
                        messageId = messageId,
                        versionTag = _messagesUiState.value.isEditingVersionTag ?: Uuid.NIL,
                        content = messageInputTextState.toMarkdown().trimEnd(),
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
                val isWithSelf =
                    message.conversationId == ChatProtocol.ConversationWithYourselfId
                _uiState.update {
                    it.copy(
                        uiDialog = DeleteMessage(
                            messageId = action.messageId,
                            allowDeleteForEveryone = isCurrentUserMessage && !isWithSelf
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
                _messagesUiState.update { it.copy(downloadingFiles = it.downloadingFiles + fileKey) }

                viewModelScope.launch {
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

                        val success = withContext(Dispatchers.IO) {
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
                        _messagesUiState.update {
                            it.copy(downloadingFiles = it.downloadingFiles - fileKey)
                        }
                    }
                }
            }

            is ConversationListUiAction.DownloadVideoMedia -> {
                val fileKey = "${action.fileId}_${action.payloadKey}"
                _messagesUiState.update { it.copy(downloadingFiles = it.downloadingFiles + fileKey) }

                viewModelScope.launch {
                    try {
                        val fullName = resolveDownloadFileName(
                            action.payload.filename(), action.payloadKey, action.payload.contentType
                        )
                        val filePath =
                            "${fileOperationsProvider.getCacheDirectory()}/$fullName"

                        val success = withContext(Dispatchers.IO) {
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
                    } catch (e: Exception) {
                        sendEvent(ShowErrorMessage("Error downloading file: ${e.message}"))
                    } finally {
                        _messagesUiState.update { it.copy(downloadingFiles = it.downloadingFiles - fileKey) }
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

                        val fileName = payload.filename() ?: payload.key
                        var extension = payload.contentType?.substringAfter("/") ?: "bin"
                        extension = when (extension) {
                            "jpeg" -> "jpg"
                            else -> extension
                        }
                        val filePath =
                            "${fileOperationsProvider.getCacheDirectory()}/$fileName.$extension"

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
                                _messagesUiState.value.decryptedFiles.toMutableMap()
                            decryptedFiles[DecryptedFileKey(message.fileId, action.payloadKey)] =
                                filePath
                            _messagesUiState.update { it.copy(decryptedFiles = decryptedFiles.toPersistentMap()) }
                        } else {
                            sendEvent(ShowErrorMessage("Error downloading file"))
                        }
                    } catch (e: Exception) {
                        sendEvent(
                            ShowErrorMessage("Error downloading file: ${e.message}")
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
                val (filePath, fileName) = when (val f = action.file) {
                    is AttachmentPendingFile.FileImage -> f.file.toString() to f.file.name
                    is AttachmentPendingFile.FileVideo -> f.file.toString() to f.file.name
                    is AttachmentPendingFile.File -> f.file.toString() to f.file.name
                    is AttachmentPendingFile.Gallery -> f.image.file.toString() to f.image.fileName
                    is AttachmentPendingFile.Audio -> f.audioFile.toString() to f.audioFile.name
                }
                sendEvent(SaveFileToDevice(filePath, fileName))
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
                        val conversation =
                            conversationService.getConversation(action.conversationId)
                                ?: return@launch
                        if (conversation.isPinned) {
                            conversationService.unpinConversation(action.conversationId)
                        } else {
                            conversationService.pinConversation(action.conversationId)
                        }
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
                        if (action.reaction.isEmpty()) return@launch
                        val newTopReactions =
                            _messagesUiState.value.userDefaultReactions.toMutableList()
                        newTopReactions.remove(action.reaction)
                        newTopReactions.add(0, action.reaction)
                        _messagesUiState.update {
                            it.copy(userDefaultReactions = newTopReactions.toPersistentList())
                        }
                        if (newTopReactions.isNotEmpty()) {
                            userPreferences.preferredUserReactions = newTopReactions.take(6)
                        }

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
                _messagesUiState.update { it.copy(scrollPosition = null, isSendingMessage = true) }

                addMessageWithFiles(
                    conversationId = action.conversationId,
                    content = action.message.trimEnd(),
                    files = action.attachments,
                )
                // Input is cleared inside addMessageWithFiles after
                // the send is successfully queued.
            }

            is ConversationListUiAction.AttachPlatformFile -> {
                viewModelScope.launch {
                    try {
                        val newFiles = action.files.map {
                            val ct = it.mimeType()?.toString()
                                ?: detectContentTypeFromExtensionOrHint(it.name)
                            when {
                                ct.startsWith("video/") -> {
                                    val thumbnailBytes = try {
                                        val resolvedPath =
                                            fileOperationsProvider.resolveToFilePath(it.toString())
                                        val thumbPath = FFmpegUtils.grabThumbnail(resolvedPath)
                                        if (thumbPath != null) {
                                            val bytes =
                                                fileOperationsProvider.readFileBytes(thumbPath)
                                            fileOperationsProvider.deleteTempFile(thumbPath)
                                            bytes
                                        } else null
                                    } catch (_: Exception) {
                                        null
                                    }
                                    AttachmentPendingFile.FileVideo(
                                        Uuid.generateV7(),
                                        it,
                                        thumbnailBytes
                                    )
                                }

                                action.isImage || ct.startsWith("image/") -> AttachmentPendingFile.FileImage(
                                    Uuid.generateV7(),
                                    it
                                )

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
                            if (it.mimeType.startsWith("video/")) {
                                val thumbnailBytes = try {
                                    val resolvedPath =
                                        fileOperationsProvider.resolveToFilePath(it.file.toString())
                                    val thumbPath = FFmpegUtils.grabThumbnail(resolvedPath)
                                    if (thumbPath != null) {
                                        val bytes = fileOperationsProvider.readFileBytes(thumbPath)
                                        fileOperationsProvider.deleteTempFile(thumbPath)
                                        bytes
                                    } else null
                                } catch (_: Exception) {
                                    null
                                }
                                AttachmentPendingFile.FileVideo(
                                    Uuid.generateV7(),
                                    it.file,
                                    thumbnailBytes
                                )
                            } else {
                                AttachmentPendingFile.Gallery(Uuid.generateV7(), it)
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
                                val localContext = localVideoContextStore.get(action.message.id)
                                val ivBytes = selectedPayload.iv?.let { Base64.decode(it) }

                                if (ivBytes != null || localContext != null) {
                                    _messagesUiState.update {
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

            is ConversationListUiAction.ForwardMessage -> {
                viewModelScope.launch {
                    try {
                        val allConversations = _uiState.value.activeConversations
                        val allContacts = contactService.contacts.value

                        val selfConversation =
                            allConversations.firstOrNull { it.conversation.isWithSelf }
                        val nonSelfConversations =
                            allConversations.filter { !it.conversation.isWithSelf }
                        val recentConversations = nonSelfConversations
                            .filter { !it.conversation.isGroupConversation }
                            .take(5)
                        val groupConversations = nonSelfConversations
                            .filter { it.conversation.isGroupConversation }
                            .sortedBy { it.getDisplayName().lowercase() }
                        val sortedContacts = allContacts.sortedBy { it.name.lowercase() }

                        val recipientGroups = buildList {
                            if (selfConversation != null) {
                                add(
                                    RecipientGroupModel(
                                        recipientType = RecipientType.You,
                                        recipients = listOf(
                                            RecipientModel.Conversation(
                                                selfConversation
                                            )
                                        )
                                    )
                                )
                            }
                            if (recentConversations.isNotEmpty()) {
                                add(
                                    RecipientGroupModel(
                                        recipientType = RecipientType.Recents,
                                        recipients = recentConversations.map {
                                            RecipientModel.Conversation(
                                                it
                                            )
                                        }
                                    ))
                            }
                            if (sortedContacts.isNotEmpty()) {
                                add(
                                    RecipientGroupModel(
                                        recipientType = RecipientType.Contacts,
                                        recipients = sortedContacts.map { RecipientModel.Contact(it) }
                                    ))
                            }
                            if (groupConversations.isNotEmpty()) {
                                add(
                                    RecipientGroupModel(
                                        recipientType = RecipientType.Groups,
                                        recipients = groupConversations.map {
                                            RecipientModel.Conversation(
                                                it
                                            )
                                        }
                                    ))
                            }
                        }

                        _messagesUiState.update {
                            it.copy(
                                uiSheet = MessageListUiSheet.ForwardMessage(
                                    message = action.message,
                                    recipients = recipientGroups.toPersistentList(),
                                    selectedRecipients = persistentListOf(),
                                )
                            )
                        }
                    } catch (e: Exception) {
                        Logger.e("Failed to open forward message sheet", e)
                        sendEvent(
                            ShowErrorMessage(
                                "Failed to open forward message sheet: ${e.message}"
                            )
                        )
                    }
                }
            }

            is ConversationListUiAction.ForwardMessageSelectRecipient -> {
                val updatedSheet =
                    (_messagesUiState.value.uiSheet as? MessageListUiSheet.ForwardMessage)?.let {
                        if (it.selectedRecipients.contains(action.recipient)) {
                            val newSelected = it.selectedRecipients - action.recipient
                            it.copy(selectedRecipients = newSelected.toPersistentList())
                        } else {
                            val newSelected = it.selectedRecipients + action.recipient
                            it.copy(selectedRecipients = newSelected.toPersistentList())
                        }
                    }
                _messagesUiState.update { it.copy(uiSheet = updatedSheet) }
            }

            is ConversationListUiAction.ForwardMessageSend -> {
                viewModelScope.launch {
                    try {
                        val conversationIds = action.recipients.map { recipientModel ->
                            when (recipientModel) {
                                is RecipientModel.Contact -> {
                                    conversationService.createConversation(
                                        recipients = listOf(recipientModel.contact.odinId),
                                        title = "",
                                        payloadBundle = null,
                                    )
                                }

                                is RecipientModel.Conversation -> recipientModel.conversation.conversation.id
                            }
                        }
                        chatMessageSenderService.forwardMessage(
                            sourceMessageUniqueId = action.message.id,
                            targetConversationIds = conversationIds
                        )
                        _messagesUiState.update { it.copy(uiSheet = null) }
                        sendEvent(ShowInfoMessage(MR.string.chat_message_forwarded))
                    } catch (e: Exception) {
                        Logger.e("Failed to send forward message", e)
                        sendEvent(
                            ShowErrorMessage(
                                "Failed to send forward message: ${e.message}"
                            )
                        )
                    }
                }
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
                if (action.odinId == uiState.value.ownerSession?.odinId?.domainName) {
                    // Show self-conversation settings as owner profile
                    _uiState.update {
                        it.copy(
                            uiEvent = NavigateToConversationSettings(
                                ChatProtocol.ConversationWithYourselfId.toString()
                            )
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            uiEvent = NavigateToContactInfo(action.odinId)
                        )
                    }
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
                        uiSheet = MessageListUiSheet.ConnectIdentities(
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

            is ConversationListUiAction.OpenConnectionRequestInOwnerConsole -> {
                uiState.value.ownerSession?.odinId?.let { currentUser ->
                    val url =
                        "https://${currentUser.domainName}/owner/connections/${action.odinId.domainName}"
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
                viewModelScope.launch {
                    try {
                        conversationService.archiveConversation(action.conversationId)
                    } catch (e: Exception) {
                        Logger.e(throwable = e, tag = "ConversationListViewModel") {
                            "Failed to archive conversation: ${e.message}"
                        }
                        sendEvent(ShowErrorMessage("Failed to archive conversation: ${e.message}"))
                    }
                }
            }

            is ConversationListUiAction.UnarchiveConversation -> {
                viewModelScope.launch {
                    try {
                        conversationService.unarchiveConversation(action.conversationId)
                    } catch (e: Exception) {
                        Logger.e(throwable = e, tag = "ConversationListViewModel") {
                            "Failed to unarchive conversation: ${e.message}"
                        }
                        sendEvent(ShowErrorMessage("Failed to unarchive conversation: ${e.message}"))
                    }
                }
            }

            is ConversationListUiAction.ShowArchivedMessagesClicked -> {
                _uiState.update { it.copy(uiEvent = ConversationListUiEvent.NavigateToArchivedConversations) }
            }

            is ConversationListUiAction.ClearConversation -> {
                viewModelScope.launch {
                    try {
                        conversationService.clearConversation(action.conversationId)
                    } catch (e: Exception) {
                        Logger.e(throwable = e, tag = "ConversationListViewModel") {
                            "Failed to clear conversation: ${e.message}"
                        }
                        sendEvent(ShowErrorMessage("Failed to clear conversation: ${e.message}"))
                    }
                }
            }

            is ConversationListUiAction.DeleteConversation -> {
                _uiState.update {
                    it.copy(uiDialog = ConversationListUiDialog.DeleteConversation(action.conversationId))
                }
            }

            is ConversationListUiAction.ConfirmDeleteConversation -> {
                viewModelScope.launch {
                    try {
                        conversationService.deleteConversation(action.conversationId)
                    } catch (e: Exception) {
                        Logger.e(throwable = e, tag = "ConversationListViewModel") {
                            "Failed to delete conversation: ${e.message}"
                        }
                        sendEvent(ShowErrorMessage("Failed to delete conversation: ${e.message}"))
                    }
                }
            }

            is ConversationListUiAction.AcceptRejoin -> {
                viewModelScope.launch {
                    try {
                        conversationService.acceptRejoin(action.conversationId)
                    } catch (e: Exception) {
                        Logger.e(throwable = e, tag = "ConversationListViewModel") {
                            "Failed to accept rejoin: ${e.message}"
                        }
                        sendEvent(ShowErrorMessage("Failed to accept rejoin: ${e.message}"))
                    }
                }
            }

            is ConversationListUiAction.DeclineRejoin -> {
                viewModelScope.launch {
                    try {
                        conversationService.declineRejoin(action.conversationId)
                        conversationStream.onConversationLeft(action.conversationId)
                    } catch (e: Exception) {
                        Logger.e(throwable = e, tag = "ConversationListViewModel") {
                            "Failed to decline rejoin: ${e.message}"
                        }
                        sendEvent(ShowErrorMessage("Failed to decline rejoin: ${e.message}"))
                    }
                }
            }

            /* Clipboard image paste */
            is ConversationListUiAction.AttachClipboardImage -> {
                viewModelScope.launch {
                    try {
                        val tempPath = fileOperationsProvider.writeBytesToTempFile(
                            action.imageBytes,
                            "clipboard_image",
                            "png"
                        )
                        val platformFile = platformFileFromPath(tempPath)
                        val newFile = AttachmentPendingFile.FileImage(
                            Uuid.generateV7(),
                            platformFile
                        )
                        val conversation = _uiState.value.activeConversations.find {
                            it.conversation.id == action.conversationId
                        }
                        if (conversation == null) return@launch

                        val overlay = _messagesUiState.value.fullScreenOverlay
                        val newOverlay = if (overlay is FullScreenOverlay.AttachmentData) {
                            overlay.copy(
                                attachments = overlay.attachments + newFile,
                            )
                        } else {
                            FullScreenOverlay.AttachmentData(
                                conversationTitle = conversation.conversation.name,
                                conversationId = action.conversationId,
                                selected = newFile.attachmentId,
                                attachments = listOf(newFile),
                            )
                        }

                        _messagesUiState.update {
                            it.copy(fullScreenOverlay = newOverlay)
                        }
                    } catch (e: Exception) {
                        Logger.e("Failed to attach clipboard image", e)
                        sendEvent(ShowErrorMessage("Failed to paste image: ${e.message}"))
                    }
                }
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

            is ConversationListUiAction.BlockUser -> {
                uiState.value.ownerSession?.odinId?.let { currentUser ->
                    val url = currentUser.buildBlockUrl(action.authorOdinId)
                    sendEvent(ConversationListUiEvent.OpenUrl(url))
                }
            }

            is ConversationListUiAction.ReportContent -> {
                sendEvent(ConversationListUiEvent.OpenUrl(AppConfig.REPORT_CONTENT_URL))
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

    private fun updateMessageSearchResults(query: String) {
        val messages = _messagesUiState.value.messages
        if (query.isBlank()) {
            _messagesUiState.update {
                it.copy(
                    searchQuery = "",
                    searchResultMessageIds = emptyList(),
                    currentSearchResultIndex = -1,
                )
            }
            return
        }
        val lowerQuery = query.lowercase()
        val matchingIds = messages
            .filterIsInstance<MessageListContentModel.Message>()
            .filter { it.message.content.lowercase().contains(lowerQuery) }
            .map { it.message.id }
        val startIndex = if (matchingIds.isNotEmpty()) matchingIds.size - 1 else -1
        _messagesUiState.update {
            it.copy(
                searchQuery = query,
                searchResultMessageIds = matchingIds,
                currentSearchResultIndex = startIndex,
            )
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
                        .filter {
                            !it.conversation.isPinned && (it.conversation.conversationState == ConversationState.Active
                                    || it.conversation.conversationState == ConversationState.Left
                                    || it.conversation.conversationState == ConversationState.RejoinPending
                                    || it.conversation.conversationState == ConversationState.Removed)
                        }
                        .map { conv -> ConversationListContentModel.Conversation(conv) }
                        .toPersistentList()
                    val archivedCount =
                        conversationsPool.count { it.conversation.conversationState == ConversationState.Archived }

                    if (normalItems.isNotEmpty()) {
                        if (pinnedItems.isNotEmpty()) {
                            items.add(ConversationListContentModel.Header(MR.string.chat_search_result_conversations))
                        }
                        items.addAll(normalItems)
                    }

                    _uiState.update {
                        it.copy(
                            conversationsContent = if (items.isEmpty()) ConversationListContentState.Empty
                            else ConversationListContentState.Items(items.toPersistentList()),
                            archivedCount = archivedCount
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

    private fun loadMessagesForConversation(
        conversationId: Uuid,
        messageIdForScroll: Uuid?,
        scrollToBottom: Boolean = false
    ) {
        val loadStart = TimeSource.Monotonic.markNow()

        val hasCachedMessages = chatMessageStream.hasCachedMessages(conversationId)

        _messagesUiState.update {
            it.copy(scrollPosition = null, isLoadingMessages = !hasCachedMessages)
        }

        // When loading message for newly selected conversation, cancel any previous job to
        // avoid observing multiple messageStreams
        currentConversationJob?.cancel()
        currentConversationJob = viewModelScope.launch {
            try {
                var messageIdForScrollNullable = messageIdForScroll
                var setInitialScroll = true

                if (!hasCachedMessages) {
                    chatMessageStream.loadConversation(conversationId)
                }

                chatMessageStream.observeMessages(conversationId).collect { messageState ->
                    when (messageState) {
                        is ChatMessagesData.Initializing -> {
                            // ignore
                        }

                        is ChatMessagesData.Messages -> {
                            val exitedAt = _uiState.value.activeConversations
                                .find { it.conversation.id == conversationId }
                                ?.conversation?.exitedAt
                            val filteredByExit = if (exitedAt != null)
                                messageState.messages.filter { it.userDate <= exitedAt }
                            else
                                messageState.messages
                            // Hide soft-deleted messages in "Note to Self" conversation
                            val messages =
                                if (conversationId == ChatProtocol.ConversationWithYourselfId)
                                    filteredByExit.filter { !it.isDeleted }
                                else
                                    filteredByExit
                            // Group messages within day sections
                            val timezone = TimeZone.currentSystemDefault()
                            val groupedMessages =
                                messages.sortedBy { it.userDate }.groupBy { message ->
                                    val date = message.userDate.toLocalDateTime(timezone).date
                                    date
                                }
                            val messagesModels: MutableList<MessageListContentModel> =
                                mutableListOf(MessageListContentModel.Header)

                            var systemIndex = 0
                            messagesModels.addAll(groupedMessages.flatMap { (date, messages) ->
                                listOf(MessageListContentModel.Section(date)) + messages.map {
                                    if (it.isStatusMessage)
                                        MessageListContentModel.System(
                                            it.content,
                                            it.userDate,
                                            systemIndex++
                                        )
                                    else
                                        MessageListContentModel.Message(it)
                                }
                            })

                            // Scroll handling, either use new message id, click message id or null
                            val newMessageId =
                                messages.firstOrNull { it.id == pendingMessageId }?.id
                            pendingMessageId = null
                            val indexOfMessageForScroll = if (newMessageId != null) {
                                val index = messagesModels.indexOfLast {
                                    it is MessageListContentModel.Message && it.message.id == newMessageId
                                }
                                Logger.i("Resetting scroll position, new message seen, index: $index")
                                index
                            } else {
                                if (messageIdForScrollNullable == null) {
                                    null
                                } else {
                                    val messageIndex = messagesModels.indexOfLast {
                                        it is MessageListContentModel.Message && it.message.id == messageIdForScrollNullable
                                    }
                                    messageIdForScrollNullable = null
                                    messageIndex
                                }
                            }

                            val newScroll = if (indexOfMessageForScroll == null) {
                                if (setInitialScroll && !scrollToBottom) {
                                    Logger.i("Getting saved scroll position")
                                    getScrollPosition(conversationId)
                                } else {
                                    Logger.i("No saved scroll position")
                                    null
                                }
                            } else {
                                Logger.i("Setting scroll position: $indexOfMessageForScroll")
                                ScrollPosition(
                                    firstVisibleItemIndex = indexOfMessageForScroll,
                                    triggerScroll = true
                                )
                            }

                            if (newScroll != null) {
                                userPreferences.setConversationScrollIndex(
                                    conversationId.toString(),
                                    newScroll.firstVisibleItemIndex
                                )
                                userPreferences.setConversationScrollOffset(
                                    conversationId.toString(),
                                    newScroll.firstVisibleItemScrollOffset
                                )
                            }

                            _uiState.value = _uiState.value.copy(
                                selectedConversationId = conversationId,
                            )

                            _messagesUiState.update {
                                it.copy(
                                    isLoadingMessages = false,
                                    messages = messagesModels.toPersistentList(),
                                    scrollPosition = newScroll
                                        ?: it.scrollPosition?.takeIf { pos -> pos.triggerScroll },
                                )
                            }

                            if (setInitialScroll) {
                                val totalElapsed = loadStart.elapsedNow()
                                Logger.d(tag = "ConversationLoad") {
                                    "conversationId=$conversationId " +
                                            "messageCount=${messages.size} " +
                                            "cached=$hasCachedMessages " +
                                            "total=$totalElapsed"
                                }
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
                messageInputTextState.clear()
            } catch (e: Exception) {
                sendEvent(ShowErrorMessage("Failed to edit message: ${e.message}"))
            } finally {
                _messagesUiState.update { it.copy(isSendingMessage = false) }
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
                Logger.d(tag = TAG) { "addMessage: message=$newMessageId conversation=$conversationId" }

                chatMessageSenderService.sendNewMessage(
                    messageUniqueId = newMessageId,
                    conversationId = conversationId,
                    messageText = content,
                    previousMessageUniqueId = null,
                    payloadBundle = payloadBundle,
                )
                messageInputTextState.clear()
                Logger.d(tag = TAG) { "addMessage: complete message=$newMessageId" }
            } catch (e: Exception) {
                Logger.e(
                    throwable = e,
                    tag = TAG
                ) { "addMessage failed for conversation=$conversationId" }
                sendEvent(ShowErrorMessage("Failed to send message: ${e.message}"))
            } finally {
                _messagesUiState.update { it.copy(isSendingMessage = false) }
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
                Logger.d(tag = TAG) { "replyToMessage: message=$newMessageId conversation=$conversationId replyTo=${replyTo.id}" }

                chatMessageSenderService.replyToMessage(
                    messageUniqueId = newMessageId,
                    conversationId = conversationId,
                    replyTo = replyPreview,
                    messageText = content,
                    previousMessageUniqueId = null,
                    payloadBundle = payloadBundle
                )
                messageInputTextState.clear()
                _messagesUiState.update { it.copy(replyToMessage = null) }
                Logger.d(tag = TAG) { "replyToMessage: complete message=$newMessageId" }
            } catch (e: Exception) {
                Logger.e(
                    throwable = e,
                    tag = TAG
                ) { "replyToMessage failed for conversation=$conversationId" }
                sendEvent(ShowErrorMessage("Failed to send reply: ${e.message}"))
            } finally {
                _messagesUiState.update { it.copy(isSendingMessage = false) }
            }
        }
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
                                contentType = resolveContentType(
                                    fileName = attachment.file.name,
                                    platformMimeType = attachment.file.mimeType()?.toString(),
                                ),
                                displayName = attachment.file.name,
                            )
                        )
                    }

                    is AttachmentPendingFile.FileImage -> {
                        var filePath = attachment.file.toString()
                        var contentType = resolveContentType(
                            fileName = attachment.file.name,
                            platformMimeType = attachment.file.mimeType()?.toString(),
                        )
                        if (contentType == "image/heic" || contentType == "image/heif") {
                            val heicBytes = fileOperationsProvider.readFileBytes(filePath)
                            val jpegBytes = convertHeicToJpeg(heicBytes)
                            if (jpegBytes != null) {
                                filePath = fileOperationsProvider.writeBytesToTempFile(
                                    jpegBytes,
                                    "heic_converted_",
                                    ".jpg"
                                )
                                contentType = "image/jpeg"
                            }
                        }
                        attachments.add(
                            AttachmentInput(
                                filePath = filePath,
                                contentType = contentType,
                                displayName = attachment.file.name,
                            )
                        )
                    }

                    is AttachmentPendingFile.FileVideo -> {
                        attachments.add(
                            AttachmentInput(
                                filePath = attachment.file.toString(),
                                contentType = resolveContentType(
                                    fileName = attachment.file.name,
                                    platformMimeType = attachment.file.mimeType()?.toString(),
                                ),
                                displayName = attachment.file.name,
                            )
                        )
                    }

                    is AttachmentPendingFile.Gallery -> {
                        var filePath = attachment.image.file.toString()
                        var contentType = resolveContentType(
                            fileName = attachment.image.fileName,
                        )
                        if (contentType == "image/heic" || contentType == "image/heif") {
                            val heicBytes = fileOperationsProvider.readFileBytes(filePath)
                            val jpegBytes = convertHeicToJpeg(heicBytes)
                            if (jpegBytes != null) {
                                filePath = fileOperationsProvider.writeBytesToTempFile(
                                    jpegBytes,
                                    "heic_converted_",
                                    ".jpg"
                                )
                                contentType = "image/jpeg"
                            }
                        }
                        attachments.add(
                            AttachmentInput(
                                filePath = filePath,
                                contentType = contentType,
                                displayName = attachment.image.fileName,
                            )
                        )
                    }

                    is AttachmentPendingFile.Audio -> {
                        attachments.add(
                            AttachmentInput(
                                filePath = attachment.audioFile.toString(),
                                contentType = resolveContentType(
                                    fileName = attachment.audioFile.name,
                                    platformMimeType = attachment.audioFile.mimeType()?.toString(),
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
            Logger.d(tag = TAG) { "addMessageWithFiles: message=$newMessageId conversation=$conversationId files=${files.size}" }

            _messagesUiState.update { state ->
                state.copy(
                    uploadProgress = (state.uploadProgress + (newMessageId to UploadStatus.Preparing)).toPersistentMap()
                )
            }

            // Store local video context for thumbnail preview during upload
            files.filterIsInstance<AttachmentPendingFile.FileVideo>()
                .firstOrNull()?.let { videoFile ->
                    val thumbBytes = videoFile.thumbnailBytes
                    if (thumbBytes != null) {
                        localVideoContextStore.put(
                            newMessageId,
                            LocalVideoContext(
                                thumbnailBytes = thumbBytes,
                                localFilePath = videoFile.file.toString(),
                            )
                        )
                    }
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
                messageInputTextState.clear()
                _messagesUiState.update {
                    it.copy(
                        fullScreenOverlay = null,
                        isSendingMessage = false
                    )
                }
            } catch (e: Exception) {
                Logger.e(
                    throwable = e,
                    tag = TAG
                ) { "addMessageWithFiles failed for message=$newMessageId conversation=$conversationId" }
                _messagesUiState.update { state ->
                    state.copy(
                        uploadProgress = (state.uploadProgress - newMessageId).toPersistentMap(),
                        isSendingMessage = false
                    )
                }
                sendEvent(
                    ShowErrorMessage(
                        "Failed to send file(s): ${e.message}"
                    )
                )
            }
        }
    }

    /**
     * Checks for pending shared content (from iOS share extension handoff)
     * and sends it to the given conversation automatically.
     */
    private suspend fun processPendingSharedContent(conversationId: Uuid) {
        val descriptor = shareContentProcessor.readPendingContent() ?: return
        // Only process if the target conversation matches
        if (descriptor.targetConversationId != conversationId.toString()) return

        Logger.i(tag = "ConversationListViewModel") {
            "Processing shared content: type=${descriptor.contentType}, files=${descriptor.fileNames.size}"
        }

        try {
            val text = descriptor.text ?: descriptor.url ?: ""

            if (descriptor.fileNames.isEmpty()) {
                // Text/URL only
                addMessage(conversationId, text)
            } else {
                // Build AttachmentInput list from shared files
                val attachments =
                    descriptor.fileNames.zip(descriptor.mimeTypes).map { (name, mime) ->
                        val filePath = shareContentProcessor.resolveFilePath(name)
                        AttachmentInput(
                            filePath = filePath,
                            contentType = mime,
                            displayName = name,
                        )
                    }

                val newMessageId = Uuid.random()
                pendingMessageId = newMessageId

                val bundle = MessageAttachmentBuilder.build(
                    attachments = attachments,
                    fileOperationsProvider = fileOperationsProvider,
                    payloadKeyFactory = { index, _ ->
                        "${ChatProtocol.PAYLOAD_KEY_MESSAGE_WEB}$index"
                    }
                )

                chatMessageSenderService.sendNewMessage(
                    messageUniqueId = newMessageId,
                    conversationId = conversationId,
                    messageText = text,
                    previousMessageUniqueId = null,
                    payloadBundle = bundle,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(tag = "ConversationListViewModel") { "Failed to send shared content: ${e.message}" }
            sendEvent(ShowErrorMessage("Failed to send shared content: ${e.message}"))
        } finally {
            shareContentProcessor.cleanup()
        }
    }
}
