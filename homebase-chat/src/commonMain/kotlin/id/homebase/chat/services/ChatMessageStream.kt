package id.homebase.chat.services

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.auth.OwnerSessionRepository
import id.homebase.api.client.drives.FileStateFilter
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.query.QueryBatchCursor
import id.homebase.api.client.drives.query.TimeRowCursor
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.client.withRetry
import id.homebase.api.common.BatchResult
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.QueryBatch
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.groodle.GroodleVote
import id.homebase.chat.poll.PollVote
import id.homebase.chat.services.content.MessageContent
import id.homebase.chat.services.convo.contact.ContactService
import id.homebase.chat.services.outbox.OptimisticWriter
import id.homebase.core.config.chatTargetDrive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

class ChatMessageStream(
    private val credentialsManager: CredentialsManager,
    private val contactService: ContactService,
    private val ownerSessionRepository: OwnerSessionRepository,
    private val dbm: DatabaseManager,
    private val eventBus: EventBus,
    private val scope: CoroutineScope,
    private val driveFileProvider: DriveFileProvider,
    private val optimisticWriter: OptimisticWriter
) : MessageLookup {
    /** Set by ConversationStream to let us skip messages for left conversations. */
    var isConversationLeft: (Uuid) -> Boolean = { false }

    /**
     * Auto-pin hook (#887). Wired at construction in DI to
     * [id.homebase.chat.services.ChatMessageActionService.pinMessage]; a settable
     * hook (like [isConversationLeft]) avoids a circular DI dependency
     * (ActionService → MessageLookup → ChatMessageStream). Wired at construction —
     * NOT in onPostAuthenticated, which is dropped on a warm relaunch / session
     * restore and left auto-pin permanently no-op. No-op only in tests that skip DI.
     */
    var autoPinTypedMessage: suspend (messageId: Uuid, dependencyUniqueId: Uuid?) -> Unit =
        { _, _ -> }

    // Messages whose auto-pin candidacy has already been decided this session.
    // Guards against re-pinning a manually-unpinned message when an unrelated
    // update (reaction toggle, edit, delivery-status) re-emits the same file via
    // BatchReceived. Cleared on logout. Silent cross-restart re-syncs go through
    // DriveEvent.Stopped (not BatchReceived), so they never reach the auto-pin pass.
    private val autoPinHandled = mutableSetOf<Uuid>()

    private val paginatedState = PaginatedConversationState()
    private val chatDrive = chatTargetDrive.alias

    // Per-conversation pinned-messages bar state (newest-first). Recomputed off the
    // same signals that refresh message windows: loadConversation, processIncrementalBatch
    // (covers both peer arrivals and own optimistic pin/unpin writes, which emit
    // BatchReceived) and DriveEvent.Stopped (silent cross-device sync). Keyed by
    // conversationId; only conversations the user has opened ever get an entry.
    private val _pinnedMessages = MutableStateFlow<Map<Uuid, List<MessageUiModel>>>(emptyMap())

    // Messages for open conversations are loaded on demand via loadConversation().
    // Two paths converge on paginatedState afterward:
    //   1) Live WS pushes arrive as DataEvent.BatchReceived → processIncrementalBatch.
    //   2) Silent DriveSync (no per-batch BatchReceived) finishes with
    //      DriveEvent.Stopped(totalCount > 0) → refreshCachedWindows
    //      re-queries the newest page from the DB for every cached window.
    //      Gated on totalCount > 0 only — NOT on result == Success — because
    //      Stopped(Aborted, totalCount > 0) still means earlier batches'
    //      writes landed cleanly. Mirrors ConversationStream's post-Stopped
    //      reload.
    init {
        scope.launch {
            eventBus.events.collect { event ->
                when (event) {
                    // Logout: drop cached message windows for the previous identity.
                    is BackendEvent.SessionEnded -> {
                        paginatedState.reset()
                        _pinnedMessages.value = emptyMap()
                        autoPinHandled.clear()
                    }
                    is BackendEvent.OutboxEvent.OptimisticRollback -> {
                        if (event.driveId == chatDrive) {
                            paginatedState.removeMessage(event.uniqueId)
                        }
                    }

                    // The outbox permanently gave up on this item (permanent failure
                    // or 20 retries exhausted). For a message that means it will never
                    // arrive back from the server to clear isPendingSendTag — so the
                    // bubble would spin forever. Swap the pending tag for the failed
                    // tag so the bubble shows the error icon. Conversation/admin file
                    // drops are ignored in markSendFailed (no bubble).
                    is BackendEvent.OutboxEvent.OutboxItemDropped -> {
                        if (event.driveId == chatDrive) {
                            scope.launch { markSendFailed(event.uniqueId) }
                        }
                    }

                    is BackendEvent.DriveEvent.Stopped -> {
                        if (event.driveId != chatDrive) return@collect
                        Logger.d("ChatMessageStream: Stopped(totalCount=${event.totalCount}, result=${event.result})")
                        // Silent-DriveSync contract: the chat-drive DriveSync just
                        // landed N files in DriveMainIndex with no per-batch
                        // BatchReceived emits. Re-query the newest page for every
                        // open conversation window so the in-memory windows
                        // converge to the post-sync snapshot.
                        //
                        // Gate on totalCount > 0 ALONE (not on result == Success):
                        // DriveSync.performSync increments totalCount per batch,
                        // and earlier batches' DB writes have already completed
                        // by the time a later batch fails. So a Stopped(Aborted)
                        // with totalCount > 0 still means real rows landed in
                        // DriveMainIndex — we want to surface those rather than
                        // wait for the next round (which on PermissionDenied may
                        // never come). totalCount == 0 means cursor at HEAD: no
                        // DB change, nothing to refresh.
                        if (event.totalCount > 0) {
                            scope.launch { refreshCachedWindows() }
                        }
                    }

                    is BackendEvent.DataEvent.BatchReceived -> {
                        if (event.driveId != chatDrive) return@collect
                        // Every BatchReceived is a live WS-push event (DriveSync is
                        // silent). The window gate inside processIncrementalBatch
                        // no-ops batches for conversations the user hasn't opened.
                        processIncrementalBatch(event.batchData)
                    }

                    // Started / Progress are state-machine signals we don't act on.
                    else -> {}
                }
            }
        }
    }

    // ---------- PUBLIC API ----------

    fun observeMessages(conversationId: Uuid): StateFlow<ChatMessagesData> =
        paginatedState
            .windows
            .map { windows ->
                val window = windows[conversationId] ?: MessageWindow()
                ChatMessagesData.Messages(window)
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), ChatMessagesData.Initializing)

    fun hasCachedMessages(conversationId: Uuid): Boolean =
        paginatedState.hasCachedMessages(conversationId)

    /**
     * Reactive pinned-messages bar for [conversationId] (newest-first). Updates
     * whenever a pin/unpin lands (own optimistic write or cross-device sync) for
     * an open conversation. Empty until [loadConversation] runs.
     */
    fun observePinnedMessages(conversationId: Uuid): StateFlow<List<MessageUiModel>> =
        _pinnedMessages
            .map { it[conversationId].orEmpty() }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** One-shot read of the pinned messages for [conversationId] (newest-first). */
    suspend fun getPinnedMessages(conversationId: Uuid): List<MessageUiModel> {
        val c = credentialsManager.requireActiveCredentials()
        val jsonHeaders = dbm.driveLocalTagIndex.selectJsonHeadersByLocalTagInGroup(
            identityId = c.getIdentityId(),
            driveId = chatDrive,
            tagId = ChatProtocol.MessagePinnedTag,
            groupId = conversationId,
        )
        return withContext(Dispatchers.Default) {
            jsonHeaders.mapNotNull { json ->
                val header = runCatching {
                    OdinSystemSerializer.deserialize<HomebaseFile>(json)
                }.getOrNull() ?: return@mapNotNull null
                mapToMessageData(header, credentialsManager, ::resolveDisplayName)
            }
        }
    }

    /** Recompute and publish the pinned bar for one conversation. */
    private suspend fun refreshPinnedFor(conversationId: Uuid) {
        val pinned = getPinnedMessages(conversationId)
        _pinnedMessages.update { it + (conversationId to pinned) }
    }

    // Full message load from local DB for a single conversation.
    // Called when the user opens a conversation (ConversationListViewModel.selectConversation).
    // Do NOT call from DriveEvent.Stopped or other sync events — see init block above.
    //
    // Loads the latest [PaginatedConversationState.PAGE_SIZE] messages and stores
    // them as a window with hasOlderMessages = result.hasMoreRows. Older messages
    // are paged in via loadOlderMessages when the user scrolls near the top.
    suspend fun loadConversation(conversationId: Uuid) {
        val start = TimeSource.Monotonic.markNow()
        Logger.d("ChatMessageStream: loadConversation($conversationId)")
        val result = fetchMessages(
            conversationId = conversationId,
            limit = PaginatedConversationState.PAGE_SIZE,
        )
        val elapsed = start.elapsedNow()
        Logger.d("ChatMessageStream: loadConversation($conversationId) → ${result.records.size} messages in $elapsed (hasMore=${result.hasMoreRows})")
        paginatedState.setInitialWindow(
            conversationId = conversationId,
            messages = result.records,
            olderCursor = result.cursor,
            hasOlderMessages = result.hasMoreRows,
        )
        refreshPinnedFor(conversationId)
    }

    /**
     * Extend the loaded window with the next page of OLDER messages, using
     * [MessageWindow.olderCursor] as the boundary. No-op if the window
     * isn't loaded, no older page is available, or a previous load is still
     * in flight.
     */
    suspend fun loadOlderMessages(conversationId: Uuid) {
        val window = paginatedState.getWindow(conversationId) ?: run {
            Logger.d(tag = "ChatPaging") { "loadOlder($conversationId) skip: no window" }
            return
        }
        if (window.isLoadingOlder || !window.hasOlderMessages) {
            Logger.d(tag = "ChatPaging") {
                "loadOlder($conversationId) skip: isLoadingOlder=${window.isLoadingOlder} hasOlder=${window.hasOlderMessages}"
            }
            return
        }
        val sizeBefore = window.messages.size
        paginatedState.setLoadingOlder(conversationId, true)
        try {
            val start = TimeSource.Monotonic.markNow()
            val result = fetchMessages(
                conversationId = conversationId,
                limit = PaginatedConversationState.PAGE_SIZE,
                cursor = window.olderCursor,
                sortOrder = QueryBatchSortOrder.NewestFirst,
            )
            paginatedState.prependOlderMessages(
                conversationId = conversationId,
                olderMessages = result.records,
                olderCursor = result.cursor,
                hasMore = result.hasMoreRows,
            )
            val after = paginatedState.getWindow(conversationId)
            Logger.d(tag = "ChatPaging") {
                "loadOlder($conversationId) +${result.records.size} hasMore=${result.hasMoreRows} " +
                    "windowSize=$sizeBefore→${after?.messages?.size} " +
                    "hasNewer=${after?.hasNewerMessages} took=${start.elapsedNow()}"
            }
        } catch (t: Throwable) {
            paginatedState.setLoadingOlder(conversationId, false)
            throw t
        }
    }

    /**
     * Extend the loaded window with the next page of NEWER messages, using
     * [MessageWindow.newerCursor] as the boundary. No-op if the window
     * isn't loaded, no newer page is available, or a previous load is still
     * in flight.
     */
    suspend fun loadNewerMessages(conversationId: Uuid) {
        val window = paginatedState.getWindow(conversationId) ?: run {
            Logger.d(tag = "ChatPaging") { "loadNewer($conversationId) skip: no window" }
            return
        }
        if (window.isLoadingNewer || !window.hasNewerMessages) {
            Logger.d(tag = "ChatPaging") {
                "loadNewer($conversationId) skip: isLoadingNewer=${window.isLoadingNewer} hasNewer=${window.hasNewerMessages}"
            }
            return
        }
        val sizeBefore = window.messages.size
        paginatedState.setLoadingNewer(conversationId, true)
        try {
            val start = TimeSource.Monotonic.markNow()
            val result = fetchMessages(
                conversationId = conversationId,
                limit = PaginatedConversationState.PAGE_SIZE,
                cursor = window.newerCursor,
                sortOrder = QueryBatchSortOrder.OldestFirst,
            )
            paginatedState.appendNewerMessages(
                conversationId = conversationId,
                newerMessages = result.records,
                newerCursor = result.cursor,
                hasMore = result.hasMoreRows,
            )
            val after = paginatedState.getWindow(conversationId)
            Logger.d(tag = "ChatPaging") {
                "loadNewer($conversationId) +${result.records.size} hasMore=${result.hasMoreRows} " +
                    "windowSize=$sizeBefore→${after?.messages?.size} " +
                    "hasOlder=${after?.hasOlderMessages} took=${start.elapsedNow()}"
            }
        } catch (t: Throwable) {
            paginatedState.setLoadingNewer(conversationId, false)
            throw t
        }
    }

    /**
     * True when [messageId] is present in the cached in-memory window for
     * [conversationId]. Lets a jump-to-message caller decide whether it needs a
     * re-seeded (centered) window — see [loadConversationAroundMessage] — or can
     * reuse the existing one. Returns false when the conversation has no window
     * cached at all.
     */
    fun isMessageInWindow(conversationId: Uuid, messageId: Uuid): Boolean =
        paginatedState.getWindow(conversationId)?.messages?.any { it.id == messageId } == true

    /**
     * Load a centered page of messages around [messageUniqueId]. Used to
     * restore scroll position on conversation open and to jump to a search
     * result (or album item) that's outside the current window. If the target
     * message isn't in the local DB (purged, never synced), falls back to
     * [loadConversation].
     *
     * Returns true when the anchor was found and a window centered on it was
     * built, false when it fell back to the latest page. Callers performing an
     * explicit jump use the false result to tell the user the target is gone,
     * rather than silently landing on the latest page.
     */
    suspend fun loadConversationAroundMessage(conversationId: Uuid, messageUniqueId: Uuid): Boolean {
        val start = TimeSource.Monotonic.markNow()
        val target = getMessage(messageUniqueId) ?: run {
            Logger.d(tag = "ChatPaging") {
                "loadAround($conversationId, $messageUniqueId) anchor not in DB; falling back to loadConversation"
            }
            loadConversation(conversationId)
            return false
        }
        val halfPage = PaginatedConversationState.PAGE_SIZE / 2
        val olderHalfCursor = QueryBatchCursor(
            paging = TimeRowCursor(UnixTimeUtc(target.userDate), 0L)
        )
        val olderHalf = fetchMessages(
            conversationId = conversationId,
            limit = halfPage,
            cursor = olderHalfCursor,
            sortOrder = QueryBatchSortOrder.NewestFirst,
        )
        val newerHalfCursor = QueryBatchCursor(
            paging = TimeRowCursor(UnixTimeUtc(target.userDate), Long.MAX_VALUE)
        )
        val newerHalf = fetchMessages(
            conversationId = conversationId,
            limit = halfPage,
            cursor = newerHalfCursor,
            sortOrder = QueryBatchSortOrder.OldestFirst,
        )
        val combined = (olderHalf.records + listOf(target) + newerHalf.records)
            .distinctBy { it.id }
        paginatedState.setInitialWindow(
            conversationId = conversationId,
            messages = combined,
            olderCursor = olderHalf.cursor,
            hasOlderMessages = olderHalf.hasMoreRows,
            newerCursor = newerHalf.cursor,
            hasNewerMessages = newerHalf.hasMoreRows,
        )
        Logger.d(tag = "ChatPaging") {
            "loadAround($conversationId, $messageUniqueId) older=${olderHalf.records.size}+anchor+newer=${newerHalf.records.size} " +
                "windowSize=${combined.size} hasOlder=${olderHalf.hasMoreRows} hasNewer=${newerHalf.hasMoreRows} took=${start.elapsedNow()}"
        }
        return true
    }

    /**
     * Re-query the newest page for every cached conversation window and
     * upsert the results into [paginatedState]. Called from the
     * [BackendEvent.DriveEvent.Stopped] handler — `DriveSync.performSync`
     * is silent (no `BatchReceived` events), so the in-memory windows
     * would otherwise stay stale across a sync round.
     *
     * Skips windows where the user has scrolled deep into history
     * (`hasNewerMessages == true`): merging the latest page into such a
     * window would leave a gap. Those windows refresh naturally when the
     * user scrolls back to the latest page (`loadNewerMessages` pulls the
     * intervening pages).
     *
     * Uses [PaginatedConversationState.upsert] (idempotent by message id)
     * rather than [PaginatedConversationState.setInitialWindow] so the
     * caller's scroll position, isLoading flags, and cursors are
     * preserved.
     */
    private suspend fun refreshCachedWindows() {
        val snapshot = paginatedState.windows.value
        if (snapshot.isEmpty()) return
        for ((conversationId, window) in snapshot) {
            if (window.hasNewerMessages) {
                Logger.d("ChatMessageStream: refreshCachedWindows skip convo=$conversationId (paged into history)")
                continue
            }
            try {
                val result = fetchMessages(
                    conversationId = conversationId,
                    limit = PaginatedConversationState.PAGE_SIZE,
                )
                if (result.records.isEmpty()) continue
                paginatedState.upsert(conversationId, result.records)
                refreshPinnedFor(conversationId)
            } catch (t: Throwable) {
                Logger.e(t) { "ChatMessageStream: refreshCachedWindows convo=$conversationId failed: ${t.message}" }
            }
        }
    }

// ---------- EVENT HANDLING ----------

    private suspend fun processIncrementalBatch(files: List<HomebaseFile>) {
        val messages = withContext(Dispatchers.Default) {
            files
                .filter { it.fileMetadata.appData.fileType == ChatProtocol.MessageFileType }
                .mapNotNull { mapToMessageData(it, credentialsManager, ::resolveDisplayName) }
        }

        val grouped = messages.groupBy { it.conversationId }
        Logger.d("ChatMessageStream: processIncrementalBatch ${messages.size} messages across ${grouped.size} conversation(s)")
        grouped.forEach { (conversationId, msgs) ->
            if (isConversationLeft(conversationId)) return@forEach
            val window = paginatedState.getWindow(conversationId) ?: return@forEach
            // Gate: when the user has paged backwards (hasNewerMessages == true), the
            // window doesn't include the latest messages. Stream events that arrive while
            // the user is deep in history would land out of order; defer them until the
            // user explicitly returns to the latest page (FAB → ScrollToLatest in PR-B).
            // In PR-A hasNewerMessages is always false, so this gate is a no-op.
            if (window.hasNewerMessages) return@forEach
            // Evict stale entries where the server returned a file whose id (uniqueId)
            // changed (e.g. cleared on delete, so id falls back to fileId). Without this,
            // the old entry (keyed by uniqueId) and new entry (keyed by fileId) both exist.
            for (msg in msgs) {
                if (msg.id == msg.fileId) {
                    paginatedState.removeByFileId(conversationId, msg.fileId)
                }
            }
            paginatedState.upsert(conversationId, msgs)
        }

        autoPinNewTypedMessages(messages)

        // Refresh the pinned bar for every affected OPEN conversation. Done off the
        // window gate above (which defers history-paged windows) because a pin/unpin
        // must surface in the bar regardless of the scroll window. Covers own
        // optimistic pin/unpin writes (they emit BatchReceived) and peer arrivals.
        for (conversationId in grouped.keys) {
            if (isConversationLeft(conversationId)) continue
            if (paginatedState.getWindow(conversationId) == null) continue
            refreshPinnedFor(conversationId)
        }
    }

    /**
     * Auto-pin (#887) freshly arrived/sent typed messages — Poll/Event/Groodle,
     * and live-location only while its share is active. Runs for ALL conversations
     * in the batch (not just open ones), so the pin is already present when the
     * user opens the conversation.
     *
     * "Newly written" = first sighting this session ([autoPinHandled]). An already-
     * pinned message is just remembered and skipped (idempotent); a manually
     * unpinned message is never re-pinned because later BatchReceived for the same
     * file are gated by the set, and silent cross-restart re-syncs don't reach here
     * (they arrive via DriveEvent.Stopped, not BatchReceived).
     *
     * For an own optimistic send (`isPendingSend`), the tags update MUST run AFTER
     * the create (UploadNewFile, uniqueId == messageId) — dependencyUniqueId =
     * messageId chains it. The sender enqueues that create before emitting the
     * BatchReceived that triggers this pass, so the dependency row already exists.
     * Without the chain the tags update would hit the server before the file and
     * DriveOutboxUploader would drop it as NotFound (versionTag is null pre-create).
     */
    private suspend fun autoPinNewTypedMessages(messages: List<MessageUiModel>) {
        if (messages.isEmpty()) return
        val now = Clock.System.now().toEpochMilliseconds()
        for (msg in messages) {
            if (msg.isDeleted) continue
            if (msg.isPinned) {
                autoPinHandled.add(msg.id)
                continue
            }
            if (!autoPinHandled.add(msg.id)) continue

            if (!shouldAutoPin(msg.messageContent, msg.ownReactions, now)) continue

            val dependency = if (msg.isPendingSend) msg.id else null
            try {
                autoPinTypedMessage(msg.id, dependency)
            } catch (t: Throwable) {
                Logger.e(t) { "ChatMessageStream: auto-pin failed for ${msg.id}: ${t.message}" }
            }
        }
    }

    override suspend fun getMessage(messageId: Uuid): MessageUiModel? {
        // Diagnostic (debug aid): getMessage returning null is invisible at the call site (e.g. a blank
        // Message Info screen with no id/times). Log the two null paths — DB row missing vs.
        // present-but-unmappable (with the fields that decide mapping) — so the cause is a single log
        // read, not guesswork. Only fires on the anomaly path, so no steady-state spam.
        val messageFile = getMessageFile(messageId)
        if (messageFile == null) {
            Logger.w(tag = "MsgLookup") { "getMessage: no DB row for uniqueId=$messageId" }
            return null
        }
        val mapped = mapToMessageData(messageFile, credentialsManager, ::resolveDisplayName)
        if (mapped == null) {
            val a = messageFile.fileMetadata.appData
            Logger.w(tag = "MsgLookup") {
                "getMessage: row found but mapped to null uniqueId=$messageId " +
                    "fileType=${a.fileType} dataType=${a.dataType} groupId=${a.groupId} appUid=${a.uniqueId}"
            }
        }
        return mapped
    }

    override suspend fun getMessageFile(messageId: Uuid): HomebaseFile? {
        val c = credentialsManager.requireActiveCredentials()
        return dbm.driveMainIndex.selectHomebaseFileByUnique(
            c.getIdentityId(),
            chatDrive,
            messageId
        )
    }

    /**
     * An outbox item for [uniqueId] was permanently dropped. If it's a chat *message*,
     * swap its [ChatProtocol.isPendingSendTag] for [ChatProtocol.isFailedSendTag] so the
     * bubble shows the Failed icon instead of spinning forever.
     *
     * Non-message drops are ignored: a dropped conversation file
     * (`uniqueId == conversationId`) or admin file has no message bubble; its dependent
     * messages each surface their own outcome via this same handler when they drop.
     */
    private suspend fun markSendFailed(uniqueId: Uuid) {
        val file = getMessageFile(uniqueId) ?: return
        if (file.fileMetadata.appData.fileType != ChatProtocol.MessageFileType) return

        val existingTags = file.fileMetadata.localAppData?.tags.orEmpty()
        // Already terminal — nothing to do (avoids a redundant upsert/BatchReceived).
        if (ChatProtocol.isFailedSendTag in existingTags) return

        val newTags = existingTags
            .filterNot { it == ChatProtocol.isPendingSendTag } + ChatProtocol.isFailedSendTag
        optimisticWriter.updateLocalTags(chatDrive, uniqueId, newTags)
    }

    /** Walk the open conversations' in-memory message lists for a model whose
     *  `id == messageId` and return its `fileId`. The hit rate is effectively
     *  100% for messageId-keyed actions invoked from a visible message
     *  (toggleReaction's optimistic path is fileId-keyed and bypasses this
     *  entirely; the remaining call sites — getReactions, deleteMessage's
     *  fall-through — operate on a message the user just interacted with).
     *
     *  The scan is over distinct *open* conversations only, which is one for
     *  the active conversation in steady state. Returns `null` (caller must
     *  fall through to DB) if the message isn't loaded — including the case
     *  where it exists on disk but its conversation hasn't been opened.
     */
    override fun findCachedFileId(messageId: Uuid): Uuid? {
        val open = paginatedState.windows.value
        for ((_, window) in open) {
            for (m in window.messages) {
                if (m.id == messageId) return m.fileId
            }
        }
        return null
    }

    suspend fun fetchMessages(
        conversationId: Uuid,
        limit: Int = 1000,
        cursor: QueryBatchCursor? = null,
        sortOrder: QueryBatchSortOrder = QueryBatchSortOrder.NewestFirst,
        /**
         * Optional header-level dataType filter (e.g. [ChatProtocol.ChatLocationMessageDataType]).
         * When set, the SQL query returns only messages stamped with one of these
         * dataTypes — lets callers page a single message kind (locations, dice…)
         * straight from the index instead of scanning every message client-side.
         */
        datatypesAnyOf: List<Int>? = null,
    ): BatchResult<MessageUiModel> {

        val c = credentialsManager.requireActiveCredentials()
        val queryBatch = QueryBatch(c.getIdentityId())

        // Note-to-self skips soft-deleted rows at SQL: a "you deleted your own
        // message" tombstone has no value and dropping them at the index level
        // is the perf win this PR was after. Regular conversations keep
        // tombstones (chat convention — peer-deleted messages render as a
        // "Deleted File" placeholder via MessageMapper's deleted-file branch).
        val fileStateFilter =
            if (conversationId == ChatProtocol.ConversationWithYourselfId) FileStateFilter.Active
            else FileStateFilter.All

        val queryStart = TimeSource.Monotonic.markNow()
        val result =
            queryBatch.queryBatchAsync(
                dbm = dbm,
                driveId = chatDrive,
                noOfItems = limit,
                cursor = cursor,
                sortOrder = sortOrder,
                sortField = QueryBatchSortField.UserDate,
                fileSystemType = 0,
                fileState = fileStateFilter,
                filetypesAnyOf = listOf(ChatProtocol.MessageFileType),
                datatypesAnyOf = datatypesAnyOf,
                groupIdAnyOf = listOf(conversationId)
            )
        val queryElapsed = queryStart.elapsedNow()

        val mapStart = TimeSource.Monotonic.markNow()
        val records = withContext(Dispatchers.Default) {
            result.records.mapNotNull { header ->
                mapToMessageData(header, credentialsManager, ::resolveDisplayName)
            }
        }
        val mapElapsed = mapStart.elapsedNow()

        if (queryElapsed + mapElapsed > 200.milliseconds) {
            // dbQuery (queue-wait + SQL) and mapping are measured locally here, so both are
            // accurate. The queue-wait-vs-SQL split for THIS exact query is in the paired
            // SlowDbRead line that executeReadQuery emits — we deliberately no longer read
            // DatabaseManager.lastReadTiming, a single shared cell a concurrent read can
            // clobber (it produced a garbled queueWait here).
            Logger.w(tag = "SlowMessageFetch") {
                "conversationId=$conversationId " +
                        "rawRecords=${result.records.size} " +
                        "mappedRecords=${records.size} " +
                        "dbQuery=$queryElapsed " +
                        "mapping=$mapElapsed"
            }
        }

        return BatchResult(
            records = records,
            hasMoreRows = result.hasMoreRows,
            cursor = result.cursor
        )
    }

    suspend fun searchMessages(
        searchQuery: String,
        limit: Int = 1000,
        cursor: QueryBatchCursor? = null
    ): BatchResult<MessageUiModel> {

        val c = credentialsManager.requireActiveCredentials()
        val queryBatch = QueryBatch(c.getIdentityId())

        // TODO - inject searchQuery into actual db query
        val result =
            queryBatch.queryBatchAsync(
                dbm = dbm,
                driveId = chatDrive,
                noOfItems = limit,
                cursor = cursor,
                sortOrder = QueryBatchSortOrder.NewestFirst,
                sortField = QueryBatchSortField.UserDate,
                fileSystemType = 0,
                filetypesAnyOf = listOf(ChatProtocol.MessageFileType),
            )

        // TODO - remove simple content search filter when actual query does filtering
        return BatchResult(
            records = withContext(Dispatchers.Default) {
                result.records
                    .filter {
                        it.fileMetadata.appData.content?.contains(
                            searchQuery,
                            ignoreCase = true
                        ) == true
                    }
                    .mapNotNull { mapToMessageData(it, credentialsManager, ::resolveDisplayName) }
            },
            hasMoreRows = result.hasMoreRows,
            cursor = result.cursor
        )
    }

    override suspend fun getMessages(
        messageIds: List<Uuid>,
        conversationId: Uuid,
    ): BatchResult<MessageUiModel> {

        // Empty input is a no-op — no need to round-trip the driver. Note this
        // also dodges the QueryBatch `uniqueIdAnyOf = emptyList()` shape that
        // would otherwise emit a `uniqueId IN ()` clause SQLite rejects.
        if (messageIds.isEmpty()) {
            return BatchResult(
                records = emptyList(),
                hasMoreRows = false,
                cursor = QueryBatchCursor(),
            )
        }

        val c = credentialsManager.requireActiveCredentials()
        val queryBatch = QueryBatch(c.getIdentityId())

        // Passing groupIdAnyOf alongside uniqueIdAnyOf makes the chat-shape
        // branch in QueryBatch (kt:262) fire, pinning idx_chatmessage_convid_userDate
        // via INDEXED BY — the lookup becomes a tight per-conversation seek
        // rather than the global CreatedDate scan the planner picks without
        // ANALYZE statistics. See MessageLookup.getMessages for context.
        val result =
            queryBatch.queryBatchAsync(
                dbm = dbm,
                driveId = chatDrive,
                noOfItems = messageIds.size,
                cursor = null,
                sortOrder = QueryBatchSortOrder.NewestFirst,
                sortField = QueryBatchSortField.UserDate,
                fileSystemType = 0,
                filetypesAnyOf = listOf(ChatProtocol.MessageFileType),
                groupIdAnyOf = listOf(conversationId),
                uniqueIdAnyOf = messageIds,
            )

        return BatchResult(
            records = withContext(Dispatchers.Default) {
                result.records.mapNotNull {
                    mapToMessageData(
                        it,
                        credentialsManager,
                        ::resolveDisplayName
                    )
                }
            },
            hasMoreRows = result.hasMoreRows,
            cursor = result.cursor
        )
    }

    override suspend fun loadFullMessage(conversationId: Uuid, messageId: Uuid): String? {

        val header = getMessage(messageId) ?: return null

        val descriptor = header.payloads?.firstOrNull { it.key == ChatProtocol.DefaultPayloadKey }

        if (descriptor == null) {
            return null
        }

        val payloadIv = Base64.decode(
            descriptor.iv ?: throw IllegalStateException(
                "encrypted payload requires key header"
            )
        )

        val fullMessage = withRetry(tag = "ChatMessageStream") {


            val response = driveFileProvider.getPayloadBytesDecrypted(
                driveId = chatDrive,
                fileId = header.fileId,
                key = descriptor.key,
                keyHeader = KeyHeader(
                    iv = payloadIv,
                    aesKey = header.keyHeader.aesKey
                )
            ) ?: return@withRetry null

            try {
                val payloadJson = response.bytes.decodeToString()
                val payload =
                    OdinSystemSerializer.deserialize<ChatMessagePayload>(payloadJson)

                payload.message
            } catch (t: Throwable) {
                Logger.e(t) { "Failed to deserialize payload for message $messageId" }
                null
            }
        } ?: return null


        // ---- update loaded conversation in memory ----

//        val current = conversationState.messages.value[conversationId] ?: return fullMessage
//
//        val updated =
//            current.map {
//                if (it.id == messageId) {
//                    it.copy(
//                        content = fullMessage,
//                        hasMore = false
//                    )
//                } else it
//            }
//
//        conversationState.set(conversationId, updated)

        return fullMessage
    }

    private suspend fun resolveDisplayName(file: HomebaseFile): String {
        val author = file.fileMetadata.originalAuthor ?: return ""

        // The logged-in user isn't in their own contacts, so contactService
        // resolves their odinId to the raw domain name. Use the owner session's
        // resolved display name for own messages instead (falls through to the
        // contact/domain path until the session's site data has loaded, or when
        // it carries no displayName).
        if (author == credentialsManager.requireActiveDomain()) {
            ownerSessionRepository.user.value?.displayName
                ?.takeIf { it != author.toString() && it.isNotBlank() }
                ?.let { return it }
        }

        return contactService.resolveByOdinId(author).name
    }

}

sealed interface ChatMessagesData {
    data object Initializing : ChatMessagesData
    data class Messages(val window: MessageWindow) : ChatMessagesData
}

/**
 * Auto-pin decision for #887, extracted so the actionability guards are unit-testable.
 *
 * True only when a freshly-seen typed message is still actionable, so a re-delivery
 * via BatchReceived — a peer's reaction/edit, or a fresh device's first sighting — can
 * NOT resurrect a pin the user already dismissed by voting or by the event/share
 * ending. [ChatMessageStream.autoPinHandled] is per-device and in-memory, so it can't
 * carry that decision across devices or restarts; the durable signal is the message's
 * own state. A null (parse-failed) descriptor is left unpinned — we can neither preview
 * nor age it. [nowMs] is epoch-ms; [ownReactions] are the current user's decoded vote
 * codes (`_p0`, `_1Y`).
 */
internal fun shouldAutoPin(
    content: MessageContent?,
    ownReactions: List<String>,
    nowMs: Long,
): Boolean = when (content) {
    is MessageContent.Poll -> content.descriptor?.let {
        PollVote.ownVotes(ownReactions, it.options.size).isEmpty()
    } ?: false
    is MessageContent.Groodle -> content.descriptor?.let {
        GroodleVote.myVotes(ownReactions, it.slots.size, it.allowMaybe).isEmpty()
    } ?: false
    is MessageContent.Event -> content.descriptor?.let {
        nowMs <= (it.endUtcMs ?: (it.startUtcMs + 3_600_000L))
    } ?: false
    is MessageContent.Location -> {
        val until = content.descriptor?.liveShareUntilMs
        until != null && nowMs < until
    }
    else -> false
}
