package id.homebase.chat.services.convo

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.auth.OwnerSessionRepository
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.OdinId
import id.homebase.api.common.publicImageUrl
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.OutboxSync
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
import id.homebase.core.config.momentsLabeledDrive
import id.homebase.core.image.HomebaseImageLoader
import id.homebase.core.image.ImageSize
import id.homebase.core.share.ShareCacheStorage
import id.homebase.core.share.ShareConversationCacheWriter
import id.homebase.core.share.ShareableConversation
import id.homebase.core.sync.OptionalDriveActivation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

// Persisted, version-gated marker for the one-time orphaned-at-rest sweep. Stored
// in the encrypted KeyValue table (UUID namespace 0b01 = recovery/maintenance), so
// it's scoped to the DB/account lifecycle — wiped on logout, gone on reinstall — and
// the sweep correctly re-runs whenever the DB is rebuilt. Bump
// ORPHAN_AT_REST_SWEEP_VERSION to force existing installs to re-sweep exactly once.
private val ORPHAN_SWEEP_KEY: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000b0101")
private const val ORPHAN_AT_REST_SWEEP_VERSION = 1
private const val ORPHAN_SWEEP_BOOT_DELAY_MS = 10_000L

// Debounce for coalesced unread-count enrichment: a sync burst of triggers collapses into a
// single run after this quiet window. Unread counts aren't latency-critical, so a generous
// window is fine — it just has to settle a sync storm into one full-DB pass. See the
// coalesced-enrichment region in ConversationStream.
private const val UNREAD_ENRICH_DEBOUNCE_MS = 500L

class ConversationStream(
    private val credentialsManager: CredentialsManager,
    private val contactService: ContactService,
    private val ownerSessionRepository: OwnerSessionRepository,
    private val dbm: DatabaseManager,
    private val eventBus: EventBus,
    private val scope: CoroutineScope,
    private val shareCacheWriter: ShareConversationCacheWriter,
    private val imageLoader: HomebaseImageLoader,
    private val cacheStorage: ShareCacheStorage,
    private val optimisticWriter: OptimisticWriter,
    private val outboxSync: OutboxSync,
    private val optionalDriveActivation: OptionalDriveActivation,
) : ConversationLoader, ConversationParticipantLookup {

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
     * self-destruct it (soft-delete + hard-delete) once cleanup runs.
     */
    var onIncomingHealRequest: (suspend (status: StatusMessageData, sender: OdinId, messageFile: HomebaseFile) -> Unit)? = null

    /**
     * Hook invoked when the live receive stream observes an incoming
     * [StatusMessage.EmergencyContactDesignated] status — i.e. the [sender] designated us as one of
     * their emergency contacts. Wired in AppModule to mark the [sender] as an emergency contact on
     * our own contact drive. Side effect only — does not affect message-list dispatch.
     */
    var onEmergencyContactDesignated: (suspend (sender: OdinId, messageFile: HomebaseFile) -> Unit)? = null

    /**
     * Hook invoked when the live receive stream observes an incoming
     * [StatusMessage.EmergencyContactRevoked] status — i.e. the [sender] removed us from their
     * emergency circle. Wired in AppModule to clear our can-locate flag for the [sender]. Side effect
     * only — does not affect message-list dispatch.
     */
    var onEmergencyContactRevoked: (suspend (sender: OdinId, messageFile: HomebaseFile) -> Unit)? = null
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

    // One-shot latch for the orphaned-at-rest scan (conversations with messages
    // but no header). Flipped only inside the sequential eventBus.events.collect
    // (the Stopped handler), so a plain var is safe — no atomic needed. Runs once
    // per process, on the first clean chat-drive sync completion.
    private var orphanedAtRestScanDone = false
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

    // region Coalesced unread enrichment
    //
    // A sync burst used to fire one enrichAllConversationsWithUnreadCounts per WebSocket
    // batch / DriveSync-Stopped, each a full-DB GROUP BY + single-writer mirror upsert. With
    // no guard, N triggers spawned N concurrent runs that piled onto the read lane and the
    // writer — measured at 47–131s under heavy sync. Funnel the recurring triggers through a
    // [CoalescingTrigger]: rapid requests collapse to the latest, a short debounce batches a
    // burst, and its single collector runs them one at a time. [enrichMutex] additionally
    // guards against overlap with the ColdLoad direct call in start().
    private val enrichMutex = Mutex()
    private val unreadEnrichTrigger = CoalescingTrigger<String>(scope, UNREAD_ENRICH_DEBOUNCE_MS) {
        enrichAllConversationsWithUnreadCounts(it)
    }

    private fun requestUnreadEnrichment(trigger: String) {
        unreadEnrichTrigger.request(trigger)
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
    // 2) DriveSync catch-up — [DriveEvent.Stopped] (any termination) for the chat drive
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
                    // Logout: drop the previous identity's conversation list so it
                    // doesn't linger on the login screen or bleed into the next
                    // session. The DB is wiped concurrently by logout(); this clears
                    // the in-memory mirror. start() reloads cold on the next login.
                    is BackendEvent.SessionEnded -> reset()
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
                        // Gate on totalCount > 0 ALONE (not on result == Success).
                        // DriveSync.performSync increments totalCount per batch and
                        // earlier batches' DB writes complete before any later
                        // batch can fail, so Stopped(Aborted, totalCount > 0)
                        // still means real conversation/message/admin rows landed.
                        // Waiting for the next Success round would hide those rows
                        // — and on PermissionDenied there is no next round at all.
                        //
                        // First three steps unconditional (DriveSync may have
                        // changed conversation files, last-messages, or admin
                        // membership without dirtying lastRead). The unread-count
                        // recount goes through [shouldRunUnreadRecountOnStopped]:
                        // pre-fix the gate was `hasDirtyUnread()` alone, which
                        // missed the common case of "BG sync wrote message rows
                        // via QueryBatch" because the dirty bit is only set by
                        // the WS-push code path (BatchReceived ->
                        // processConversationBatchIncrementally). The widened
                        // gate adds `chatDrive && totalCount > 0`, so any
                        // DriveSync round that landed chat rows triggers a
                        // recount — the original asymmetric-badge bug
                        // (homebase.log 2026-06-01 Session 5: warm-swipe +
                        // FCM Frodo + WS-push Sam; Sam bumped via WS, Frodo's
                        // QB-delivered rows never tripped the gate).
                        // Cost: one selectAllUnreadCount (~121-163ms covering-
                        // index pass measured live) per chat-drive Stopped
                        // that carried rows. Cheap; trades a small recurrent
                        // cost for badge correctness.
                        if (event.totalCount > 0) {
                            val isDirty = hasDirtyUnread()
                            val shouldRecount = shouldRunUnreadRecountOnStopped(
                                isChatDrive = true, // filtered above at line 233
                                totalCount = event.totalCount,
                                hasDirtyUnread = isDirty,
                            )
                            Logger.i(tag = "UnreadDiag") {
                                "Stopped recount-gate driveId=${event.driveId} " +
                                    "totalCount=${event.totalCount} " +
                                    "result=${event.result::class.simpleName} " +
                                    "hasDirtyUnread=$isDirty " +
                                    "decision=${if (shouldRecount) "RECOUNT" else "SKIP"}"
                            }
                            scope.launch {
                                try {
                                    loadBasicConversations()
                                    enrichWithLastMessages()
                                    enrichWithAdmins()
                                    if (shouldRecount) {
                                        requestUnreadEnrichment("DriveSyncStopped")
                                    }
                                } catch (e: Exception) {
                                    Logger.e(e) {
                                        "ConversationStream: post-Stopped reload FAILED: ${e.message}"
                                    }
                                }
                            }
                        }

                        // One-shot, once per process: after the chat drive's initial sync
                        // has cleanly drained (so a missing header is genuinely missing, not
                        // mid-download), scan for conversations that have messages but no
                        // header — "orphaned at rest" (e.g. a 1:1 whose header is gone from
                        // both the server and local DB; it has no broken row for the
                        // Invalid-row path to flag, and no incoming message for the live
                        // orphan path to catch, so it falls through every other net).
                        // Deliberately NOT gated on totalCount > 0: such orphans are
                        // pre-existing, and the common cold-start case is a no-op (cursor
                        // already at HEAD, totalCount == 0). Fire-and-forget; never blocks.
                        if (event.result is BackendEvent.DriveResult.Completed && !orphanedAtRestScanDone) {
                            orphanedAtRestScanDone = true
                            sweepOrphanedAtRestOnce()
                        }
                    }

                    is BackendEvent.DataEvent.BatchReceived -> {
                        if (event.driveId != chatDrive) return@collect
                        // Every BatchReceived is a live event from the WS push path
                        // (DriveWebSocketUpsertWorker or its in-process analog
                        // OptimisticWriter). DriveSync.performSync is silent — the
                        // matching DriveEvent.Stopped(totalCount > 0) above does a full DB
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

    private suspend fun dispatchEmergencyDesignations(messageFiles: List<HomebaseFile>) {
        val handler = onEmergencyContactDesignated ?: return
        for (file in messageFiles) {
            val appData = file.fileMetadata.appData
            if (appData.dataType != ChatProtocol.ChatStatusMessageDataType) continue
            // originalAuthor is null on our own synced copy, so this only fires on the receiver side.
            val sender = file.fileMetadata.originalAuthor ?: file.fileMetadata.senderOdinId ?: continue
            val content = appData.content ?: continue
            val status = runCatching {
                OdinSystemSerializer.deserialize<StatusMessageData>(content)
            }.getOrNull() ?: continue
            if (status.statusMessage != StatusMessage.EmergencyContactDesignated) continue
            try {
                handler(sender, file)
            } catch (e: Exception) {
                Logger.e(e) { "ConversationStream: emergency-designation handler threw for sender=${sender.domainName}: ${e.message}" }
            }
        }
    }

    private suspend fun dispatchEmergencyRevocations(messageFiles: List<HomebaseFile>) {
        val handler = onEmergencyContactRevoked ?: return
        for (file in messageFiles) {
            val appData = file.fileMetadata.appData
            if (appData.dataType != ChatProtocol.ChatStatusMessageDataType) continue
            // originalAuthor is null on our own synced copy, so this only fires on the receiver side.
            val sender = file.fileMetadata.originalAuthor ?: file.fileMetadata.senderOdinId ?: continue
            val content = appData.content ?: continue
            val status = runCatching {
                OdinSystemSerializer.deserialize<StatusMessageData>(content)
            }.getOrNull() ?: continue
            if (status.statusMessage != StatusMessage.EmergencyContactRevoked) continue
            try {
                handler(sender, file)
            } catch (e: Exception) {
                Logger.e(e) { "ConversationStream: emergency-revocation handler threw for sender=${sender.domainName}: ${e.message}" }
            }
        }
    }

    private suspend fun processMessageBatchIncrementally(messageFiles: List<HomebaseFile>) {
        if (messageFiles.isEmpty()) throw IllegalArgumentException("It can't be empty")

        // Pre-pass: dispatch GroupHealRequested status messages to the heal
        // handler. Done here (live BatchReceived only — never on cold reads or
        // searches) so the side effects fire exactly once per arrival.
        dispatchGroupHealRequests(messageFiles)
        // Same live-only contract: mark the sender as an emergency contact when they designate us,
        // and clear that mark when they revoke us.
        dispatchEmergencyDesignations(messageFiles)
        dispatchEmergencyRevocations(messageFiles)

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

        // Process every incoming event — no by-id de-dupe. The chat-drive WS can
        // legitimately deliver two *distinct* events for one logical write (e.g. a
        // reaction emits fileModified + statisticsChanged with the same uniqueId);
        // collapsing by id would silently drop a real event. Re-processing a
        // same-id event is safe here: each branch below re-reads
        // `matchingConversation` from live `_conversations.value`, so unarchive /
        // revive / orphan-placeholder no-op on the second pass, and the unread bump
        // is guarded by `sqlUserDate <= latestMessageTimestamp` in
        // [applyIncomingMessageBump], so re-emits carrying the original message's
        // userDate never double-count.
        for ((file, m) in incoming) {
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
                        lastMessageIsPendingSend = m.isPendingSend,
                        lastMessageIsDeleted = m.isDeleted,
                        lastMessageFirstPayload = m.payloads?.firstOrNull(),
                        lastMessageHasMultiplePayloads = (m.payloads?.size ?: 0) > 1,
                        lastMessageContent = m.messageContent,
                        lastMessageIsFromActiveUser =
                            m.isFromActiveUser(credentialsManager.getActiveDomain()),
                        lastMessageSender = m.originalAuthor,
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

        // Diagnostic for the asymmetric-badge investigation (plan
        // snazzy-frolicking-crown). End-of-batch snapshot of every
        // conversation touched by this batch, so a later enrichUnreadLocked
        // overwrite that drops the badge can be correlated against the
        // value the in-memory bump actually landed on. The existing
        // `unread++` log at line 620 only fires when the count rose; this
        // covers the no-bump-but-touched case (e.g. suppressed by
        // `sqlUserDate <= existing.latestMessageTimestamp`).
        val touchedIds = incoming.map { it.second.conversationId }.distinct()
        for (id in touchedIds) {
            val c = sortedList.firstOrNull { it.id == id } ?: continue
            Logger.d(tag = "UnreadDiag") {
                "batchDone convo=$id unreadCount=${c.unreadCount} " +
                    "latestMessageMs=${c.latestMessageTimestamp.toEpochMilliseconds()} " +
                    "lastReadMs=${c.lastRead.toEpochMilliseconds()}"
            }
        }
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
            requestUnreadEnrichment("WebSocketBatch")
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
        // All value reconciliation (state stickiness, exitedAt, lastRead/dirty,
        // structural-vs-preview field ownership) lives in the pure
        // [mergeConversationFileUpdate]; this method keeps only the side effects.
        val result = mergeConversationFileUpdate(
            existing = existing,
            incoming = incoming,
            now = UnixTimeUtc().toInstant(),
        )

        // Stamp lastExitedAt in localAppData the first time we detect a Removed
        // transition. The mapper reads it back from localAppData on subsequent
        // loads (cold start, reconnect).
        if (result.isNewlyRemoved) {
            optimisticWriter.stampConversationExitedAt(chatDrive, existing.id)
                ?.let { outboxSync.tryEnqueue(it) }
        }

        // We should optimize later to not map the full list
        _conversations.value = _conversations.value.copy(
            items = _conversations.value.items.map { if (it.id == existing.id) result.merged else it }
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

        // Snapshot the prior in-memory rows so the wholesale replace below stays
        // non-destructive of derived state: an un-flushed local lastRead advance
        // (+ its owe-push `dirty`) and the computed `unreadCount` live only in
        // memory and would otherwise be reset to the disk row. We reconcile each
        // reloaded row against its prior counterpart instead. Empty on cold
        // start ⇒ no merge, rows come straight from disk.
        val priorById = _conversations.value.items.associateBy { it.id }

        val files = dbm.chatReadCount.selectAllConversations(c.getIdentityId())
        val afterQuery = Clock.System.now().toEpochMilliseconds()

        val basicWithSource = files
            // Locally-deleted conversations stay on disk (soft-deleted) until the
            // outbox processes the server-side delete; without this filter the
            // mapper would resurrect a "deleted conversation" placeholder for them
            // on every reload. See [onConversationDeleted].
            .filterNot { it.fileMetadata.appData.uniqueId in deletedIds }
            .mapNotNull { file ->
                // [ConversationMapper.mapToBasic] re-throws JVM `Error`s
                // (NoClassDefFoundError, LinkageError, OOM, …) instead of
                // routing them through the Invalid placeholder + recovery
                // path, because those errors are environment problems —
                // they have nothing to do with the file's content. Catch
                // them here and SKIP the row: leave the conv-file on disk
                // untouched, do not write a placeholder, do not delete it.
                // The next reload (or app restart with the env fixed) will
                // map it correctly. Surfacing a stale env error as "your
                // data is corrupt" is what mass-overwrote real conv-files
                // with placeholders during the missing-ImageSize incident.
                val ui = try {
                    mapper.mapToBasic(file)
                } catch (e: Error) {
                    Logger.e(throwable = e, tag = "ConvListPerf") {
                        "loadBasicConversations: JVM error mapping fileId=${file.fileId} " +
                            "uniqueId=${file.fileMetadata.appData.uniqueId} — SKIPPING this row " +
                            "(file untouched on disk; recovery NOT triggered). " +
                            "Likely a runtime/classpath issue, not file corruption."
                    }
                    return@mapNotNull null
                }
                val withLeft = if (ui.id in leftIds && ui.conversationState != ConversationState.Left) {
                    ui.copy(conversationState = ConversationState.Left)
                } else ui

                // Reconcile the disk row against the prior in-memory row so the
                // reload doesn't throw away local state:
                //  - lastRead = max, dirty kept only while our local read still
                //    leads disk (same rule as the WS receive merge), so an
                //    un-flushed local advance survives and still flushes.
                //  - unreadCount carried forward to avoid a flicker-to-0 (the
                //    disk row is always 0); when lastRead actually changed vs.
                //    the prior (a peer advance pulled to disk), mark it
                //    unread-dirty so the Stopped pipeline's recount corrects it.
                val prior = priorById[withLeft.id]
                val finalUi = if (prior != null) {
                    val reconciled = prior.reconciledWithRemoteLastRead(withLeft.lastRead)
                    if (reconciled.lastRead != prior.lastRead) markUnreadDirty(withLeft.id)
                    withLeft.copy(
                        lastRead = reconciled.lastRead,
                        dirty = reconciled.dirty,
                        unreadCount = prior.unreadCount,
                    )
                } else withLeft
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
     * Once-ever gate for the orphaned-at-rest recovery. Runs the sweep a single
     * time per DB lifetime: deferred past the contended boot window, gated on a
     * persisted KeyValue version flag (so it re-runs only if the DB is rebuilt —
     * KeyValue is wiped on logout — or if [ORPHAN_AT_REST_SWEEP_VERSION] is bumped).
     * Fire-and-forget; never blocks. All orphan logic lives here, not in the
     * [BackendEvent.DriveEvent.Stopped] handler.
     */
    private fun sweepOrphanedAtRestOnce() {
        val recover = onRecoverConversation ?: return
        scope.launch {
            try {
                // Off the boot window: the initial list mapping + WS connect both
                // hit the single SQLite connection (see the SQLITE_BUSY / WS-drop at
                // startup). Orphan recovery isn't urgent.
                delay(ORPHAN_SWEEP_BOOT_DELAY_MS)

                val storedVersion = readOrphanSweepVersion()
                if (storedVersion >= ORPHAN_AT_REST_SWEEP_VERSION) {
                    Logger.d(tag = "OrphanAtRest") {
                        "sweep already done (storedVersion=$storedVersion) — skipping"
                    }
                    return@launch
                }

                val recovered = recoverOrphanedAtRestConversations(recover)

                // Mark done ONLY after a clean sweep, so a failure retries next launch.
                dbm.keyValue.upsertValue(
                    ORPHAN_SWEEP_KEY,
                    encodeOrphanSweepVersion(ORPHAN_AT_REST_SWEEP_VERSION),
                )
                Logger.i(tag = "OrphanAtRest") {
                    "sweep complete: recovered=$recovered, marked version=$ORPHAN_AT_REST_SWEEP_VERSION"
                }
            } catch (e: Exception) {
                Logger.e(e) {
                    "ConversationStream: orphaned-at-rest sweep FAILED (will retry next launch): ${e.message}"
                }
            }
        }
    }

    /**
     * Scans for conversations that have live messages but no header, and recovers
     * each via [recover] — rebuilding a real 1:1 when a non-self counterparty is
     * known (otherwise recoverConversation falls back to a "1:1 repair" group).
     * Returns the number recovered. The scan's NOT-EXISTS-header filter naturally
     * skips any conversation the live orphan path already healed this session.
     */
    private suspend fun recoverOrphanedAtRestConversations(
        recover: suspend (conversationId: Uuid, originalAuthor: OdinId?) -> Unit,
    ): Int {
        val c = credentialsManager.requireActiveCredentials()
        val selfDomain = credentialsManager.requireActiveDomain().domainName
        val orphans = dbm.chatReadCount.selectOrphanedAtRestConversations(c.getIdentityId(), selfDomain)
        if (orphans.isEmpty()) {
            Logger.i(tag = "OrphanAtRest") { "orphaned-at-rest scan: none found" }
            return 0
        }
        Logger.w(tag = "OrphanAtRest") {
            "orphaned-at-rest scan: ${orphans.size} conversation(s) have messages but no header — recovering: " +
                orphans.joinToString(separator = "; ") {
                    "convoId=${it.conversationId} msgs=${it.messageCount} " +
                        "counterparty=${it.counterpartyAuthor ?: "unknown(self-only)"}"
                }
        }
        var recovered = 0
        for (orphan in orphans) {
            val counterparty = orphan.counterpartyAuthor?.let { runCatching { OdinId(it) }.getOrNull() }
            try {
                recover(orphan.conversationId, counterparty)
                recovered++
            } catch (t: Throwable) {
                Logger.e(throwable = t, tag = "OrphanAtRest") {
                    "recover failed for convoId=${orphan.conversationId}: ${t.message}"
                }
            }
        }
        return recovered
    }

    /** Reads the persisted orphaned-at-rest sweep version (0 when never run). */
    private suspend fun readOrphanSweepVersion(): Int {
        val bytes = runCatching { dbm.keyValue.selectByKey(ORPHAN_SWEEP_KEY)?.data_ }.getOrNull() ?: return 0
        if (bytes.size != 4) return 0
        return (bytes[0].toInt() and 0xFF shl 24) or
            (bytes[1].toInt() and 0xFF shl 16) or
            (bytes[2].toInt() and 0xFF shl 8) or
            (bytes[3].toInt() and 0xFF)
    }

    private fun encodeOrphanSweepVersion(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

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
    // Serialized entry point: [enrichMutex] ensures the ColdLoad direct call (start()) and the
    // channel-driven reruns (init collector) never overlap — each is a full-DB read + mirror
    // write, and concurrent runs were the 47–131s pileup.
    suspend fun enrichAllConversationsWithUnreadCounts(trigger: String) =
        enrichMutex.withLock { enrichUnreadLocked(trigger) }

    private suspend fun enrichUnreadLocked(trigger: String) {
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
            // Diagnostic for the asymmetric-badge investigation (plan
            // snazzy-frolicking-crown). Fires only when an in-memory unread
            // count is about to be erased to zero — the exact "badge
            // disappeared" symptom the user reported. We distinguish the
            // two hypotheses by whether the convo appears in unreadMap:
            //   - absent  ⇒ H2: the SQL filter excluded the row entirely
            //                   (originalAuthor IS NULL / fileState / dataType).
            //   - present ⇒ H1: the row is counted but lastReadTime is
            //                   saturated past the message.
            if (convo.unreadCount > 0 && newCount == 0) {
                val rowAbsent = !unreadMap.containsKey(convo.id)
                Logger.w(tag = "UnreadDiag") {
                    "badge-disappeared convo=${convo.id} oldCount=${convo.unreadCount} " +
                        "newCount=0 rowAbsentFromSqlMap=$rowAbsent " +
                        "lastReadMs=${convo.lastRead.toEpochMilliseconds()} " +
                        "latestMessageMs=${convo.latestMessageTimestamp.toEpochMilliseconds()} " +
                        "dirty=${convo.dirty} trigger=$trigger"
                }
            }
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
     * The list-level counterpart of [ConversationUiModel.advancedLastRead]:
     * runs that same entity transition on the in-memory entry for
     * [conversationId] (move lastRead + set dirty, together) and commits the
     * result back into the live list, re-deriving unreadCount from ChatReadCount
     * in the same state emission. Same name as the entity method on purpose —
     * the entity decides the next value, this applies it to the list. No-op if
     * the conversation isn't in memory yet or [candidate] isn't a genuine
     * advance.
     */
    override suspend fun advancedLastRead(conversationId: Uuid, candidate: Instant) {
        val convo = getConversationById(conversationId) ?: run {
            Logger.w(tag = "MarkAsRead") {
                "ConversationStream.advancedLastRead: convo=$conversationId NOT FOUND " +
                        "in in-memory list — UI badge will not update"
            }
            return
        }

        // The entity's single advance transition decides + moves lastRead +
        // sets dirty. If it's not a genuine advance, bail before the unread
        // query.
        if (convo.advancedLastRead(candidate) === convo) return

        val c = credentialsManager.requireActiveCredentials()
        val newCount = dbm.chatReadCount
            .selectUnreadCountForConversation(c.getIdentityId(), conversationId, c.domain)
            .toInt()

        updateConversation(conversationId) { current ->
            val advanced = current.advancedLastRead(candidate)
            // Layer the freshly-computed unread on top of the advance (lastRead
            // + dirty). `unreadCount` needs the DB so the model can't own it.
            if (advanced === current) current else advanced.copy(unreadCount = newCount)
        }
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

    // region lastRead dirty tracking — the in-memory list is the source of
    // truth; the entity (ConversationUiModel) owns the transitions (dirty is
    // set by advancedLastRead, applied to the list by [advancedLastRead] above),
    // this class only reads the flag and clears it after a push.
    override fun getDirtyConversationIds(): List<Uuid> =
        _conversations.value.items.filter { it.dirty }.map { it.id }

    override suspend fun clearLastReadDirtyIfUnchanged(conversationId: Uuid, pushed: UnixTimeUtc) {
        updateConversation(conversationId) { it.clearedDirtyIfUnchanged(pushed.milliseconds) }
    }

    /** Replace one conversation in the list via [transform] and emit. No-op if
     *  the conversation isn't present or [transform] returns an unchanged model. */
    private fun updateConversation(
        conversationId: Uuid,
        transform: (ConversationUiModel) -> ConversationUiModel,
    ) {
        val current = _conversations.value
        val index = current.items.indexOfFirst { it.id == conversationId }
        if (index < 0) return
        val updated = transform(current.items[index])
        if (updated == current.items[index]) return
        _conversations.value = current.copy(
            items = current.items.toMutableList().apply { this[index] = updated }
        )
    }
    // endregion

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
        additionalRecipients: List<OdinId>,
        recipientOverride: List<OdinId>?,
    ): List<OdinId> {

        val domain = credentialsManager.requireActiveDomain()
        fun compute(): List<OdinId>? {
            val base = recipientOverride
                ?: getConversationById(conversationId)?.participants
                ?: return null
            return (base + additionalRecipients).filter { it != domain }.distinct()
        }

        val first = compute()
        if (recipientOverride == null &&
            conversationId != ChatProtocol.ConversationWithYourselfId &&
            first.isNullOrEmpty()
        ) {
            // The in-memory row may be a placeholder/Invalid map (participants=[]) or
            // missing entirely (e.g. an archived 1:1 opened via Contacts) while the DB
            // conversation file maps fine — re-map it into memory and try once more (#934).
            loadConversation(conversationId)
            return compute() ?: emptyList()
        }
        return first ?: emptyList()
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
                    otherParticipant.publicImageUrl()
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
            val momentsActivated = optionalDriveActivation.isActivated(momentsLabeledDrive)
            shareCacheWriter.updateCache(
                shareable,
                domain,
                momentsActivated,
                ownerDisplayName = ownerSessionRepository.user.value?.displayName,
            )

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

