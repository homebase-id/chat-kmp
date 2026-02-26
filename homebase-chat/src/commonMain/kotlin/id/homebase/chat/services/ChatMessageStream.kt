package id.homebase.chat.services

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.FileState
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
import id.homebase.chat.services.convo.ContactService
import id.homebase.core.config.chatTargetDrive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

class ChatMessageStream(
    private val credentialsManager: CredentialsManager,
    private val contactService: ContactService,
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
                .filter { it.fileMetadata.appData.fileType == ChatProtocol.MessageFileType }
                .mapNotNull { mapToMessageData(it, ::resolveDisplayName) }

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


    suspend fun getMessage(messageId: Uuid): MessageUiModel? {
        val c = credentialsManager.requireActiveCredentials()
        val queryBatch = QueryBatch(c.getIdentityId())

        val result =
            queryBatch.queryBatchAsync(
                dbm = dbm,
                driveId = chatDrive,
                noOfItems = 1,
                filetypesAnyOf = listOf(ChatProtocol.MessageFileType),
                uniqueIdAnyOf = listOf(messageId),
                fileSystemType = 0
            )

        val messageFile = result.records.singleOrNull() ?: return null;
        return mapToMessageData(messageFile, ::resolveDisplayName)
    }

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
                filetypesAnyOf = listOf(ChatProtocol.MessageFileType),
                groupIdAnyOf = listOf(conversationId)
            )

        return BatchResult(
            records = result.records.mapNotNull { header ->
                mapToMessageData(header, ::resolveDisplayName)
            },
            hasMoreRows = result.hasMoreRows,
            cursor = result.cursor
        )
    }

    suspend fun searchMessages(
        searchQuery: String,
        limit: Int = 1000,
        cursor: QueryBatchCursor? = null
    ): BatchResult<MessageUiModel> {

        val c = credentialsManager.requireActiveCredentials()
        val queryBatch = QueryBatch(c.getIdentityId())

        // TODO - inject searchQuery into actual db query
        val result = queryBatch.queryBatchAsync(
            dbm = dbm,
            driveId = chatDrive,
            noOfItems = limit,
            cursor = cursor,
            sortOrder = QueryBatchSortOrder.NewestFirst,
            sortField = QueryBatchSortField.CreatedDate,
            fileSystemType = 0,
            filetypesAnyOf = listOf(ChatProtocol.MessageFileType),
        )

        // TODO - remove simple content search filter when actual query does filtering
        return BatchResult(
            records = result.records
                .filter {
                    it.fileMetadata.appData.content?.contains(
                        searchQuery,
                        ignoreCase = true
                    ) == true
                }
                .mapNotNull { mapToMessageData(it, ::resolveDisplayName) },
            hasMoreRows = result.hasMoreRows,
            cursor = result.cursor
        )
    }

    private suspend fun resolveDisplayName(file: HomebaseFile): String {
        val author = file.fileMetadata.originalAuthor ?: return ""

        return contactService
            .resolveByOdinId(author)
            ?.name
            ?: author.domainName
            ?: ""
    }

    companion object {

        private fun getDeliveryStatus(header: HomebaseFile): ChatDeliveryStatus {

            val count = header.serverMetadata.originalRecipientCount
            val transferSummary = header.serverMetadata
                .transferHistory?.summary ?: return ChatDeliveryStatus.Sent

            if (header.fileMetadata.appData.groupId == ChatProtocol.ConversationWithYourselfId) {
                return ChatDeliveryStatus.Read
            }

            return when {
                transferSummary.totalFailed > 0 ->
                    ChatDeliveryStatus.Failed

                transferSummary.totalReadByRecipient >= count ->
                    ChatDeliveryStatus.Read

                transferSummary.totalDelivered >= count ->
                    ChatDeliveryStatus.Delivered

                else ->
                    ChatDeliveryStatus.Sent
            }
        }

        suspend fun mapToMessageData(
            header: HomebaseFile,
            displayNameResolver: suspend (HomebaseFile) -> String = {
                it.fileMetadata.originalAuthor?.domainName ?: ""
            }
        ): MessageUiModel? {
            val metadata = header.fileMetadata
            val appData = metadata.appData

            try {
                require(appData.fileType == ChatProtocol.MessageFileType)
                val content = appData.content
                require(content != null)
                require(appData.uniqueId != null)
                require(appData.groupId != null)

                val messageAppDataSource = OdinSystemSerializer
                    .deserialize<MessageAppData>(content)
                val messageAppData = messageAppDataSource
                    .copy(deliveryStatus = getDeliveryStatus(header).value)

                val displayName = displayNameResolver(header)

                return MessageUiModel(
                    id = appData.uniqueId!!,
                    globalTransitId = metadata.globalTransitId,
                    fileId = header.fileId,
                    conversationId = appData.groupId!!,
                    created = metadata.created.toInstant(),
                    modified = metadata.updated.toInstant(),
                    originalAuthor = metadata.originalAuthor,
                    displayName = displayName,
                    isRead = false,
                    isEdited = (metadata.created != metadata.updated),
                    content = messageAppData.message,
                    messageAppData = messageAppData,
                    reactionPreview = metadata.reactionPreview,
                    previewThumbnail = metadata.appData.previewThumbnail,
                    payloads = metadata.payloads,
                    keyHeader = header.keyHeader,
                    isDeleted = header.fileState == FileState.Deleted
                )
            } catch (t: Throwable) {

                Logger.e(t) {
                    "failed while mapping a message with uniqueId ${appData.uniqueId} and fileId ${header.fileId} appData=[${appData}]"
                }

                try {
                    return MessageUiModel(
                        id = appData.uniqueId!!,
                        globalTransitId = metadata.globalTransitId,
                        fileId = header.fileId,
                        conversationId = appData.groupId!!,
                        created = metadata.created.toInstant(),
                        modified = metadata.updated.toInstant(),
                        originalAuthor = metadata.originalAuthor,
                        displayName = metadata.originalAuthor?.domainName ?: "",
                        isRead = false,
                        isEdited = (metadata.created != metadata.updated),
                        content = "Failed to parse message from server",
                        messageAppData = MessageAppData(),
                        reactionPreview = metadata.reactionPreview,
                        previewThumbnail = metadata.appData.previewThumbnail,
                        payloads = metadata.payloads,
                        keyHeader = header.keyHeader
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
