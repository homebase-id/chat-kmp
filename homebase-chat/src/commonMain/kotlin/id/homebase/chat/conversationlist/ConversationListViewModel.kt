package id.homebase.chat.conversationlist

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.mohamedrejeb.richeditor.model.RichTextState
import id.homebase.api.client.ClientException
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.OdinClientErrorCode
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.connections.AutoConnectOutcome
import id.homebase.api.client.connections.ConnectionRequestResult
import id.homebase.api.client.connections.ConnectionRequestHeader
import id.homebase.api.common.OdinId
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.auth.OwnerSession
import id.homebase.api.client.auth.OwnerSessionRepository
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.chat.services.renderer.PayloadRenderer
import id.homebase.chat.services.renderer.toCombinedPayloadBundle
import id.homebase.chat.services.renderer.toMessageDataType
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.image.ImageHeaderParser
import id.homebase.api.image.ImageUtils
import id.homebase.api.image.convertHeicToJpeg
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.util.truncateToCodePoints
import id.homebase.api.video.FFmpegUtils
import id.homebase.api.video.VideoMetadata
import id.homebase.api.video.VideoThumbnailExtractor
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
import id.homebase.chat.services.LocalAttachmentContext
import id.homebase.chat.services.LocalAttachmentContextStore
import id.homebase.chat.services.ReplyPreview
import id.homebase.chat.services.builder.AttachmentInput
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
import id.homebase.core.notifications.PendingNotificationTap
import id.homebase.core.settings.UserPreferences
import id.homebase.core.share.ShareContentProcessor
import id.homebase.core.util.ScrollPosition
import id.homebase.core.widget.ReactionDisplayItem
import id.homebase.core.util.applyDefaultStyling
import id.homebase.core.util.buildBlockUrl
import id.homebase.core.util.buildConnectToIdentityUrl
import id.homebase.core.util.detectContentTypeFromExtensionOrHint
import id.homebase.core.util.extensionForMimeType
import id.homebase.core.util.resolveContentType
import id.homebase.resources.MR
import id.homebase.resources.auto_connect_blocked
import id.homebase.resources.auto_connect_duplicate_introductory_request
import id.homebase.resources.auto_connect_failed_generic
import id.homebase.resources.auto_connect_invalid_request
import id.homebase.resources.auto_connect_invalid_request_with_detail
import id.homebase.resources.auto_connect_outgoing_request_exists
import id.homebase.resources.auto_connect_pending_manual_approval
import id.homebase.resources.auto_connect_recipient_not_configured
import id.homebase.resources.auto_connect_recipient_rejected
import id.homebase.resources.auto_connect_recipient_requires_upgrade
import id.homebase.resources.auto_connect_recipient_unreachable
import id.homebase.resources.chat_conversation_deleted_confirmation
import id.homebase.resources.chat_conversation_deleting_in_progress
import id.homebase.resources.chat_conversation_leaving_and_deleting_in_progress
import id.homebase.resources.chat_group_introduce_everyone_status
import id.homebase.resources.chat_introduce_preflight_in_progress
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
import id.homebase.core.localization.TranslationUtil
import id.homebase.resources.chat_attach_file_failed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
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

private data class ConnectionStatusContext(
    val connectionMap: Map<id.homebase.api.common.OdinId, id.homebase.api.client.connections.RedactedIdentityConnectionRegistration>,
    val incomingSenders: Set<id.homebase.api.common.OdinId>,
    val outgoingRecipients: Set<id.homebase.api.common.OdinId>,
    val statusKnown: Boolean,
)

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
    private val credentialsManager: CredentialsManager,
    private val authConnectionCoordinator: AuthConnectionCoordinator,
    private val audioRecorder: AudioRecorder,
    private val audioWaveFormGenerator: AudioWaveFormGenerator,
    private val eventBus: EventBus,
    private val contactService: ContactService,
    private val connectionService: ConnectionService,
    private val connectionRequestService: ConnectionRequestService,
    private val driveFileProvider: DriveFileProvider,
    private val shareContentProcessor: ShareContentProcessor,
    private val localVideoContextStore: LocalAttachmentContextStore,
    private val pendingNotificationTap: PendingNotificationTap,
    private val cropResultBus: id.homebase.imageeditor.ui.CropResultBus,
    private val drawResultBus: id.homebase.imageeditor.ui.DrawResultBus,
    private val postCreateIntroductionPreflightBus: id.homebase.chat.services.convo.PostCreateIntroductionPreflightBus,
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

    // Tracks in-flight video thumbnail extraction per pending attachment so the editor can
    // open instantly (Signal-style) while the FFmpeg/MediaMetadataRetriever poster work
    // happens in the background. The send path awaits these so the message envelope still
    // ships a poster frame.
    private val pendingThumbnails = mutableMapOf<Uuid, Deferred<ByteArray?>>()

    private fun extractThumbnailAsync(attachmentId: Uuid, videoPath: String) {
        val deferred = viewModelScope.async {
            runCatching { VideoThumbnailExtractor.extractPosterFrame(videoPath) }.getOrNull()
        }
        pendingThumbnails[attachmentId] = deferred
        // Duration is needed by the trim screen and is cheap to read; kick it off in
        // parallel with the poster extraction.
        val durationDeferred = viewModelScope.async {
            runCatching { id.homebase.api.video.FFmpegUtils.getDurationMs(videoPath) }
                .getOrNull()
        }
        viewModelScope.launch {
            val bytes = try {
                deferred.await()
            } catch (_: CancellationException) {
                null
            }
            val durationMs = try {
                durationDeferred.await()
            } catch (_: CancellationException) {
                null
            }
            pendingThumbnails.remove(attachmentId)
            if (bytes == null && durationMs == null) return@launch
            _messagesUiState.update { state ->
                val overlay = state.fullScreenOverlay as? FullScreenOverlay.AttachmentData
                    ?: return@update state
                if (overlay.attachments.none { it.attachmentId == attachmentId }) return@update state
                val updated = overlay.attachments.map { a ->
                    if (a is AttachmentPendingFile.FileVideo && a.attachmentId == attachmentId) {
                        a.copy(
                            thumbnailBytes = bytes ?: a.thumbnailBytes,
                            durationMs = durationMs?.takeIf { it > 0 } ?: a.durationMs,
                        )
                    } else a
                }
                state.copy(fullScreenOverlay = overlay.copy(attachments = updated))
            }
        }
    }

    private suspend fun ensureThumbnail(file: AttachmentPendingFile.FileVideo): AttachmentPendingFile.FileVideo {
        if (file.thumbnailBytes != null) return file
        val pending = pendingThumbnails.remove(file.attachmentId) ?: return file
        val bytes = runCatching { pending.await() }.getOrNull()
        return if (bytes != null) file.copy(thumbnailBytes = bytes) else file
    }

    init {
        viewModelScope.launch {
            ownerSessionRepository.user.collect { session ->
                _uiState.update { it.copy(ownerSession = session) }
                _messagesUiState.update { it.copy(ownerSession = session) }
            }
        }

        // Post-create preflight collector. CreateConversationGroupViewModel emits the
        // newly-created conversation id to the bus; we run a best-effort introduction
        // preflight and, if any recipient is non-Ready, surface the
        // IntroducePreflight dialog. Best-effort — preflight failure logs and
        // returns null, so the user simply doesn't see a dialog (introductions
        // already fired silently at create time via trySendIntroductions).
        //
        // The bus is a StateFlow so a value emitted before this VM existed (mobile
        // single-pane: the create-group screen is up while this VM hasn't been
        // constructed yet) is observed the moment we start collecting. We
        // explicitly call consume(id) after handling so the same value isn't
        // re-processed on a subsequent collector restart (e.g. config change).
        //
        // Overlay is shown during the preflight call so the user gets visible
        // feedback that something is happening on the freshly-created conversation
        // — without it the brief delay before the dialog feels like the app stuck.
        viewModelScope.launch {
            postCreateIntroductionPreflightBus.pending
                .filterNotNull()
                .collect { conversationId ->
                    _uiState.update { it.copy(inFlightOperationLabel = MR.string.chat_introduce_preflight_in_progress) }
                    try {
                        val preflight = conversationService.previewIntroduceEveryone(conversationId)
                        _uiState.update { it.copy(inFlightOperationLabel = null) }
                        if (preflight != null && !preflight.allReady) {
                            val defaultMessage =
                                "${_uiState.value.ownerSession?.displayName ?: "Unknown"} has added you to group chat"
                            _uiState.update {
                                it.copy(
                                    uiDialog = ConversationListUiDialog.IntroducePreflight(
                                        conversationId = conversationId,
                                        message = defaultMessage,
                                        result = preflight,
                                    )
                                )
                            }
                        }
                    } catch (e: Exception) {
                        Logger.w(throwable = e, tag = "ConversationListViewModel") {
                            "post-create preflight failed for $conversationId: ${e.message}"
                        }
                        _uiState.update { it.copy(inFlightOperationLabel = null) }
                    } finally {
                        // Always consume — even on null/all-ready/error paths — so the
                        // same id isn't re-processed if the collector restarts.
                        postCreateIntroductionPreflightBus.consume(conversationId)
                    }
                }
        }

        viewModelScope.launch {
            val vmInitMark = TimeSource.Monotonic.markNow()
            contactService.start()
            conversationStream.start()
            connectionService.start()
            connectionRequestService.start()

            val connectionStatusFlow = combine(
                connectionService.connections,
                connectionRequestService.incomingRequests,
                connectionRequestService.outgoingRequests,
                connectionRequestService.isLoaded,
            ) { connections, incoming, outgoing, requestsLoaded ->
                ConnectionStatusContext(
                    connectionMap = connections.map,
                    incomingSenders = incoming.map { it.senderOdinId }.toSet(),
                    outgoingRecipients = outgoing.map { it.recipientOdinId }.toSet(),
                    // Only claim the status is "known" once both sources have produced
                    // at least one snapshot (either from the cache or from the network).
                    statusKnown = connections.isLoaded && requestsLoaded,
                )
            }.distinctUntilChanged()

            viewModelScope.launch {
                conversationStream.conversations.first { it.dataReady }
                Logger.i(tag = "ConvListPerf") {
                    "combineSource firstEmit=conversations(dataReady) at ${vmInitMark.elapsedNow().inWholeMilliseconds}ms from vmInit"
                }
            }
            viewModelScope.launch {
                contactService.contacts.first { it.isNotEmpty() }
                Logger.i(tag = "ConvListPerf") {
                    "combineSource firstEmit=contacts(nonEmpty) at ${vmInitMark.elapsedNow().inWholeMilliseconds}ms from vmInit"
                }
            }
            viewModelScope.launch {
                ownerSessionRepository.user.first { it != null }
                Logger.i(tag = "ConvListPerf") {
                    "combineSource firstEmit=ownerSession(nonNull) at ${vmInitMark.elapsedNow().inWholeMilliseconds}ms from vmInit"
                }
            }
            viewModelScope.launch {
                connectionStatusFlow.first { it.statusKnown }
                Logger.i(tag = "ConvListPerf") {
                    "combineSource firstEmit=connectionStatus(known) at ${vmInitMark.elapsedNow().inWholeMilliseconds}ms from vmInit"
                }
            }

            combine(
                conversationStream.conversations,
                contactService.contacts,
                ownerSessionRepository.user,
                credentialsManager.credentialsFlow,
                connectionStatusFlow,
            ) { conversationState, contacts, ownerSession, credentials, connectionCtx ->

                // Synthesize a minimal OwnerSession from the active credentials
                // when the live session hasn't loaded yet. credentialsFlow is
                // set synchronously at login/restore, so this fills the gap
                // before OwnerSessionRepository.load() has run — the enricher
                // never has to deal with a null session.
                val effectiveSession = synthesizeOwnerSession(ownerSession, credentials)
                if (effectiveSession == null) return@combine Pair(false, emptyList())

                val contactMap = contacts.associateBy { it.odinId }

                Pair(conversationState.dataReady, conversationState.items.map {
                    enricher.enrich(
                        convo = it,
                        contactMap = contactMap,
                        ownerSession = effectiveSession,
                        connectionMap = connectionCtx.connectionMap,
                        incomingRequestSenders = connectionCtx.incomingSenders,
                        outgoingRequestRecipients = connectionCtx.outgoingRecipients,
                        connectionStatusKnown = connectionCtx.statusKnown,
                    )
                })
            }.debounce(50).collect { (dataReady: Boolean, enriched: List<EnrichedConversationUiModel>) ->
                Logger.i(tag = "ConversationListViewModel") {
                    "conversationStream emit: dataReady=$dataReady enrichedCount=${enriched.size}"
                }
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

        // Once the message is durably queued in the outbox, leave the
        // "Preparing…" state — local prep is done, the network handoff is
        // the only thing left. Show the generic "Sending…" spinner until
        // the upload reports progress or completes. Don't move backwards
        // from Processing/Uploading/Completed if those somehow arrive
        // first.
        viewModelScope.launch {
            eventBus.events.filter { it is BackendEvent.OutboxEvent.ItemEnqueued }
                .collect { event ->
                    event as BackendEvent.OutboxEvent.ItemEnqueued
                    _messagesUiState.update { state ->
                        val current = state.uploadProgress[event.uniqueId]
                        if (current is UploadStatus.Preparing) {
                            state.copy(
                                uploadProgress = (state.uploadProgress + (event.uniqueId to UploadStatus.Sending)).toPersistentMap()
                            )
                        } else state
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
                        // Keep the local video thumbnail for the rest of the session so the
                        // bubble never swaps to HomebaseImage (avoids progressive load + resize).
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

        // Deferred notification-tap resolution. NotificationService sets a
        // PendingNotificationTap(conversationId, messageId) when the push
        // payload carries both ids. If the conversation hasn't synced yet,
        // conversationStream.conversations will re-emit as soon as drive
        // sync lands it — this collector picks that up and fires
        // selectConversation once. A user-initiated tap on a different
        // conversation (onAction.ConversationClicked) clears the pending
        // tap so a stale notification can't yank them away.
        //
        // Note on combine first-emit semantics: combine fires immediately on
        // collect with the current values of both upstream flows. If a tap is
        // already pending AND the conversation is already in the list when
        // CLVM is created (returning user, instant cold-load), selectConversation
        // fires during VM init. That's intentional — it's the right behavior —
        // but worth knowing when reading a stack trace.
        viewModelScope.launch {
            combine(
                pendingNotificationTap.state,
                conversationStream.conversations,
            ) { tap, convos -> tap to convos }
                .collect { (tap, convosState) ->
                    val resolved = resolveNotificationTap(
                        tap = tap,
                        conversationIds = convosState.items.map { it.id }.toSet(),
                    ) ?: return@collect
                    Logger.i(tag = "ConversationListViewModel") {
                        "pendingNotificationTap resolved convo=${resolved.conversationId} msg=${resolved.messageId}"
                    }
                    selectConversation(
                        conversationId = resolved.conversationId,
                        messageId = resolved.messageId,
                        scrollToBottom = true,
                    )
                    pendingNotificationTap.clearIfMatches(resolved.conversationId)
                }
        }

        // Fast-path: when a notification tap arrives, kick a direct DB
        // lookup for the target conversation off the Main dispatcher.
        // ConversationStream.loadConversation runs one
        // selectHomebaseFileByUnique against DriveMainIndex; if the file
        // is in the local DB (which it usually is — the background sync
        // that delivered the push wrote it), insertNewConversation /
        // updateConversation mutates _conversations.items, which re-emits
        // the StateFlow and lets the deferred resolver above fire
        // immediately. Without this kick, the resolver waits for the
        // full ConversationStream.start() enrichment pipeline (or, on a
        // warm VM, for the next sync batch) — which is what made
        // notification taps feel like they were gated on the drive-sync
        // spinner. Dispatchers.IO so the kick can't be queued behind
        // enrichAllConversationsWithUnreadCounts on Main.
        viewModelScope.launch {
            pendingNotificationTap.state.collect { tap ->
                if (tap == null) return@collect
                val convoId = tap.conversationId
                val alreadyLoaded = conversationStream.conversations.value.items
                    .any { it.id == convoId }
                if (alreadyLoaded) return@collect
                launch(Dispatchers.IO) {
                    conversationStream.loadConversation(convoId)
                }
            }
        }
    }

    override fun onCleared() {
        // PendingNotificationTap is a Koin singleton — clearing it from this per-VM
        // hook is intentional: when CLVM is destroyed (config change, navigation
        // away, process-recovery), an unresolved tap from THIS session must not
        // auto-resolve in the next CLVM instance and yank the user somewhere
        // unexpected.
        pendingNotificationTap.clear()
        super.onCleared()
    }

    fun selectConversation(
        conversationId: Uuid,
        messageId: Uuid? = null,
        scrollToBottom: Boolean = false
    ) {
        Logger.i(tag = "ConversationListViewModel") {
            "selectConversation id=$conversationId scrollToBottom=$scrollToBottom"
        }
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
                // User explicitly picked a conversation — drop any pending
                // notification tap so a late-arriving sync can't yank them
                // to a different one.
                pendingNotificationTap.clear()
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
                // User-initiated attachments (location, contact, etc.) enable send even with no
                // text. Link previews don't — they're auto-detected from typed URLs and only
                // ride along when there's a text message to send.
                val hasUserInitiatedAttachment = action.payloadRenderers.any {
                    it !is id.homebase.chat.services.renderer.LinkPreviewRenderer
                }
                if (hasMessage || hasUserInitiatedAttachment) {
                    _messagesUiState.update { it.copy(isSendingMessage = true) }
                    val content = messageInputTextState.toMarkdown().trimEnd()
                    val replyTo = _messagesUiState.value.replyToMessage
                    if (replyTo != null) {
                        replyToMessage(
                            conversationId = action.conversationId,
                            replyTo = replyTo,
                            content = content,
                            payloadRenderers = action.payloadRenderers,
                        )
                    } else {
                        addMessage(
                            conversationId = action.conversationId,
                            content = content,
                            payloadRenderers = action.payloadRenderers,
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

            is ConversationListUiAction.ClearScrollTrigger -> {
                _messagesUiState.update {
                    it.copy(
                        scrollPosition = it.scrollPosition?.copy(triggerScroll = false)
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
                        val hlsMetadata = resolveHlsVideoMetadata(
                            descriptorContent = action.payload.descriptorContent,
                            fileId = action.fileId,
                            keyHeader = action.keyHeader,
                        )

                        if (hlsMetadata != null) {
                            val (mp4Path, mp4Name) = withContext(Dispatchers.IO) {
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
                            chatMessageActionService.markAllAsRead(action.conversationId)
                        } else {
                            chatMessageActionService.markAsReadByFiles(
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
                    if (action.reaction.isEmpty()) return@launch
                    val previousReactions = _messagesUiState.value.messageReactions
                    try {
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

                        _messagesUiState.update { state ->
                            if (state.reactionDetailsMessageId != action.messageId) {
                                return@update state
                            }
                            val ownerSession = state.ownerSession
                                ?: return@update state
                            val ownerOdinId = ownerSession.odinId.domainName
                            val current = state.messageReactions ?: emptyList()
                            val hasReaction = current.any {
                                it.odinId == ownerOdinId && it.emoji == action.reaction
                            }
                            val updated = if (hasReaction) {
                                current.filterNot {
                                    it.odinId == ownerOdinId && it.emoji == action.reaction
                                }
                            } else {
                                current + ReactionDisplayItem(
                                    odinId = ownerOdinId,
                                    displayName = ownerSession.displayName ?: ownerOdinId,
                                    emoji = action.reaction,
                                )
                            }
                            if (updated.isEmpty()) {
                                state.copy(
                                    messageReactions = null,
                                    reactionDetailsMessageId = null,
                                )
                            } else {
                                state.copy(messageReactions = updated)
                            }
                        }

                        chatMessageActionService.toggleReaction(
                            action.conversationId,
                            action.messageId,
                            action.reaction
                        )
                    } catch (e: Exception) {
                        _messagesUiState.update { it.copy(messageReactions = previousReactions) }
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
                                ct.startsWith("video/") -> AttachmentPendingFile.FileVideo(
                                    Uuid.generateV7(),
                                    it,
                                    thumbnailBytes = null,
                                )

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
                                conversationTitle = conversation.getDisplayName(),
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

                        // Editor is now visible — extract thumbnails in the background and
                        // patch the pending FileVideo entries when they complete.
                        newFiles.forEach { f ->
                            if (f is AttachmentPendingFile.FileVideo) {
                                extractThumbnailAsync(f.attachmentId, f.file.toString())
                            }
                        }
                    } catch (e: Exception) {
                        Logger.e("Failed to attach file(s)", e)
                        sendEvent(
                            ShowErrorMessage(
                                TranslationUtil.getString(
                                    MR.string.chat_attach_file_failed,
                                    e.message ?: ""
                                )
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
                                AttachmentPendingFile.FileVideo(
                                    Uuid.generateV7(),
                                    it.file,
                                    thumbnailBytes = null,
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
                                conversationTitle = conversation.getDisplayName(),
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

                        // Editor visible — kick off thumbnail extraction in parallel, using
                        // the gallery URI directly (no resolveToFilePath copy).
                        newFiles.zip(action.files).forEach { (pending, gallery) ->
                            if (pending is AttachmentPendingFile.FileVideo) {
                                extractThumbnailAsync(pending.attachmentId, gallery.file.toString())
                            }
                        }
                    } catch (e: Exception) {
                        Logger.e("Failed to attach file(s)", e)
                        sendEvent(
                            ShowErrorMessage(
                                TranslationUtil.getString(
                                    MR.string.chat_attach_file_failed,
                                    e.message ?: ""
                                )
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
                                val localContext = localVideoContextStore.get(action.message.id, selectedPayload.key)
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
                _messagesUiState.update { it.copy(isSendingMessage = true) }
                viewModelScope.launch {
                    try {
                        val conversationIds = action.recipients.map { recipientModel ->
                            when (recipientModel) {
                                is RecipientModel.Contact -> {
                                    conversationService.createConversation(
                                        recipients = listOf(recipientModel.contact.odinId),
                                        title = "",
                                        payloadBundle = null,
                                    ).conversationId
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
                    } finally {
                        _messagesUiState.update { it.copy(isSendingMessage = false) }
                    }
                }
            }

            is ConversationListUiAction.CancelReplyToMessage -> {
                _messagesUiState.update { it.copy(replyToMessage = null) }
            }

            is ConversationListUiAction.ShowReactionDetails -> {
                _messagesUiState.update { it.copy(reactionDetailsMessageId = action.messageId) }
                loadReactionDetails(action.messageId)
            }

            is ConversationListUiAction.HideReactionDetails -> {
                _messagesUiState.update { it.copy(messageReactions = null, isReactionsLoading = false, reactionDetailsMessageId = null) }
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

            is ConversationListUiAction.AutoConnect -> {
                autoConnect(action.odinId)
            }

            is ConversationListUiAction.OpenConnectionRequestInOwnerConsole -> {
                uiState.value.ownerSession?.odinId?.let { currentUser ->
                    val url =
                        "https://${currentUser.domainName}/owner/connections/${action.odinId.domainName}"
                    _uiState.update { it.copy(uiEvent = ConversationListUiEvent.OpenUrl(url)) }
                }
            }

            is ConversationListUiAction.OpenSendConnectionRequestDialog -> {
                _uiState.update {
                    it.copy(
                        uiEvent = ConversationListUiEvent.OpenSendConnectionRequestDialog(
                            action.odinId
                        )
                    )
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

            is ConversationListUiAction.IntroduceSendAnyway -> {
                viewModelScope.launch {
                    _uiState.update { it.copy(uiDialog = null) }
                    conversationService.introduceEveryone(action.conversationId, action.message)
                    sendEvent(ShowInfoMessage(MR.string.chat_group_introduce_everyone_status))
                }
            }

            is ConversationListUiAction.IntroduceSendReadyOnly -> {
                viewModelScope.launch {
                    _uiState.update { it.copy(uiDialog = null) }
                    if (action.readyRecipients.isEmpty()) {
                        // No-op — the dialog should not have allowed this, but be defensive.
                        sendEvent(ShowErrorMessage("No ready recipients to send to."))
                        return@launch
                    }
                    conversationService.introduceRecipients(
                        conversationId = action.conversationId,
                        recipients = action.readyRecipients,
                        message = action.message,
                    )
                    sendEvent(ShowInfoMessage(MR.string.chat_group_introduce_everyone_status))
                }
            }

            is ConversationListUiAction.IntroduceCancel -> {
                _uiState.update { it.copy(uiDialog = null) }
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
                _uiState.update { it.copy(showArchived = true) }
            }

            is ConversationListUiAction.ArchiveBackClicked -> {
                _uiState.update { it.copy(showArchived = false) }
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
                    runDeleteConversationFlow(
                        conversationId = action.conversationId,
                        leaveFirst = false,
                        overlayLabel = MR.string.chat_conversation_deleting_in_progress,
                    )
                }
            }

            is ConversationListUiAction.ConfirmLeaveAndDeleteConversation -> {
                viewModelScope.launch {
                    runDeleteConversationFlow(
                        conversationId = action.conversationId,
                        leaveFirst = true,
                        overlayLabel = MR.string.chat_conversation_leaving_and_deleting_in_progress,
                    )
                }
            }

            is ConversationListUiAction.CloseDetailPaneRequestConsumed -> {
                _uiState.update { it.copy(closeDetailPaneRequest = null) }
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
                            ".png"
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
                                conversationTitle = conversation.getDisplayName(),
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

            /* Crop attachment */
            is ConversationListUiAction.RequestCropAttachment -> {
                viewModelScope.launch {
                    try {
                        val overlay = _messagesUiState.value.fullScreenOverlay as? FullScreenOverlay.AttachmentData
                        val attachment = overlay?.attachments?.firstOrNull {
                            it.attachmentId == action.attachmentId
                        }
                        val sourcePath = when (attachment) {
                            is AttachmentPendingFile.FileImage -> attachment.file.toString()
                            is AttachmentPendingFile.Gallery -> attachment.image.file.toString()
                            else -> null
                        }
                        if (sourcePath == null) {
                            sendEvent(ShowErrorMessage("Cannot crop this attachment"))
                            return@launch
                        }
                        val bytes = fileOperationsProvider.readFileBytes(sourcePath)
                        val requestId = Uuid.random()
                        cropResultBus.postSource(requestId, bytes)

                        viewModelScope.launch {
                            cropResultBus.resultsFor(requestId).collect { result ->
                                onAction(
                                    ConversationListUiAction.ApplyCropResult(
                                        action.conversationId,
                                        action.attachmentId,
                                        result.bytes,
                                    )
                                )
                            }
                        }

                        sendEvent(ConversationListUiEvent.NavigateToCropper(requestId))
                    } catch (e: Exception) {
                        Logger.e("RequestCropAttachment failed", e)
                        sendEvent(ShowErrorMessage("Failed to open cropper: ${e.message}"))
                    }
                }
            }

            is ConversationListUiAction.ApplyCropResult -> {
                viewModelScope.launch {
                    try {
                        val tempPath = fileOperationsProvider.writeBytesToTempFile(
                            action.croppedBytes,
                            "cropped_image",
                            ".jpg",
                        )
                        val newFile = AttachmentPendingFile.FileImage(
                            id = action.attachmentId,
                            file = id.homebase.core.clipboard.platformFileFromPath(tempPath),
                        )
                        val overlay = _messagesUiState.value.fullScreenOverlay
                        if (overlay !is FullScreenOverlay.AttachmentData) return@launch
                        val newAttachments = overlay.attachments.map { existing ->
                            if (existing.attachmentId == action.attachmentId) newFile else existing
                        }
                        _messagesUiState.update {
                            it.copy(fullScreenOverlay = overlay.copy(attachments = newAttachments))
                        }
                    } catch (e: Exception) {
                        Logger.e("ApplyCropResult failed", e)
                        sendEvent(ShowErrorMessage("Failed to apply crop: ${e.message}"))
                    }
                }
            }

            /* Draw on attachment — same shape as crop, different result bus. */
            is ConversationListUiAction.RequestDrawAttachment -> {
                viewModelScope.launch {
                    try {
                        val overlay = _messagesUiState.value.fullScreenOverlay as? FullScreenOverlay.AttachmentData
                        val attachment = overlay?.attachments?.firstOrNull {
                            it.attachmentId == action.attachmentId
                        }
                        val sourcePath = when (attachment) {
                            is AttachmentPendingFile.FileImage -> attachment.file.toString()
                            is AttachmentPendingFile.Gallery -> attachment.image.file.toString()
                            else -> null
                        }
                        if (sourcePath == null) {
                            sendEvent(ShowErrorMessage("Cannot draw on this attachment"))
                            return@launch
                        }
                        val bytes = fileOperationsProvider.readFileBytes(sourcePath)
                        val requestId = Uuid.random()
                        drawResultBus.postSource(requestId, bytes)

                        viewModelScope.launch {
                            drawResultBus.resultsFor(requestId).collect { result ->
                                onAction(
                                    ConversationListUiAction.ApplyDrawResult(
                                        action.conversationId,
                                        action.attachmentId,
                                        result.bytes,
                                    )
                                )
                            }
                        }

                        sendEvent(ConversationListUiEvent.NavigateToDrawer(requestId))
                    } catch (e: Exception) {
                        Logger.e("RequestDrawAttachment failed", e)
                        sendEvent(ShowErrorMessage("Failed to open draw editor: ${e.message}"))
                    }
                }
            }

            is ConversationListUiAction.ApplyDrawResult -> {
                viewModelScope.launch {
                    try {
                        val tempPath = fileOperationsProvider.writeBytesToTempFile(
                            action.paintedBytes,
                            "painted_image",
                            ".jpg",
                        )
                        val newFile = AttachmentPendingFile.FileImage(
                            id = action.attachmentId,
                            file = id.homebase.core.clipboard.platformFileFromPath(tempPath),
                        )
                        val overlay = _messagesUiState.value.fullScreenOverlay
                        if (overlay !is FullScreenOverlay.AttachmentData) return@launch
                        val newAttachments = overlay.attachments.map { existing ->
                            if (existing.attachmentId == action.attachmentId) newFile else existing
                        }
                        _messagesUiState.update {
                            it.copy(fullScreenOverlay = overlay.copy(attachments = newAttachments))
                        }
                    } catch (e: Exception) {
                        Logger.e("ApplyDrawResult failed", e)
                        sendEvent(ShowErrorMessage("Failed to apply drawing: ${e.message}"))
                    }
                }
            }

            /* Inline trim scrubber result. */
            is ConversationListUiAction.ApplyTrimResult -> {
                val overlay = _messagesUiState.value.fullScreenOverlay
                if (overlay !is FullScreenOverlay.AttachmentData) return
                val newAttachments = overlay.attachments.map { existing ->
                    if (existing.attachmentId == action.attachmentId &&
                        existing is AttachmentPendingFile.FileVideo
                    ) {
                        existing.copy(
                            trimStartMs = action.trimStartMs,
                            trimEndMs = action.trimEndMs,
                        )
                    } else existing
                }
                _messagesUiState.update {
                    it.copy(fullScreenOverlay = overlay.copy(attachments = newAttachments))
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
            // Show the in-flight overlay during preflight — without this the user
            // sees nothing for ~hundreds of ms and the app feels stuck.
            _uiState.update { it.copy(inFlightOperationLabel = MR.string.chat_introduce_preflight_in_progress) }
            // Best-effort preflight: if every recipient is Ready, proceed silently
            // as before; if any recipient is non-Ready, surface a dialog so the
            // user can choose Send anyway / Skip and send to the rest / Cancel.
            // If preflight itself fails (returns null), fall through to the
            // existing send-everyone path — preflight is advisory, not enforcing.
            val preflight = conversationService.previewIntroduceEveryone(conversationId)
            // Always clear the overlay before doing anything follow-up; the dialog
            // (if shown) draws on top of the conversation view, not the overlay.
            _uiState.update { it.copy(inFlightOperationLabel = null) }
            if (preflight == null || preflight.allReady) {
                conversationService.introduceEveryone(conversationId, defaultMessage)
                sendEvent(ShowInfoMessage(MR.string.chat_group_introduce_everyone_status))
                return@launch
            }
            // Some recipients are not ready; let the user decide.
            _uiState.update {
                it.copy(
                    uiDialog = ConversationListUiDialog.IntroducePreflight(
                        conversationId = conversationId,
                        message = defaultMessage,
                        result = preflight,
                    )
                )
            }
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
                        conversation.getDisplayName().contains(searchQuery, ignoreCase = true)
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
        _messagesUiState.update { it.copy(isReactionsLoading = true, messageReactions = emptyList()) }
        viewModelScope.launch {
            try {
                val rawReactions = chatMessageActionService.getReactions(messageId)
                val reactions = rawReactions.map { reaction ->
                    val displayName = contactService.resolveByOdinId(reaction.odinId)?.name
                        ?: reaction.odinId.domainName
                    ReactionDisplayItem(
                        odinId = reaction.odinId.domainName,
                        displayName = displayName,
                        emoji = reaction.emoji,
                    )
                }
                _messagesUiState.update {
                    it.copy(messageReactions = reactions, isReactionsLoading = false)
                }
            } catch (_: Exception) {
                _messagesUiState.update { it.copy(isReactionsLoading = false, messageReactions = null) }
            }
        }
    }

    private fun loadMessagesForConversation(
        conversationId: Uuid,
        messageIdForScroll: Uuid?,
        scrollToBottom: Boolean = false
    ) {
        val loadStart = TimeSource.Monotonic.markNow()

        val hasCachedMessages = chatMessageStream.hasCachedMessages(conversationId)
        Logger.i(tag = "ConversationListViewModel") {
            "loadMessagesForConversation id=$conversationId hasCached=$hasCachedMessages"
        }

        _messagesUiState.update {
            it.copy(
                scrollPosition = null,
                isLoadingMessages = !hasCachedMessages,
                replyToMessage = null,
            )
        }

        // Flip the selected id NOW, not after messages arrive. The scaffold's
        // detail-pane navigation in NotificationNavigationEffects keys off this
        // value via LaunchedEffect(selectedConversationId); waiting for the first
        // ChatMessagesData.Messages emission held the navigation hostage to a
        // potentially slow DB read on cold-start / post-reconnect. The detail pane
        // already shows isLoadingMessages = true above; messages will fill in via
        // the collect block below.
        Logger.i(tag = "ConversationListViewModel") {
            "selectedConversationId set id=$conversationId (pending messages)"
        }
        _uiState.update { it.copy(selectedConversationId = conversationId) }

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
                                val sectionHeader = listOf(MessageListContentModel.Section(date))
                                val items = messages.map { msg ->
                                    if (msg.isStatusMessage)
                                        MessageListContentModel.System(
                                            msg.content,
                                            msg.userDate,
                                            systemIndex++
                                        )
                                    else
                                        MessageListContentModel.Message(msg)
                                }
                                val messageItems = items.filterIsInstance<MessageListContentModel.Message>()
                                val clustered = computeClusterPositions(messageItems)
                                val clusteredMap = clustered.associateBy { it.message.id }
                                sectionHeader + items.map { item ->
                                    if (item is MessageListContentModel.Message)
                                        clusteredMap[item.message.id] ?: item
                                    else
                                        item
                                }
                            })

                            // Scroll handling: navigate to a specific message (search results,
                            // cross-conversation jumps, etc.). The user's own just-sent messages
                            // are handled by the LazyColumn auto-follow effect in ConversationContent.kt,
                            // which only scrolls when the user was already at the bottom. Forcing
                            // a scroll-to-new-message here would yank the user out of history.
                            pendingMessageId = null
                            // If the target message hasn't synced yet, keep
                            // messageIdForScrollNullable set so the next
                            // ChatMessagesData.Messages emission retries the
                            // lookup (messages stream re-emits on each sync
                            // batch). Clear only once the message is found.
                            val indexOfMessageForScroll = if (messageIdForScrollNullable != null) {
                                val messageIndex = messagesModels.indexOfLast {
                                    it is MessageListContentModel.Message && it.message.id == messageIdForScrollNullable
                                }
                                if (messageIndex >= 0) {
                                    messageIdForScrollNullable = null
                                    messageIndex
                                } else {
                                    null
                                }
                            } else {
                                null
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

                            if (setInitialScroll) {
                                // Crisp proof that the detail pane is no longer
                                // gated on messages: this is the gap between the
                                // synchronous selectedConversationId flip and the
                                // first messages payload landing in the UI. If
                                // it's long, navigation already completed (tap →
                                // detailPane render) without waiting for it.
                                Logger.i(tag = "ConversationListViewModel") {
                                    "messages first emission id=$conversationId messageCount=${messages.size} sinceSelected=${loadStart.elapsedNow()}"
                                }
                            }

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

    /**
     * Combined delete (and optional leave-first) flow. Drives the in-flight overlay
     * via [ConversationListUiState.inFlightOperationLabel], runs the service ops,
     * then reconciles UI state (drops the row from the in-memory list, pops the
     * detail pane if the deleted conversation was open, fires the snackbar).
     *
     * @param leaveFirst when true, calls [ConversationService.leaveGroup] before
     *                   the delete. Required when the user is still an active
     *                   member of a group conversation; the service-side delete
     *                   guard otherwise rejects with IllegalStateException.
     */
    private suspend fun runDeleteConversationFlow(
        conversationId: Uuid,
        leaveFirst: Boolean,
        overlayLabel: org.jetbrains.compose.resources.StringResource,
    ) {
        _uiState.update { it.copy(inFlightOperationLabel = overlayLabel) }
        try {
            if (leaveFirst) {
                // Mirror GroupSettingsViewModel.LeaveGroupConfirm logic so a sole-admin
                // with no reachable non-admin still goes through the local-only branch.
                val enriched = uiState.value.activeConversations
                    .find { it.conversation.id == conversationId }
                val conversation = enriched?.conversation
                val currentUser = credentialsManager.requireActiveCredentials().domain
                val isSoleAdmin = conversation != null
                        && conversation.isCurrentUserAdmin(currentUser)
                        && conversation.admins.size == 1
                val hasReachableNonAdmin = enriched != null && enriched.participants.any {
                    it.connectionState ==
                            id.homebase.chat.services.convo.contact.ContactConnectionState.Connected
                            && conversation?.isCurrentUserAdmin(it.odinId) == false
                }
                val forceLocalOnly = isSoleAdmin && !hasReachableNonAdmin
                conversationService.leaveGroup(
                    conversationId = conversationId,
                    forceLocalOnly = forceLocalOnly,
                )
            }
            conversationService.deleteConversation(conversationId)

            // Drop the row from the in-memory list immediately and tell the stream
            // to keep it filtered across reloads — see ConversationStream.deletedIds.
            conversationStream.onConversationDeleted(conversationId)

            val close = uiState.value.selectedConversationId == conversationId
            _uiState.update {
                it.copy(
                    inFlightOperationLabel = null,
                    selectedConversationId = if (close) null else it.selectedConversationId,
                    closeDetailPaneRequest = if (close) conversationId else it.closeDetailPaneRequest,
                )
            }
            if (close) {
                // ClearSelection also resets messages and stops the per-convo job.
                onAction(ConversationListUiAction.ClearSelection)
            }
            sendEvent(ShowInfoMessage(MR.string.chat_conversation_deleted_confirmation))
        } catch (e: Exception) {
            Logger.e(throwable = e, tag = "ConversationListViewModel") {
                "Failed to delete conversation (leaveFirst=$leaveFirst): ${e.message}"
            }
            _uiState.update { it.copy(inFlightOperationLabel = null) }
            sendEvent(ShowErrorMessage("Failed to delete conversation: ${e.message}"))
        }
    }


    private fun autoConnect(recipient: OdinId) {
        // Only operate while the Connect-identities sheet is open; otherwise there is no
        // row UI to reflect the state change against.
        val openSheet = _messagesUiState.value.uiSheet as? MessageListUiSheet.ConnectIdentities
            ?: return
        if (openSheet.autoConnectStates[recipient] == AutoConnectRowState.Connecting) return

        updateConnectSheetRow(recipient, AutoConnectRowState.Connecting)

        viewModelScope.launch {
            val header = ConnectionRequestHeader(
                id = Uuid.random(),
                recipient = recipient,
                message = null,
                circleIds = null,
                introducerOdinId = null,
                connectionRequestOrigin = null,
            )
            try {
                val result = connectionRequestService.autoConnect(header)
                val succeeded = when (result.outcome) {
                    AutoConnectOutcome.Connected,
                    AutoConnectOutcome.AcceptedFromExistingIncoming,
                    AutoConnectOutcome.AlreadyConnected -> true
                    else -> false
                }
                if (succeeded) {
                    updateConnectSheetRow(recipient, AutoConnectRowState.Succeeded)
                } else {
                    updateConnectSheetRow(recipient, failedOutcomeRowState(result, recipient))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ClientException) {
                Logger.w(e) { "autoConnect($recipient) ClientException code=${e.errorCode}" }
                updateConnectSheetRow(recipient, clientExceptionRowState(e, recipient))
            } catch (e: Exception) {
                Logger.e(e) { "autoConnect($recipient) failed: ${e::class.simpleName}: ${e.message}" }
                updateConnectSheetRow(
                    recipient,
                    AutoConnectRowState.Failed(
                        MR.string.auto_connect_recipient_unreachable,
                        listOf(recipient.domainName),
                    ),
                )
            }
        }
    }

    private fun updateConnectSheetRow(recipient: OdinId, newState: AutoConnectRowState?) {
        _messagesUiState.update { state ->
            val sheet = state.uiSheet as? MessageListUiSheet.ConnectIdentities
                ?: return@update state
            val next = if (newState == null) {
                sheet.autoConnectStates - recipient
            } else {
                sheet.autoConnectStates + (recipient to newState)
            }
            state.copy(uiSheet = sheet.copy(autoConnectStates = next))
        }
    }

    /** Translate a server-side 400 ClientException into the same row state we'd show for the
     *  corresponding in-band AutoConnectOutcome. Keeps the UX consistent whether the server
     *  returns a typed outcome or bubbles the legacy error-code path. */
    private fun clientExceptionRowState(
        e: ClientException,
        recipient: OdinId,
    ): AutoConnectRowState.Failed {
        val who = recipient.domainName
        return when (e.errorCode) {
            OdinClientErrorCode.ConnectionRequestAlreadySent ->
                AutoConnectRowState.Failed(
                    MR.string.auto_connect_outgoing_request_exists,
                    listOf(who),
                )
            OdinClientErrorCode.BlockedConnection ->
                AutoConnectRowState.Failed(MR.string.auto_connect_blocked, listOf(who))
            OdinClientErrorCode.CannotSendConnectionRequestToValidConnection ->
                AutoConnectRowState.Failed(MR.string.auto_connect_failed_generic)
            OdinClientErrorCode.ConnectionRequestToYourself ->
                AutoConnectRowState.Failed(MR.string.auto_connect_invalid_request)
            else -> AutoConnectRowState.Failed(
                MR.string.auto_connect_invalid_request_with_detail,
                listOf(e.message ?: "Failed"),
            )
        }
    }

    private fun failedOutcomeRowState(
        result: ConnectionRequestResult,
        recipient: OdinId,
    ): AutoConnectRowState.Failed {
        val who = recipient.domainName
        return when (result.outcome) {
            AutoConnectOutcome.PendingManualApproval ->
                AutoConnectRowState.Failed(MR.string.auto_connect_pending_manual_approval, listOf(who))
            AutoConnectOutcome.Blocked ->
                AutoConnectRowState.Failed(MR.string.auto_connect_blocked, listOf(who))
            AutoConnectOutcome.OutgoingRequestAlreadyExists ->
                AutoConnectRowState.Failed(MR.string.auto_connect_outgoing_request_exists, listOf(who))
            AutoConnectOutcome.DuplicateIntroductoryRequest ->
                AutoConnectRowState.Failed(MR.string.auto_connect_duplicate_introductory_request, listOf(who))
            AutoConnectOutcome.RecipientUnreachable ->
                AutoConnectRowState.Failed(MR.string.auto_connect_recipient_unreachable, listOf(who))
            AutoConnectOutcome.RecipientRejected ->
                AutoConnectRowState.Failed(MR.string.auto_connect_recipient_rejected, listOf(who))
            AutoConnectOutcome.RecipientIdentityNotConfigured ->
                AutoConnectRowState.Failed(MR.string.auto_connect_recipient_not_configured, listOf(who))
            AutoConnectOutcome.RecipientRequiresUpgrade ->
                AutoConnectRowState.Failed(MR.string.auto_connect_recipient_requires_upgrade, listOf(who))
            AutoConnectOutcome.InvalidRequest ->
                result.detail?.let {
                    AutoConnectRowState.Failed(
                        MR.string.auto_connect_invalid_request_with_detail,
                        listOf(it),
                    )
                } ?: AutoConnectRowState.Failed(MR.string.auto_connect_invalid_request)
            AutoConnectOutcome.Failed,
            AutoConnectOutcome.Unknown ->
                AutoConnectRowState.Failed(MR.string.auto_connect_failed_generic)
            // Success outcomes never route here, but keep `when` exhaustive.
            AutoConnectOutcome.Connected,
            AutoConnectOutcome.AcceptedFromExistingIncoming,
            AutoConnectOutcome.AlreadyConnected ->
                AutoConnectRowState.Failed(MR.string.auto_connect_failed_generic)
        }
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
        payloadRenderers: List<PayloadRenderer> = emptyList(),
    ) {
        viewModelScope.launch {
            try {
                val payloadBundle = payloadRenderers.toCombinedPayloadBundle(fileOperationsProvider)

                val newMessageId = Uuid.random()
                pendingMessageId = newMessageId
                Logger.d(tag = TAG) { "addMessage: message=$newMessageId conversation=$conversationId" }

                chatMessageSenderService.sendNewMessage(
                    messageUniqueId = newMessageId,
                    conversationId = conversationId,
                    messageText = content,
                    previousMessageUniqueId = null,
                    payloadBundle = payloadBundle,
                    dataType = payloadRenderers.toMessageDataType(),
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
        payloadRenderers: List<PayloadRenderer> = emptyList(),
    ) {
        viewModelScope.launch {
            try {
                val payloadBundle = payloadRenderers.toCombinedPayloadBundle(fileOperationsProvider)

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
                    payloadBundle = payloadBundle,
                    dataType = payloadRenderers.toMessageDataType(),
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
        val playlistPath = FFmpegUtils.cacheInputVideo(
            fileName = "hlsdl_${uid}.m3u8",
            data = rewrittenPlaylist.encodeToByteArray(),
        )

        val ok = FFmpegUtils.remuxHlsToMp4(playlistPath = playlistPath, outputPath = mp4Path)

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

    private fun addMessageWithFiles(
        conversationId: Uuid,
        content: String,
        files: List<AttachmentPendingFile>
    ) {
        val sentAt = UnixTimeUtc.now()
        viewModelScope.launch {
            // If any FileVideo entries still have a thumbnail extraction in flight (the
            // user hit Send before the background poster task finished), wait on it once
            // so the message envelope ships with a poster frame.
            val resolvedFiles = files.map { f ->
                if (f is AttachmentPendingFile.FileVideo) ensureThumbnail(f) else f
            }
            val attachments = mutableListOf<AttachmentInput>()
            resolvedFiles.forEach { attachment ->
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
                                trimStartMs = attachment.trimStartMs,
                                trimEndMs = attachment.trimEndMs,
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

            // Store a local preview context per attachment (keyed by the payload key the
            // MessageAttachmentBuilder will emit), so both the placeholder and the eventual
            // real bubble can render the local preview without fetching from the server.
            // Populate local contexts synchronously with what we have on hand, so the
            // placeholder shows immediately. For videos the thumbnail bytes give us the
            // aspect for free; for images we compute aspect asynchronously below and
            // re-put once we have it — avoids blocking the placeholder on image I/O.
            val imagePathsToRefine = mutableListOf<Pair<String, String>>()
            resolvedFiles.forEachIndexed { index, file ->
                val payloadKey = "${ChatProtocol.PAYLOAD_KEY_MESSAGE_WEB}$index"
                val ctx: LocalAttachmentContext? = when (file) {
                    is AttachmentPendingFile.FileVideo -> {
                        val bytes = file.thumbnailBytes
                        if (bytes != null) {
                            val aspect = runCatching {
                                val size = ImageUtils.getNaturalSize(bytes)
                                if (size.pixelWidth > 0 && size.pixelHeight > 0)
                                    size.pixelWidth.toFloat() / size.pixelHeight.toFloat()
                                else null
                            }.getOrNull()
                            LocalAttachmentContext.Video(
                                thumbnailBytes = bytes,
                                localFilePath = file.file.toString(),
                                aspectRatio = aspect,
                                trimStartMs = file.trimStartMs,
                                trimEndMs = file.trimEndMs,
                                durationMs = file.durationMs,
                            )
                        } else null
                    }
                    is AttachmentPendingFile.FileImage -> {
                        val path = file.file.toString()
                        imagePathsToRefine += payloadKey to path
                        LocalAttachmentContext.Image(localFilePath = path, aspectRatio = null)
                    }
                    is AttachmentPendingFile.Gallery -> {
                        val path = file.image.file.toString()
                        imagePathsToRefine += payloadKey to path
                        LocalAttachmentContext.Image(localFilePath = path, aspectRatio = null)
                    }
                    is AttachmentPendingFile.File -> null
                    is AttachmentPendingFile.Audio -> null
                }
                if (ctx != null) {
                    localVideoContextStore.put(newMessageId, payloadKey, ctx)
                }
            }

            // Refine image aspect ratios off the main path. Try a header-only parser
            // first so we don't allocate the full image bytes just to read width/height;
            // fall back to the full-bytes decoder for formats we can't sniff (e.g. HEIC).
            if (imagePathsToRefine.isNotEmpty()) {
                viewModelScope.launch {
                    imagePathsToRefine.forEach { (payloadKey, path) ->
                        val aspect = runCatching {
                            val header = fileOperationsProvider.readFileHeaderBytes(path)
                            val size = ImageHeaderParser.parse(header)
                                ?: ImageUtils.getNaturalSize(fileOperationsProvider.readFileBytes(path))
                            if (size.pixelWidth > 0 && size.pixelHeight > 0)
                                size.pixelWidth.toFloat() / size.pixelHeight.toFloat()
                            else null
                        }.getOrNull()
                        if (aspect != null) {
                            localVideoContextStore.put(
                                newMessageId,
                                payloadKey,
                                LocalAttachmentContext.Image(localFilePath = path, aspectRatio = aspect),
                            )
                        }
                    }
                }
            }

            pendingMessageId = newMessageId

            val placeholder = PendingOutgoingMessage(
                id = newMessageId,
                conversationId = conversationId,
                text = content,
                attachmentCount = files.size,
                sentAt = kotlin.time.Instant.fromEpochMilliseconds(sentAt.milliseconds),
            )

            // Register the placeholder, register upload progress, clear the
            // composer, and close the overlay BEFORE the heavy work so the
            // user sees a "Preparing…" bubble in the chat immediately.
            _messagesUiState.update { state ->
                state.copy(
                    uploadProgress = (state.uploadProgress + (newMessageId to UploadStatus.Preparing)).toPersistentMap(),
                    pendingOutgoing = (state.pendingOutgoing + placeholder).toPersistentList(),
                    fullScreenOverlay = null,
                    isSendingMessage = false,
                )
            }
            messageInputTextState.clear()

            viewModelScope.launch {
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
                        userDate = sentAt,
                    )
                    // Real optimistic bubble has landed — drop the placeholder.
                    _messagesUiState.update { state ->
                        state.copy(
                            pendingOutgoing = state.pendingOutgoing
                                .filterNot { it.id == newMessageId }
                                .toPersistentList(),
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
                            pendingOutgoing = state.pendingOutgoing
                                .filterNot { it.id == newMessageId }
                                .toPersistentList(),
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

/**
 * Chooses the [OwnerSession] passed to [ConversationEnricher]: prefer the
 * fully-resolved [live] session, fall back to a minimal one synthesized
 * from [credentials] when the async profile load hasn't arrived yet.
 *
 * Returns null only when neither source is available (pre-login state).
 * The preference order is load-bearing — inverting it would replace a
 * resolved display name / profile image with a bare odinId.
 */
internal fun synthesizeOwnerSession(
    live: OwnerSession?,
    credentials: ApiCredentials?,
): OwnerSession? = live ?: credentials?.let {
    OwnerSession(
        odinId = it.domain,
        displayName = null,
        firstName = null,
        surName = null,
        profileImageFileId = null,
        profileImageFileKey = null,
        profileImagePreviewThumbnail = null,
        profileImageLastModified = null,
        status = null,
    )
}

/**
 * Decides whether a pending notification tap is ready to be resolved
 * against the current conversation list snapshot. Returns the tap
 * when the snapshot already contains the tap's conversation id —
 * caller then routes to the conversation + message.
 *
 * No `dataReady` gate: a fast-path collector in the VM force-loads
 * the tap's conversation directly from the local DB the moment a tap
 * is set, so by the time the conversation appears in `items` we know
 * it's locally available and safe to navigate to — even if
 * `ConversationStream.start()` hasn't finished its full enrichment
 * pipeline. The deferred fallback (sync delivers the conversation
 * later) keeps working because `processConversationBatchIncrementally`
 * also mutates `items`, which re-emits the StateFlow.
 *
 * Pure function so unit tests can exercise the resolution policy
 * without spinning up a VM.
 */
internal fun resolveNotificationTap(
    tap: PendingNotificationTap.Tap?,
    conversationIds: Set<Uuid>,
): PendingNotificationTap.Tap? {
    if (tap == null) return null
    return tap.takeIf { conversationIds.contains(it.conversationId) }
}

