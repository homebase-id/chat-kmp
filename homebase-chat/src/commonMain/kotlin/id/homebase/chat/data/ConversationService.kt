package id.homebase.chat.data

import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.query.QueryBatchCursor
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.chat.Conversation
import id.homebase.chat.config.chatTargetDrive
import id.homebase.core.model.UnixTimeUtc
import id.homebase.homebasekmppoc.prototype.lib.serialization.OdinSystemSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

const val CHAT_CONVERSATION_FILE_TYPE:Int = 8888
const val CHAT_CONVERSATION_LOCAL_METADATA_FILE_TYPE = 8889
const val ConversationWithYourselfId = "e4ef2382-ab3c-405d-a8b5-ad3e09e980dd"
const val CONVERSATION_PAYLOAD_KEY = "convo_pk"
const val CONVERSATION_IMAGE_KEY = "convo_img"

class ConversationService(
    private val credentialsManager: CredentialsManager,
    private val dbm: DatabaseManager,
    private val eventBus: EventBus,
    private val scope: CoroutineScope
) {

    private val chatDrive = chatTargetDrive.alias
    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())

    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    init {
        scope.launch {
            eventBus.events.collect { event ->
                if (event is BackendEvent.DriveEvent.Completed &&
                    event.driveId == chatDrive
                ) {
                    refresh()
                }
            }
        }
    }

    fun start() {
        scope.launch {
            refresh()
        }
    }

    private suspend fun refresh() {

        val result = fetchConversations()
        val unread = dbm.chatReadCount.selectAllUnreadCount()

        for (u in unread)
        {
            result.find { it.id == u.conversationId }?.let { conversation ->
                conversation.unreadCount = u.unreadCount.toInt()
            }
        }

        _conversations.value = result
    }

    suspend fun fetchConversations(
        limit: Int = 1000,
        cursor: QueryBatchCursor? = null
    ): List<Conversation> {

        val result = dbm.chatReadCount.selectAllConversationPlusLastMessage();
        return result.map { mapToConversation(it.conversation, it.message) }
    }

    companion object
    {
        // You must pass in the last message of the conversation to generate a properly
        // populated Conversation object (we fetch data like last 40 chars, message time, etc)
        public suspend fun mapToConversation(
            conversation: HomebaseFile,
            lastMsg: HomebaseFile?
        ): Conversation
        {
            val metadata = conversation.fileMetadata
            val appData = metadata.appData
            val conversation = OdinSystemSerializer.deserialize<ConversationFromServer>(appData.content ?: "")

            if (appData.fileType != CHAT_CONVERSATION_FILE_TYPE)
                throw IllegalArgumentException("HomebaseFile must be of type Chat_conversation")

            if (appData.content == null)
                throw IllegalArgumentException("AppData is empty")

            val result = Conversation(
                id = appData.uniqueId ?: throw Exception("missing unique id, data error"),
                name = conversation.title ?: "",
                lastMessage = "",
                timestamp = UnixTimeUtc(0).toInstant(),
                unreadCount = 0,
                avatarTiny = null, // TODO: WHERE DO WE FETCH THIS ONE?
                avatarInitials = "",
                avatarUrl = "",
                isPinned = false
            )

            var message: Message? = null
            if (lastMsg != null)
            {
                message = ChatMessageService.mapToMessageData(lastMsg)
                result.updateWithLatestMessage(message)
            }

            return result
        }
    }
}


@Serializable
data class ConversationFromServer(
    //FROM JS
    val title: String? = "",
    val recipient: String? = "",
    val version: Int = 0,
    val conversationId: Uuid = Uuid.random(),
    val lastReadTime: UnixTimeUtc? = null,
    val recipients: List<String> = listOf()
)