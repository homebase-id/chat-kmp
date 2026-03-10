package id.homebase.chat.services.convo

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.OdinId
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.util.truncateToCodePoints
import id.homebase.chat.data.ConversationUiModel
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.ChatMessageStream
import id.homebase.chat.services.ChatProtocol
import id.homebase.core.avatars.ConversationAvatarModel
import id.homebase.core.config.chatTargetDrive
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ConversationStream(
    private val credentialsManager: CredentialsManager,
    private val conversationService: ConversationService,
    private val contactService: ContactService,
    private val dbm: DatabaseManager,
    private val eventBus: EventBus,
    private val scope: CoroutineScope
) {

    private val chatDrive = chatTargetDrive.alias
    private val _conversations = MutableStateFlow<List<ConversationUiModel>>(emptyList())
    private var isSyncing = false // Track if chat drive sync is in progress

    val conversations: StateFlow<List<ConversationUiModel>> = _conversations.asStateFlow()

    init {
        scope.launch {
            eventBus.events.collect { event ->
                if (event !is BackendEvent.DriveEvent || event.driveId != chatDrive) return@collect

                when (event) {
                    is BackendEvent.DriveEvent.Started -> {
                        isSyncing = true
                    }

                    is BackendEvent.DriveEvent.Completed -> {
                        isSyncing = false
                        // After the drive has been synchronized we fetch all conversations once
                        // and their unread counts
                        refresh()
                        // From this point on we need to process all incoming messages /
                        // conversations
                        // so that everything in the client is up to date.
                    }

                    is BackendEvent.DriveEvent.Failed -> {
                        if (event.source == BackendEvent.SyncSource.DriveSync) {
                            isSyncing = false
                            Logger.e { "Failed during drive sync" }
                            refresh()
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
                ChatMessageStream.Companion.mapToMessageData(file, ::resolveDisplayName)
            }

        if (messageFiles.size != incomingMessages.size)
            throw IllegalArgumentException("Size mismatch - conversion problem")

        for (m in incomingMessages) {
            val matchingConversation = _conversations.value.find { it.id == m.conversationId }
            if (matchingConversation == null) {
                val emptyConversation =
                    ConversationUiModel(
                        id = m.conversationId,
                        name = "Pending...",
                        lastMessage = m.content,
                        timestamp = m.created,
                        admins = (if (m.originalAuthor == null) emptySet<OdinId>() else listOf(m.originalAuthor)) as Set<OdinId>,
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
                            m.isCurrentUser(credentialsManager.getActiveDomain())
                    )

                insertNewConversation(emptyConversation)
            } else {
                updateConversationFromNewMessage(matchingConversation, m)
            }
        }

        // Sort by descending timestamp (adjust based on your UI needs)
        val sortedList = _conversations.value.sortedByDescending { it.timestamp }
        _conversations.value = sortedList
    }

    private suspend fun updateConversationFromNewMessage(
        c: ConversationUiModel,
        m: MessageUiModel
    ) {
        if (m.created > c.timestamp) {
            if (!m.isEdited) c.unreadCount++
            c.timestamp = m.created
            c.lastMessage = m.content.truncateToCodePoints(40) // TODO: Global constant
            c.lastMessageDeliveryStatus = m.messageAppData.deliveryStatus
            c.lastMessageIsDeleted = m.isDeleted
            c.lastMessageFirstPayload = m.payloads?.firstOrNull()
            c.lastMessageHasMultiplePayloads = (m.payloads?.size ?: 0) > 1
            c.lastMessageIsFromActiveUser = m.isCurrentUser(credentialsManager.getActiveDomain())
        }

        // Logger.i("Unread count now ${c.unreadCount} edited ${m.isEdited} on coversation id
        // ${c.id}")
    }

    private suspend fun processConversationBatchIncrementally(
        conversationFiles: List<HomebaseFile>
    ) {
        // For each file in the batch, map to model (fetch last message from DB if needed)
        val incomingConversations =
            conversationFiles.map { file ->
                conversationService.mapToConversationUi(file, null)
            }

        for (c in incomingConversations) {
            val matchingConversation = _conversations.value.find { it.id == c.id }
            if (matchingConversation == null) {
                insertNewConversation(c)
            } else {
                updateConversation(matchingConversation, c)
            }
        }

        // Sort by descending timestamp (adjust based on your UI needs)
        val sortedList = _conversations.value.sortedByDescending { it.timestamp }
        _conversations.value = sortedList
    }

    private fun insertNewConversation(conversation: ConversationUiModel) {
        // We should optimize later to not copy the full list
        val currentList = _conversations.value.toMutableList()
        currentList.add(conversation)

        _conversations.value = currentList
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
                _conversations.value.map { if (it.id == existing.id) updatedConvo else it }
        }
    }

    fun start() {
        scope.launch { refresh() }
    }

    private suspend fun refresh() {

        val result = fetchConversations()
        val unread = dbm.chatReadCount.selectAllUnreadCount()

        for (u in unread) {
            result.find { it.id == u.conversationId }?.let { conversation ->
                conversation.unreadCount = u.unreadCount.toInt()
            }
        }

        _conversations.value = result
    }

    suspend fun fetchConversations(): List<ConversationUiModel> {
        val result = dbm.chatReadCount.selectAllConversationPlusLastMessage()
        return result.map { conversationService.mapToConversationUi(it.conversation, it.message) }
    }

    fun getConversationById(conversationId: Uuid): ConversationUiModel? {
        return _conversations.value.firstOrNull { it.id == conversationId }
    }

    suspend fun getRecipients(conversationId: Uuid): List<OdinId> {

        val domain = credentialsManager.getActiveDomain()!!
        val conversation = getConversationById(conversationId) ?: return listOf()
        val recipients = conversation.participants.filter { it != domain }
        return recipients
    }
}
