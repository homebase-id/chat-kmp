package id.homebase.chat.services

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.drives.query.QueryBatchCursor
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.BatchResult
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.QueryBatch
import id.homebase.chat.data.MessageUiModel
import id.homebase.core.config.chatTargetDrive
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

const val CHAT_MESSAGE_FILE_TYPE = 7878

/** Archival status indicating a deleted chat */
const val ChatDeletedArchivalStatus = 2

const val CHAT_MESSAGE_PAYLOAD_KEY = "chat_mbl" // Is this for "more text" ? YESS
const val CHAT_LINKS_PAYLOAD_KEY = "chat_links"

class ChatMessageReaderService(
        private val credentialsManager: CredentialsManager,
        private val dbm: DatabaseManager,
        private val eventBus: EventBus,
        private val scope: CoroutineScope
) {

    private val conversationState = ActiveConversationState()
    private val chatDrive = chatTargetDrive.alias
    private var isSyncing = false
    private val loadedConversations = mutableSetOf<Uuid>()

    init {
        scope.launch {
            eventBus.events.collect { event ->
                if (event !is BackendEvent.DriveEvent || event.driveId != chatDrive) return@collect

                when (event) {
                    is BackendEvent.DriveEvent.Started -> {
                        isSyncing = true
                    }
                    is BackendEvent.DriveEvent.Completed, is BackendEvent.DriveEvent.Failed -> {
                        isSyncing = false
                        refreshLoadedConversations()
                    }
                    is BackendEvent.DriveEvent.BatchReceived -> {
                        if (!isSyncing) {
                            processIncrementalBatch(event.batchData)
                        }
                    }
                }
            }
        }
    }

    // ---------- PUBLIC API ----------

    fun observeMessages(conversationId: Uuid): StateFlow<List<MessageUiModel>> =
            conversationState
                    .messages
                    .map { it[conversationId].orEmpty() }
                    .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun loadConversation(conversationId: Uuid) {
        loadedConversations += conversationId
        val result = fetchMessages(conversationId)
        conversationState.set(conversationId, result.records)
    }

    // ---------- EVENT HANDLING ----------

    private suspend fun processIncrementalBatch(files: List<HomebaseFile>) {
        val messages =
                files
                        .filter {
                            it.fileMetadata.appData.fileType == ChatProtocol.MESSAGE_FILE_TYPE
                        }
                        .mapNotNull { mapToMessageData(it) }

        messages.groupBy { it.conversationId }.forEach { (conversationId, msgs) ->
            conversationState.upsert(conversationId, msgs)
        }
    }

    private suspend fun refreshLoadedConversations() {
        loadedConversations.forEach { conversationId ->
            val result = fetchMessages(conversationId)
            conversationState.set(conversationId, result.records)
        }
    }

    // ---------- EXISTING LOGIC (UNCHANGED) ----------

    suspend fun fetchMessages(
            conversationId: Uuid,
            limit: Int = 1000,
            cursor: QueryBatchCursor? = null
    ): BatchResult<MessageUiModel> {

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
                        filetypesAnyOf = listOf(ChatProtocol.MESSAGE_FILE_TYPE),
                        groupIdAnyOf = listOf(conversationId)
                )

        return BatchResult(
                records = result.records.mapNotNull { mapToMessageData(it) },
                hasMoreRows = result.hasMoreRows,
                cursor = result.cursor
        )
    }

    companion object {

        suspend fun mapToMessageData(header: HomebaseFile): MessageUiModel? {
            val metadata = header.fileMetadata
            val appData = metadata.appData

            try {
                require(appData.fileType == ChatProtocol.MESSAGE_FILE_TYPE)
                val content = appData.content
                require(content != null)
                require(appData.uniqueId != null)
                require(appData.groupId != null)

                val messageAppData = OdinSystemSerializer.deserialize<MessageAppData>(content)

                return MessageUiModel(
                        id = appData.uniqueId!!,
                        globalTransitId = metadata.globalTransitId,
                        fileId = header.fileId,
                        conversationId = appData.groupId!!,
                        created = metadata.created.toInstant(),
                        modified = metadata.updated.toInstant(),
                        senderOdinId = metadata.originalAuthor ?: "",
                        isCurrentUser = metadata.senderOdinId.isNullOrEmpty(),
                        isRead = false,
                        isEdited = (metadata.created != metadata.updated),
                        senderId = metadata.senderOdinId ?: "Me",
                        content = messageAppData.message,
                        messageAppData = messageAppData,
                        reactionPreview = metadata.reactionPreview,
                        previewThumbnail = metadata.appData.previewThumbnail,
                        payloads = metadata.payloads,

                )
            } catch (t: Throwable) {

                Logger.e(t) {
                    "failed while mapping a message with uniqueId $appData.uniqueId and fileId ${header.fileId}"
                }

                try {
                    return MessageUiModel(
                            id = appData.uniqueId!!,
                            globalTransitId = metadata.globalTransitId,
                            fileId = header.fileId,
                            conversationId = appData.groupId!!,
                            created = metadata.created.toInstant(),
                            modified = metadata.updated.toInstant(),
                            senderOdinId = metadata.senderOdinId ?: "",
                            isCurrentUser = metadata.senderOdinId.isNullOrEmpty(),
                            isRead = false,
                            isEdited = (metadata.created != metadata.updated),
                            senderId = metadata.senderOdinId ?: "Me",
                            content = "Failed to parse message from server",
                            messageAppData = MessageAppData(),
                            reactionPreview = metadata.reactionPreview,
                            previewThumbnail = metadata.appData.previewThumbnail,
                            payloads = metadata.payloads,
                    )
                } catch (t2: Throwable) {
                    Logger.e(t2) {
                        "Failed in fallback handling for parsing a message: fileId ${header.fileId}"
                        return null
                    }
                }

                return null
            }
        }
    }
}
