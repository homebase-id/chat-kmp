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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

class ConversationStream(
    private val credentialsManager: CredentialsManager,
    private val contactService: ContactService,
    private val dbm: DatabaseManager,
    private val eventBus: EventBus,
    private val scope: CoroutineScope
) {

    private val chatDrive = chatTargetDrive.alias
    private val _conversations = MutableStateFlow(ConversationsData(dataReady = false))
    private var isSyncing = false // Track if chat drive sync is in progress

    private val mapper: ConversationMapper = ConversationMapper(
        credentialsManager = credentialsManager
    )

    val conversations: StateFlow<ConversationsData> = _conversations.asStateFlow()

    init {
        scope.launch {
            eventBus.events.collect { event ->

                if (event is BackendEvent.ConnectionOnline) {
                    updateUnreadCounts()
                    return@collect
                }


                if (event !is BackendEvent.DriveEvent || event.driveId != chatDrive) return@collect

                when (event) {


                    is BackendEvent.DriveEvent.Started -> {
                        isSyncing = true
                    }

                    is BackendEvent.DriveEvent.Completed -> {
                        isSyncing = false
                        // After the drive has been synchronized we fetch all conversations once
                        // and their unread counts
                        start()
                        // From this point on we need to process all incoming messages /
                        // conversations
                        // so that everything in the client is up to date.
                    }

                    is BackendEvent.DriveEvent.Failed -> {
                        if (event.source == BackendEvent.SyncSource.DriveSync) {
                            isSyncing = false
                            Logger.e { "Failed during drive sync" }
                            start()
                            // Optionally handle failure, e.g., log or partial refresh
                        }
                    }

                    is BackendEvent.DriveEvent.BatchReceived -> {
                        if (!isSyncing) {
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

                            if (!conversationFiles.isEmpty())
                                processConversationBatchIncrementally(conversationFiles)

                            if (!messageFiles.isEmpty())
                                processMessageBatchIncrementally(messageFiles)
                        }
                        // Ignore during sync; refresh() will cover it post-Completed
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
                ChatMessageStream.mapToMessageData(file, ::resolveDisplayName)
            }

        if (messageFiles.size != incomingMessages.size)
            throw IllegalArgumentException("Size mismatch - conversion problem")

        for (m in incomingMessages) {
            val matchingConversation = _conversations.value.items.find { it.id == m.conversationId }
            if (matchingConversation == null) {
                val emptyConversation =
                    ConversationUiModel(
                        id = m.conversationId,
                        name = "Pending...",
                        lastMessage = m.content,
                        timestamp = m.created,
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

                insertNewConversation(emptyConversation)
            } else {
                updateConversationFromNewMessage(matchingConversation, m)
            }
        }

        // Sort by descending timestamp (adjust based on your UI needs)
        val sortedList = _conversations.value.items.sortedByDescending { it.timestamp }
        _conversations.value = ConversationsData(true, sortedList)
    }

    private suspend fun updateConversationFromNewMessage(
        c: ConversationUiModel,
        m: MessageUiModel
    ) {
        if (m.created > c.timestamp) {
            val domain = credentialsManager.getActiveDomain()

            // new message that was not sent by the current user
            val updatedConversation = c.copy(
                unreadCount = c.unreadCount + if (!m.isEdited && !m.isAuthoredBy(domain) && !m.isStatusMessage) 1 else 0,
                timestamp = m.created,
                lastMessage = m.content.truncateToCodePoints(40), // TODO: Global constant
                lastMessageDeliveryStatus = m.messageAppData.deliveryStatus,
                lastMessageIsDeleted = m.isDeleted,
                lastMessageFirstPayload = m.payloads?.firstOrNull(),
                lastMessageHasMultiplePayloads = (m.payloads?.size ?: 0) > 1,
                lastMessageIsFromActiveUser = m.isAuthoredBy(credentialsManager.getActiveDomain()),
            )
            updateConversation(c, updatedConversation)
        }


        // Logger.i("Unread count now ${c.unreadCount} edited ${m.isEdited} on conversation id
        // ${c.id}")
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
        val sortedList = _conversations.value.items.sortedByDescending { it.timestamp }
        _conversations.value = ConversationsData(items = sortedList)
    }

    private fun insertNewConversation(conversation: ConversationUiModel) {
        // We should optimize later to not copy the full list
        val currentList = _conversations.value.items.toMutableList()
        currentList.add(conversation)

        _conversations.value = ConversationsData(items = currentList)
    }

    private fun updateConversation(existing: ConversationUiModel, incoming: ConversationUiModel) {
        // Update the existing conversation
        if (incoming.timestamp >= existing.timestamp) {
            val updatedConvo =
                existing.copy(
                    name = incoming.name,
                    avatarTiny = incoming.avatarTiny,
                    avatarUrl = incoming.avatarUrl,
                    avatarInitials = incoming.avatarInitials,
                    participants = incoming.participants,
                    timestamp = incoming.timestamp,
                    lastMessage = incoming.lastMessage,
                    lastMessageDeliveryStatus = incoming.lastMessageDeliveryStatus,
                    lastMessageIsDeleted = incoming.lastMessageIsDeleted,
                    lastMessageFirstPayload = incoming.lastMessageFirstPayload,
                    lastMessageHasMultiplePayloads =
                        incoming.lastMessageHasMultiplePayloads,
                    lastMessageIsFromActiveUser = incoming.lastMessageIsFromActiveUser
                )
            // We should optimize later to not  map the full list
            _conversations.value =
                ConversationsData(items = _conversations.value.items.map { if (it.id == existing.id) updatedConvo else it })
        }
    }

    private suspend fun loadConversations() {
        val result = fetchConversations()
        _conversations.value = ConversationsData(items = result)
    }

    fun start() {
        scope.launch {
            loadConversations()
            updateUnreadCounts()
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
            ChatMessageStream.mapToMessageData(it) { file ->
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
}

data class ConversationsData(
    val dataReady: Boolean = true,
    val items: List<ConversationUiModel> = emptyList(),
)
