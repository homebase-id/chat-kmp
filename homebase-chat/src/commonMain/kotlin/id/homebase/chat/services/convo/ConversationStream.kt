package id.homebase.chat.services.convo

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.OdinId
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.OutboxSync
import id.homebase.api.util.truncateToCodePoints
import id.homebase.chat.data.ConversationState
import id.homebase.chat.data.ConversationUiModel
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.ChatMessageStream
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.XorIdUtil
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
    private var loadJob: Job? = null
    private var shareCacheJob: Job? = null

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
     *  Currently has no live caller — the previous sync-handler triggers
     *  were removed because enqueuing a server write from inside a
     *  drive-sync batch produced spurious conflicts (real file later in
     *  sync, or transfer-to-self). The plumbing is retained so a future
     *  explicit-recovery path (e.g. ensure-file-on-send, or a post-sync
     *  reconciliation pass) can wire in without touching DI. */
    var onRecoverConversation: (suspend (conversationId: Uuid, originalAuthor: OdinId) -> Unit)? = null
    // endregion

    // region Placeholder reconciliation
    // Conversation IDs whose in-memory placeholder hasn't yet been replaced
    // by a real fileType=8888 file from the sync stream. Populated when the
    // orphan branch in processMessageBatchIncrementally creates a placeholder;
    // drained in processConversationBatchIncrementally when a real file
    // arrives with a matching id; any remaining ids at DriveEvent.Stopped
    // get persisted to local DB so the conversation survives an app restart.
    //
    // Only mutated from inside the sequential `eventBus.events.collect { ... }`
    // loop in init, so no synchronization is needed. The reconciliation
    // coroutine takes a snapshot before the set is cleared.
    private val placeholderIds = mutableSetOf<Uuid>()
    // endregion

    // region Auto-unarchive: incoming message for archived conversation
    /** Called when a message arrives for an archived conversation.
     *  Wired in AppModule to ConversationService.unarchiveConversation(). */
    var onUnarchiveConversation: (suspend (conversationId: Uuid) -> Unit)? = null
    // endregion


    // The full conversation list is loaded once from the local DB on authentication
    // (via start(), called from onPostAuthenticated in AppModule).
    //
    // After that, all updates — whether from a reconnect syncAll() or a single WS
    // file notification — arrive as BatchReceived events and are applied incrementally.
    // We intentionally do NOT re-read the full list on DriveEvent.Stopped; the
    // incremental BatchReceived path is sufficient and avoids an expensive full reload
    // on every incoming message.
    init {
        scope.launch {
            eventBus.events.collect { event ->

                if (event is BackendEvent.ConnectionOnline) {
                    enrichWithUnreadCounts()
                    return@collect
                }

                if (event !is BackendEvent.DriveEvent || event.driveId != chatDrive) return@collect

                when (event) {
                    is BackendEvent.DriveEvent.Started -> {}

                    is BackendEvent.DriveEvent.Stopped -> {
                        Logger.d("ConversationStream: Stopped(totalCount=${event.totalCount})")
                        // If placeholders remain after sync completion, the real
                        // conversation files genuinely didn't arrive — persist
                        // the placeholders to local DB so they survive restart.
                        // Only on success: on failure we'd prefer to retry on the
                        // next sync rather than commit a speculative placeholder.
                        if (event.result is BackendEvent.DriveResult.Success &&
                            placeholderIds.isNotEmpty()
                        ) {
                            val toReconcile = placeholderIds.toSet()
                            placeholderIds.clear()
                            scope.launch {
                                try {
                                    reconcileUnresolvedPlaceholders(toReconcile)
                                } catch (e: Exception) {
                                    Logger.e(e) {
                                        "ConversationStream: placeholder reconciliation FAILED: ${e.message}"
                                    }
                                }
                            }
                        }
                    }

                    is BackendEvent.DriveEvent.BatchReceived -> {
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
                }
            }
        }
    }

    private suspend fun resolveDisplayName(file: HomebaseFile): String {
        val author = file.fileMetadata.originalAuthor ?: return ""

        return contactService.resolveByOdinId(author)?.name ?: author.domainName
    }

    private suspend fun processMessageBatchIncrementally(messageFiles: List<HomebaseFile>) {
        if (messageFiles.isEmpty()) throw IllegalArgumentException("It can't be empty")

        // For each file in the batch, map to model (fetch last message from DB if needed)
        val incomingMessages =
            messageFiles.mapNotNull { file ->
                ChatMessageStream.mapToMessageData(file, credentialsManager, ::resolveDisplayName)
            }

        if (messageFiles.size != incomingMessages.size)
            Logger.w("ConversationStream: ${messageFiles.size - incomingMessages.size} of ${messageFiles.size} messages failed to convert")

        for (m in incomingMessages) {
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
                updateConversationFromNewMessage(revived, m)

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
                // Determine 1:1 vs group so the placeholder has a useful avatar
                val activeDomain = credentialsManager.getActiveDomain()
                val isOneToOne = activeDomain != null && m.originalAuthor != null
                    && m.conversationId == XorIdUtil.getNewXorId(
                        activeDomain.domainName, m.originalAuthor.domainName
                    )

                val placeholderAvatar = if (isOneToOne) {
                    ConversationAvatarModel(
                        type = ConversationAvatarModel.Type.Connection,
                        odinId = m.originalAuthor
                    )
                } else {
                    ConversationAvatarModel(type = ConversationAvatarModel.Type.GroupFallback)
                }

                val placeholderParticipants = if (isOneToOne) {
                    listOf(activeDomain, m.originalAuthor).distinct()
                } else {
                    emptyList()
                }

                val emptyConversation =
                    ConversationUiModel(
                        id = m.conversationId,
                        name = "Conversation missing...",
                        lastMessage = m.content,
                        latestMessageTimestamp = m.userDate,
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
                // Intentionally do NOT enqueue a server-side recovery from
                // here. A drive-sync handler reads server state; enqueuing
                // a server write from inside that read produced spurious
                // "File already exists with ClientUniqueId" conflicts
                // (when the real conversation file is simply later in the
                // sync order) and "Cannot transfer to yourself" rejections
                // (when originalAuthor == self for groups we started), for
                // every login. Recovery is now self-healing:
                //   1. If the real conversation file exists on the server
                //      (the common case), it will arrive in a later sync
                //      batch and replace this placeholder via
                //      processConversationBatchIncrementally →
                //      updateConversation.
                //   2. If the server truly lacks the file, the placeholder
                //      stays until the user interacts with the
                //      conversation. A follow-up will add "ensure
                //      conversation file on send" to close that gap.
                Logger.w("ConversationStream: orphaned conversation ${m.conversationId} from=${m.originalAuthor} isOneToOne=$isOneToOne, creating placeholder")
                insertNewConversation(emptyConversation)
                placeholderIds += m.conversationId
                // endregion
            } else {
                updateConversationFromNewMessage(matchingConversation, m)
            }
        }

        // Sort by descending timestamp (adjust based on your UI needs)
        val sortedList = _conversations.value.items.sortedByDescending { it.latestMessageTimestamp }
        _conversations.value = _conversations.value.copy(dataReady = true, items = sortedList)
    }

    private suspend fun updateConversationFromNewMessage(
        c: ConversationUiModel,
        m: MessageUiModel
    ) {
        if (m.userDate >= c.latestMessageTimestamp) {
            val domain = credentialsManager.getActiveDomain()

            val increment =
                if (!m.isEdited && !m.isAuthoredBy(domain) && !m.isStatusMessage) 1 else 0
            if (increment > 0) {
                Logger.d("ConversationStream: unread++ convo=${c.id} count=${c.unreadCount + increment}")
            }

            val updatedConversation = c.copy(
                unreadCount = c.unreadCount + increment,
                latestMessageTimestamp = m.userDate,
                lastMessage = m.content.truncateToCodePoints(40), // TODO: Global constant
                lastMessageDeliveryStatus = m.messageAppData.deliveryStatus,
                lastMessageIsDeleted = m.isDeleted,
                lastMessageFirstPayload = m.payloads?.firstOrNull(),
                lastMessageHasMultiplePayloads = (m.payloads?.size ?: 0) > 1,
                lastMessageIsFromActiveUser = m.isAuthoredBy(credentialsManager.getActiveDomain()),
            )
            updateConversation(c, updatedConversation)
        }
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

    private suspend fun processAdminFileBatch(adminFiles: List<HomebaseFile>) {
        val conversationIds = adminFiles.mapNotNull { it.fileMetadata.appData.groupId }.distinct()
        for (conversationId in conversationIds) {
            loadConversation(conversationId)
        }
    }

    private suspend fun processConversationBatchIncrementally(
        conversationFiles: List<HomebaseFile>
    ) {
        // For each file in the batch, map to model (fetch last message from DB if needed)
        val incomingConversations =
            conversationFiles.map { file ->
                mapper.mapToConversationUi(file, null)
            }

        for (c in incomingConversations) {
            val matchingConversation = _conversations.value.items.find { it.id == c.id }
            if (matchingConversation == null) {
                insertNewConversation(c)
            } else {
                updateConversation(matchingConversation, c)
            }
            // A real file has now arrived for this id; it no longer needs
            // reconciliation. Safe whether or not it was ever a placeholder.
            placeholderIds -= c.id
        }

        // Sort by descending timestamp (adjust based on your UI needs)
        val sortedList = _conversations.value.items.sortedByDescending { it.latestMessageTimestamp }
        _conversations.value = _conversations.value.copy(items = sortedList)
    }

    private fun insertNewConversation(conversation: ConversationUiModel) {
        // We should optimize later to not copy the full list
        val currentList = _conversations.value.items.toMutableList()
        currentList.add(conversation)

        _conversations.value = _conversations.value.copy(items = currentList)
    }

    // region Placeholder reconciliation
    /**
     * Persist any in-memory placeholders whose real conversation file did
     * not arrive during the just-completed drive sync. Fired from the
     * [BackendEvent.DriveEvent.Stopped] handler for the chat drive.
     *
     * Each placeholder is written as a local-only row via
     * [OptimisticWriter.writeLocalOnlyConversationPlaceholder] — NO outbox
     * enqueue, NO server distribution. The row's `modified` timestamp is
     * set to 0 so a later-arriving real server file cleanly supersedes it
     * via the DriveMainIndex upsert guard.
     */
    private suspend fun reconcileUnresolvedPlaceholders(ids: Set<Uuid>) {
        if (ids.isEmpty()) return
        Logger.i("ConversationStream: reconciling ${ids.size} placeholder(s) — persisting to local DB")

        for (id in ids) {
            val existing = _conversations.value.items.find { it.id == id }
            if (existing == null) {
                Logger.w("ConversationStream: placeholder $id vanished before reconciliation, skipping")
                continue
            }
            val participants = existing.participants.filterNotNull()
            try {
                optimisticWriter.writeLocalOnlyConversationPlaceholder(
                    driveId = chatDrive,
                    conversationId = id,
                    participants = participants,
                    isGroup = existing.isGroup,
                )
            } catch (e: Exception) {
                Logger.e(e) {
                    "ConversationStream: failed to persist placeholder id=$id: ${e.message}"
                }
            }
        }
    }
    // endregion

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

        val basic = files.map { file ->
            val ui = mapper.mapToBasic(file)
            if (ui.id in leftIds && ui.conversationState != ConversationState.Left) {
                ui.copy(conversationState = ConversationState.Left)
            } else ui
        }

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

        val msgByConversation = HashMap<Uuid, id.homebase.api.client.drives.HomebaseFile>(rows.size)
        for (row in rows) {
            val convoId = row.conversation.fileMetadata.appData.uniqueId ?: continue
            val msg = row.message ?: continue
            msgByConversation[convoId] = msg
        }

        val current = _conversations.value
        val updated = current.items.map { ui ->
            val msgFile = msgByConversation[ui.id] ?: return@map ui
            mapper.applyLastMessage(ui, msgFile, domain)
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
     * ENRICHMENT — applies unread counts from the `ChatReadCount` table.
     *
     * Also invoked from message-read actions (see [ChatMessageActionService])
     * and on `BackendEvent.ConnectionOnline` to resync counts after a
     * reconnect. Flips `enrichment.hasUnreadCounts` (first call only;
     * subsequent calls just patch counts).
     *
     * Safe to defer, safe to skip, safe to retry.
     */
    suspend fun enrichWithUnreadCounts() {
        val startedAt = Clock.System.now().toEpochMilliseconds()
        val c = credentialsManager.requireActiveCredentials()
        val unread = dbm.chatReadCount.selectAllUnreadCount(c.getIdentityId(), c.domain)
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
            "enrichWithUnreadCounts=${Clock.System.now().toEpochMilliseconds() - startedAt}ms changedRows=$changed totalRows=${current.items.size}"
        }
    }

    /**
     * Single-conversation variant of [enrichWithUnreadCounts] — patches the
     * unread count for one conversation without re-scanning the whole list.
     * Called from message-read actions after the local read timestamp is
     * advanced for a specific conversation.
     */
    override suspend fun enrichConversationWithUnreadCounts(conversationId: Uuid) {
        Logger.d(tag = "MarkAsRead") {
            "ConversationStream.enrichConversationWithUnreadCounts: enter convo=$conversationId"
        }
        val c = credentialsManager.requireActiveCredentials()
        val newCount = dbm.chatReadCount
            .selectUnreadCountForConversation(c.getIdentityId(), conversationId)
            .toInt()
        Logger.d(tag = "MarkAsRead") {
            "ConversationStream.enrichConversationWithUnreadCounts: db newCount=$newCount convo=$conversationId"
        }

        val current = _conversations.value
        val index = current.items.indexOfFirst { it.id == conversationId }
        if (index < 0) {
            Logger.w(tag = "MarkAsRead") {
                "ConversationStream.enrichConversationWithUnreadCounts: convo=$conversationId NOT FOUND in in-memory list (size=${current.items.size}) — UI badge will not update from this call"
            }
            return
        }

        val convo = current.items[index]
        if (convo.unreadCount == newCount) {
            Logger.d(tag = "MarkAsRead") {
                "ConversationStream.enrichConversationWithUnreadCounts: convo=$conversationId no-op (unreadCount already $newCount)"
            }
            return
        }

        Logger.d(tag = "MarkAsRead") {
            "ConversationStream.enrichConversationWithUnreadCounts: convo=$conversationId unread ${convo.unreadCount}->$newCount (publishing to StateFlow)"
        }
        Logger.d("ConversationStream: unreadSync convo=${conversationId} ${convo.unreadCount}->${newCount}")
        val updated = current.items.toMutableList().apply {
            this[index] = convo.copy(unreadCount = newCount)
        }
        _conversations.value = current.copy(items = updated)
    }

    // endregion

    // Full conversation list load from local DB.  Idempotent (skips if already running).
    // Intended call sites — all user-initiated or startup:
    //   - AppModule onPostAuthenticated  (auth startup, preloads while UI composes)
    //   - ConversationListViewModel init (user navigates to list)
    //   - ArchivedConversationsViewModel init (user opens archived screen)
    //   - GroupSettingsViewModel init     (user opens group settings)
    // Do NOT call from DriveEvent.Stopped or other sync events — see init block above.
    fun start() {
        if (loadJob?.isActive == true) return
        Logger.d("ConversationStream: start() — loading full conversation list from DB")
        loadJob = scope.launch {
            // MANDATORY — flips dataReady=true for the UI.
            loadBasicConversations()

            // ENRICHMENT — three passes run sequentially. All three go through
            // the single-threaded DB dispatcher anyway, so there's no benefit
            // to parallelism and sequencing keeps the log trace readable.
            // Ordering is deliberate: last-messages first (also drives the sort),
            // then admins (group-settings only), then unread counts.
            enrichWithLastMessages()
            enrichWithAdmins()
            enrichWithUnreadCounts()
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
    /** `true` once [ConversationStream.enrichWithUnreadCounts] has applied
     *  the unread counts from ChatReadCount. */
    val hasUnreadCounts: Boolean = false,
)
