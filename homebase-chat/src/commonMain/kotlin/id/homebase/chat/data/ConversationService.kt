package id.homebase.chat.data

import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.drives.query.QueryBatchCursor
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.BatchResult
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.QueryBatch
import id.homebase.chat.Conversation
import id.homebase.chat.config.chatTargetDrive
import id.homebase.homebasekmppoc.prototype.lib.serialization.OdinSystemSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

const val CHAT_CONVERSATION_FILE_TYPE = 8888
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
        val result =
            fetchConversations()

        _conversations.value = result.records
    }

    suspend fun fetchConversations(
        limit: Int = 1000,
        cursor: QueryBatchCursor? = null
    ): BatchResult<Conversation> {

        val c = credentialsManager.requireActiveCredentials()
        val queryBatch = QueryBatch(c.getIdentityId())

        val result =
            queryBatch.queryBatchAsync(
                dbm = dbm,
                driveId = chatDrive,
                noOfItems = limit,
                cursor = cursor,
                sortOrder = QueryBatchSortOrder.NewestFirst,
                sortField = QueryBatchSortField.CreatedDate,
                fileSystemType = 0,
                filetypesAnyOf = listOf(CHAT_CONVERSATION_FILE_TYPE),
                groupIdAnyOf = null
            )

        return BatchResult(
            records = result.records.map { mapToConversation(it) },
            hasMoreRows = result.hasMoreRows,
            cursor = result.cursor
        )
    }

    private suspend fun mapToConversation(header: HomebaseFile): Conversation {
        val metadata = header.fileMetadata
        val appData = metadata.appData

        val content = appData.content ?: "";
        val parsedContent = OdinSystemSerializer.deserialize<Conversation>(content)

       return  parsedContent.copy(
            id = appData.uniqueId.toString(),
        )

        return parsedContent;
    }
}
