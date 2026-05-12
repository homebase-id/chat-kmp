package id.homebase.chat.services.convo

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.OdinId
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.OutboxSync
import id.homebase.api.util.truncateToCodePoints
import id.homebase.chat.data.ConversationState
import id.homebase.chat.data.ConversationUiModel
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.mapToMessageData
import id.homebase.chat.services.StatusMessage
import id.homebase.chat.services.StatusMessageData
import id.homebase.chat.services.convo.contact.ContactService
import id.homebase.chat.services.outbox.OptimisticWriter
import id.homebase.core.avatars.ConversationAvatarModel
import id.homebase.core.config.chatTargetDrive
import id.homebase.core.image.HomebaseImageLoader
import id.homebase.core.image.ImageSize
import id.homebase.core.share.ShareCacheStorage
import id.homebase.core.share.ShareConversationCacheWriter
import id.homebase.core.share.ShareableConversation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ConversationStream(
    private val credentialsManager: CredentialsManager,
    private val contactService: ContactService,
    private val dbm: DatabaseManager,
    private val eventBus: EventBus,
    private val scope: CoroutineScope,
    private val shareCacheWriter: ShareConversationCacheWriter,
    private val imageLoader: HomebaseImageLoader,
    private val cacheStorage: ShareCacheStorage,
    private val optimisticWriter: OptimisticWriter,
    private val outboxSync: OutboxSync,
) : ConversationLoader, UnreadCountEnricher, ConversationParticipantLookup {

    private val chatDrive = chatTargetDrive.alias
    private val _conversations = MutableStateFlow(ConversationsData(dataReady = false))
    private val _shareableConversations = MutableStateFlow<List<ShareableConversation>>(emptyList())
    private var started = false
    private var shareCacheJob: Job? = null

    /**
     * Conversation ids the user deleted in this app session. The file is still
     * on disk (soft-deleted via archivalStatus=Removed) until the outbox processes
     * the server-side delete, so [loadBasicConversations] would otherwise resurrect
     * a "deleted conversation" placeholder via [ConversationMapper.mapDeletedConversation]
     * on every reload. Tracking the ids here lets us filter them out for the rest
     * of the session. Cleared on app restart (when the outbox should have already
     * hard-deleted them).
     */
    private val deletedIds: MutableSet<Uuid> = mutableSetOf()

    private val mapper: ConversationMapper = ConversationMapper(
        credentialsManager = credentialsManager,
        dbm = dbm
    )

    val conversations: StateFlow<ConversationsData> = _conversations.asStateFlow()
    val shareableConversations: StateFlow<List<ShareableConversation>> =
        _shareableConversations.asStateFlow()

    // region Recovery: missing or deleted conversation file
    /** Hook for explicit (non-sync) conversation recovery.
     *
     *  Wired in AppModule to ConversationService.recoverConversation().
     *  Live callers:
     *    - [triggerRecoveryForInvalidRows] (post-load reconciliation for
     *      rows whose state is [ConversationState.Invalid]).
     *    - the orphan branch in [processMessageBatchIncrementally] (when an
     *      incoming message references a conversationId with no main file
     *      in the local DB — closes the gap where requireConversation
     *      would otherwise throw on every send/react/markRead).
     *
     *  recoverConversation is local-only today (writes a versionTag=null
     *  placeholder, no outbox enqueue, no peer fan-out), so it is safe to
     *  call from inside a drive-sync batch handler. The historic concerns
     *  about server-write conflicts no longer apply. */
    var onRecoverConversation: (suspend (conversationId: Uuid, originalAuthor: OdinId?) -> Unit)? = null

    /**
     * Hook invoked when the live receive stream observes an incoming
     * [StatusMessage.GroupHealRequested] status. Wired in AppModule to
     * [ConversationService.handleIncomingHealRequest]. Side effect only —
     * does not affect message-list dispatch.
     *
     * The status-message [HomebaseFile] is passed through so the handler can
     * read/write its `localAppData.tags` and short-circuit on
     * [ChatProtocol.HealAppliedTag] (per-message idempotency gate).
     */
    var onIncomingHealRequest: (suspend (status: StatusMessageData, sender: OdinId, messageFile: HomebaseFile) -> Unit)? = null
    // endregion

// region Orphan-recovery: read-path dedup of recover attempts
    // [loadBasicConversations] triggers [onRecoverConversation] for any row
    // that mapped to [ConversationState.Invalid] (the mapper's catch-all for
    // unmappable conversation files — e.g. a 1:1 whose other participant is
    // gone, leaving recipients=[self], which crashes the
    // `participants.first { it != domain }` deref). Recovery is local-only and
    // idempotent; the set is session-scoped so a fresh app launch retries.
    // Within a session the set prevents a recovery → reload → recovery loop
    // if the placeholder write somehow fails silently.
    private val recoveryAttemptedIds = mutableSetOf<Uuid>()
    // endregion

    // region Auto-unarchive: incoming message for archived conversation
    /** Called when a message arrives for an archived conversation.
     *  Wired in AppModule to ConversationService.unarchiveConversation(). */
    var onUnarchiveConversation: (suspend (conversationId: Uuid) -> Unit)? = null
    // endregion

    // region Unread-count dirty bits
    // Conversation IDs whose unread count is suspected stale relative to the
    // DB. Set when a conversation file arrives and its lastRead advanced
    // (peer-device mark-as-read) or when a brand-new conversation appears
    // in memory without an unread baseline. The Stopped handler (drive-sync)
    // and the WebSocket-batch handler each check this set at their respective
    // checkpoints; if non-empty, run [enrichAllConversationsWithUnreadCounts],
    // which clears the set at the top.
    //
    // Only mutated from inside the sequential `eventBus.events.collect { ... }`
    // loop in init and from inside enrichAllConversationsWithUnreadCounts,
    // so no synchronization is needed.
    //
    // All access goes through [markUnreadDirty] / [hasDirtyUnread] /
    // [clearDirtyUnread] so callers don't reach into the set directly.
    private val dirtyUnreadIds = mutableSetOf<Uuid>()

    private fun markUnreadDirty(conversationId: Uuid) {
        dirtyUnreadIds += conversationId
    }

    private fun hasDirtyUnread(): Boolean = dirtyUnreadIds.isNotEmpty()

    private fun clearDirtyUnread() {
        dirtyUnreadIds.clear()
    }
    // endregion


    // Two paths converge on _conversations:
    //
    // 1) Live updates — WS-push [DataEvent.BatchReceived] events drive incremental
    //    in-memory mutations via processConversationBatchIncrementally /
    //    processMessageBatchIncrementally / processAdminFileBatch. This is the
    //    steady-state channel (per-file events from DriveWebSocketUpsertWorker and
    //    in-process OptimisticWriter writes).
    //
    // 2) DriveSync catch-up — [DriveEvent.Stopped(Success)] for the chat drive
    //    fires the full reload pipeline (loadBasicConversations →
    //    enrichWithLastMessages → enrichWithAdmins → conditional
    //    enrichAllConversationsWithUnreadCounts). This is the silent-DriveSync
    //    contract: DriveSync.performSync emits no per-batch BatchReceived events
    //    (DataEvent or DriveEvent) — it just upserts file headers into
    //    DriveMainIndex and signals Stopped, and we converge the in-memory model
    //    from the DB then.
    //
    // The unread-count step in (2) is gated on [hasDirtyUnread] — it's the
    // ~500ms full-DB pass and only materially changes the result when a
    // conversation file's lastRead actually advanced during the round
    // (peer-device mark-as-read echo). The other three steps are unconditional
    // because DriveSync may have changed conversation files, last-messages, or
    // admin membership without touching lastRead.
    init {
        scope.launch {
            eventBus.events.collect { event ->
                when (event) {
                    is BackendEvent.DriveEvent.Stopped -> {
                        if (event.driveId != chatDrive) return@collect
                        Logger.d("ConversationStream: Stopped(totalCount=${event.totalCount})")
                        // Silent-DriveSync contract: the chat-drive DriveSync just
                        // landed N files in DriveMainIndex with no per-batch
                        // BatchReceived emits. Reload the conversation list from DB
                        // to converge to the post-sync snapshot — but only when the
                        // round actually upserted records. totalCount=0 means the
                        // cursor was already at HEAD (a common no-op reconnect
                        // catch-up); DB state is unchanged, so the four-step
                        // pipeline would be pure busywork.
                        //
                        // First three steps unconditional (DriveSync may have
                        // changed conversation files, last-messages, or admin
                        // membership without dirtying lastRead). The unread-count
                        // recount is the ~500ms full-DB pass and is gated on
                        // [hasDirtyUnread] — it only materially changes the result
                        // when a conversation file's lastRead actually advanced
                        // during the round (peer-device mark-as-read echo).
                        if (event.result is BackendEvent.DriveResult.Success && event.totalCount > 0) {
                            scope.launch {
                                try {
                                    loadBasicConversations()
                                    enrichWithLastMessages()
                                    enrichWithAdmins()
                                    if (hasDirtyUnread()) {
                                        enrichAllConversationsWithUnreadCounts(trigger = "DriveSyncStopped")
                                    }
                                } catch (e: Exception) {
                                    Logger.e(e) {
                                        "ConversationStream: post-Stopped reload FAILED: ${e.message}"
                                    }
                                }
                            }
                        }
                    }

                    is BackendEvent.DataEvent.BatchReceived -> {
                        if (event.driveId != chatDrive) return@collect
                        // Every BatchReceived is a live event from the WS push path
                        // (DriveWebSocketUpsertWorker or its in-process analog
                        // OptimisticWriter). DriveSync.performSync is silent — the
                        // matching DriveEvent.Stopped(Success) above does a full DB
                        // reload to cover that path.
                        val conversationFiles =
                            event.batchData.filter {
                                it.fileMetadata.appData.fileType ==
                                        ChatProtocol.ConversationFileType
                            }
                        val messageFiles =
                            event.batchData.filter {
                                it.fileMetadata.appData.fileType ==
                                        ChatProtocol.MessageFileType
                            }
                        val adminFiles =
                            event.batchData.filter {
                                it.fileMetadata.appData.fileType ==
                                        ChatProtocol.ConversationAdminFileType
                            }

                        Logger.d(
                            "ConversationStream: BatchReceived " +
                                    "${event.batchData.size} files " +
                                    "(conversations=${conversationFiles.size}, messages=${messageFiles.size}, adminFiles=${adminFiles.size})"
                        )

                        if (conversationFiles.isNotEmpty())
                            processConversationBatchIncrementally(conversationFiles)

                        if (messageFiles.isNotEmpty())
                            processMessageBatchIncrementally(messageFiles)

                        if (adminFiles.isNotEmpty())
                            processAdminFileBatch(adminFiles)
                    }

                    // Started / Progress are state-machine signals we don't act on.
                    else -> {}
                }
            }
        }
    }

    private suspend fun resolveDisplayName(file: HomebaseFile): String {
        val author = file.fileMetadata.originalAuthor ?: return ""

        return contactService.resolveByOdinId(author)?.name ?: author.domainName
    }

    private suspend fun dispatchGroupHealRequests(messageFiles: List<HomebaseFile>) {
        val handler = onIncomingHealRequest ?: return
        for (file in messageFiles) {
            val appData = file.fileMetadata.appData
            if (appData.dataType != ChatProtocol.ChatStatusMessageDataType) continue
            val sender = file.fileMetadata.originalAuthor ?: file.fileMetadata.senderOdinId ?: continue
            val content = appData.content ?: continue
            val status = runCatching {
                OdinSystemSerializer.deserialize<StatusMessageData>(content)
            }.getOrNull() ?: continue
            if (status.statusMessage != StatusMessage.GroupHealRequested) continue
            try {
                handler(status, sender, file)
            } catch (e: Exception) {
                Logger.e(e) { "ConversationStream: heal-request handler threw for sender=${sender.domainName}: ${e.message}" }
            }
        }
    }

    private suspend fun processMessageBatchIncrementally(messageFiles: List<HomebaseFile>) {
        if (messageFiles.isEmpty()) throw IllegalArgumentException("It can't be empty")

        // Pre-pass: dispatch GroupHealRequested status messages to the heal
        // handler. Done here (live BatchReceived only — never on cold reads or
        // searches) so the side effects fire exactly once per arrival.
        dispatchGroupHealRequests(messageFiles)

        // For each file in the batch, map to model (fetch last message from DB if needed).
        // Keep the original HomebaseFile alongside the mapped MessageUiModel so we can
        // pull the SQL-faithful userDate via `file.sqlUserDateMs()` — `MessageUiModel.userDate`
        // is clamped to `transitCreated` for display and can underrun the SQL column.
        val incoming = ArrayList<Pair<HomebaseFile, MessageUiModel>>(messageFiles.size)
        for (file in messageFiles) {
            val mapped = mapToMessageData(file, credentialsManager, ::resolveDisplayName)
            if (mapped != null) incoming.add(file to mapped)
        }

        if (messageFiles.size != incoming.size)
            Logger.w("ConversationStream: ${messageFiles.size - incoming.size} of ${messageFiles.size} messages failed to convert")

        // ╔══════════════════════════════════════════════════════════════╗
        // ║  HACK ── REMOVE ONCE THE SERVER STOPS FAN-OUT-PER-WRITE      ║
        // ║                                                              ║
        // ║  TODO(server): the chat-drive WS currently emits N           ║
        // ║  notifications (typically 3) per logical file write — same   ║
        // ║  uniqueId, same versionTag, same content, just delivered     ║
        // ║  multiple times. Suspected cause: multi-stage server-side    ║
        // ║  processing or duplicated subscription registration.         ║
        // ║                                                              ║
        // ║  Downstream caches dedupe naturally:                         ║
        // ║    • [DriveMainIndex] upsert is idempotent on uniqueId.      ║
        // ║    • [PaginatedConversationState.upsert] collapses by        ║
        // ║      `m.id`.                                                 ║
        // ║                                                              ║
        // ║  But the per-message side effects in this loop (unread bump  ║
        // ║  via [applyIncomingMessageBump], orphan placeholder, deleted-║
        // ║  conversation revive, auto-unarchive) would otherwise fire   ║
        // ║  N times per logical message — N-x over-counting unread      ║
        // ║  observable in homebase.log as repeated `unread++` lines all ║
        // ║  carrying the same convo id within a millisecond.            ║
        // ║                                                              ║
        // ║  Until the server is fixed, we collapse the batch by         ║
        // ║  message id here. `associateBy` keeps the LAST occurrence on ║
        // ║  key collision, which gives us the latest wire state.        ║
        // ║                                                              ║
        // ║  When the server-side fix lands: delete this block and the   ║
        // ║  loop should iterate `incoming` directly.                    ║
        // ╚══════════════════════════════════════════════════════════════╝
        val deduped = incoming.associateBy { (_, m) -> m.id }.values.toList()
        if (deduped.size != incoming.size) {
            Logger.w(
                "ConversationStream: HACK dedupe — dropped ${incoming.size - deduped.size} " +
                    "duplicate message file(s) from batch (server fan-out workaround; " +
                    "kept ${deduped.size} unique by id)"
            )
        }

        for ((file, m) in deduped) {
            val matchingConversation = _conversations.value.items.find { it.id == m.conversationId }

            // Drop messages for conversations the user has left or been removed from
            if (matchingConversation?.conversationState == ConversationState.Left
                || matchingConversation?.conversationState == ConversationState.Removed
            ) continue

            // region Auto-unarchive: Signal-style unarchive on incoming message
            if (matchingConversation?.conversationState == ConversationState.Archived) {
                // Only unarchive for messages from others, not our own synced messages
                if (!m.isAuthoredBy(credentialsManager.getActiveDomain())) {
                    Logger.i("ConversationStream: unarchiving conversation ${m.conversationId} due to incoming message from ${m.originalAuthor}")
                    val unarchived = matchingConversation.copy(conversationState = ConversationState.Active)
                    _conversations.value = _conversations.value.copy(
                        items = _conversations.value.items.map { if (it.id == unarchived.id) unarchived else it }
                    )
                    scope.launch {
                        try {
                            onUnarchiveConversation?.invoke(m.conversationId)
                        } catch (e: Exception) {
                            Logger.e(e) { "ConversationStream: failed to unarchive conversation ${m.conversationId}: ${e.message}" }
                        }
                    }
                }
                // Fall through to updateConversationFromNewMessage below (don't continue)
            }
            // endregion

            // region Recovery: revive deleted conversation on new incoming message
            // Compare server-stamped *created* timestamp against the conversation's
            // last-updated time. Using `created` (not `modified`) because the delete
            // operation itself can bump `modified` on existing messages, causing a
            // false-positive revival.
            if (matchingConversation?.conversationState == ConversationState.Deleted) {
                val messageCreated = m.created
                if (messageCreated <= matchingConversation.fileUpdated) {
                    Logger.d("ConversationStream: skipping old message for deleted conversation ${m.conversationId} (msgCreated=$messageCreated <= convoUpdated=${matchingConversation.fileUpdated})")
                    continue
                }
                Logger.i("ConversationStream: new message for deleted conversation ${m.conversationId} from=${m.originalAuthor} (msgCreated=$messageCreated > convoUpdated=${matchingConversation.fileUpdated}), reviving")
                // Flip state to Active in memory so it reappears in the UI immediately
                val revived = matchingConversation.copy(conversationState = ConversationState.Active)
                _conversations.value = _conversations.value.copy(
                    items = _conversations.value.items.map { if (it.id == revived.id) revived else it }
                )
                updateConversationFromNewMessage(revived, m, file.sqlUserDateMs())

                // Intentionally do NOT trigger server-side recovery here. A
                // drive-sync batch handler is the wrong place to enqueue
                // outbox items — see the orphan branch below for the full
                // rationale. If the server keeps delivering messages for a
                // locally-deleted conversation, that conversation is
                // already alive server-side; we simply re-align local state
                // to match.
                continue
            }
            // endregion

            if (matchingConversation == null) {
                // Determine 1:1 vs group so the placeholder has a useful avatar.
                // Use sender (not originalAuthor) for the XOR test — for forwarded
                // messages those differ and originalAuthor is content provenance,
                // not the wire-level counterparty.
                val activeDomain = credentialsManager.getActiveDomain()
                val isOneToOne = activeDomain != null && m.isOneToOne(activeDomain)

                val placeholderAvatar = if (isOneToOne) {
                    ConversationAvatarModel(
                        type = ConversationAvatarModel.Type.Connection,
                        odinId = m.sender,
                    )
                } else {
                    ConversationAvatarModel(type = ConversationAvatarModel.Type.GroupFallback)
                }

                val placeholderParticipants = if (isOneToOne) {
                    listOfNotNull(activeDomain, m.sender).distinct()
                } else {
                    emptyList()
                }

                val emptyConversation =
                    ConversationUiModel(
                        id = m.conversationId,
                        name = "Conversation missing...",
                        lastMessage = m.content,
                        latestMessageTimestamp = Instant.fromEpochMilliseconds(file.sqlUserDateMs()),
                        admins = (if (m.originalAuthor == null) emptySet() else setOf(m.originalAuthor)),
                        unreadCount = 0,
                        avatarTiny = null,
                        avatarInitials = "",
                        avatarUrl = "",
                        participants = placeholderParticipants,
                        lastRead = UnixTimeUtc(0).toInstant(),
                        avatarModel = placeholderAvatar,
                        lastMessageDeliveryStatus = m.messageAppData.deliveryStatus,
                        lastMessageIsDeleted = m.isDeleted,
                        lastMessageFirstPayload = m.payloads?.firstOrNull(),
                        lastMessageHasMultiplePayloads = (m.payloads?.size ?: 0) > 1,
                        lastMessageIsFromActiveUser =
                            m.isAuthoredBy(credentialsManager.getActiveDomain()),
                        isGroup = !isOneToOne
                    )

                // region Placeholder: conversation file not yet synced
                // Insert a UI placeholder so the message is visible now.
                // We intentionally do NOT enqueue a server-side write from
                // here — that path historically produced spurious
                // "File already exists with ClientUniqueId" conflicts
                // (when the real conversation file is simply later in the
                // sync order) and "Cannot transfer to yourself" rejections
                // (when originalAuthor == self for groups we started).
                //
                // We DO trigger [onRecoverConversation] (local-only — writes a
                // versionTag=null placeholder via
                // OptimisticWriter.writeLocalOnlyConversationPlaceholder, no
                // outbox enqueue, no peer fan-out). This closes the gap that
                // used to leave a user with messages arriving for a
                // conversation whose main file was missing from local DB:
                // requireConversation would throw on every send/react/markRead
                // because getConversationHomebaseFile returned null. Wiring
                // the local-only recovery here gives us a DB row immediately,
                // so requireConversation succeeds and the heal flow can take
                // over if the canonical author is online.
                //
                // Self-healing for the in-memory UI is unchanged:
                //   1. If the real conversation file exists on the server
                //      (the common case), it will arrive in a later sync
                //      batch and replace this placeholder via
                //      processConversationBatchIncrementally →
                //      updateConversation.
                //   2. If the server truly lacks the file, the local-only
                //      placeholder written by recoverConversation persists
                //      until a peer push from a member who has the file
                //      restores it.
                Logger.w("ConversationStream: orphaned conversation ${m.conversationId} from=${m.originalAuthor} isOneToOne=$isOneToOne, creating placeholder")
                insertNewConversation(emptyConversation)

                // Fire-and-forget local-only recovery. recoveryAttemptedIds
                // dedups within a session — exactly one attempt per missing
                // conversation, matching triggerRecoveryForInvalidRows
                // (line 841). The recover lambda may be null (DI not wired
                // for tests / before AppModule binds it).
                val recover = onRecoverConversation
                if (recover != null && recoveryAttemptedIds.add(m.conversationId)) {
                    val convoId = m.conversationId
                    val originalAuthor = m.originalAuthor
                    Logger.i(tag = "OrphanRecovery") {
                        "ConversationStream: triggering recoverConversation from orphan branch " +
                            "convoId=$convoId originalAuthor=${originalAuthor?.domainName} sender=${m.sender?.domainName} isOneToOne=$isOneToOne"
                    }
                    scope.launch {
                        try {
                            recover(convoId, originalAuthor)
                            Logger.i(tag = "OrphanRecovery") {
                                "ConversationStream: orphan-branch recoverConversation completed convoId=$convoId"
                            }
                        } catch (t: Throwable) {
                            Logger.e(throwable = t, tag = "OrphanRecovery") {
                                "ConversationStream: orphan-branch recoverConversation FAILED convoId=$convoId — " +
                                    "in-memory placeholder will keep the message visible but requireConversation may still throw on send"
                            }
                        }
                    }
                } else if (recover == null) {
                    Logger.d(tag = "OrphanRecovery") {
                        "ConversationStream: orphan branch — onRecoverConversation not wired, skipping local-only recovery for ${m.conversationId}"
                    }
                }
                // endregion
            } else {
                updateConversationFromNewMessage(matchingConversation, m, file.sqlUserDateMs())
            }
        }

        // Sort by descending timestamp (adjust based on your UI needs)
        val sortedList = _conversations.value.items.sortedByDescending { it.latestMessageTimestamp }
        _conversations.value = _conversations.value.copy(dataReady = true, items = sortedList)
    }

    /**
     * @param sqlUserDateMs Authoritative `DriveMainIndex.userDate` of the
     *   incoming message file (epoch ms). Used as the source of truth for the
     *   conversation's `latestMessageTimestamp` so it stays in lock-step with
     *   `selectAllUnreadCount`. The clamped `m.userDate` is correct for
     *   display but can underrun the SQL value.
     *
     * Writes the message-preview fields and the bumped [unreadCount]
     * directly to [_conversations]. Does NOT route through
     * [updateConversation] — that helper is designed for whole-file
     * refreshes and explicitly preserves `existing.unreadCount` (because
     * incoming-from-file always maps with `unreadCount=0`). Routing this
     * path through it silently drops the bump; the bug was masked while
     * `Stopped` always re-ran [enrichAllConversationsWithUnreadCounts]
     * but is no longer masked now that the dirty-bit gates the post-sync
     * recount.
     *
     * The map callback reads `existing` (live state) instead of the
     * captured `c` parameter so consecutive bumps for the same
     * conversation in one batch are additive — each iteration of the
     * caller's loop sees the previous iteration's write.
     */
    private suspend fun updateConversationFromNewMessage(
        c: ConversationUiModel,
        m: MessageUiModel,
        sqlUserDateMs: Long,
    ) {
        val sqlUserDate = Instant.fromEpochMilliseconds(sqlUserDateMs)
        val domain = credentialsManager.getActiveDomain()

        val current = _conversations.value
        val updated = applyIncomingMessageBump(
            items = current.items,
            targetConversationId = c.id,
            m = m,
            sqlUserDate = sqlUserDate,
            activeDomain = domain,
        ) ?: return

        // Log the unread bump for the touched conversation, if any. We
        // diff before-vs-after rather than recomputing the increment
        // here so the log never disagrees with the persisted state.
        val before = current.items.firstOrNull { it.id == c.id }
        val after = updated.firstOrNull { it.id == c.id }
        if (before != null && after != null && after.unreadCount > before.unreadCount) {
            Logger.d("ConversationStream: unread++ convo=${c.id} count=${after.unreadCount}")
        }

        _conversations.value = current.copy(items = updated)
    }

    override suspend fun loadConversation(conversationId: Uuid) {
        val c = credentialsManager.requireActiveCredentials()

        val conversationFile = dbm.driveMainIndex.selectHomebaseFileByUnique(
            c.getIdentityId(), chatDrive, conversationId
        ) ?: return

        val incoming = mapper.mapToConversationUi(conversationFile, null)
        val existing = _conversations.value.items.find { it.id == conversationId }
        if (existing == null) {
            insertNewConversation(incoming)
        } else {
            updateConversation(existing, incoming)
        }
    }

    override suspend fun removeConversation(conversationId: Uuid) {
        val current = _conversations.value
        if (current.items.none { it.id == conversationId }) return
        _conversations.value = current.copy(
            items = current.items.filterNot { it.id == conversationId }
        )
    }

    private suspend fun processAdminFileBatch(adminFiles: List<HomebaseFile>) {
        val conversationIds = adminFiles.mapNotNull { it.fileMetadata.appData.groupId }.distinct()
        for (conversationId in conversationIds) {
            loadConversation(conversationId)
        }
    }

    private suspend fun processConversationBatchIncrementally(
        conversationFiles: List<HomebaseFile>,
    ) {
        // For each file in the batch, map to model (fetch last message from DB if needed)
        val incomingConversations =
            conversationFiles.map { file ->
                mapper.mapToConversationUi(file, null)
            }

        for (c in incomingConversations) {
            val matchingConversation = _conversations.value.items.find { it.id == c.id }
            if (matchingConversation == null) {
                // Brand-new in-memory conversation — we have no unread baseline
                // yet, so mark dirty so the next checkpoint recounts.
                insertNewConversation(c)
                markUnreadDirty(c.id)
            } else {
                // lastRead advanced ⇒ peer-device mark-as-read echo. Anything
                // else the file carries (name, participants, lastMessage)
                // does not affect unread count. Compare BEFORE updateConversation
                // runs — that helper merges lastRead with `max(existing, incoming)`
                // and would erase the delta we're testing for.
                if (c.lastRead > matchingConversation.lastRead) {
                    markUnreadDirty(c.id)
                }
                updateConversation(matchingConversation, c)
            }
        }

        // Sort by descending timestamp (adjust based on your UI needs)
        val sortedList = _conversations.value.items.sortedByDescending { it.latestMessageTimestamp }
        _conversations.value = _conversations.value.copy(items = sortedList)

        // Skip while the cold-load pipeline hasn't run its own enrich yet.
        // During initial sync, every conversation file streams in via this
        // path; the end-of-start() enrich
        // (`enrichAllConversationsWithUnreadCounts(trigger = "ColdLoad")`)
        // flips `hasUnreadCounts` once cold-load is done. Only after that
        // should per-batch arrivals drive enrichment.
        if (!_conversations.value.enrichment.hasUnreadCounts) return

        // Every BatchReceived is a live WS-push event (DriveSync is silent).
        // There's no Started/Stopped envelope to defer to — this batch IS the
        // whole event. If anything dirtied an unread count, run enrichAll now.
        if (hasDirtyUnread()) {
            scope.launch {
                try {
                    enrichAllConversationsWithUnreadCounts(trigger = "WebSocketBatch")
                } catch (e: Exception) {
                    Logger.e(e) {
                        "ConversationStream: WebSocket-batch enrich failed: ${e.message}"
                    }
                }
            }
        }
    }

    private fun insertNewConversation(conversation: ConversationUiModel) {
        // We should optimize later to not copy the full list
        val currentList = _conversations.value.items.toMutableList()
        currentList.add(conversation)

        _conversations.value = _conversations.value.copy(items = currentList)
    }

    private suspend fun updateConversation(
        existing: ConversationUiModel,
        incoming: ConversationUiModel
    ) {
        // Left is sticky — only cleared by an explicit rejoin action, not by outbox responses
        // which may not preserve localAppData tags. RejoinPending is the one exception: it means
        // the server shows us back in participants while we still have the Left tag locally.
        val resolvedState = if (existing.conversationState == ConversationState.Left
            && incoming.conversationState != ConversationState.Left
            && incoming.conversationState != ConversationState.RejoinPending
        ) {
            ConversationState.Left
        } else {
            incoming.conversationState
        }

        // Stamp lastExitedAt in localAppData the first time we detect a Removed transition.
        // The mapper reads it back from localAppData on subsequent loads (cold start, reconnect).
        val isNewlyRemoved = resolvedState == ConversationState.Removed
                && existing.conversationState != ConversationState.Removed
                && existing.conversationState != ConversationState.Left
        if (isNewlyRemoved) {
            optimisticWriter.stampConversationExitedAt(chatDrive, existing.id)
                ?.let { outboxSync.tryEnqueue(it) }
        }

        // Clear exitedAt when active again; preserve on Left/Removed (first stamp wins).
        val resolvedExitedAt = when {
            resolvedState == ConversationState.Active
                    || resolvedState == ConversationState.RejoinPending -> null

            isNewlyRemoved -> UnixTimeUtc().toInstant()
            else -> existing.exitedAt ?: incoming.exitedAt
        }

        // Structural fields (membership, identity) always come from the conversation file,
        // regardless of timestamp. The in-memory timestamp is driven by message arrivals and
        // is almost always newer than metadata.created, so a timestamp guard would silently
        // drop participant/admin/name changes distributed by peers (e.g. leave, add member).
        // Message-preview fields are only applied when the file is genuinely newer.
        val updatedConvo = existing.copy(
            name = incoming.name,
            isGroup = incoming.isGroup,
            isLegacyGroup = incoming.isLegacyGroup,
            admins = incoming.admins,
            avatarModel = incoming.avatarModel,
            avatarTiny = incoming.avatarTiny,
            avatarUrl = incoming.avatarUrl,
            avatarInitials = incoming.avatarInitials,
            participants = incoming.participants,
            isPinned = incoming.isPinned,
            conversationState = resolvedState,
            exitedAt = resolvedExitedAt,
            fileUpdated = incoming.fileUpdated,
            // Carries the conversation file's localAppData.lastReadTime forward —
            // a peer device's mark-as-read advances it, syncs it, and we'd otherwise
            // drop it here, which leaves enrichAllConversationsWithUnreadCounts
            // mirroring modelMs=0 and skipping the ChatReadCount upsert. Max
            // keeps it monotonic against a stale drive-sync echo.
            lastRead = if (incoming.lastRead > existing.lastRead) incoming.lastRead else existing.lastRead,
            // Message preview — only overwrite if the file carries a newer last-message snapshot
            latestMessageTimestamp = if (incoming.latestMessageTimestamp >= existing.latestMessageTimestamp) incoming.latestMessageTimestamp else existing.latestMessageTimestamp,
            lastMessage = if (incoming.latestMessageTimestamp >= existing.latestMessageTimestamp) incoming.lastMessage else existing.lastMessage,
            lastMessageDeliveryStatus = if (incoming.latestMessageTimestamp >= existing.latestMessageTimestamp) incoming.lastMessageDeliveryStatus else existing.lastMessageDeliveryStatus,
            lastMessageIsDeleted = if (incoming.latestMessageTimestamp >= existing.latestMessageTimestamp) incoming.lastMessageIsDeleted else existing.lastMessageIsDeleted,
            lastMessageFirstPayload = if (incoming.latestMessageTimestamp >= existing.latestMessageTimestamp) incoming.lastMessageFirstPayload else existing.lastMessageFirstPayload,
            lastMessageHasMultiplePayloads = if (incoming.latestMessageTimestamp >= existing.latestMessageTimestamp) incoming.lastMessageHasMultiplePayloads else existing.lastMessageHasMultiplePayloads,
            lastMessageIsFromActiveUser = if (incoming.latestMessageTimestamp >= existing.latestMessageTimestamp) incoming.lastMessageIsFromActiveUser else existing.lastMessageIsFromActiveUser,
        )
        // We should optimize later to not map the full list
        _conversations.value = _conversations.value.copy(
            items = _conversations.value.items.map { if (it.id == existing.id) updatedConvo else it }
        )
    }

    // region Cold-load pipeline — MANDATORY + ENRICHMENT phases
    //
    // The full conversation list load is split into two layers:
    //
    //   1. MANDATORY — [loadBasicConversations] runs one simple SELECT and
    //      flips `dataReady=true`. This is the fast first-paint path.
    //
    //   2. ENRICHMENT — three `enrichWithX` passes run sequentially after
    //      the basic emit. Each pass patches a subset of fields on the
    //      rows already in `_conversations`, then flips its flag on
    //      [EnrichmentState]. Passes are independently skippable, safe to
    //      retry, and must never block the basic emit.
    //
    // Orchestration lives in [start].

    /**
     * MANDATORY — basic conversation load. Runs one simple SELECT against
     * the `DriveMainIndex` table and produces [ConversationUiModel]s with
     * only the fields required to render the list.
     *
     * Flips `dataReady=true` on the StateFlow. Preserves any in-memory
     * `Left` state for conversations that already had it (those can exist
     * from a prior session or an optimistic update that hasn't been
     * reconciled with the server file yet).
     *
     * Do not add DB calls, network calls, or per-row fan-out here —
     * anything optional belongs in an `enrichWithX` pass. This is the
     * fast first-paint path; every added cost here delays tap-to-render
     * latency on cold start.
     */
    private suspend fun loadBasicConversations() {
        val startedAt = Clock.System.now().toEpochMilliseconds()
        val c = credentialsManager.requireActiveCredentials()

        // Preserve any in-memory Left state across reloads — Left is sticky
        // until an explicit rejoin.
        val leftIds = _conversations.value.items
            .filter { it.conversationState == ConversationState.Left }
            .map { it.id }
            .toSet()

        val files = dbm.chatReadCount.selectAllConversations(c.getIdentityId())
        val afterQuery = Clock.System.now().toEpochMilliseconds()

        val basicWithSource = files
            // Locally-deleted conversations stay on disk (soft-deleted) until the
            // outbox processes the server-side delete; without this filter the
            // mapper would resurrect a "deleted conversation" placeholder for them
            // on every reload. See [onConversationDeleted].
            .filterNot { it.fileMetadata.appData.uniqueId in deletedIds }
            .map { file ->
                val ui = mapper.mapToBasic(file)
                val finalUi = if (ui.id in leftIds && ui.conversationState != ConversationState.Left) {
                    ui.copy(conversationState = ConversationState.Left)
                } else ui
                finalUi to file
            }

        val basic = basicWithSource.map { it.first }

        _conversations.value = ConversationsData(
            dataReady = true,
            items = basic,
            enrichment = EnrichmentState(),
        )

        Logger.i(tag = "ConvListPerf") {
            "loadBasicConversations end-to-end=${Clock.System.now().toEpochMilliseconds() - startedAt}ms " +
                    "(query=${afterQuery - startedAt}ms map=${Clock.System.now().toEpochMilliseconds() - afterQuery}ms) " +
                    "items=${basic.size}"
        }

        // Trigger orphan recovery for any row that landed in the Invalid
        // placeholder. Fire-and-forget on the existing scope: do NOT block
        // the basic emit, do NOT await — recoverConversation writes a local
        // placeholder file and re-syncs the row, which arrives back via the
        // normal drive-sync → reload path.
        triggerRecoveryForInvalidRows(basicWithSource)
    }

    private fun triggerRecoveryForInvalidRows(
        basicWithSource: List<Pair<ConversationUiModel, id.homebase.api.client.drives.HomebaseFile>>,
    ) {
        val recover = onRecoverConversation ?: return
        val toRecover = mutableListOf<Pair<Uuid, OdinId?>>()
        for ((ui, file) in basicWithSource) {
            if (ui.conversationState != ConversationState.Invalid) continue
            val convoId = file.fileMetadata.appData.uniqueId ?: continue
            if (!recoveryAttemptedIds.add(convoId)) continue
            toRecover.add(convoId to file.fileMetadata.originalAuthor)
        }
        for ((convoId, originalAuthor) in toRecover) {
            scope.launch {
                try {
                    Logger.w(tag = "OrphanRecovery") {
                        "loadBasicConversations: triggering recoverConversation for Invalid row " +
                                "convoId=$convoId originalAuthor=${originalAuthor?.domainName}"
                    }
                    recover(convoId, originalAuthor)
                } catch (t: Throwable) {
                    Logger.e(throwable = t, tag = "OrphanRecovery") {
                        "loadBasicConversations: recoverConversation failed for convoId=$convoId — " +
                                "placeholder will keep showing until next session retry"
                    }
                }
            }
        }
    }

    /**
     * ENRICHMENT — patches `lastMessage*` fields on the already-loaded
     * rows by running `selectAllConversationPlusLastMessage` (JOIN against
     * the message index) and applying each last message via
     * [ConversationMapper.applyLastMessage].
     *
     * Safe to defer, safe to skip, safe to retry on failure — the result
     * is either improved rows or no change. Never block the basic emit
     * on this.
     *
     * Runs after [loadBasicConversations]. Flips `enrichment.hasLastMessages`.
     */
    private suspend fun enrichWithLastMessages() {
        val startedAt = Clock.System.now().toEpochMilliseconds()
        val c = credentialsManager.requireActiveCredentials()
        val domain = credentialsManager.requireActiveDomain()

        val rows = dbm.chatReadCount.selectAllConversationPlusLastMessage(c.getIdentityId())
        val afterQuery = Clock.System.now().toEpochMilliseconds()

        // Carry the SQL `msgUserDate` (DriveMainIndex.userDate) alongside the
        // file. The conversation list's `latestMessageTimestamp` must use this
        // SQL value, not the clamped `MessageUiModel.userDate`, so it stays in
        // lock-step with what `selectAllUnreadCount` filters on. See
        // `HomebaseFile.sqlUserDateMs()` for the formula.
        val msgByConversation = HashMap<Uuid, Pair<id.homebase.api.client.drives.HomebaseFile, Long?>>(rows.size)
        for (row in rows) {
            val convoId = row.conversation.fileMetadata.appData.uniqueId ?: continue
            val msg = row.message ?: continue
            msgByConversation[convoId] = msg to row.msgUserDateMs
        }

        val current = _conversations.value
        val updated = current.items.map { ui ->
            val pair = msgByConversation[ui.id] ?: return@map ui
            mapper.applyLastMessage(ui, pair.first, domain, sqlUserDateMs = pair.second)
        }

        _conversations.value = current.copy(
            items = updated,
            enrichment = current.enrichment.copy(hasLastMessages = true),
        )

        Logger.i(tag = "ConvListPerf") {
            "enrichWithLastMessages end-to-end=${Clock.System.now().toEpochMilliseconds() - startedAt}ms " +
                    "(query=${afterQuery - startedAt}ms map=${Clock.System.now().toEpochMilliseconds() - afterQuery}ms) " +
                    "messages=${msgByConversation.size}/${current.items.size}"
        }
    }

    /**
     * ENRICHMENT — resolves admin sets for group conversations by reading
     * the separate admin-file records in a single batched DB round-trip,
     * then patches each row via [ConversationMapper.applyAdmins].
     *
     * Rows without a matching admin file keep their in-content
     * `adminData` seed from [ConversationMapper.mapToBasic] — that's the
     * intended fallback ordering.
     *
     * Safe to defer, safe to skip, safe to retry. Flips
     * `enrichment.hasAdmins`.
     */
    private suspend fun enrichWithAdmins() {
        val startedAt = Clock.System.now().toEpochMilliseconds()

        val current = _conversations.value
        val groupIds = ArrayList<Uuid>()
        for (ui in current.items) {
            if (ui.isGroupConversation) groupIds.add(ui.id)
        }
        if (groupIds.isEmpty()) {
            _conversations.value = current.copy(
                enrichment = current.enrichment.copy(hasAdmins = true),
            )
            Logger.i(tag = "ConvListPerf") {
                "enrichWithAdmins end-to-end=${Clock.System.now().toEpochMilliseconds() - startedAt}ms groups=0"
            }
            return
        }

        val adminMap = ConversationAdminInfo.queryBatchFromDb(
            credentialsManager, dbm, chatDrive, groupIds
        )

        val updated = current.items.map { ui ->
            val resolved = adminMap[ui.id] ?: return@map ui
            mapper.applyAdmins(ui, resolved)
        }

        _conversations.value = current.copy(
            items = updated,
            enrichment = current.enrichment.copy(hasAdmins = true),
        )

        Logger.i(tag = "ConvListPerf") {
            "enrichWithAdmins end-to-end=${Clock.System.now().toEpochMilliseconds() - startedAt}ms " +
                    "hits=${adminMap.size}/${groupIds.size}"
        }
    }

    /**
     * ENRICHMENT — patches unread counts from `ChatReadCount` onto the
     * in-memory list, AND mirrors any peer-device read advances carried in
     * the conversation file's `localAppData.lastReadTime` (already exposed
     * via [ConversationUiModel.lastRead]) into `ChatReadCount`.
     *
     * Cold-load steady state: one SELECT, no writes. When a peer device
     * advanced read state, this pass does SELECT → batch UPSERT → re-SELECT
     * so the unread counts reflect the just-mirrored values before they're
     * patched onto the model.
     *
     * Invoked from cold-load ([start]), the [BackendEvent.DriveEvent.Stopped]
     * handler when [hasDirtyUnread] is true, and the WebSocket-batch
     * handler when [hasDirtyUnread] is true at end of batch. Flips
     * `enrichment.hasUnreadCounts` (first call only; subsequent calls just
     * patch counts).
     *
     * Clears the dirty set at the top — anything dirtied while this call
     * is in flight remains for the next checkpoint.
     *
     * @param trigger short label naming the call site (e.g. "ColdLoad",
     *   "Stopped", "WebSocketBatch"). Surfaces in the `ConvListPerf` log so
     *   we can attribute frequency without a stack walk.
     *
     * Safe to defer, safe to skip, safe to retry.
     */
    suspend fun enrichAllConversationsWithUnreadCounts(trigger: String) {
        val startedAt = Clock.System.now().toEpochMilliseconds()
        // Take ownership of the current dirty set as we begin work. New
        // bits set during this call's lifetime persist for the next
        // checkpoint — preferred over a lost-wakeup race.
        clearDirtyUnread()
        val c = credentialsManager.requireActiveCredentials()
        var unread = dbm.chatReadCount.selectAllUnreadCount(c.getIdentityId(), c.domain)

        // Mirror: only consider conversations that appear in the unread
        // result set. A conversation missing from this result has zero
        // unread under the current stored value, which means the badge is
        // already correct on this device — no mirror needed even if the
        // file's lastReadTime is ahead. Self-corrects on next markAsRead.
        val modelByConvo = _conversations.value.items.associate {
            it.id to it.lastRead.toEpochMilliseconds()
        }
        val toUpsert = mutableListOf<Pair<Uuid, UnixTimeUtc>>()
        for (row in unread) {
            val modelMs = modelByConvo[row.conversationId] ?: continue
            val storedMs = row.lastReadTime ?: 0L
            if (modelMs > storedMs) {
                toUpsert.add(row.conversationId to UnixTimeUtc(modelMs))
            }
        }
        val mirroredCount = toUpsert.size
        if (toUpsert.isNotEmpty()) {
            dbm.chatReadCount.bulkUpsertLastReadTimes(toUpsert)
            // Re-query so the unread counts we apply reflect the mirror.
            unread = dbm.chatReadCount.selectAllUnreadCount(c.getIdentityId(), c.domain)
        }

        val unreadMap = unread.associate { it.conversationId to it.unreadCount.toInt() }

        var changed = 0
        val current = _conversations.value
        val updated = current.items.map { convo ->
            val newCount = unreadMap[convo.id] ?: 0
            if (newCount != convo.unreadCount) {
                changed++
                Logger.d("ConversationStream: unreadSync convo=${convo.id} ${convo.unreadCount}->${newCount}")
                convo.copy(unreadCount = newCount)
            } else {
                convo
            }
        }

        _conversations.value = current.copy(
            items = if (changed > 0) updated else current.items,
            enrichment = current.enrichment.copy(hasUnreadCounts = true),
        )

        Logger.i(tag = "ConvListPerf") {
            "enrichAllConversationsWithUnreadCounts=${Clock.System.now().toEpochMilliseconds() - startedAt}ms " +
                    "trigger=$trigger mirrored=$mirroredCount changedRows=$changed totalRows=${current.items.size}"
        }
    }

    /**
     * Patch the in-memory entry for [conversationId] to reflect a successful
     * mark-as-read advance: set lastRead to [newLastRead] and re-derive
     * unreadCount from ChatReadCount. Single state emission so the UI sees both
     * deltas at once. No-op if the conversation isn't in memory yet.
     */
    override suspend fun applyLocalAdvance(conversationId: Uuid, newLastRead: Instant) {
        val current = _conversations.value
        val index = current.items.indexOfFirst { it.id == conversationId }
        if (index < 0) {
            Logger.w(tag = "MarkAsRead") {
                "ConversationStream.applyLocalAdvance: convo=$conversationId NOT FOUND " +
                        "in in-memory list (size=${current.items.size}) — UI badge will not update"
            }
            return
        }

        val c = credentialsManager.requireActiveCredentials()
        val newCount = dbm.chatReadCount
            .selectUnreadCountForConversation(c.getIdentityId(), conversationId, c.domain)
            .toInt()

        val convo = current.items[index]
        if (convo.lastRead == newLastRead && convo.unreadCount == newCount) return

        Logger.d("ConversationStream: unreadSync convo=$conversationId ${convo.unreadCount}->$newCount")
        val updated = current.items.toMutableList().apply {
            this[index] = convo.copy(lastRead = newLastRead, unreadCount = newCount)
        }
        _conversations.value = current.copy(items = updated)
    }

    // endregion

    // Full conversation list load from local DB.  One-shot — subsequent calls
    // return immediately.  This is what allows the AppModule preload at
    // onPostAuthenticated (the ~800ms cold-boot win) to coexist with
    // unconditional start() calls from the various ViewModels' init blocks
    // without re-running the whole load + enrichment pipeline.
    // Intended call sites — all user-initiated or startup:
    //   - AppModule onPostAuthenticated  (auth startup, preloads while UI composes)
    //   - ConversationListViewModel init (user navigates to list)
    //   - ArchivedConversationsViewModel init (user opens archived screen)
    //   - GroupSettingsViewModel init     (user opens group settings)
    // Do NOT call from DriveEvent.Stopped or other sync events — see init block above.

    /**
     * Clear all in-memory state so a subsequent [start] loads cleanly for
     * a different identity. Called from [onPostAuthenticated] before [start].
     */
    fun reset() {
        started = false
        _conversations.value = ConversationsData(dataReady = false)
        _shareableConversations.value = emptyList()
        deletedIds.clear()
    }

    fun start() {
        if (started) return
        started = true
        Logger.d("ConversationStream: start() — loading full conversation list from DB")
        scope.launch {
            // MANDATORY — flips dataReady=true for the UI.
            loadBasicConversations()

            // ENRICHMENT — three passes run sequentially. All three go through
            // the single-threaded DB dispatcher anyway, so there's no benefit
            // to parallelism and sequencing keeps the log trace readable.
            // Ordering is deliberate: last-messages first (also drives the sort),
            // then admins (group-settings only), then unread counts.
            enrichWithLastMessages()
            enrichWithAdmins()
            enrichAllConversationsWithUnreadCounts(trigger = "ColdLoad")
        }

        // Reactively update share cache when conversations or contacts change,
        // so the iOS share extension always has resolved display names.
        // Only launch once — subsequent start() calls reuse the existing collector.
        if (shareCacheJob == null) {
            shareCacheJob = scope.launch {
                @OptIn(kotlinx.coroutines.FlowPreview::class)
                combine(
                    _conversations,
                    contactService.contacts,
                ) { convos, contacts -> Pair(convos, contacts) }
                    .debounce(500) // Avoid rapid writes during initial load
                    .collect { (convos, contacts) ->
                        if (convos.dataReady) {
                            updateShareCache(convos.items, contacts)
                        }
                    }
            }
        }
    }

    override fun getConversationById(conversationId: Uuid): ConversationUiModel? {
        return _conversations.value.items.firstOrNull { it.id == conversationId }
    }

    fun onConversationLeft(conversationId: Uuid) {
        _conversations.value = _conversations.value.copy(
            items = _conversations.value.items.map { convo ->
                if (convo.id == conversationId)
                    convo.copy(conversationState = ConversationState.Left)
                else convo
            }
        )
    }

    /**
     * Drop the conversation from the in-memory list immediately. Called from the
     * conversation-delete UI flow so the row disappears as soon as the service-side
     * delete is enqueued, instead of lingering as a "deleted conversation" placeholder
     * (the result of [ConversationMapper.mapDeletedConversation] firing on the soft-
     * deleted file) until the next app start.
     *
     * Also records the id in [deletedIds] so [loadBasicConversations] keeps it out
     * of the list across subsequent reloads; without this the row would be
     * resurrected by the next DB read because the file is still on disk (soft-
     * deleted) until the outbox processes the server-side delete.
     */
    fun onConversationDeleted(conversationId: Uuid) {
        deletedIds += conversationId
        _conversations.value = _conversations.value.copy(
            items = _conversations.value.items.filterNot { it.id == conversationId }
        )
    }

    override suspend fun getRecipients(
        conversationId: Uuid,
        additionalRecipients: List<OdinId>
    ): List<OdinId> {

        val domain = credentialsManager.requireActiveDomain()
        val conversation = getConversationById(conversationId) ?: return listOf()
        val recipients =
            (conversation.participants + additionalRecipients).filter { it != domain }.distinct()
        return recipients
    }

    private suspend fun updateShareCache(
        conversations: List<ConversationUiModel>,
        contacts: List<id.homebase.chat.data.ContactUiModel>,
    ) {
        try {
            val activeDomain = credentialsManager.getActiveDomain() ?: return
            val domain = activeDomain.domainName
            val contactMap = contacts.associateBy { it.odinId }

            val shareable = conversations.map { convo ->
                val otherParticipant = convo.participants
                    .firstOrNull { it != activeDomain }

                val avatarUrl = if (!convo.isGroupConversation && otherParticipant != null) {
                    "https://${otherParticipant.domainName}/pub/image"
                } else null

                // Resolve contact name using the same contact map pattern as ConversationEnricher
                val displayName = if (!convo.isGroupConversation && otherParticipant != null) {
                    contactMap[otherParticipant]?.name ?: convo.getDisplayName()
                } else {
                    convo.getDisplayName()
                }

                ShareableConversation(
                    id = convo.id.toString(),
                    displayName = displayName,
                    avatarInitials = convo.avatarInitials,
                    isGroup = convo.isGroupConversation,
                    participantCount = convo.participants.size,
                    lastMessageTimestamp = convo.latestMessageTimestamp.toEpochMilliseconds(),
                    avatarUrl = avatarUrl,
                )
            }
            _shareableConversations.value = shareable
            shareCacheWriter.updateCache(shareable, domain)

            // Pre-cache group avatar images for the iOS share extension
            for (convo in conversations) {
                if (convo.avatarModel.type == ConversationAvatarModel.Type.ConversationImage) {
                    val imageData = convo.avatarModel.imageData ?: continue
                    try {
                        val cached = imageLoader.loadThumbnail(imageData, ImageSize.THUMB_SMALL)
                        if (cached != null) {
                            cacheStorage.writeGroupAvatar(convo.id.toString(), cached.bytes)
                        }
                    } catch (e: Exception) {
                        Logger.d(tag = "ConversationStream") {
                            "Failed to cache group avatar for ${convo.id}: ${e.message}"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Logger.e(tag = "ConversationStream") { "Failed to update share cache: ${e.message}" }
        }
    }
}

data class ConversationsData(
    val dataReady: Boolean = true,
    val items: List<ConversationUiModel> = emptyList(),
    val enrichment: EnrichmentState = EnrichmentState(),
)

/**
 * Tracks which optional enrichment passes have completed for the current
 * conversation list.
 *
 * All flags start `false`. The UI must be able to render with any
 * combination — a basic-only emit (all flags false) is legal and expected
 * on cold start. Each enrichment pass in [ConversationStream] flips its
 * own flag when its data has been merged into the list.
 *
 * Flags never go back to false for a given [ConversationsData] instance;
 * a new basic load (e.g. after logout / re-auth) replaces the whole value
 * with a fresh default [EnrichmentState].
 */
data class EnrichmentState(
    /** `true` once [ConversationStream.enrichWithLastMessages] has patched
     *  `lastMessage*` fields + reordered by latest timestamp. */
    val hasLastMessages: Boolean = false,
    /** `true` once [ConversationStream.enrichWithAdmins] has resolved
     *  admin sets for group conversations. */
    val hasAdmins: Boolean = false,
    /** `true` once [ConversationStream.enrichAllConversationsWithUnreadCounts] has applied
     *  the unread counts from ChatReadCount. */
    val hasUnreadCounts: Boolean = false,
)

