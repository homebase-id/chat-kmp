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
) {

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
    /** Called when a message arrives for a conversation that is missing or soft-deleted.
     *  Wired in AppModule to ConversationService.recoverConversation(). */
    var onRecoverConversation: (suspend (conversationId: Uuid, originalAuthor: OdinId) -> Unit)? = null
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
                    updateUnreadCounts()
                    return@collect
                }

                if (event !is BackendEvent.DriveEvent || event.driveId != chatDrive) return@collect

                when (event) {
                    is BackendEvent.DriveEvent.Started -> {}

                    is BackendEvent.DriveEvent.Stopped -> {
                        Logger.d("ConversationStream: Stopped(totalCount=${event.totalCount})")
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
                    _conversations.value = ConversationsData(
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
                _conversations.value = ConversationsData(
                    items = _conversations.value.items.map { if (it.id == revived.id) revived else it }
                )
                updateConversationFromNewMessage(revived, m)

                // Trigger server-side revival via unified recovery
                val revivalAuthor = m.originalAuthor
                if (revivalAuthor != null) {
                    scope.launch {
                        try {
                            onRecoverConversation?.invoke(m.conversationId, revivalAuthor)
                            Logger.i("ConversationStream: deleted conversation ${m.conversationId} — recovery triggered successfully")
                        } catch (e: Exception) {
                            Logger.e(e) { "ConversationStream: deleted conversation ${m.conversationId} — recovery FAILED: ${e.message}" }
                        }
                    }
                } else {
                    Logger.w("ConversationStream: deleted conversation ${m.conversationId} — cannot recover, originalAuthor is null")
                }
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

                // region Recovery: missing conversation file
                Logger.w("ConversationStream: orphaned conversation ${m.conversationId} from=${m.originalAuthor} isOneToOne=$isOneToOne, creating placeholder")
                insertNewConversation(emptyConversation)

                if (m.originalAuthor != null) {
                    Logger.i("ConversationStream: orphaned conversation ${m.conversationId} — triggering recovery for author=${m.originalAuthor}")
                    scope.launch {
                        try {
                            onRecoverConversation?.invoke(m.conversationId, m.originalAuthor)
                            Logger.i("ConversationStream: orphaned conversation ${m.conversationId} — recovery triggered successfully")
                        } catch (e: Exception) {
                            Logger.e(e) { "ConversationStream: orphaned conversation ${m.conversationId} — recovery FAILED: ${e.message}" }
                        }
                    }
                } else {
                    Logger.w("ConversationStream: orphaned conversation ${m.conversationId} — cannot recover, originalAuthor is null")
                }
                // endregion
            } else {
                updateConversationFromNewMessage(matchingConversation, m)
            }
        }

        // Sort by descending timestamp (adjust based on your UI needs)
        val sortedList = _conversations.value.items.sortedByDescending { it.latestMessageTimestamp }
        _conversations.value = ConversationsData(true, sortedList)
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

    suspend fun loadConversation(conversationId: Uuid) {
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
        }

        // Sort by descending timestamp (adjust based on your UI needs)
        val sortedList = _conversations.value.items.sortedByDescending { it.latestMessageTimestamp }
        _conversations.value = ConversationsData(items = sortedList)
    }

    private fun insertNewConversation(conversation: ConversationUiModel) {
        // We should optimize later to not copy the full list
        val currentList = _conversations.value.items.toMutableList()
        currentList.add(conversation)

        _conversations.value = ConversationsData(items = currentList)
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
        _conversations.value =
            ConversationsData(items = _conversations.value.items.map { if (it.id == existing.id) updatedConvo else it })
    }

    private suspend fun loadConversations() {
        val startedAt = Clock.System.now().toEpochMilliseconds()
        val leftIds = _conversations.value.items
            .filter { it.conversationState == ConversationState.Left }
            .map { it.id }
            .toSet()

        val result = fetchConversations().map { convo ->
            if (convo.id in leftIds && convo.conversationState != ConversationState.Left)
                convo.copy(conversationState = ConversationState.Left)
            else convo
        }
        Logger.i(tag = "ConvListPerf") {
            "loadConversations end-to-end=${Clock.System.now().toEpochMilliseconds() - startedAt}ms items=${result.size}"
        }
        _conversations.value = ConversationsData(items = result)
    }

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
            loadConversations()
        }
        scope.launch {
            _conversations.first { it.dataReady }
            updateUnreadCounts()
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

    suspend fun updateUnreadCounts() {
        val startedAt = Clock.System.now().toEpochMilliseconds()
        val c = credentialsManager.requireActiveCredentials()
        val unread = dbm.chatReadCount.selectAllUnreadCount(c.getIdentityId(), c.domain)
        val unreadMap = unread.associate { it.conversationId to it.unreadCount.toInt() }

        var changed = 0

        val updated = _conversations.value.items.map { convo ->
            val newCount = unreadMap[convo.id] ?: 0
            if (newCount != convo.unreadCount) {
                changed++
                Logger.d("ConversationStream: unreadSync convo=${convo.id} ${convo.unreadCount}->${newCount}")
                convo.copy(unreadCount = newCount)
            } else {
                convo
            }
        }

        if (changed > 0) {
            _conversations.value = ConversationsData(items = updated)
        }
        Logger.i(tag = "ConvListPerf") {
            "updateUnreadCounts=${Clock.System.now().toEpochMilliseconds() - startedAt}ms changedRows=$changed totalRows=${updated.size}"
        }
    }

    suspend fun fetchConversations(): List<ConversationUiModel> {

        val c = credentialsManager.requireActiveCredentials()
        val queryStart = Clock.System.now().toEpochMilliseconds()
        val result = dbm.chatReadCount.selectAllConversationPlusLastMessage(c.getIdentityId())
        val afterQuery = Clock.System.now().toEpochMilliseconds()
        val conversationIds = ArrayList<Uuid>(result.size)
        for (row in result) {
            val id = row.conversation.fileMetadata.appData.uniqueId
            if (id != null) conversationIds.add(id)
        }
        val adminMap = ConversationAdminInfo.queryBatchFromDb(
            credentialsManager, dbm, chatDrive, conversationIds
        )
        val afterAdmins = Clock.System.now().toEpochMilliseconds()
        val mapped = result.map {
            mapper.mapToConversationUi(it.conversation, it.message, adminMap)
        }
        val afterMap = Clock.System.now().toEpochMilliseconds()
        Logger.i(tag = "ConvListPerf") {
            "fetchConversations: wrapperQueryPlusHeaderMap=${afterQuery - queryStart}ms batchAdmins=${afterAdmins - afterQuery}ms(hits=${adminMap.size}/${conversationIds.size}) outerMapToUi=${afterMap - afterAdmins}ms total=${afterMap - queryStart}ms items=${mapped.size}"
        }
        return mapped

    }

    fun getConversationById(conversationId: Uuid): ConversationUiModel? {
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

    suspend fun getRecipients(
        conversationId: Uuid,
        additionalRecipients: List<OdinId> = emptyList()
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
)
