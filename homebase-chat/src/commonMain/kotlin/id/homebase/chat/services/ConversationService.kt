package id.homebase.chat.services

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.util.truncateToCodePoints
import id.homebase.chat.data.ConversationUiModel
import id.homebase.chat.data.MessageUiModel
import id.homebase.core.config.chatTargetDrive
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * File type constant used to identify conversation files in the chat drive.
 *
 * Used to filter and recognize HomebaseFile entries that represent chat conversations (for example
 * when processing drive batches or querying storage).
 */
const val CHAT_CONVERSATION_FILE_TYPE: Int = 8888

/**
 * Constant UUID used for the "conversation with yourself" special-case.
 *
 * This id is treated as a static conversation identifier when the user chats with their own
 * account.
 */
const val ConversationWithYourselfId = "e4ef2382-ab3c-405d-a8b5-ad3e09e980dd"

/**
 * Key stored in the file header to indicate that the conversation's JSON content is incomplete and
 * additional data should be loaded from payloads.
 *
 * If this key is absent, the header's jsonContent is assumed to be complete.
 */
const val CONVERSATION_PAYLOAD_KEY = "convo_pk"

/** PayloadKey for conversation image. Used for group Chats */
const val CONVERSATION_IMAGE_KEY = "convo_img"

class ConversationService(
        private val credentialsManager: CredentialsManager,
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
                            refresh()
                            // Optionally handle failure, e.g., log or partial refresh
                        }
                    }
                    is BackendEvent.DriveEvent.BatchReceived -> {
                        if (!isSyncing) {
                            val conversationFiles =
                                    event.batchData.filter {
                                        it.fileMetadata.appData.fileType ==
                                                ChatProtocol.CONVERSATION_FILE_TYPE
                                    }
                            val messageFiles =
                                    event.batchData.filter {
                                        it.fileMetadata.appData.fileType ==
                                                ChatProtocol.MESSAGE_FILE_TYPE
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

    suspend fun processMessageBatchIncrementally(messageFiles: List<HomebaseFile>) {
        if (messageFiles.isEmpty()) throw IllegalArgumentException("It can't be empty")

        // For each file in the batch, map to model (fetch last message from DB if needed)
        val incomingMessages =
                messageFiles.mapNotNull { file -> ChatMessageReaderService.mapToMessageData(file) }

        if (messageFiles.size != incomingMessages.size)
                throw IllegalArgumentException("Size mismatch - conversion problem")

        for (m in incomingMessages) {
            val matchingConversation = _conversations.value.find { it.id == m.conversationId }
            if (matchingConversation != null) {
                updateConversationFromNewMessage(matchingConversation, m)
            } else Logger.e { "BOOM" }
        }

        // Sort by descending timestamp (adjust based on your UI needs)
        val sortedList = _conversations.value.sortedByDescending { it.timestamp }
        _conversations.value = sortedList
    }

    suspend fun updateConversationFromNewMessage(c: ConversationUiModel, m: MessageUiModel) {
        if (m.created > c.timestamp) {
            if (!m.isEdited) c.unreadCount++
            c.timestamp = m.created
            c.lastMessage = m.content.truncateToCodePoints(40) // TODO: Global constant
        }

        // Logger.i("Unread count now ${c.unreadCount} edited ${m.isEdited} on coversation id
        // ${c.id}")
    }

    suspend fun processConversationBatchIncrementally(conversationFiles: List<HomebaseFile>) {
        // For each file in the batch, map to model (fetch last message from DB if needed)
        val incomingConversations = conversationFiles.map { file -> mapToConversation(file, null) }

        for (c in incomingConversations) {
            val matchingConversation = _conversations.value.find { it.id == c.id }
            if (matchingConversation == null) insertNewConversation(c)
            else updateConversation(matchingConversation, c)
        }

        // Sort by descending timestamp (adjust based on your UI needs)
        val sortedList = _conversations.value.sortedByDescending { it.timestamp }
        _conversations.value = sortedList
    }

    fun insertNewConversation(conversation: ConversationUiModel) {
        // We should optimize later to not copy the full list
        val currentList = _conversations.value.toMutableList()
        currentList.add(conversation)

        _conversations.value = currentList
    }

    fun updateConversation(existing: ConversationUiModel, incoming: ConversationUiModel) {
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
                            lastMessage = incoming.lastMessage
                    )
            // We should optimize later to not  map the full list
            _conversations.value =
                    _conversations.value.map { if (it.id == existing.id) existing else it }
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
        return result.map { mapToConversation(it.conversation, it.message) }
    }

    fun getConversationById(conversationId: Uuid): ConversationUiModel? {
        return _conversations.value.firstOrNull { it.id == conversationId }?.let {
            return it
        }
    }

    suspend fun getRecipients(conversationId: Uuid): List<String> {

        val domain = credentialsManager.getActiveDomain()!!
        val conversation = getConversationById(conversationId) ?: return listOf()
        val recipients = conversation.participants.filter { it != domain }
        return recipients
    }

    companion object {
        // You must pass in the last message of the conversation to generate a properly
        // populated Conversation object (we fetch data like last 40 chars, message time, etc)
        public suspend fun mapToConversation(
                conversation: HomebaseFile,
                lastMsg: HomebaseFile?
        ): ConversationUiModel {
            val metadata = conversation.fileMetadata
            val appData = metadata.appData
            val appDataObj =
                    OdinSystemSerializer.deserialize<ConversationAppDataJson>(appData.content ?: "")

            if (appData.fileType != ChatProtocol.CONVERSATION_FILE_TYPE)
                    throw IllegalArgumentException("HomebaseFile must be of type Chat_conversation")

            if (appData.content == null) throw IllegalArgumentException("AppData is empty")

            var localAppDataObj: ConversationLocalAppDataJson? = null
            val localAppData = metadata.localAppData?.content
            if (localAppData != null) {
                localAppDataObj =
                        OdinSystemSerializer.deserialize<ConversationLocalAppDataJson>(localAppData)
            }

            val result =
                    ConversationUiModel(
                            id = appData.uniqueId
                                            ?: throw Exception("missing unique id, data error"),
                            name = appDataObj.title ?: "",
                            lastMessage = " ", // use the ConversationLastMessageContent
                            timestamp = UnixTimeUtc(0).toInstant(),
                            unreadCount = 0,
                            avatarTiny = appData.previewThumbnail, // Populated only if the group
                            // Conversation has an image
                            avatarInitials = "AB",
                            avatarUrl = "",
                            participants = appDataObj.recipients,
                            lastRead = localAppDataObj?.lastReadTime?.toInstant()
                                            ?: UnixTimeUtc(0).toInstant()
                    )

            if (lastMsg != null) {
                val message = ChatMessageReaderService.mapToMessageData(lastMsg)
                if (message != null) {
                    result.updateWithLatestMessage(message)
                }
            }

            return result
        }
    }
}

@Serializable
data class ConversationAppDataJson(
        val title: String? = "",
        val recipient: String? = "",
        val version: Int = 0,
        val recipients: List<String> = listOf()
)

@Serializable
data class ConversationLocalAppDataJson(
        /**
         * DEPRECATED: But we still needed for backwards compatibility. Remove it after April Launch
         * 2026
         */
        @Transient
        val conversationId: Uuid =
                Uuid.NIL, // TODO: Obsolete, ignore. Same as uniqueId for conversation
        val lastReadTime: UnixTimeUtc?
)
