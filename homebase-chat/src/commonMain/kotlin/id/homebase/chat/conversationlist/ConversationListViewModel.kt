package id.homebase.chat.conversationlist

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.mohamedrejeb.richeditor.model.RichTextState
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.common.OdinId
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.auth.OwnerSession
import id.homebase.api.client.auth.OwnerSessionRepository
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.coroutines.ioDispatcher
import id.homebase.core.emoji.EmojiNormalization.distinctByEmoji
import id.homebase.api.file.FileOperationsProvider
import id.homebase.chat.conversationlist.ConversationListUiEvent.NavigateBack
import id.homebase.chat.conversationlist.ConversationListUiEvent.NavigateToNewConversation
import id.homebase.chat.conversationlist.ConversationListUiEvent.ShowErrorMessage
import id.homebase.chat.conversationlist.ConversationListUiEvent.ShowInfoMessage
import id.homebase.chat.data.ConversationState
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.ChatMessageActionService
import id.homebase.chat.services.ChatMessageSenderService
import id.homebase.chat.services.ChatMessageStream
import id.homebase.chat.services.ChatMessagesData
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.LocalAttachmentContextStore
import id.homebase.chat.services.content.MessageContent
import id.homebase.chat.services.content.MessageContentParser
import id.homebase.chat.services.livelocation.ShareBackResult
import id.homebase.chat.services.livelocation.liveShareAnyUntilMs
import id.homebase.chat.services.livelocation.liveShareCoverageUntilMs
import id.homebase.chat.services.livelocation.shareLiveLocationBack
import id.homebase.core.location.GpsRequestReason
import id.homebase.chat.services.convo.ConversationEnricher
import id.homebase.chat.services.convo.ConversationService
import id.homebase.chat.services.convo.ConversationStream
import id.homebase.chat.services.convo.EnrichedConversationUiModel
import id.homebase.chat.services.convo.contact.ConnectionService
import id.homebase.chat.services.convo.contact.ContactService
import id.homebase.chat.services.requests.ConnectionRequestService
import id.homebase.core.audio.AudioRecorder
import id.homebase.core.audio.AudioWaveFormGenerator
import id.homebase.core.auth.AuthConnectionCoordinator
import id.homebase.core.clipboard.platformFileFromPath
import id.homebase.core.auth.toConnectionStatus
import id.homebase.core.avatars.AppConnectionStatus
import id.homebase.core.config.AppConfig
import id.homebase.core.config.chatTargetDrive
import id.homebase.core.config.stickerLabeledDrive
import id.homebase.core.navigation.ActiveConversation
import id.homebase.core.notifications.PendingNotificationTap
import id.homebase.core.settings.UserPreferences
import id.homebase.core.share.ShareContentProcessor
import id.homebase.core.util.ScrollPosition
import id.homebase.core.util.applyDefaultStyling
import id.homebase.resources.MR
import id.homebase.resources.chat_introduce_preflight_in_progress
import id.homebase.resources.chat_location_unavailable
import id.homebase.resources.chat_search_result_conversations
import id.homebase.resources.chat_search_result_messages
import id.homebase.resources.chat_search_result_pinned
import id.homebase.resources.conversation_jump_message_unavailable
import id.homebase.resources.live_share_ended
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
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
import kotlin.time.Instant
import kotlin.time.TimeSource
import kotlin.time.Clock
import kotlin.uuid.Uuid

private data class ConnectionStatusContext(
    val connectionMap: Map<id.homebase.api.common.OdinId, id.homebase.api.client.connections.RedactedIdentityConnectionRegistration>,
    val incomingSenders: Set<id.homebase.api.common.OdinId>,
    val outgoingRecipients: Set<id.homebase.api.common.OdinId>,
    val statusKnown: Boolean,
)

/**
 * What triggered a conversation to be opened/loaded. Logged on every [ConversationListViewModel.selectConversation]
 * / loadMessagesForConversation so a burst of opens in the log can be attributed to a cause
 * (a real user tap vs. a deep link vs. a recomposition/restore loop) instead of guessed at.
 */
enum class ConversationLoadTrigger {
    /** User picked a conversation from the list, or a deep link routed through navigation. */
    Navigation,

    /** A notification tap resolved to a now-loaded conversation. */
    NotificationResolved,

    /**
     * Navigation caused by an incoming OS share (homebase-share:// on iOS,
     * ShareReceiverActivity → MainActivity on Android). This is the ONLY trigger
     * that may auto-send a pending share descriptor — see
     * [MessageActionsHandler.processPendingSharedContent].
     */
    ShareIntent,

    /** Caller did not specify — investigate if these show up in a burst. */
    Unknown,
}

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
    private val stickerStream: id.homebase.chat.services.sticker.StickerStream,
    private val stickerService: id.homebase.chat.services.sticker.StickerService,
    private val stickerPermissionViewModel: ExtendPermissionViewModel,
    private val liveLocationShareService: id.homebase.chat.services.livelocation.LiveLocationShareService,
    private val liveShareReadiness: id.homebase.chat.services.livelocation.LiveShareReadiness,
    private val locationService: id.homebase.core.location.LocationService,
) : ViewModel() {

    companion object {
        private const val TAG = "ConversationListViewModel"

        // How long the chat-details loading spinner may stay up for one conversation
        // before the watchdog logs it as stuck. Generous so a slow-but-legitimate DB
        // read isn't flagged; the point is to catch a load that never completes/fails.
        private const val SPINNER_WATCHDOG_MS = 8_000L
    }

    private val enricher = ConversationEnricher()
    val ownerSession = ownerSessionRepository.user

    private val _uiState = MutableStateFlow(ConversationListUiState())
    val uiState: StateFlow<ConversationListUiState> = _uiState.asStateFlow()

    private val _messagesUiState = MutableStateFlow(
        MessageListUiState(
            userDefaultReactions = userPreferences.preferredUserReactions
                .distinctByEmoji()
                .toPersistentList()
        )
    )
    val messagesUiState: StateFlow<MessageListUiState> = _messagesUiState.asStateFlow()

    val conversationSearchTextState = TextFieldState()

    val messagesSearchTextState = TextFieldState()
    val messageInputTextState = RichTextState().applyDefaultStyling()
    private var currentConversationJob: Job? = null

    // Conversations whose next message emission should land the user at the
    // newest message (set by the ScrollToLatest action, consumed once in the
    // message-emission collect block below). Per-conversation so rapid
    // conversation switches can't cross-fire a scroll.
    private val pendingScrollToLatest = mutableSetOf<Uuid>()

    // Unread boundary (lastRead snapshot) frozen at the moment a conversation is
    // opened with unread messages. Keeps the "New messages" separator pinned in
    // place while viewing, even after mark-as-read advances the live lastRead and
    // zeroes unreadCount. Cleared on leave so re-entry recomputes. Keyed per
    // conversation like pendingScrollToLatest.
    private val frozenUnreadBoundary = mutableMapOf<Uuid, Instant>()

    private val mediaDownloadHandler = MediaDownloadHandler(
        scope = viewModelScope,
        uiState = _uiState,
        messagesUiState = _messagesUiState,
        driveFileProvider = driveFileProvider,
        fileOperationsProvider = fileOperationsProvider,
        chatMessageActionService = chatMessageActionService,
        chatMessageStream = chatMessageStream,
        localVideoContextStore = localVideoContextStore,
        sendEvent = ::sendEvent,
        dispatch = ::onAction,
    )

    // ensureThumbnail closes over `this`, resolving `attachmentHandler` at call time
    // — that's fine because attachmentHandler is initialized further down in the same
    // class body and the lambda is only invoked by user-action paths after both are
    // constructed.
    private val messageActionsHandler: MessageActionsHandler = MessageActionsHandler(
        scope = viewModelScope,
        uiState = _uiState,
        messagesUiState = _messagesUiState,
        messageInputTextState = messageInputTextState,
        chatMessageStream = chatMessageStream,
        chatMessageSenderService = chatMessageSenderService,
        chatMessageActionService = chatMessageActionService,
        conversationService = conversationService,
        contactService = contactService,
        fileOperationsProvider = fileOperationsProvider,
        localVideoContextStore = localVideoContextStore,
        shareContentProcessor = shareContentProcessor,
        userPreferences = userPreferences,
        sendEvent = ::sendEvent,
        dispatch = ::onAction,
        ensureThumbnail = { f -> attachmentHandler.ensureThumbnail(f) },
    )

    private val attachmentHandler: AttachmentHandler = AttachmentHandler(
        scope = viewModelScope,
        uiState = _uiState,
        messagesUiState = _messagesUiState,
        fileOperationsProvider = fileOperationsProvider,
        cropResultBus = cropResultBus,
        drawResultBus = drawResultBus,
        audioRecorder = audioRecorder,
        audioWaveFormGenerator = audioWaveFormGenerator,
        sendEvent = ::sendEvent,
        dispatch = ::onAction,
        addMessageWithFiles = messageActionsHandler::addMessageWithFiles,
    )

    private val conversationLifecycleHandler = ConversationLifecycleHandler(
        scope = viewModelScope,
        uiState = _uiState,
        messagesUiState = _messagesUiState,
        conversationService = conversationService,
        conversationStream = conversationStream,
        connectionRequestService = connectionRequestService,
        credentialsManager = credentialsManager,
        sendEvent = ::sendEvent,
        dispatch = ::onAction,
    )

    private val stickerHandler = StickerHandler(
        scope = viewModelScope,
        messagesUiState = _messagesUiState,
        sendEvent = ::sendEvent,
        addMessageWithFiles = messageActionsHandler::addMessageWithFiles,
        resolveStickerForSend = stickerService::resolveForSend,
        saveStickerBytes = { bytes, contentType, sourceFileId ->
            stickerService.saveSticker(
                bytes = bytes,
                contentType = contentType,
                scope = viewModelScope,
                sourceFileId = sourceFileId,
            )
        },
        deleteSticker = stickerService::deleteSticker,
        getPayloadBytes = chatMessageActionService::getPayloadBytes,
        savedStickers = { stickerStream.stickers.value },
        awaitDriveGranted = ::ensureStickerDriveReady,
    )

    private val stickerCreator = StickerCreator(
        scope = viewModelScope,
        saveSticker = { bytes, contentType ->
            stickerService.saveSticker(bytes = bytes, contentType = contentType, scope = viewModelScope)
        },
        sendSticker = { conversationId, bytes, contentType ->
            val suffix = when (contentType) {
                "image/jpeg" -> ".jpg"
                "image/webp" -> ".webp"
                else -> ".png"
            }
            val path = fileOperationsProvider.writeBytesToTempFile(bytes, "sticker_editor_", suffix)
            messageActionsHandler.addMessageWithFiles(
                conversationId, "",
                listOf(AttachmentPendingFile.FileImage(
                    id = Uuid.generateV7(),
                    file = platformFileFromPath(path),
                    forceSticker = true,
                )),
            )
        },
        sendInfo = { res -> sendEvent(ConversationListUiEvent.ShowInfoMessage(res)) },
        awaitDriveGranted = ::ensureStickerDriveReady,
    )

    /**
     * Gate the sticker save/create paths: surface the extend-permissions dialog if needed,
     * suspend until the Stickers drive is granted, then register + mount it BEFORE the caller
     * writes — so the upload isn't enqueued to an ungranted/unmounted (unsynced) drive. Instant
     * for an already-activated user ([StickerService.activate] is idempotent); on first use it
     * waits for the user to complete the permission flow.
     */
    private suspend fun ensureStickerDriveReady() {
        stickerPermissionViewModel.recheckPermissions()
        stickerPermissionViewModel.permissionsGranted.first { it }
        stickerService.activate()
    }

    /** Create-a-sticker chooser state (null = no flow). Observed by ConversationListScreen. */
    val stickerCreateState: StateFlow<StickerCreateState?> = stickerCreator.state

    /**
     * Extend-permissions VM for the optional Stickers drive. Exposed so the host
     * ([ConversationListScreen]) can render the shared [ExtendPermissionDialog] for it,
     * exactly like Moments exposes [MomentsViewModel.momentsExtendPermissionViewModel].
     * The drive is requested via this gate (ask-permission / extend-permissions flow)
     * rather than silently mounted, so a not-yet-granted Stickers drive surfaces the
     * owner-console permission prompt instead of failing.
     */
    val stickerExtendPermissionViewModel: ExtendPermissionViewModel
        get() = stickerPermissionViewModel

    init {
        // Once the Stickers drive is authorized (the user completed the extend-permissions
        // flow, or it was already granted), delegate activation to StickerService — it
        // registers + mounts the drive (idempotently) and cold-loads the tray. This VM owns
        // the sticker permission UI but does no mounting itself; on subsequent app starts the
        // registered drive is auto-mounted by AuthConnectionCoordinator's login pre-mount loop.
        viewModelScope.launch {
            stickerPermissionViewModel.permissionsGranted
                .filter { it }
                .collect { stickerService.activate() }
        }

        viewModelScope.launch {
            ownerSessionRepository.user.collect { session ->
                _uiState.update { it.copy(ownerSession = session) }
                _messagesUiState.update { it.copy(ownerSession = session) }
            }
        }

        // Track MY live-share state against the open conversation and globally. FULL coverage
        // hides the bubbles' "share live location" offers (starting a share that's already
        // running is confusing, and a second tap would create a duplicate live message — #966
        // follow-up). ANY coverage + the global value drive the purple sharing pins in the
        // conversation and chat-list top bars (#816). No ticker here: the pin/bubble composables
        // derive visibility from `now < untilMs` with their own exact-deadline tickers.
        viewModelScope.launch {
            combine(
                ActiveConversation.conversation,
                liveLocationShareService.recipients,
            ) { conversationId, roster -> conversationId to roster }
                .collectLatest { (conversationId, roster) ->
                    val nowMs = Clock.System.now().toEpochMilliseconds()
                    _uiState.update {
                        it.copy(ownLiveShareAnyUntilMs = liveShareAnyUntilMs(roster, null, nowMs))
                    }
                    var fullUntilMs: Long? = null
                    var anyUntilMs: Long? = null
                    conversationId?.let { id ->
                        val recipientIds = runCatching {
                            conversationStream.getRecipients(id, emptyList(), null)
                        }.getOrDefault(emptyList()).map { it.domainName }
                        fullUntilMs = liveShareCoverageUntilMs(roster, recipientIds, nowMs)
                        anyUntilMs = liveShareAnyUntilMs(roster, recipientIds, nowMs)
                    }
                    _messagesUiState.update {
                        it.copy(
                            ownLiveShareUntilMs = fullUntilMs,
                            ownLiveShareInConversationUntilMs = anyUntilMs,
                        )
                    }
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
            Logger.i(tag = "ConvListPerf") {
                "main list pipeline: launch body entered at ${vmInitMark.elapsedNow().inWholeMilliseconds}ms from vmInit"
            }
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

            Logger.i(tag = "ConvListPerf") {
                "main list pipeline: reaching combine.collect setup at ${vmInitMark.elapsedNow().inWholeMilliseconds}ms from vmInit"
            }
            var firstCombineEval = true
            val listDebounce = ConversationListDebounce()
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
                if (firstCombineEval) {
                    firstCombineEval = false
                    Logger.i(tag = "ConvListPerf") {
                        "main list pipeline: combine first eval at ${vmInitMark.elapsedNow().inWholeMilliseconds}ms from vmInit " +
                            "ownerSession=${ownerSession != null} credentials=${credentials != null} " +
                            "effectiveSession=${effectiveSession != null} dataReady=${conversationState.dataReady} items=${conversationState.items.size}"
                    }
                }
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
            }.debounce { (dataReady, _) ->
                // Leading-edge debounce: render the FIRST ready list immediately, then
                // coalesce the burst that follows. The cached conversations + a
                // credentials-synthesized session make a complete list available within
                // a few ms of vmInit, but the combine's sources (ownerSession,
                // connectionStatusFlow) keep re-emitting all through the auth/connect
                // lifecycle. A flat debounce(50) never sees 50ms of quiet during that
                // storm, so it withheld the ready list for ~half a second until the
                // connection settled ("blocked until it goes green"). See
                // [ConversationListDebounce] / ConversationListDebounceTest.
                listDebounce.timeoutMillisFor(dataReady)
            }.collect { (dataReady: Boolean, enriched: List<EnrichedConversationUiModel>) ->
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
            // The note-to-self bootstrap needs *credentials*, not just a renderable
            // list. `dataReady` can fire from cached conversations + a synthesized
            // session (see the leading-edge debounce note above) before
            // CredentialsManager has its active credentials — and the first thing
            // ensureNoteToSelfExists() does is requireActiveDomain(), which throws
            // "No active credentials set" in that window. An empty/just-wiped DB (e.g.
            // a legacy install landing on a fresh odin-2.db, or the stale-schema
            // self-heal) makes the cached list resolve faster, widening that window.
            //
            // This launch runs on the bare viewModelScope (no CoroutineExceptionHandler),
            // so an uncaught throw here reaches the global crash handler and kills the
            // app right as the overview appears. Gate on credentials so we don't run too
            // early, and contain any residual failure — this is a best-effort bootstrap
            // that is retried on every launch, never worth crashing over.
            conversationStream.conversations.first { it.dataReady }
            credentialsManager.credentialsFlow.first { it != null }
            try {
                conversationService.ensureNoteToSelfExists()
            } catch (e: Throwable) {
                Logger.w(tag = TAG, throwable = e) {
                    "ensureNoteToSelfExists failed; will retry on next launch"
                }
            }
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
                    val status = state.toConnectionStatus()
                    _uiState.update { it.copy(connectionStatus = status) }
                    // Drives the media upload overlay's online-only "Sending" spinner (#948).
                    _messagesUiState.update { it.copy(isConnected = status == AppConnectionStatus.Connected) }
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
        // Monotonic mark stamped the moment a notification tap is first observed (by
        // whichever of the two collectors below sees it first), so every NotifTap log can
        // report elapsed-since-tap — pinning where a notification-tap delay lives:
        // resolution (convo not in items yet), fast-path DB load, message fetch, or
        // detail-pane render. Both collectors run on viewModelScope (Main), so a plain var
        // is safe.
        var notifTapMark: kotlin.time.TimeMark? = null

        viewModelScope.launch {
            combine(
                pendingNotificationTap.state,
                conversationStream.conversations,
            ) { tap, convos -> tap to convos }
                .collect { (tap, convosState) ->
                    if (tap != null && notifTapMark == null) {
                        notifTapMark = TimeSource.Monotonic.markNow()
                    }
                    val resolved = resolveNotificationTap(
                        tap = tap,
                        conversationIds = convosState.items.map { it.id }.toSet(),
                    ) ?: return@collect
                    Logger.i(tag = "NotifTap") {
                        "resolved convo=${resolved.conversationId} msg=${resolved.messageId} " +
                            "sinceTap=${notifTapMark?.elapsedNow()?.inWholeMilliseconds ?: -1}ms itemsAtResolve=${convosState.items.size}"
                    }
                    notifTapMark = null
                    selectConversation(
                        conversationId = resolved.conversationId,
                        messageId = resolved.messageId,
                        scrollToBottom = true,
                        trigger = ConversationLoadTrigger.NotificationResolved,
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
        // spinner. ioDispatcher so the kick can't be queued behind
        // enrichAllConversationsWithUnreadCounts on Main.
        viewModelScope.launch {
            pendingNotificationTap.state.collect { tap ->
                if (tap == null) return@collect
                if (notifTapMark == null) notifTapMark = TimeSource.Monotonic.markNow()
                val convoId = tap.conversationId
                val alreadyLoaded = conversationStream.conversations.value.items
                    .any { it.id == convoId }
                Logger.i(tag = "NotifTap") {
                    "fast-path: tap convo=$convoId msg=${tap.messageId} alreadyInItems=$alreadyLoaded " +
                        "sinceTap=${notifTapMark?.elapsedNow()?.inWholeMilliseconds ?: -1}ms"
                }
                if (alreadyLoaded) return@collect
                launch(ioDispatcher) {
                    val mark = TimeSource.Monotonic.markNow()
                    conversationStream.loadConversation(convoId)
                    val nowInItems = conversationStream.conversations.value.items.any { it.id == convoId }
                    Logger.i(tag = "NotifTap") {
                        "fast-path: loadConversation($convoId) took=${mark.elapsedNow().inWholeMilliseconds}ms " +
                            "nowInItems=$nowInItems"
                    }
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
        scrollToBottom: Boolean = false,
        trigger: ConversationLoadTrigger = ConversationLoadTrigger.Navigation
    ) {
        Logger.i(tag = "ConversationListViewModel") {
            "selectConversation id=$conversationId scrollToBottom=$scrollToBottom trigger=$trigger"
        }
        // Check for pending shared content (from iOS share extension or other handoff).
        // Pass the trigger: only a ShareIntent-caused open consumes the descriptor, so a
        // leftover share can't be auto-re-sent by an ordinary open or a notification tap.
        viewModelScope.launch {
            messageActionsHandler.processPendingSharedContent(conversationId, trigger)
        }

        ActiveConversation.selectConversation(conversationId)
        loadMessagesForConversation(conversationId, messageId, scrollToBottom, trigger)
    }

    /**
     * Set/clear the live-share window on a location message by editing its header descriptor
     * ([LocationPreviewDescriptor.liveShareUntilMs]). Only applies to new-format (typed) location
     * messages; a no-op otherwise.
     */
    private suspend fun updateLocationLiveShare(messageId: Uuid, untilMs: Long) {
        val msg = chatMessageStream.getMessage(messageId) ?: return
        val descriptor = (msg.messageContent as? MessageContent.Location)?.descriptor ?: return
        val updated = descriptor.copy(liveShareUntilMs = untilMs)
        runCatching {
            chatMessageSenderService.updateMessage(
                messageId = messageId,
                versionTag = msg.versionTag,
                content = MessageContentParser.serialize(MessageContent.Location(updated)),
            )
        }.onFailure { Logger.e(throwable = it, tag = "LiveRelay") { "live-share update failed" } }
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
                loadMessagesForConversation(
                    action.conversationId,
                    action.messageId,
                    trigger = ConversationLoadTrigger.Navigation,
                )
            }

            is ConversationListUiAction.BackClicked -> {
                sendEvent(NavigateBack)
            }

            is ConversationListUiAction.SearchClicked -> {
                _uiState.update { it.copy(isSearchActive = true) }
            }

            is ConversationListUiAction.OpenLiveLocationMap -> {
                sendEvent(ConversationListUiEvent.NavigateToLiveLocationMap)
            }

            is ConversationListUiAction.OpenShareLocation -> {
                sendEvent(ConversationListUiEvent.NavigateToShareLocation(action.conversationId.toString()))
            }

            is ConversationListUiAction.OpenLocationSetup -> {
                sendEvent(ConversationListUiEvent.NavigateToLocationSetup)
            }

            is ConversationListUiAction.OpenLocationDashboard -> {
                // Same navigation as setup: openLocation routes to the dashboard when the add-on
                // is activated — always true while a share is running (the pin's only tap path).
                sendEvent(ConversationListUiEvent.NavigateToLocationSetup)
            }

            // Live share has two linked halves: the synced *declaration* (the message's
            // liveShareUntilMs header descriptor — Location is a raw-header typed kind, so
            // updateMessage(content = descriptorJson) sets it and syncs to both sides) AND the local
            // *relay* roster that actually streams GPS. Both use the SAME absolute end-time so stop can
            // later remove exactly this share's {recipient, end-time} roster entries.
            is ConversationListUiAction.StartLiveLocationShare -> viewModelScope.launch {
                // Guard: a live share is pointless if location can't actually capture GPS (add-on not
                // set up / permission not granted) — prompt the user to set it up instead of silently
                // broadcasting nothing.
                if (!liveShareReadiness.isReady()) {
                    _uiState.update { it.copy(uiDialog = ConversationListUiDialog.EnableLocationForShare) }
                    return@launch
                }
                val untilMs = Clock.System.now().toEpochMilliseconds() + action.durationMs
                val msg = chatMessageStream.getMessage(action.messageId) ?: return@launch
                val recipients = conversationStream.getRecipients(msg.conversationId, emptyList(), null)
                updateLocationLiveShare(action.messageId, untilMs) // synced declaration → bubbles flip LIVE
                liveLocationShareService.start(recipients, untilMs) // local relay → GPS arms + streams
            }

            is ConversationListUiAction.StopLiveLocationShare -> viewModelScope.launch {
                val msg = chatMessageStream.getMessage(action.messageId) ?: return@launch
                // The share's end-time off the descriptor BEFORE we overwrite it — the key that targets
                // just this share's roster entries, leaving any other live share running.
                val untilMs = (msg.messageContent as? MessageContent.Location)?.descriptor?.liveShareUntilMs
                val recipients = conversationStream.getRecipients(msg.conversationId, emptyList(), null)
                if (untilMs != null) liveLocationShareService.stop(recipients, untilMs)
                updateLocationLiveShare(action.messageId, Clock.System.now().toEpochMilliseconds())
            }

            // Share back from someone ELSE's location bubble (#966). Their message is never
            // touched (liveShareUntilMs is only ever written on the sharer's own message) —
            // shareLiveLocationBack sends a NEW lightweight live message of our own and starts
            // the relay; see its doc for the mirror-the-sender's-window semantics.
            is ConversationListUiAction.ShareLiveLocationBack -> viewModelScope.launch {
                if (!liveShareReadiness.isReady()) {
                    _uiState.update { it.copy(uiDialog = ConversationListUiDialog.EnableLocationForShare) }
                    return@launch
                }
                val msg = chatMessageStream.getMessage(action.messageId) ?: return@launch
                val recipients = conversationStream.getRecipients(msg.conversationId, emptyList(), null)
                val result = shareLiveLocationBack(
                    durationMs = action.durationMs,
                    senderLiveShareUntilMs =
                        (msg.messageContent as? MessageContent.Location)?.descriptor?.liveShareUntilMs,
                    nowMs = Clock.System.now().toEpochMilliseconds(),
                    getFix = { locationService.requestLatestGps(GpsRequestReason.LiveMap) },
                    send = { descriptor ->
                        chatMessageSenderService.sendNewTypedMessage(
                            messageUniqueId = Uuid.random(),
                            conversationId = msg.conversationId,
                            content = MessageContent.Location(descriptor),
                            previousMessageUniqueId = null,
                        )
                    },
                    startRelay = { untilMs -> liveLocationShareService.start(recipients, untilMs) },
                )
                when (result) {
                    ShareBackResult.Expired -> sendEvent(ShowInfoMessage(MR.string.live_share_ended))
                    ShareBackResult.NoFix -> sendEvent(ShowInfoMessage(MR.string.chat_location_unavailable))
                    ShareBackResult.Sent -> Unit
                }
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
                // Drop the frozen unread boundary so re-entering recomputes it from
                // the (now advanced) lastRead — a fully-read conversation then shows
                // no separator on the next open.
                _uiState.value.selectedConversationId?.let { frozenUnreadBoundary.remove(it) }
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

            is ConversationListUiAction.SendMessage -> messageActionsHandler.handleSendMessage(action)

            is ConversationListUiAction.SaveScrollPosition -> {
                // Pane resolves the visible-window index to a message anchor
                // before dispatch. A null anchor means the visible window had
                // nothing resolvable (empty list, all-non-message rows) — skip
                // the write rather than overwrite the previous good anchor.
                //
                // No in-memory _messagesUiState update either: scrollPosition
                // in MessageListUiState is only consumed when (a) priming the
                // LazyListState on conversation switch (re-read from prefs
                // there) or (b) honouring a programmatic triggerScroll.
                // Neither benefits from the user-scroll debounce — and
                // writing it here would cause a needless recomposition every
                // 300 ms while the user is reading.
                val anchor = action.anchorMessageId
                if (anchor != null) {
                    viewModelScope.launch {
                        userPreferences.setConversationScrollAnchor(
                            action.conversationId.toString(), anchor
                        )
                        userPreferences.setConversationScrollOffset(
                            action.conversationId.toString(),
                            action.firstVisibleItemScrollOffset
                        )
                    }
                }
            }

            is ConversationListUiAction.ClearScrollTrigger -> {
                _messagesUiState.update {
                    it.copy(
                        scrollPosition = it.scrollPosition?.copy(triggerScroll = false)
                    )
                }
            }

            is ConversationListUiAction.EditMessage -> messageActionsHandler.handleEditMessage(action)

            is ConversationListUiAction.EditMessageSave -> messageActionsHandler.handleEditMessageSave()

            is ConversationListUiAction.CancelEditMessage -> messageActionsHandler.handleCancelEditMessage()

            is ConversationListUiAction.DeleteMessage -> messageActionsHandler.handleDeleteMessage(action)

            is ConversationListUiAction.ShareMedia -> mediaDownloadHandler.handleShareMedia(action)

            is ConversationListUiAction.ShareMessage -> mediaDownloadHandler.handleShareMessage(action)

            is ConversationListUiAction.DownloadMedia -> mediaDownloadHandler.handleDownloadMedia(action)

            is ConversationListUiAction.DownloadVideoMedia -> mediaDownloadHandler.handleDownloadVideoMedia(action)

            is ConversationListUiAction.DecryptFile -> mediaDownloadHandler.handleDecryptFile(action)

            is ConversationListUiAction.ScrollToMessageId -> messageActionsHandler.handleScrollToMessageId(action)

            is ConversationListUiAction.OpenReplyTarget -> messageActionsHandler.handleOpenReplyTarget(action)

            ConversationListUiAction.DismissEventDetailFromReply -> messageActionsHandler.handleDismissEventDetailFromReply()

            is ConversationListUiAction.LoadOlderMessages -> {
                viewModelScope.launch { chatMessageStream.loadOlderMessages(action.conversationId) }
            }

            is ConversationListUiAction.LoadNewerMessages -> {
                viewModelScope.launch { chatMessageStream.loadNewerMessages(action.conversationId) }
            }

            is ConversationListUiAction.ScrollToLatest -> {
                // Mark the reload as scroll-to-latest BEFORE loading: when the
                // user is paged deep into history (hasNewerMessages == true) the
                // loaded window's bottom isn't the real latest message, so we
                // reload the newest page. The next observeMessages emission for
                // this conversation then resolves a triggerScroll bottom anchor
                // (see resolveScrollToLatestPosition) instead of leaving the
                // listState parked at its stale history index.
                pendingScrollToLatest.add(action.conversationId)
                viewModelScope.launch { chatMessageStream.loadConversation(action.conversationId) }
            }

            is ConversationListUiAction.ConsumeScrollToLatestRequest -> {
                _messagesUiState.update { it.copy(scrollToLatestRequest = null) }
            }

            is ConversationListUiAction.ClearHighlightedMessage -> messageActionsHandler.handleClearHighlightedMessage()

            is ConversationListUiAction.SaveFile -> mediaDownloadHandler.handleSaveFile(action)

            is ConversationListUiAction.DeleteMessageForEveryone -> messageActionsHandler.handleDeleteMessageForEveryone(action)

            is ConversationListUiAction.DeleteMessageForMe -> messageActionsHandler.handleDeleteMessageForMe(action)

            is ConversationListUiAction.MarkAsRead -> messageActionsHandler.handleMarkAsRead(action)

            is ConversationListUiAction.TogglePinConversation -> conversationLifecycleHandler.handleTogglePinConversation(action)

            is ConversationListUiAction.ToggleReaction -> messageActionsHandler.handleToggleReaction(action)

            is ConversationListUiAction.SendFile -> messageActionsHandler.handleSendFile(action)

            is ConversationListUiAction.AttachPlatformFile -> attachmentHandler.handleAttachPlatformFile(action)

            is ConversationListUiAction.AttachGalleryItem -> attachmentHandler.handleAttachGalleryItem(action)

            is ConversationListUiAction.UnAttachFile -> attachmentHandler.handleUnAttachFile(action)

            is ConversationListUiAction.MediaClicked -> mediaDownloadHandler.handleMediaClicked(action)

            is ConversationListUiAction.ShowMoreClicked -> mediaDownloadHandler.handleShowMoreClicked(action)

            is ConversationListUiAction.CloseFullScreenOverlay -> mediaDownloadHandler.handleCloseFullScreenOverlay()

            is ConversationListUiAction.ReplyToMessage -> messageActionsHandler.handleReplyToMessage(action)

            is ConversationListUiAction.BattleDiceRoll -> messageActionsHandler.handleBattleDiceRoll(action)

            ConversationListUiAction.CancelBattleDiceRoll -> messageActionsHandler.handleCancelBattleDiceRoll()

            is ConversationListUiAction.ForwardMessage -> messageActionsHandler.handleForwardMessage(action)

            is ConversationListUiAction.ForwardMessageSelectRecipient -> messageActionsHandler.handleForwardMessageSelectRecipient(action)

            is ConversationListUiAction.ForwardMessageSend -> messageActionsHandler.handleForwardMessageSend(action)

            is ConversationListUiAction.CancelReplyToMessage -> messageActionsHandler.handleCancelReplyToMessage()

            is ConversationListUiAction.ShowReactionDetails -> messageActionsHandler.handleShowReactionDetails(action)

            is ConversationListUiAction.HideReactionDetails -> messageActionsHandler.handleHideReactionDetails()

            is ConversationListUiAction.ShowContactInfo -> conversationLifecycleHandler.handleShowContactInfo(action)

            is ConversationListUiAction.ShowMessageInfo -> conversationLifecycleHandler.handleShowMessageInfo(action)

            is ConversationListUiAction.DismissSheet -> conversationLifecycleHandler.handleDismissSheet()

            is ConversationListUiAction.ConnectIdentities -> conversationLifecycleHandler.handleConnectIdentities(action)

            is ConversationListUiAction.ConnectToIdentity -> conversationLifecycleHandler.handleConnectToIdentity(action)

            is ConversationListUiAction.AutoConnect -> conversationLifecycleHandler.handleAutoConnect(action)

            is ConversationListUiAction.OpenConnectionRequestInOwnerConsole -> conversationLifecycleHandler.handleOpenConnectionRequestInOwnerConsole(action)

            is ConversationListUiAction.OpenSendConnectionRequestDialog -> conversationLifecycleHandler.handleOpenSendConnectionRequestDialog(action)

            is ConversationListUiAction.ShowConversationSettings -> conversationLifecycleHandler.handleShowConversationSettings(action)

            is ConversationListUiAction.IntroduceEveryone -> conversationLifecycleHandler.handleIntroduceEveryone(action)

            is ConversationListUiAction.IntroduceSendAnyway -> conversationLifecycleHandler.handleIntroduceSendAnyway(action)

            is ConversationListUiAction.IntroduceSendReadyOnly -> conversationLifecycleHandler.handleIntroduceSendReadyOnly(action)

            is ConversationListUiAction.IntroduceCancel -> conversationLifecycleHandler.handleIntroduceCancel()

            is ConversationListUiAction.ArchiveConversation -> conversationLifecycleHandler.handleArchiveConversation(action)

            is ConversationListUiAction.UnarchiveConversation -> conversationLifecycleHandler.handleUnarchiveConversation(action)

            is ConversationListUiAction.ShowArchivedMessagesClicked -> conversationLifecycleHandler.handleShowArchivedMessagesClicked()

            is ConversationListUiAction.ArchiveBackClicked -> conversationLifecycleHandler.handleArchiveBackClicked()

            is ConversationListUiAction.ClearConversation -> conversationLifecycleHandler.handleClearConversation(action)

            is ConversationListUiAction.DeleteConversation -> conversationLifecycleHandler.handleDeleteConversation(action)

            is ConversationListUiAction.ConfirmDeleteConversation -> conversationLifecycleHandler.handleConfirmDeleteConversation(action)

            is ConversationListUiAction.ConfirmLeaveAndDeleteConversation -> conversationLifecycleHandler.handleConfirmLeaveAndDeleteConversation(action)

            is ConversationListUiAction.CloseDetailPaneRequestConsumed -> conversationLifecycleHandler.handleCloseDetailPaneRequestConsumed()

            is ConversationListUiAction.AcceptRejoin -> conversationLifecycleHandler.handleAcceptRejoin(action)

            is ConversationListUiAction.DeclineRejoin -> conversationLifecycleHandler.handleDeclineRejoin(action)

            /* Clipboard image paste */
            is ConversationListUiAction.AttachClipboardImage -> attachmentHandler.handleAttachClipboardImage(action)

            is ConversationListUiAction.RequestCropAttachment -> attachmentHandler.handleRequestCropAttachment(action)

            is ConversationListUiAction.ApplyCropResult -> attachmentHandler.handleApplyCropResult(action)

            is ConversationListUiAction.RequestDrawAttachment -> attachmentHandler.handleRequestDrawAttachment(action)

            is ConversationListUiAction.ApplyDrawResult -> attachmentHandler.handleApplyDrawResult(action)

            is ConversationListUiAction.RemoveBackgroundAttachment -> attachmentHandler.handleRemoveBackground(action)

            is ConversationListUiAction.ApplyTrimResult -> attachmentHandler.handleApplyTrimResult(action)

            is ConversationListUiAction.ToggleStickerAttachment -> attachmentHandler.handleToggleStickerAttachment(action)

            is ConversationListUiAction.ShowRecordingHelp -> attachmentHandler.handleShowRecordingHelp()

            is ConversationListUiAction.StartRecording -> attachmentHandler.handleStartRecording(action)

            is ConversationListUiAction.StopRecording -> attachmentHandler.handleStopRecording(action)

            is ConversationListUiAction.CancelRecording -> attachmentHandler.handleCancelRecording()

            is ConversationListUiAction.BlockUser -> conversationLifecycleHandler.handleBlockUser(action)

            is ConversationListUiAction.ReportContent -> conversationLifecycleHandler.handleReportContent()

            /* Stickers */
            // Entry point into the sticker feature (tray open / save / import): run the
            // extend-permissions gate. If the optional Stickers drive isn't granted yet,
            // ExtendPermissionViewModel flips to ShowDialog and the host renders the shared
            // ExtendPermissionDialog (ask-permission flow, mirroring Moments). If it is
            // already granted, permissionsGranted emits true and the init observer
            // hot-mounts the drive. Either way no silent mount of an ungranted drive.
            is ConversationListUiAction.EnsureStickerDriveMounted ->
                stickerPermissionViewModel.recheckPermissions()

            is ConversationListUiAction.SendSavedSticker -> stickerHandler.handleSendSavedSticker(action)

            // Saving/importing a sticker needs the Stickers drive granted+mounted so the
            // new sticker syncs back to the tray. The handler awaits that grant (via
            // [StickerHandler.awaitDriveGranted], which surfaces the extend-permissions
            // dialog when not yet granted) BEFORE enqueuing the upload, so we never write
            // to an ungranted drive that the sync engine would silently drop.
            is ConversationListUiAction.SaveStickerFromMessage ->
                stickerHandler.handleSaveStickerFromMessage(action)

            is ConversationListUiAction.CreateStickerFromImage ->
                stickerCreator.create(action.bytes, action.contentType, action.conversationId)
            is ConversationListUiAction.SelectStickerVariant -> stickerCreator.selectVariant(action.variant)
            is ConversationListUiAction.ConfirmStickerCreate -> stickerCreator.confirm()
            is ConversationListUiAction.DismissStickerCreate -> stickerCreator.dismiss()

            is ConversationListUiAction.RemoveSticker -> stickerHandler.handleRemoveSticker(action)

            // Sticker-tap bottom sheet (WhatsApp-style): open the sheet, dismiss it, or
            // remove the saved copy created from this message. "Add to my stickers" reuses
            // SaveStickerFromMessage; "Save to device" reuses DownloadMedia.
            is ConversationListUiAction.ShowStickerOptions ->
                stickerHandler.handleShowStickerOptions(action)
            is ConversationListUiAction.DismissStickerOptions ->
                stickerHandler.handleDismissStickerOptions()
            is ConversationListUiAction.RemoveStickerFromMessage ->
                stickerHandler.handleRemoveStickerFromMessage(action)
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
    private fun loadMessagesForConversation(
        conversationId: Uuid,
        messageIdForScroll: Uuid?,
        scrollToBottom: Boolean = false,
        trigger: ConversationLoadTrigger = ConversationLoadTrigger.Unknown
    ) {
        val loadStart = TimeSource.Monotonic.markNow()

        val hasCachedMessages = chatMessageStream.hasCachedMessages(conversationId)
        Logger.i(tag = "ConversationListViewModel") {
            "loadMessagesForConversation id=$conversationId hasCached=$hasCachedMessages trigger=$trigger"
        }

        // Freeze the unread boundary on a genuine open (selection transition), so
        // the "New messages" separator stays pinned in place while viewing — even
        // after mark-as-read advances lastRead and zeroes unreadCount. Reloads of
        // the already-open conversation must NOT re-capture, or the boundary would
        // reset to the (now advanced) lastRead and the separator would vanish.
        // Runs before selectedConversationId is flipped below, so the comparison
        // still sees the previous selection.
        val isNewSelection = _uiState.value.selectedConversationId != conversationId
        if (isNewSelection) {
            // Only one conversation is viewed at a time; drop any stale boundaries.
            frozenUnreadBoundary.clear()
            val convo = _uiState.value.activeConversations
                .find { it.conversation.id == conversationId }
                ?.conversation
            if (convo != null && convo.unreadCount > 0) {
                frozenUnreadBoundary[conversationId] = convo.lastRead
            }
        }

        // Always mark loading here, even when hasCachedMessages == true.
        //
        // Previously this short-circuited to `isLoadingMessages = !hasCachedMessages`
        // so cached conversations skipped the loading state for instant-feel
        // switching — but that caused a race when rapidly switching between
        // a few cached conversations:
        //   1. This update fires (isLoadingMessages = false, scrollPosition = null).
        //   2. Pane's `remember(conversationId, isLoadingMessages)` re-evaluates
        //      with the new (id, false) keys, sees scrollPosition = null, and
        //      pre-inits LazyListState at Int.MAX_VALUE → land at bottom.
        //   3. The collect block below THEN runs, calls getScrollPosition, and
        //      writes the resolved scrollPosition to state — but the Pane's
        //      `remember` block doesn't re-fire because its keys are unchanged,
        //      so the LazyListState stays at the bottom.
        //   4. snapshotFlow then persists "bottom" as the new anchor, sticking
        //      the bug for that conversation.
        //
        // Holding loading=true until the collect block atomically lands
        // (messages, scrollPosition, isLoadingMessages=false) closes the race:
        // the Pane's remember only re-evaluates ONCE, with the resolved scroll.
        // The brief blank-screen window is the same one uncached switches
        // already have (a few ms of groupBy + clustering on Dispatchers.Default).
        _messagesUiState.update {
            it.copy(
                scrollPosition = null,
                isLoadingMessages = true,
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
            "selectedConversationId set id=$conversationId (pending messages) trigger=$trigger"
        }
        _uiState.update { it.copy(selectedConversationId = conversationId) }

        // When loading message for newly selected conversation, cancel any previous job to
        // avoid observing multiple messageStreams
        currentConversationJob?.cancel()
        currentConversationJob = viewModelScope.launch {
            // Spinner watchdog: if the detail pane is still loading THIS conversation
            // after a generous threshold, log it (with elapsed) so a stuck/hung load is
            // visible in homebase.log even when it neither completed nor threw — the
            // signature of a real "infinity spinner" report. Diagnostic only; a
            // genuinely slow DB read is still allowed to finish.
            val watchdog = launch {
                delay(SPINNER_WATCHDOG_MS)
                if (_uiState.value.selectedConversationId == conversationId &&
                    _messagesUiState.value.isLoadingMessages
                ) {
                    Logger.w(tag = "ConversationListViewModel") {
                        "chat-details still loading after ${SPINNER_WATCHDOG_MS}ms id=$conversationId " +
                            "trigger=$trigger sinceSelected=${loadStart.elapsedNow()} — " +
                            "load has not completed or failed"
                    }
                }
            }
            try {
                var messageIdForScrollNullable = messageIdForScroll
                var setInitialScroll = true

                if (!hasCachedMessages) {
                    val savedAnchor = if (!scrollToBottom) {
                        userPreferences.getConversationScrollAnchor(conversationId.toString())
                    } else null
                    val anchorTarget = messageIdForScroll ?: savedAnchor
                    Logger.d(tag = "ChatPaging") {
                        "open conversationId=$conversationId hasCached=false scrollToBottom=$scrollToBottom " +
                            "savedAnchor=$savedAnchor messageIdForScroll=$messageIdForScroll → " +
                            (if (anchorTarget != null) "loadAround($anchorTarget)" else "loadLatest")
                    }
                    if (anchorTarget != null) {
                        // Around-anchor open: load a centered window so the
                        // user lands exactly where they left off, even if many
                        // newer messages arrived since. The fallback inside
                        // loadConversationAroundMessage covers the case where
                        // the anchor's message has been purged from the DB.
                        val found = chatMessageStream
                            .loadConversationAroundMessage(conversationId, anchorTarget)
                        if (!found && messageIdForScroll != null) {
                            // Explicit jump to a message that's no longer on disk:
                            // the window fell back to the latest page, so the
                            // scroll lookup below can never resolve. Tell the user
                            // instead of silently landing at the bottom, and stop
                            // the per-emission retry from waiting forever.
                            reportJumpTargetUnavailable(conversationId, messageIdForScroll)
                            messageIdForScrollNullable = null
                        }
                    } else {
                        chatMessageStream.loadConversation(conversationId)
                    }
                } else if (messageIdForScroll != null &&
                    !chatMessageStream.isMessageInWindow(conversationId, messageIdForScroll)
                ) {
                    // Cached, but the jump target lives outside the in-memory
                    // window (e.g. an album item older than the ~PAGE_SIZE
                    // window). Reusing the window would never contain it and the
                    // jump would silently no-op, so re-seed a window centered on
                    // the target — same machinery as the uncached around-open.
                    Logger.d(tag = "ChatPaging") {
                        "open conversationId=$conversationId hasCached=true but target=$messageIdForScroll " +
                            "outside window → loadAround"
                    }
                    val found = chatMessageStream
                        .loadConversationAroundMessage(conversationId, messageIdForScroll)
                    if (!found) {
                        reportJumpTargetUnavailable(conversationId, messageIdForScroll)
                        messageIdForScrollNullable = null
                    }
                } else {
                    Logger.d(tag = "ChatPaging") {
                        "open conversationId=$conversationId hasCached=true → reuse window"
                    }
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

                            val window = messageState.window
                            val windowMessages = window.messages
                            val messagesModels = withContext(Dispatchers.Default) {
                                // Soft-delete display lives at the SQL layer now (see
                                // ChatMessageStream.fetchMessages): note-to-self filters
                                // tombstones out, regular conversations keep them. We let
                                // the in-memory window mirror SQL on the next page load —
                                // a just-deleted note-to-self message remains visible as a
                                // "Deleted File" tombstone briefly and disappears on the
                                // next visit. Single source of truth.
                                val messages = if (exitedAt != null)
                                    windowMessages.filter { it.userDate <= exitedAt }
                                else
                                    windowMessages
                                val timezone = TimeZone.currentSystemDefault()
                                val groupedMessages =
                                    messages.sortedBy { it.userDate }.groupBy { message ->
                                        val date = message.userDate.toLocalDateTime(timezone).date
                                        date
                                    }
                                // Top of the list: spinner while older messages page in,
                                // Header (avatar + group title) only once we've reached the
                                // start of the conversation. Otherwise the Header would sit
                                // ambiguously between loaded pages and read as "you've
                                // reached the beginning" when you haven't.
                                val models: MutableList<MessageListContentModel> = mutableListOf()
                                if (window.hasOlderMessages) {
                                    models.add(MessageListContentModel.LoadingOlder)
                                } else {
                                    models.add(MessageListContentModel.Header)
                                }

                                var systemIndex = 0
                                models.addAll(groupedMessages.flatMap { (date, messages) ->
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
                                if (window.hasNewerMessages) {
                                    models.add(MessageListContentModel.LoadingNewer)
                                }
                                models
                            }

                            // Insert "New Messages" separator before the first unread message.
                            // Position it from the boundary frozen at open time (see
                            // loadMessagesForConversation top), NOT the live lastRead/unreadCount —
                            // so it stays pinned while viewing instead of vanishing once
                            // mark-as-read advances lastRead and zeroes unreadCount.
                            val frozenBoundary = frozenUnreadBoundary[conversationId]
                            val currentOdinId = _uiState.value.ownerSession?.odinId
                            if (frozenBoundary != null) {
                                val firstUnreadIdx = messagesModels.indexOfFirst {
                                    it is MessageListContentModel.Message &&
                                        it.message.sender != currentOdinId &&
                                        it.message.userDate > frozenBoundary
                                }
                                if (firstUnreadIdx > 0) {
                                    messagesModels.add(firstUnreadIdx, MessageListContentModel.UnreadSeparator)
                                }
                            }

                            // Scroll handling: navigate to a specific message (search results,
                            // cross-conversation jumps, etc.). The user's own just-sent messages
                            // are handled by the LazyColumn auto-follow effect in ConversationContent.kt,
                            // which only scrolls when the user was already at the bottom. Forcing
                            // a scroll-to-new-message here would yank the user out of history.
                            messageActionsHandler.pendingMessageId = null
                            // If the target message hasn't synced yet, keep
                            // messageIdForScrollNullable set so the next
                            // ChatMessagesData.Messages emission retries the
                            // lookup (messages stream re-emits on each sync
                            // batch). Clear only once the message is found.
                            // Capture the requested scroll-to-message id BEFORE we null it
                            // so we can persist it as the new anchor below.
                            val targetMessageId = messageIdForScrollNullable
                            val indexOfMessageForScroll = if (targetMessageId != null) {
                                val messageIndex = messagesModels.indexOfLast {
                                    it is MessageListContentModel.Message && it.message.id == targetMessageId
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

                            val newScroll = when {
                                // ScrollToLatest reload: the user tapped the
                                // scroll-to-bottom FAB while paged into history.
                                // .remove() consumes the flag once and reports
                                // whether it was set. Land on the newest message.
                                pendingScrollToLatest.remove(conversationId) -> {
                                    Logger.i("Scroll-to-latest: landing at newest message")
                                    resolveScrollToLatestPosition(messagesModels)
                                }

                                indexOfMessageForScroll != null -> {
                                    Logger.i("Setting scroll position: $indexOfMessageForScroll")
                                    ScrollPosition(
                                        firstVisibleItemIndex = indexOfMessageForScroll,
                                        triggerScroll = true
                                    )
                                }

                                // New-messages landing: on the initial open of a
                                // conversation that has unread messages, land with the
                                // "New messages" separator at the top of the viewport so
                                // the new messages are visible instead of hidden below the
                                // fold. Pre-init (triggerScroll = false), same as the
                                // saved-anchor path below — no scroll flash, and a real
                                // bounded index (not the Int.MAX_VALUE sentinel). Takes
                                // priority over the saved anchor: surfacing new messages is
                                // the point. An explicit jump to a specific message
                                // (indexOfMessageForScroll, above) still wins.
                                setInitialScroll && !scrollToBottom &&
                                    messagesModels.any { it is MessageListContentModel.UnreadSeparator } -> {
                                    val separatorIndex = messagesModels.indexOfFirst {
                                        it is MessageListContentModel.UnreadSeparator
                                    }
                                    Logger.i("Landing on new-messages separator at index $separatorIndex")
                                    ScrollPosition(firstVisibleItemIndex = separatorIndex)
                                }

                                setInitialScroll && !scrollToBottom -> {
                                    Logger.i("Getting saved scroll position")
                                    getScrollPosition(conversationId, messagesModels)
                                }

                                else -> {
                                    Logger.i("No saved scroll position")
                                    null
                                }
                            }

                            // Persist the anchor only when we just landed on a freshly
                            // requested message (the messageIdForScroll path). The
                            // saved-anchor path doesn't need to re-persist itself, and
                            // the user-scroll path persists via SaveScrollPosition.
                            if (indexOfMessageForScroll != null && targetMessageId != null) {
                                userPreferences.setConversationScrollAnchor(
                                    conversationId.toString(),
                                    targetMessageId,
                                )
                                userPreferences.setConversationScrollOffset(
                                    conversationId.toString(),
                                    newScroll!!.firstVisibleItemScrollOffset,
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
                                    "messages first emission id=$conversationId messageCount=${messagesModels.size} sinceSelected=${loadStart.elapsedNow()}"
                                }
                            }

                            _messagesUiState.update {
                                it.copy(
                                    isLoadingMessages = false,
                                    messages = messagesModels.toPersistentList(),
                                    scrollPosition = newScroll
                                        ?: it.scrollPosition?.takeIf { pos -> pos.triggerScroll },
                                    hasOlderMessages = window.hasOlderMessages,
                                    hasNewerMessages = window.hasNewerMessages,
                                    isLoadingOlder = window.isLoadingOlder,
                                    isLoadingNewer = window.isLoadingNewer,
                                )
                            }

                            if (setInitialScroll) {
                                val totalElapsed = loadStart.elapsedNow()
                                Logger.d(tag = "ConversationLoad") {
                                    "conversationId=$conversationId " +
                                            "messageCount=${messagesModels.size} " +
                                            "cached=$hasCachedMessages " +
                                            "total=$totalElapsed"
                                }
                            }

                            setInitialScroll = false
                        }
                    }
                }
            } catch (_: CancellationException) {
                // ignore — a new selection cancels this job; the new job owns the
                // loading state for the conversation being switched to.
            } catch (e: Exception) {
                // Previously snackbar-only (invisible in homebase.log) AND the spinner
                // was never cleared — a failed load (DB error, deserialization, …) left
                // the detail pane spinning forever (the reported "infinity spinner").
                Logger.e(throwable = e, tag = "ConversationListViewModel") {
                    "loadMessagesForConversation failed id=$conversationId trigger=$trigger: ${e.message}"
                }
                sendEvent(ShowErrorMessage("Failed to load messages: ${e.message}"))
            } finally {
                watchdog.cancel()
                // Backstop so the detail pane can never spin forever: the success path
                // clears isLoadingMessages inside the collect; this covers an error or an
                // unexpected end (cancellation/scope teardown) while still on this
                // conversation. Guarded (see [shouldClearLoadingSpinnerOnLoadEnd]) so a
                // cancellation from switching conversations — which already set
                // isLoadingMessages=true for the NEW conversation — can't wipe its
                // spinner. Reaching here with loading still true is abnormal (the success
                // path should have cleared it), so log it loudly.
                if (shouldClearLoadingSpinnerOnLoadEnd(
                        selectedConversationId = _uiState.value.selectedConversationId,
                        conversationId = conversationId,
                        stillLoading = _messagesUiState.value.isLoadingMessages,
                    )
                ) {
                    Logger.w(tag = TAG) {
                        "chat-details spinner backstop fired id=$conversationId trigger=$trigger " +
                            "sinceSelected=${loadStart.elapsedNow()} — clearing a spinner the load left set"
                    }
                    _messagesUiState.update { it.copy(isLoadingMessages = false) }
                }
            }
        }
    }

    /**
     * Resolve the persisted scroll anchor for [conversationId] against the
     * freshly-loaded [messages] list. The anchor is a message uniqueId — we
     * scan the list for the matching [MessageListContentModel.Message] and
     * return its index. Returns `null` when no anchor was saved, when the
     * anchor isn't in the current window (deleted, not-yet-synced, or one of
     * the rare race cases), or when the offset wasn't saved alongside it.
     * The caller falls through to the "land at bottom" path in those cases.
     */
    private fun getScrollPosition(
        conversationId: Uuid,
        messages: List<MessageListContentModel>,
    ): ScrollPosition? {
        val anchor =
            userPreferences.getConversationScrollAnchor(conversationId.toString()) ?: return null
        val firstVisibleItemScrollOffset =
            userPreferences.getConversationScrollOffset(conversationId.toString()) ?: return null
        val resolvedIndex = resolveAnchorIndex(messages, anchor) ?: return null
        return ScrollPosition(
            firstVisibleItemIndex = resolvedIndex,
            firstVisibleItemScrollOffset = firstVisibleItemScrollOffset,
        )
    }

    private fun sendEvent(event: ConversationListUiEvent) {
        _uiState.update { it.copy(uiEvent = event) }
    }

    /**
     * A jump-to-message (album item, search result, cross-conversation link)
     * resolved to a message that's no longer on disk, so no window could be
     * centered on it. Surface a snackbar instead of silently landing on the
     * latest page, and log it so the miss is visible in homebase.log.
     */
    private fun reportJumpTargetUnavailable(conversationId: Uuid, messageId: Uuid) {
        Logger.w(tag = TAG) {
            "jump-to-message target unavailable id=$conversationId message=$messageId — " +
                "anchor not in DB, landed on latest page"
        }
        sendEvent(ShowInfoMessage(MR.string.conversation_jump_message_unavailable))
    }
}

/**
 * Leading-edge debounce policy for the conversation-list combine. The list's sources
 * (ownerSession, connectionStatusFlow) re-emit continuously through the auth/connect
 * lifecycle; a flat debounce would withhold the already-ready cached list until that storm
 * settled (~when the connection goes online — the "blocked until green" bug).
 *
 * The first ready snapshot is granted the leading edge (0ms) so the cached list renders
 * immediately; everything after is coalesced at 50ms so the storm is a few re-sorts, not
 * one per source emission.
 *
 * Stateful (remembers whether the first ready snapshot has been granted) — use one instance
 * per collect, on a single coroutine. Owning the flag here keeps it out of the ViewModel
 * and lets ConversationListDebounceTest exercise the real policy instead of a mirror.
 */
internal class ConversationListDebounce {
    private var firstReadyEmitted = false

    /** Debounce timeout (ms) for a combine emission with the given [dataReady]. */
    fun timeoutMillisFor(dataReady: Boolean): Long {
        val timeout = if (dataReady && !firstReadyEmitted) 0L else 50L
        if (timeout == 0L) firstReadyEmitted = true
        return timeout
    }
}

/**
 * Whether the message-load backstop should clear the chat-details loading spinner when a
 * load coroutine ends. The success path clears it inside the observeMessages collect; this
 * guards the `finally` backstop that catches an error / unexpected cancellation / hung load
 * that left the spinner set (the reported "infinity spinner").
 *
 * Clears only when (a) THIS conversation is still the selected one — so a cancellation from
 * switching conversations, which already armed the spinner for the NEW conversation, can't
 * wipe it — and (b) the spinner is actually still up (reaching the backstop with it already
 * cleared is the normal success case, nothing to do).
 *
 * Pure so SpinnerBackstopGuardTest can pin the rule without a ViewModel.
 */
internal fun shouldClearLoadingSpinnerOnLoadEnd(
    selectedConversationId: Uuid?,
    conversationId: Uuid,
    stillLoading: Boolean,
): Boolean = stillLoading && selectedConversationId == conversationId

/**
 * Chooses the [OwnerSession] passed to [ConversationEnricher]: prefer the fully-resolved
 * [live] session, fall back to a minimal one synthesized from [credentials] when the async
 * profile load hasn't arrived yet. Returns null only when neither source is available
 * (pre-login). The preference order is load-bearing — inverting it would replace a resolved
 * display name / profile image with a bare odinId.
 */
fun synthesizeOwnerSession(
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

