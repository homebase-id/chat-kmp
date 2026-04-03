package id.homebase.chat.services.convo

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.OdinId
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.QueryBatch
import id.homebase.api.util.truncateToCodePoints
import id.homebase.chat.data.ConversationUiModel
import id.homebase.chat.data.ConversationUiModel.Companion.updateWithLatestMessage
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.ChatMessageStream
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.convo.contact.ContactService
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
) {

    private val chatDrive = chatTargetDrive.alias
    private val _conversations = MutableStateFlow(ConversationsData(dataReady = false))
    private val _shareableConversations = MutableStateFlow<List<ShareableConversation>>(emptyList())
    private var loadJob: Job? = null
    private var shareCacheJob: Job? = null

    private val mapper: ConversationMapper = ConversationMapper(
        credentialsManager = credentialsManager
    )

    val conversations: StateFlow<ConversationsData> = _conversations.asStateFlow()
    val shareableConversations: StateFlow<List<ShareableConversation>> =
        _shareableConversations.asStateFlow()

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
                    is BackendEvent.DriveEvent.Started -> { }

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

                        Logger.d("ConversationStream: BatchReceived " +
                                "${event.batchData.size} files " +
                                "(conversations=${conversationFiles.size}, messages=${messageFiles.size})")

                        if (conversationFiles.isNotEmpty())
                            processConversationBatchIncrementally(conversationFiles)

                        if (messageFiles.isNotEmpty())
                            processMessageBatchIncrementally(messageFiles)
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
            if (matchingConversation == null) {
                val emptyConversation =
                    ConversationUiModel(
                        id = m.conversationId,
                        name = "Pending...",
                        lastMessage = m.content,
                        latestMessageTimestamp = m.userDate,
                        admins = (if (m.originalAuthor == null) emptySet() else setOf(m.originalAuthor)),
                        unreadCount = 0,
                        avatarTiny = null,
                        // Conversation has an image
                        avatarInitials = "AxB",
                        avatarUrl = "",
                        participants = emptyList(),
                        lastRead = UnixTimeUtc(0).toInstant(),
                        avatarModel =
                            ConversationAvatarModel(
                                type = ConversationAvatarModel.Type.GroupFallback,
                                imageData = null,
                                odinId = null,
                                initials = null
                            ),
                        lastMessageDeliveryStatus = m.messageAppData.deliveryStatus,
                        lastMessageIsDeleted = m.isDeleted,
                        lastMessageFirstPayload = m.payloads?.firstOrNull(),
                        lastMessageHasMultiplePayloads = (m.payloads?.size ?: 0) > 1,
                        lastMessageIsFromActiveUser =
                            m.isAuthoredBy(credentialsManager.getActiveDomain())
                    )

                Logger.w("ConversationStream: message arrived for unknown conversation ${m.conversationId}, creating placeholder")
                insertNewConversation(emptyConversation)
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

            val increment = if (!m.isEdited && !m.isAuthoredBy(domain) && !m.isStatusMessage) 1 else 0
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

    private fun updateConversation(existing: ConversationUiModel, incoming: ConversationUiModel) {
        // isPinned and conversationState are always applied regardless of timestamp ordering
        val updatedConvo = if (incoming.latestMessageTimestamp >= existing.latestMessageTimestamp) {
            existing.copy(
                name = incoming.name,
                avatarTiny = incoming.avatarTiny,
                avatarUrl = incoming.avatarUrl,
                avatarInitials = incoming.avatarInitials,
                participants = incoming.participants,
                latestMessageTimestamp = incoming.latestMessageTimestamp,
                lastMessage = incoming.lastMessage,
                lastMessageDeliveryStatus = incoming.lastMessageDeliveryStatus,
                lastMessageIsDeleted = incoming.lastMessageIsDeleted,
                lastMessageFirstPayload = incoming.lastMessageFirstPayload,
                lastMessageHasMultiplePayloads = incoming.lastMessageHasMultiplePayloads,
                lastMessageIsFromActiveUser = incoming.lastMessageIsFromActiveUser,
                isPinned = incoming.isPinned,
                conversationState = incoming.conversationState
            )
        } else {
            existing.copy(
                isPinned = incoming.isPinned,
                conversationState = incoming.conversationState
            )
        }
        // We should optimize later to not map the full list
        _conversations.value =
            ConversationsData(items = _conversations.value.items.map { if (it.id == existing.id) updatedConvo else it })
    }

    private suspend fun loadConversations() {
        val result = fetchConversations()
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
        val domain = credentialsManager.requireActiveDomain()
        val unread = dbm.chatReadCount.selectAllUnreadCount(domain)
        val unreadMap = unread.associate { it.conversationId to it.unreadCount.toInt() }

        var changed = false

        val updated = _conversations.value.items.map { convo ->
            val newCount = unreadMap[convo.id] ?: 0
            if (newCount != convo.unreadCount) {
                changed = true
                Logger.d("ConversationStream: unreadSync convo=${convo.id} ${convo.unreadCount}->${newCount}")
                convo.copy(unreadCount = newCount)
            } else {
                convo
            }
        }

        if (changed) {
            _conversations.value = ConversationsData(items = updated)
        }
    }

    suspend fun fetchConversations(): List<ConversationUiModel> {
        val result = dbm.chatReadCount.selectAllConversationPlusLastMessage()
        val conversations = result.map { mapper.mapToConversationUi(it.conversation, it.message) }
        val c = credentialsManager.requireActiveCredentials()
        val domain = c.domain
        var self = ChatProtocol.buildSelfConversation(domain)

        // Query the latest message for the self-conversation directly from the DB.
        // There is no conversation file for the self-conversation, so we query message files
        // by groupId and use the latest one to populate timestamp, lastMessage, etc.
        val queryBatch = QueryBatch(c.getIdentityId())
        val selfMessages = queryBatch.queryBatchAsync(
            dbm = dbm,
            driveId = chatDrive,
            noOfItems = 1,
            sortOrder = QueryBatchSortOrder.NewestFirst,
            sortField = QueryBatchSortField.CreatedDate,
            fileSystemType = 0,
            filetypesAnyOf = listOf(ChatProtocol.MessageFileType),
            groupIdAnyOf = listOf(ChatProtocol.ConversationWithYourselfId)
        )

        val latestMsg = selfMessages.records.firstOrNull()?.let {
            ChatMessageStream.mapToMessageData(it, credentialsManager) { file ->
                file.fileMetadata.originalAuthor?.domainName ?: ""
            }
        }

        if (latestMsg != null) {
            self = self.updateWithLatestMessage(latestMsg, domain)
        }

        return listOf(self) + conversations.filter { it.id != ChatProtocol.ConversationWithYourselfId }
    }

    fun getConversationById(conversationId: Uuid): ConversationUiModel? {
        return _conversations.value.items.firstOrNull { it.id == conversationId }
    }

    suspend fun getRecipients(conversationId: Uuid): List<OdinId> {

        val domain = credentialsManager.getActiveDomain()!!
        val conversation = getConversationById(conversationId) ?: return listOf()
        val recipients = conversation.participants.filter { it != domain }
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
