package id.homebase.chat.services

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.CredentialsManager
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
import id.homebase.chat.services.convo.contact.ContactService
import id.homebase.core.config.chatTargetDrive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.io.encoding.Base64
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

class ChatMessageStream(
    private val credentialsManager: CredentialsManager,
    private val contactService: ContactService,
    private val dbm: DatabaseManager,
    private val eventBus: EventBus,
    private val scope: CoroutineScope,
    private val driveFileProvider: DriveFileProvider
) : MessageLookup {
    /** Set by ConversationStream to let us skip messages for left conversations. */
    var isConversationLeft: (Uuid) -> Boolean = { false }

    private val paginatedState = PaginatedConversationState()
    private val chatDrive = chatTargetDrive.alias

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
                    is BackendEvent.SessionEnded -> paginatedState.reset()
                    is BackendEvent.OutboxEvent.OptimisticRollback -> {
                        if (event.driveId == chatDrive) {
                            paginatedState.removeMessage(event.uniqueId)
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
     * Load a centered page of messages around [messageUniqueId]. Used to
     * restore scroll position on conversation open and to jump to a search
     * result that's outside the current window. If the target message
     * isn't in the local DB (purged, never synced), falls back to
     * [loadConversation].
     */
    suspend fun loadConversationAroundMessage(conversationId: Uuid, messageUniqueId: Uuid) {
        val start = TimeSource.Monotonic.markNow()
        val target = getMessage(messageUniqueId) ?: run {
            Logger.d(tag = "ChatPaging") {
                "loadAround($conversationId, $messageUniqueId) anchor not in DB; falling back to loadConversation"
            }
            loadConversation(conversationId)
            return
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
    }

    override suspend fun getMessage(messageId: Uuid): MessageUiModel? {
        val messageFile = getMessageFile(messageId) ?: return null
        return mapToMessageData(messageFile, credentialsManager, ::resolveDisplayName)
    }

    override suspend fun getMessageFile(messageId: Uuid): HomebaseFile? {
        val c = credentialsManager.requireActiveCredentials()
        return dbm.driveMainIndex.selectHomebaseFileByUnique(
            c.getIdentityId(),
            chatDrive,
            messageId
        )
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
        messageIds: List<Uuid>
    ): BatchResult<MessageUiModel> {

        val c = credentialsManager.requireActiveCredentials()
        val queryBatch = QueryBatch(c.getIdentityId())

        // TODO - inject searchQuery into actual db query
        val result =
            queryBatch.queryBatchAsync(
                dbm = dbm,
                driveId = chatDrive,
                noOfItems = messageIds.size,
                cursor = null,
                sortOrder = QueryBatchSortOrder.NewestFirst,
                sortField = QueryBatchSortField.CreatedDate,
                fileSystemType = 0,
                filetypesAnyOf = listOf(ChatProtocol.MessageFileType),
                uniqueIdAnyOf = messageIds
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

        return contactService.resolveByOdinId(author)?.name ?: author.domainName
    }

}

sealed interface ChatMessagesData {
    data object Initializing : ChatMessagesData
    data class Messages(val window: MessageWindow) : ChatMessagesData
}
