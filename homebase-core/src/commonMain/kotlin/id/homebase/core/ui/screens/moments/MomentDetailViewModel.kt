package id.homebase.core.ui.screens.moments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.common.OdinId
import id.homebase.api.file.FileOperationsProvider
import id.homebase.core.util.extensionForMimeType
import id.homebase.chat.conversationlist.FullScreenOverlay
import id.homebase.chat.data.ContactUiModel
import id.homebase.chat.services.convo.ConversationStream
import id.homebase.chat.services.convo.contact.ContactService
import id.homebase.chat.services.toChatDeliveryStatus
import id.homebase.chat.services.toErrorDetailRes
import id.homebase.core.moments.services.MomentActionService
import id.homebase.core.moments.services.MomentCommentsService
import id.homebase.core.moments.services.MomentFeedItem
import id.homebase.core.moments.services.MomentGroup
import id.homebase.core.moments.services.MomentGroupService
import id.homebase.core.moments.services.MomentSource
import id.homebase.core.moments.services.MomentsFeedService
import id.homebase.core.moments.services.MomentsPostSenderService
import id.homebase.core.settings.UserPreferences
import id.homebase.chat.data.ConversationUiModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Instant
import kotlin.uuid.Uuid

private const val TAG = "MomentDetailViewModel"

/**
 * Resolves a single moment by id from the live feed flow. We deliberately
 * source from [MomentsFeedService] rather than re-querying the DB:
 *  - The user lands here from the feed, where the moment is already in
 *    memory — no extra read needed for the happy path.
 *  - When new sync batches arrive (e.g. a description edit replays from
 *    another device), [MomentsFeedService] re-emits and the detail screen
 *    re-renders automatically.
 *  - On cold start before the feed has loaded, `moment` stays null and the
 *    screen shows a loading state until the feed populates.
 *
 * Comments stream from [MomentCommentsService.commentsFor] for the same
 * `momentId` — the service handles cold-load + live event-bus updates so the
 * VM just merges the snapshot into uiState.
 */
class MomentDetailViewModel(
    private val momentId: Uuid,
    private val initialPayloadKey: String?,
    feedService: MomentsFeedService,
    private val commentsService: MomentCommentsService,
    private val postSender: MomentsPostSenderService,
    private val actionService: MomentActionService,
    private val credentialsManager: CredentialsManager,
    private val userPreferences: UserPreferences,
    momentGroupService: MomentGroupService,
    conversationStream: ConversationStream,
    private val contactService: ContactService,
    private val driveFileProvider: DriveFileProvider,
    private val fileOperationsProvider: FileOperationsProvider,
) : ViewModel() {

    private val _overlay = MutableStateFlow<FullScreenOverlay?>(null)
    private val _selfOdinId = MutableStateFlow<OdinId?>(null)

    /**
     * Compose-screen-local state — comment composer/edit fields plus the
     * moment-delete dialog. Bundled into a single flow so the
     * [uiState] combine stays within the 5-flow typed overload.
     */
    private data class ScreenLocalState(
        val commentDraft: String = "",
        val isPostingComment: Boolean = false,
        val editingCommentId: Uuid? = null,
        val editingCommentDraft: String = "",
        val isSavingCommentEdit: Boolean = false,
        val showDeleteDialog: Boolean = false,
        val isDeletingMoment: Boolean = false,
        val deletingCommentIds: Set<Uuid> = emptySet(),
        val deleteCommentDialogTarget: Uuid? = null,
        val sharedWithExpanded: Boolean = false,
        val isTransferHistoryLoading: Boolean = false,
        val transferHistoryLoaded: Boolean = false,
        val recipientDeliveries: List<RecipientDeliveryUiModel> = emptyList(),
        val showReactionsSheet: Boolean = false,
        val isReactionsLoading: Boolean = false,
        val reactions: List<MomentReactionUiModel> = emptyList(),
    )

    private val _screenLocal = MutableStateFlow(ScreenLocalState())

    private val _events = MutableSharedFlow<MomentDetailUiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<MomentDetailUiEvent> = _events.asSharedFlow()

    /**
     * Snapshot at construction time — `UserPreferences.preferredUserReactions`
     * is a plain `var`, not a flow. Chat reads it the same way. If/when the
     * preference becomes reactive we can promote this into the combine.
     */
    private val userDefaultReactions: List<String> =
        userPreferences.preferredUserReactions.takeIf { it.isNotEmpty() }
            ?: listOf("❤️", "😂", "😮", "😢", "🔥", "👏")

    init {
        // Self-identity for "is this comment mine" checks. Owner-side files
        // also tend to have a null senderOdinId, so the UI's "mine" predicate
        // accepts either null or a match on this id.
        viewModelScope.launch {
            _selfOdinId.value = credentialsManager.getActiveCredentials()?.domain
        }
    }

    /**
     * Resolve the moment + its shared-with audience in one flow so the main
     * uiState combine stays within the 5-typed-overload limit. We need groups
     * + conversations to translate `MomentSource` ids into human-readable
     * labels; doing it here keeps the downstream combine focused on screen
     * state.
     */
    private val momentWithSharedWith: kotlinx.coroutines.flow.Flow<MomentWithSharedWith> = combine(
        feedService.feed,
        momentGroupService.groups,
        conversationStream.conversations,
        contactService.contacts,
        _selfOdinId,
    ) { feed, groups, conversationsData, contacts, self ->
        val match = feed.firstOrNull { it.id == momentId }
        val sharedWith = match?.let {
            resolveSharedWith(it, groups, conversationsData.items, contacts, self)
        }
        val avatars = match?.let { resolveRecipientAvatars(it, contacts, self) }.orEmpty()
        MomentWithSharedWith(match, sharedWith, avatars)
    }

    val uiState: StateFlow<MomentDetailUiState> = combine(
        momentWithSharedWith,
        _overlay,
        commentsService.commentsFor(momentId),
        _screenLocal,
        _selfOdinId,
    ) { momentBundle, overlay, comments, local, self ->
        val match = momentBundle.moment
        // Owner-side moment files have a null senderOdinId (the server only
        // populates it on the receiving drive). A match on the active
        // identity catches the edge case where the file was sent to self.
        val isMine = match != null &&
            (match.senderOdinId == null || (self != null && match.senderOdinId == self))
        MomentDetailUiState(
            moment = match,
            isLoading = match == null,
            fullScreenOverlay = overlay,
            initialPayloadKey = initialPayloadKey,
            comments = comments,
            selfOdinId = self,
            commentDraft = local.commentDraft,
            isPostingComment = local.isPostingComment,
            editingCommentId = local.editingCommentId,
            editingCommentDraft = local.editingCommentDraft,
            isSavingCommentEdit = local.isSavingCommentEdit,
            userDefaultReactions = userDefaultReactions,
            isMine = isMine,
            showDeleteDialog = local.showDeleteDialog,
            isDeleting = local.isDeletingMoment,
            deletingCommentIds = local.deletingCommentIds,
            deleteCommentDialogTarget = local.deleteCommentDialogTarget,
            sharedWith = momentBundle.sharedWith,
            sharedWithExpanded = local.sharedWithExpanded,
            isTransferHistoryLoading = local.isTransferHistoryLoading,
            recipientDeliveries = local.recipientDeliveries,
            recipientAvatars = momentBundle.recipientAvatars,
            showReactionsSheet = local.showReactionsSheet,
            isReactionsLoading = local.isReactionsLoading,
            reactions = local.reactions,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        MomentDetailUiState(initialPayloadKey = initialPayloadKey),
    )

    /**
     * Diagnostic: log every comment-list change observed on this VM's
     * uiState. Pairs with the MomentCommentsService logs to triangulate a
     * "comment didn't appear" report — if the service shows `added comment=…`
     * but no corresponding `uiState comments=…` line fires here, the gap is
     * in the combine/StateFlow plumbing. If both fire but the row doesn't
     * appear on screen, the gap is in the composable's recomposition.
     *
     * Must live in a second init block placed AFTER [uiState] is declared.
     * With `Dispatchers.Main.immediate` (the default for `viewModelScope`),
     * `launch { ... }` can execute its body synchronously during init — if
     * this ran in the first init block, `uiState` would still be null at
     * that moment and the `.map { … }` operator would NPE.
     */
    init {
        viewModelScope.launch {
            uiState
                .map {
                    // Service emits newest-first, so [0] is the newest comment.
                    // distinctUntilChanged-on-pair fires only when either the
                    // count or the newest id changes, so an edit/reaction
                    // re-emit of the same head doesn't spam the log.
                    val newest = it.comments.firstOrNull()
                    Triple(it.comments.size, newest?.id, newest?.senderOdinId?.domainName)
                }
                .distinctUntilChanged()
                .collect { (size, newestId, newestSender) ->
                    Logger.d(tag = TAG) {
                        "uiState comments updated: moment=$momentId count=$size " +
                            "newest=$newestId sender=${newestSender ?: "self"}"
                    }
                }
        }
    }

    private data class MomentWithSharedWith(
        val moment: MomentFeedItem?,
        val sharedWith: SharedWithDisplay?,
        val recipientAvatars: List<RecipientBaseUiModel>,
    )

    /**
     * Flat per-individual recipient list for the avatar stack + (for received
     * moments) the expanded recipient list. Drawn directly from
     * `MomentFeedItem.recipients` — the underlying transit recipient list,
     * which carries individual OdinIds even when the audience picker framed
     * the post around a group. Self is filtered out so the active user
     * doesn't see their own address listed under "Shared with".
     */
    private fun resolveRecipientAvatars(
        moment: MomentFeedItem,
        contacts: List<ContactUiModel>,
        self: OdinId?,
    ): List<RecipientBaseUiModel> {
        return moment.recipients
            .filter { it != self }
            .map { odinId ->
                RecipientBaseUiModel(
                    odinId = odinId,
                    displayName = odinId.displayName(contacts),
                )
            }
    }

    private fun resolveSharedWith(
        moment: MomentFeedItem,
        groups: List<MomentGroup>,
        conversations: List<ConversationUiModel>,
        contacts: List<ContactUiModel>,
        self: OdinId?,
    ): SharedWithDisplay? {
        val source = moment.source

        // Chat-conversation source: one entry, conversation title (with a
        // localised fallback when the conversation list hasn't loaded yet).
        if (source is MomentSource.Conversation) {
            val convo = conversations.firstOrNull { it.id == source.conversationId }
            return SharedWithDisplay.Recipients(
                listOf(SharedWithEntry.Conversation(name = convo?.getDisplayName())),
            )
        }

        // Structured audience source: render the breakdown of groups +
        // individuals. Only used when the picker actually selected a group —
        // `MomentAudienceViewModel` deliberately drops this field on
        // individuals-only posts to avoid duplicating the recipients list.
        if (source is MomentSource.Audience &&
            (source.groupIds.isNotEmpty() || source.individuals.isNotEmpty())
        ) {
            val groupEntries = source.groupIds.mapNotNull { id ->
                val group = groups.firstOrNull { it.id == id } ?: return@mapNotNull null
                SharedWithEntry.Group(name = group.title, memberCount = group.members.size)
            }
            val individualEntries = source.individuals
                .filter { it != self }
                .map { SharedWithEntry.Individual(name = it.displayName(contacts)) }
            val entries = groupEntries + individualEntries
            // Mid-load: source has IDs but nothing resolved yet. Hide the row
            // transiently rather than flashing "Private" or an empty list.
            return entries.takeIf { it.isNotEmpty() }
                ?.let { SharedWithDisplay.Recipients(it) }
        }

        // No structured source (null, or empty Audience). Fall back to the
        // flat recipient list the writer always populates — this is the path
        // for individuals-only posts and for legacy moments that still carry
        // recipients but no source. Filter self so the receiver doesn't see
        // their own address listed.
        val flatRecipients = moment.recipients.filter { it != self }
        if (flatRecipients.isEmpty()) {
            // "Private" means the author kept this with no recipients. For a
            // received moment (sender != self), an empty co-recipient list
            // just means "1:1 share to you alone" — never private; emit
            // [JustYou] so the receiver gets an explicit confirmation rather
            // than an ambiguous missing row. Same "is mine" rule as the
            // uiState combine (line 155) and the feed card's isPrivate().
            val isMine = moment.senderOdinId == null ||
                (self != null && moment.senderOdinId == self)
            return if (isMine) SharedWithDisplay.Private else SharedWithDisplay.JustYou
        }
        return SharedWithDisplay.Recipients(
            flatRecipients.map { SharedWithEntry.Individual(name = it.displayName(contacts)) },
        )
    }

    /**
     * Friendly display name from the user's contacts when one is on file;
     * falls back to the raw domain. Mirrors the lookup pattern used in
     * `MessageInfoViewModel` and `MomentsRecipientLookupService`. Blank
     * contact names are skipped so a contact saved with no name doesn't
     * render as an empty pill.
     */
    private fun OdinId.displayName(contacts: List<ContactUiModel>): String {
        val contact = contacts.firstOrNull { it.odinId == this }
        return contact?.name?.takeIf { it.isNotBlank() } ?: domainName
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun onAction(action: MomentDetailUiAction) {
        when (action) {
            is MomentDetailUiAction.MediaClicked -> {
                val moment = uiState.value.moment ?: return
                val payload = moment.payloads.firstOrNull { it.key == action.payloadKey } ?: return
                val contentType = payload.contentType ?: ""

                when {
                    contentType.startsWith("image/") -> {
                        _overlay.value = FullScreenOverlay.ViewMessageData(
                            messageId = moment.id,
                            // Empty title for moments — the chat viewer renders this
                            // in its top bar; we don't have an author display name to
                            // surface here.
                            title = "",
                            userDate = Instant.fromEpochMilliseconds(moment.userDateMs),
                            // The chat viewer treats `content` as markdown for the
                            // caption — moment description is plain text but markdown
                            // tolerates it.
                            content = moment.description,
                            fileId = moment.fileId,
                            driveId = moment.driveId,
                            payloads = moment.payloads,
                            keyHeader = moment.keyHeader,
                            selectedPayloadKey = action.payloadKey,
                        )
                    }

                    contentType.startsWith("video/") ||
                            contentType == "application/vnd.apple.mpegurl" -> {
                        val ivBytes = payload.iv?.let { Base64.decode(it) }
                        // The video player needs a per-payload KeyHeader (the
                        // payload's IV + the moment's master AES key). If the IV
                        // is somehow missing, fall back to a 16-byte zero IV so
                        // the surface still renders the thumbnail; playback will
                        // fail loudly which is better than a silent no-op.
                        _overlay.value = FullScreenOverlay.VideoPlayerData(
                            fileId = moment.fileId,
                            driveId = moment.driveId,
                            payloadKey = action.payloadKey,
                            keyHeader = KeyHeader(
                                iv = ivBytes ?: ByteArray(16),
                                aesKey = moment.keyHeader.aesKey,
                            ),
                            payload = payload,
                            localFilePath = null,
                            uploadMessageId = null,
                        )
                    }

                    // Audio / document / other content types currently no-op.
                    // Add branches here if/when moments grow to support them.
                    else -> Unit
                }
            }

            MomentDetailUiAction.CloseFullScreenOverlay -> {
                _overlay.value = null
            }

            is MomentDetailUiAction.CommentDraftChanged ->
                _screenLocal.update { it.copy(commentDraft = action.text) }

            MomentDetailUiAction.PostComment -> postComment()

            is MomentDetailUiAction.StartEditComment -> {
                val target = uiState.value.comments.firstOrNull { it.id == action.commentId } ?: return
                _screenLocal.update {
                    it.copy(editingCommentId = action.commentId, editingCommentDraft = target.body)
                }
            }

            is MomentDetailUiAction.EditCommentDraftChanged ->
                _screenLocal.update { it.copy(editingCommentDraft = action.text) }

            MomentDetailUiAction.SaveCommentEdit -> saveCommentEdit()

            MomentDetailUiAction.CancelCommentEdit ->
                _screenLocal.update { it.copy(editingCommentId = null, editingCommentDraft = "") }

            is MomentDetailUiAction.ToggleReactionOnMoment -> toggleMomentReaction(action.emoji)

            is MomentDetailUiAction.ToggleReactionOnComment ->
                toggleCommentReaction(action.commentId, action.emoji)

            MomentDetailUiAction.RequestDeleteMoment ->
                _screenLocal.update { it.copy(showDeleteDialog = true) }

            MomentDetailUiAction.DismissDeleteDialog ->
                _screenLocal.update { it.copy(showDeleteDialog = false) }

            is MomentDetailUiAction.ConfirmDeleteMoment ->
                deleteMoment(action.forEveryone)

            is MomentDetailUiAction.RequestDeleteComment ->
                _screenLocal.update { it.copy(deleteCommentDialogTarget = action.commentId) }

            MomentDetailUiAction.DismissDeleteCommentDialog ->
                _screenLocal.update { it.copy(deleteCommentDialogTarget = null) }

            is MomentDetailUiAction.ConfirmDeleteComment ->
                deleteComment(action.commentId, action.forEveryone)

            is MomentDetailUiAction.ToggleSharedWithExpansion ->
                toggleSharedWithExpansion(action.expanded)

            is MomentDetailUiAction.ShareMedia -> shareMedia(action.payloadKey)

            MomentDetailUiAction.OpenReactionsSheet -> openReactionsSheet()

            MomentDetailUiAction.DismissReactionsSheet ->
                _screenLocal.update { it.copy(showReactionsSheet = false) }
        }
    }

    /**
     * Open the "who reacted" sheet and refresh the reactor list. The chip
     * preview on the detail screen reads `reactionPreview` (already live), so
     * the only thing this call adds is the per-user attribution — fresh on
     * every open so a reactor who joined while the sheet was closed still
     * appears.
     */
    private fun openReactionsSheet() {
        _screenLocal.update { it.copy(showReactionsSheet = true) }
        if (_screenLocal.value.isReactionsLoading) return
        loadReactions()
    }

    private fun loadReactions() {
        _screenLocal.update { it.copy(isReactionsLoading = true) }
        viewModelScope.launch {
            try {
                val raw = actionService.getReactionsForMoment(momentId)
                val resolved = raw.map { entry ->
                    val displayName = contactService.resolveByOdinId(entry.odinId)?.name
                        ?.takeIf { it.isNotBlank() }
                        ?: entry.odinId.domainName
                    MomentReactionUiModel(
                        odinId = entry.odinId,
                        displayName = displayName,
                        emoji = entry.emoji,
                    )
                }
                _screenLocal.update {
                    it.copy(reactions = resolved, isReactionsLoading = false)
                }
            } catch (t: Throwable) {
                Logger.e(throwable = t, tag = TAG) { "loadReactions failed: ${t.message}" }
                _screenLocal.update { it.copy(isReactionsLoading = false) }
            }
        }
    }

    /**
     * Decrypt the selected payload and write a cleartext copy into the
     * share_outbound sweep dir, then surface the path so the screen can hand
     * it to the platform share sheet. Mirrors `MediaDownloadHandler.handleShareMedia`
     * on the chat side — same KeyHeader assembly and same sequestered temp
     * dir so the cleartext copy is reaped by the cold-start + foreground sweepers.
     */
    @OptIn(ExperimentalEncodingApi::class)
    private fun shareMedia(payloadKey: String) {
        val moment = uiState.value.moment ?: return
        val payload = moment.payloads.firstOrNull { it.key == payloadKey } ?: return
        val ivString = payload.iv ?: run {
            Logger.e(tag = TAG) { "shareMedia: payload $payloadKey has no IV" }
            _events.tryEmit(MomentDetailUiEvent.ShareFailed("Payload missing key header"))
            return
        }
        viewModelScope.launch {
            try {
                val payloadIv = Base64.decode(ivString)
                val response = driveFileProvider.getPayloadBytesDecrypted(
                    driveId = moment.driveId,
                    fileId = moment.fileId,
                    key = payloadKey,
                    keyHeader = KeyHeader(payloadIv, moment.keyHeader.aesKey),
                )
                val bytes = response?.bytes
                if (bytes == null) {
                    _events.tryEmit(MomentDetailUiEvent.ShareFailed("Could not download file"))
                    return@launch
                }
                val extension = payload.contentType?.let { extensionForMimeType(it) }
                    ?: payload.contentType?.substringAfter("/")
                    ?: "bin"
                val tempPath = fileOperationsProvider.writeBytesToShareOutboundFile(
                    bytes = bytes,
                    suffix = ".$extension",
                )
                _events.tryEmit(MomentDetailUiEvent.ShareFileReady(tempPath))
            } catch (t: Throwable) {
                Logger.e(throwable = t, tag = TAG) { "shareMedia failed: ${t.message}" }
                _events.tryEmit(MomentDetailUiEvent.ShareFailed(t.message))
            }
        }
    }

    /**
     * Flip the expansion state and, on the first open against an authored
     * moment, kick off the transfer-history fetch. Subsequent opens reuse the
     * already-loaded rows — the data is a snapshot from the server, but we
     * deliberately don't poll: the user can close+reopen to refresh.
     *
     * Gated on `isMine` because the server only returns transfer history to
     * the file's author. For received moments the row collapses/expands but
     * never fetches.
     */
    private fun toggleSharedWithExpansion(expanded: Boolean) {
        _screenLocal.update { it.copy(sharedWithExpanded = expanded) }
        if (!expanded) return

        val current = uiState.value
        if (!current.isMine) return
        if (_screenLocal.value.transferHistoryLoaded) return
        if (_screenLocal.value.isTransferHistoryLoading) return

        val moment = current.moment ?: return
        loadTransferHistory(driveId = moment.driveId, fileId = moment.fileId)
    }

    private fun loadTransferHistory(driveId: Uuid, fileId: Uuid) {
        _screenLocal.update { it.copy(isTransferHistoryLoading = true) }
        viewModelScope.launch {
            try {
                val history = driveFileProvider.getTransferHistory(driveId, fileId)
                val entries = history?.history?.results.orEmpty().map { entry ->
                    val odinId = OdinId(entry.recipient)
                    val displayName = contactService.resolveByOdinId(odinId)?.name
                        ?.takeIf { it.isNotBlank() }
                        ?: odinId.domainName
                    RecipientDeliveryUiModel(
                        odinId = entry.recipient,
                        displayName = displayName,
                        deliveryStatus = entry.toChatDeliveryStatus(),
                        errorDetailRes = entry.latestTransferStatus.toErrorDetailRes(),
                    )
                }
                _screenLocal.update {
                    it.copy(
                        recipientDeliveries = entries,
                        isTransferHistoryLoading = false,
                        transferHistoryLoaded = true,
                    )
                }
            } catch (t: Throwable) {
                Logger.e(throwable = t, tag = TAG) {
                    "loadTransferHistory failed driveId=$driveId fileId=$fileId: ${t.message}"
                }
                // Mark not-loaded so the next expand retries — a transient
                // network hiccup shouldn't permanently hide the rows.
                _screenLocal.update {
                    it.copy(
                        isTransferHistoryLoading = false,
                        transferHistoryLoaded = false,
                    )
                }
            }
        }
    }

    private fun toggleMomentReaction(emoji: String) {
        viewModelScope.launch {
            try {
                actionService.toggleReactionOnMoment(momentId, emoji)
            } catch (t: Throwable) {
                Logger.e(throwable = t, tag = TAG) {
                    "toggleReactionOnMoment failed: ${t.message}"
                }
                _events.tryEmit(MomentDetailUiEvent.ReactionFailed(t.message))
            }
        }
    }

    private fun toggleCommentReaction(commentId: Uuid, emoji: String) {
        viewModelScope.launch {
            try {
                actionService.toggleReactionOnComment(commentId, emoji)
            } catch (t: Throwable) {
                Logger.e(throwable = t, tag = TAG) {
                    "toggleReactionOnComment failed: ${t.message}"
                }
                _events.tryEmit(MomentDetailUiEvent.ReactionFailed(t.message))
            }
        }
    }

    private fun postComment() {
        val local = _screenLocal.value
        val body = local.commentDraft.trim()
        if (body.isEmpty() || local.isPostingComment) return

        _screenLocal.update { it.copy(isPostingComment = true) }
        viewModelScope.launch {
            try {
                postSender.postComment(
                    momentId = momentId,
                    attachments = emptyList(),
                    body = body,
                )
                _screenLocal.update { it.copy(commentDraft = "", isPostingComment = false) }
            } catch (t: Throwable) {
                Logger.e(throwable = t, tag = TAG) { "postComment failed: ${t.message}" }
                _screenLocal.update { it.copy(isPostingComment = false) }
                _events.tryEmit(MomentDetailUiEvent.CommentPostFailed(t.message))
            }
        }
    }

    private fun saveCommentEdit() {
        val local = _screenLocal.value
        val commentId = local.editingCommentId ?: return
        val body = local.editingCommentDraft.trim()
        if (body.isEmpty() || local.isSavingCommentEdit) return

        // The version tag is needed to submit; if we don't have one yet (still
        // optimistic), bail rather than racing with the in-flight initial post.
        val current = uiState.value.comments.firstOrNull { it.id == commentId }
        val versionTag = current?.versionTag
        if (versionTag == null) {
            _events.tryEmit(
                MomentDetailUiEvent.CommentEditFailed(
                    "Comment is still being posted — try again in a moment."
                )
            )
            return
        }

        _screenLocal.update { it.copy(isSavingCommentEdit = true) }
        viewModelScope.launch {
            try {
                postSender.updateComment(
                    commentUniqueId = commentId,
                    versionTag = versionTag,
                    body = body,
                )
                _screenLocal.update {
                    it.copy(
                        editingCommentId = null,
                        editingCommentDraft = "",
                        isSavingCommentEdit = false,
                    )
                }
            } catch (t: Throwable) {
                Logger.e(throwable = t, tag = TAG) { "updateComment failed: ${t.message}" }
                _screenLocal.update { it.copy(isSavingCommentEdit = false) }
                _events.tryEmit(MomentDetailUiEvent.CommentEditFailed(t.message))
            }
        }
    }

    private fun deleteComment(commentId: Uuid, forEveryone: Boolean) {
        if (_screenLocal.value.deletingCommentIds.contains(commentId)) return
        _screenLocal.update {
            it.copy(
                deleteCommentDialogTarget = null,
                deletingCommentIds = it.deletingCommentIds + commentId,
            )
        }
        viewModelScope.launch {
            try {
                actionService.deleteComment(commentId, deleteForEveryone = forEveryone)
            } catch (t: Throwable) {
                Logger.e(throwable = t, tag = TAG) { "deleteComment failed: ${t.message}" }
                _events.tryEmit(MomentDetailUiEvent.CommentDeleteFailed(t.message))
            } finally {
                // Clear the in-flight marker either way — on success the comment
                // has already been dropped from the comments list by the
                // optimistic writer, so the row is gone; on failure the row
                // is still visible and should accept further input.
                _screenLocal.update {
                    it.copy(deletingCommentIds = it.deletingCommentIds - commentId)
                }
            }
        }
    }

    private fun deleteMoment(forEveryone: Boolean) {
        if (_screenLocal.value.isDeletingMoment) return
        _screenLocal.update { it.copy(isDeletingMoment = true, showDeleteDialog = false) }
        viewModelScope.launch {
            try {
                actionService.deleteMoment(momentId, deleteForEveryone = forEveryone)
                // Optimistic delete already removed the moment from the feed;
                // surface the event so the screen pops back to the feed
                // without waiting for a follow-up state read.
                _events.tryEmit(MomentDetailUiEvent.MomentDeleted)
            } catch (t: Throwable) {
                Logger.e(throwable = t, tag = TAG) { "deleteMoment failed: ${t.message}" }
                _screenLocal.update { it.copy(isDeletingMoment = false) }
                _events.tryEmit(MomentDetailUiEvent.DeleteFailed(t.message))
            }
        }
    }
}
