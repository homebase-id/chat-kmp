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
import id.homebase.core.config.chatTargetDrive
import id.homebase.homebasekmppoc.prototype.lib.serialization.OdinSystemSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

const val CHAT_MESSAGE_FILE_TYPE = 7878

/** Archival status indicating a deleted chat */
const val ChatDeletedArchivalStatus = 2

const val CHAT_MESSAGE_PAYLOAD_KEY = "chat_mbl"  // Is this for "more text" ?
const val CHAT_LINKS_PAYLOAD_KEY = "chat_links"


class ChatMessageReaderService(
    private val credentialsManager: CredentialsManager,
    private val dbm: DatabaseManager,
    private val eventBus: EventBus,
    private val scope: CoroutineScope
) {

    private val chatDrive = chatTargetDrive.alias
    private val _messages = MutableStateFlow<List<MessageUiModel>>(emptyList())

    val messages: StateFlow<List<MessageUiModel>> = _messages.asStateFlow()

    private var currentConversationId: Uuid? = null

    init {
        scope.launch {
            eventBus.events.collect { event ->
                if ((event is BackendEvent.DriveEvent.Completed || event is BackendEvent.DriveEvent.BatchReceived) &&
                    event.driveId == chatDrive
                ) {
                    refresh()
                }
            }
        }
    }

    fun start(
        conversationId: Uuid
    ) {
        currentConversationId = conversationId

        scope.launch {
            refresh()
        }
    }

    private suspend fun refresh() {
        val conversationId = currentConversationId ?: return

        val result =
            fetchMessages(
                conversationId = conversationId
            )

        _messages.value = result.records
    }

    // ----- existing logic, unchanged -----

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
                filetypesAnyOf = listOf(CHAT_MESSAGE_FILE_TYPE),
                groupIdAnyOf = listOf(conversationId)
            )

        return BatchResult(
            records = result.records.map { mapToMessageData(it) },
            hasMoreRows = result.hasMoreRows,
            cursor = result.cursor
        )
    }

    companion object {
        /**
         * Convert HomebaseFile to ConversationData object
         * Handles fileType 8888 (chat conversations)
         */


        /** Maps a SharedSecretEncryptedFileHeader to ChatMessageData with decrypted content. */
        public suspend fun mapToMessageData(header: HomebaseFile): MessageUiModel {
            val metadata = header.fileMetadata
            val appData = metadata.appData

            if (appData.fileType != CHAT_MESSAGE_FILE_TYPE)
                throw IllegalArgumentException("HomebaseFile must be of type Chat_message")

//            if (metadata.senderOdinId.isNullOrBlank())
//                throw IllegalArgumentException("SenderId must be set")

            if (appData.content == null)
                throw IllegalArgumentException("AppData is empty")

            if (appData.uniqueId == null)
                throw IllegalArgumentException("UniqueId is empty")

            if (appData.groupId == null)
                throw IllegalArgumentException("GroupId is empty")

            val messageAppData = parseMessageAppDataJson(header.fileMetadata.appData.content!!)

            // Get preview thumbnail from appData or first payload
            //        val previewThumbnail =
            //            appData.previewThumbnail ?: metadata.payloads?.firstOrNull()?.previewThumbnail

            val isCurrentUser = metadata.senderOdinId.isNullOrEmpty()
            // todo: resolve from contacts

            return MessageUiModel(
                id = appData.uniqueId!!,
                conversationId = appData.groupId!!,
                timestamp = metadata.created.toInstant(),
                senderOdinId = metadata.senderOdinId ?: "",
                isCurrentUser = isCurrentUser,
                isRead = false,
                senderId = metadata.senderOdinId ?: "Me",
                content = messageAppData.message,
                messageAppData = messageAppData
                //            versionTag = metadata.versionTag,
                //            previewThumbnail = previewThumbnail,
                //            contentIsComplete = metadata.payloads?.find { it.keyEquals(CHAT_MESSAGE_PAYLOAD_KEY) } == null,
                //            reactionSummary = metadata.reactionPreview,
            )
        }

        /** Parses a JSON string as ChatMessageContent. */
        private fun parseMessageAppDataJson(jsonContent: String): MessageAppData {
            return try {
                OdinSystemSerializer.deserialize<MessageAppData>(jsonContent)
            } catch (e: Exception) {
                println(
                    "ChatProvider: Failed to parse ChatMetadata: ${e.message}\nContent: [${jsonContent}]"
                )

                // If parsing fails, create a simple message with the raw content
                // TODO: Why? :point_up:
                try {
                    MessageAppData(message = jsonContent)
                } catch (e2: Exception) {
                    MessageAppData()
                }
            }
        }
    }
}
