package id.homebase.chat.data

import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.chat.ConversationUiModel
import id.homebase.chat.config.chatTargetDrive
import id.homebase.homebasekmppoc.prototype.lib.serialization.OdinSystemSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.uuid.Uuid

const val CHAT_CONVERSATION_FILE_TYPE: Int = 8888
// const val CHAT_CONVERSATION_LOCAL_METADATA_FILE_TYPE = 8889 // TODO: OBSOLETE - We switched to localAppData
const val ConversationWithYourselfId = "e4ef2382-ab3c-405d-a8b5-ad3e09e980dd"
const val CONVERSATION_PAYLOAD_KEY = "convo_pk" // TODO: Explain what this represents
const val CONVERSATION_IMAGE_KEY = "convo_img"// TODO: Explain what this represents (and where's the tiny)

class ConversationService(
    private val credentialsManager: CredentialsManager,
    private val dbm: DatabaseManager,
    private val eventBus: EventBus,
    private val scope: CoroutineScope
) {

    private val chatDrive = chatTargetDrive.alias
    private val _conversations = MutableStateFlow<List<ConversationUiModel>>(emptyList())

    val conversations: StateFlow<List<ConversationUiModel>> = _conversations.asStateFlow()

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

        for (u in unread) {
            result.find { it.id == u.conversationId }?.let { conversation ->
                conversation.unreadCount = u.unreadCount.toInt()
            }
        }

        _conversations.value = result
    }

    suspend fun fetchConversations(): List<ConversationUiModel> {
        val result = dbm.chatReadCount.selectAllConversationPlusLastMessage();
        return result.map { mapToConversation(it.conversation, it.message) }
    }

    fun getConversationById(
        conversationId: Uuid
    ): ConversationUiModel? {
        return _conversations.value.firstOrNull { it.id == conversationId }?.let {
            return it
        }
    }

    suspend fun getRecipients(conversationId: Uuid): List<String> {

        val domain = credentialsManager.getActiveDomain()!!;
        val conversation = getConversationById(conversationId) ?: return listOf();
        val recipients = conversation.participants
            .filter { it != domain }
        return recipients;
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

            if (appData.fileType != CHAT_CONVERSATION_FILE_TYPE)
                throw IllegalArgumentException("HomebaseFile must be of type Chat_conversation")

            if (appData.content == null)
                throw IllegalArgumentException("AppData is empty")

            var localAppDataObj: ConversationLocalAppDataJson? = null
            val localAppData = metadata.localAppData?.content
            if (localAppData != null)
            {
                localAppDataObj =
                    OdinSystemSerializer.deserialize<ConversationLocalAppDataJson>(localAppData)
            }

            val result = ConversationUiModel(
                id = appData.uniqueId ?: throw Exception("missing unique id, data error"),
                name = appDataObj.title ?: "",
                lastMessage = "",
                timestamp = UnixTimeUtc(0).toInstant(),
                unreadCount = 0,
                avatarTiny = appData.previewThumbnail, // TODO: Is this even populated?
                avatarInitials = "",
                avatarUrl = "",
                participants = appDataObj.recipients,
                lastRead = localAppDataObj?.lastReadTime?.toInstant() ?: UnixTimeUtc(0).toInstant()
            )

            if (lastMsg != null)
            {
                val message = ChatMessageReaderService.mapToMessageData(lastMsg)
                result.updateWithLatestMessage(message)
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
    @Transient val conversationId: Uuid = Uuid.NIL, // TODO: LOOKS OBSOLETE / WRONG, ID IS ON UNIQUEID
    @Transient val lastReadTime: UnixTimeUtc? = null, // TODO: OBSOLETE / WRONG
    val recipients: List<String> = listOf()
)

@Serializable
data class ConversationLocalAppDataJson(
    @Transient val conversationId: Uuid = Uuid.NIL,  // TODO: Obsolete, ignore. Same as uniqueId for conversation
    val lastReadTime: UnixTimeUtc?
)