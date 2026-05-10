package id.homebase.chat.services

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.query.QueryBatchCursor
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.client.withRetry
import id.homebase.api.common.BatchResult
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.QueryBatch
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.convo.contact.ContactService
import id.homebase.core.config.chatTargetDrive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.io.encoding.Base64
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

class ChatMessageStream(
    private val credentialsManager: CredentialsManager,
    private val contactService: ContactService,
    private val dbm: DatabaseManager,
    private val eventBus: EventBus,
    private val scope: CoroutineScope,
    private val driveFileProvider: DriveFileProvider
) : MessageLookup {
    /** Set by ConversationStream to let us skip messages for left conversations. */
    var isConversationLeft: (Uuid) -> Boolean = { false }

    private val conversationState = ActiveConversationState()
    private val chatDrive = chatTargetDrive.alias

    // Messages for open conversations are loaded on demand via loadConversation().
    // All subsequent updates arrive incrementally through BatchReceived events —
    // we do not re-read from DB on DriveEvent.Stopped (same rationale as ConversationStream).
    init {
        scope.launch {
            eventBus.events.collect { event ->
                if (event is BackendEvent.OutboxEvent.OptimisticRollback && event.driveId == chatDrive) {
                    conversationState.removeMessage(event.uniqueId)
                    return@collect
                }

                if (event !is BackendEvent.DriveEvent || event.driveId != chatDrive) return@collect

                when (event) {
                    is BackendEvent.DriveEvent.Started -> {}

                    is BackendEvent.DriveEvent.Stopped -> {
                        Logger.d("ChatMessageStream: Stopped(totalCount=${event.totalCount})")
                    }

                    is BackendEvent.DriveEvent.BatchReceived -> {
                        processIncrementalBatch(event.batchData)
                    }
                }
            }
        }
    }

    // ---------- PUBLIC API ----------

    fun observeMessages(conversationId: Uuid): StateFlow<ChatMessagesData> =
        conversationState
            .messages
            .map { ChatMessagesData.Messages(it[conversationId].orEmpty()) }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), ChatMessagesData.Initializing)

    fun hasCachedMessages(conversationId: Uuid): Boolean =
        conversationState.hasCachedMessages(conversationId)

    // Full message load from local DB for a single conversation.
    // Called when the user opens a conversation (ConversationListViewModel.selectConversation).
    // Do NOT call from DriveEvent.Stopped or other sync events — see init block above.
    suspend fun loadConversation(conversationId: Uuid) {
        val start = TimeSource.Monotonic.markNow()
        Logger.d("ChatMessageStream: loadConversation($conversationId)")
        val result = fetchMessages(conversationId)
        val elapsed = start.elapsedNow()
        Logger.d("ChatMessageStream: loadConversation($conversationId) → ${result.records.size} messages in $elapsed")
        conversationState.set(conversationId, result.records)
    }

// ---------- EVENT HANDLING ----------

    private suspend fun processIncrementalBatch(files: List<HomebaseFile>) {
        val messages = withContext(Dispatchers.Default) {
            files
                .filter { it.fileMetadata.appData.fileType == ChatProtocol.MessageFileType }
                .mapNotNull { mapToMessageData(it, credentialsManager, ::resolveDisplayName) }
        }

        val grouped = messages.groupBy { it.conversationId }
        Logger.d("ChatMessageStream: processIncrementalBatch ${messages.size} messages across ${grouped.size} conversation(s)")
        grouped.forEach { (conversationId, msgs) ->
            if (isConversationLeft(conversationId)) return@forEach
            // Evict stale entries where the server returned a file whose id (uniqueId)
            // changed (e.g. cleared on delete, so id falls back to fileId). Without this,
            // the old entry (keyed by uniqueId) and new entry (keyed by fileId) both exist.
            for (msg in msgs) {
                if (msg.id == msg.fileId) {
                    conversationState.removeByFileId(conversationId, msg.fileId)
                }
            }
            conversationState.upsert(conversationId, msgs)
        }
    }

    override suspend fun getMessage(messageId: Uuid): MessageUiModel? {
        val messageFile = getMessageFile(messageId) ?: return null
        return mapToMessageData(messageFile, credentialsManager, ::resolveDisplayName)
    }

    override suspend fun getMessageFile(messageId: Uuid): HomebaseFile? {
        val c = credentialsManager.requireActiveCredentials()
        return dbm.driveMainIndex.selectHomebaseFileByUnique(
            c.getIdentityId(),
            chatDrive,
            messageId
        )
    }

    /** Walk the open conversations' in-memory message lists for a model whose
     *  `id == messageId` and return its `fileId`. The hit rate is effectively
     *  100% for messageId-keyed actions invoked from a visible message
     *  (toggleReaction's optimistic path is fileId-keyed and bypasses this
     *  entirely; the remaining call sites — getReactions, deleteMessage's
     *  fall-through — operate on a message the user just interacted with).
     *
     *  The scan is over distinct *open* conversations only, which is one for
     *  the active conversation in steady state. Returns `null` (caller must
     *  fall through to DB) if the message isn't loaded — including the case
     *  where it exists on disk but its conversation hasn't been opened.
     */
    override fun findCachedFileId(messageId: Uuid): Uuid? {
        val open = conversationState.messages.value
        for ((_, list) in open) {
            for (m in list) {
                if (m.id == messageId) return m.fileId
            }
        }
        return null
    }

    suspend fun fetchMessages(
        conversationId: Uuid,
        limit: Int = 1000,
        cursor: QueryBatchCursor? = null
    ): BatchResult<MessageUiModel> {

        val c = credentialsManager.requireActiveCredentials()
        val queryBatch = QueryBatch(c.getIdentityId())

        val queryStart = TimeSource.Monotonic.markNow()
        val result =
            queryBatch.queryBatchAsync(
                dbm = dbm,
                driveId = chatDrive,
                noOfItems = limit,
                cursor = cursor,
                sortOrder = QueryBatchSortOrder.NewestFirst,
                sortField = QueryBatchSortField.UserDate,
                fileSystemType = 0,
                filetypesAnyOf = listOf(ChatProtocol.MessageFileType),
                groupIdAnyOf = listOf(conversationId)
            )
        val queryElapsed = queryStart.elapsedNow()

        val mapStart = TimeSource.Monotonic.markNow()
        val records = withContext(Dispatchers.Default) {
            result.records.mapNotNull { header ->
                mapToMessageData(header, credentialsManager, ::resolveDisplayName)
            }
        }
        val mapElapsed = mapStart.elapsedNow()

        if (queryElapsed + mapElapsed > 200.milliseconds) {
            Logger.w(tag = "SlowMessageFetch") {
                "conversationId=$conversationId " +
                        "rawRecords=${result.records.size} " +
                        "mappedRecords=${records.size} " +
                        "dbQuery=$queryElapsed " +
                        "mapping=$mapElapsed"
            }
        }

        return BatchResult(
            records = records,
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
        val result =
            queryBatch.queryBatchAsync(
                dbm = dbm,
                driveId = chatDrive,
                noOfItems = limit,
                cursor = cursor,
                sortOrder = QueryBatchSortOrder.NewestFirst,
                sortField = QueryBatchSortField.UserDate,
                fileSystemType = 0,
                filetypesAnyOf = listOf(ChatProtocol.MessageFileType),
            )

        // TODO - remove simple content search filter when actual query does filtering
        return BatchResult(
            records = withContext(Dispatchers.Default) {
                result.records
                    .filter {
                        it.fileMetadata.appData.content?.contains(
                            searchQuery,
                            ignoreCase = true
                        ) == true
                    }
                    .mapNotNull { mapToMessageData(it, credentialsManager, ::resolveDisplayName) }
            },
            hasMoreRows = result.hasMoreRows,
            cursor = result.cursor
        )
    }

    override suspend fun getMessages(
        messageIds: List<Uuid>
    ): BatchResult<MessageUiModel> {

        val c = credentialsManager.requireActiveCredentials()
        val queryBatch = QueryBatch(c.getIdentityId())

        // TODO - inject searchQuery into actual db query
        val result =
            queryBatch.queryBatchAsync(
                dbm = dbm,
                driveId = chatDrive,
                noOfItems = messageIds.size,
                cursor = null,
                sortOrder = QueryBatchSortOrder.NewestFirst,
                sortField = QueryBatchSortField.CreatedDate,
                fileSystemType = 0,
                filetypesAnyOf = listOf(ChatProtocol.MessageFileType),
                uniqueIdAnyOf = messageIds
            )

        return BatchResult(
            records = withContext(Dispatchers.Default) {
                result.records.mapNotNull {
                    mapToMessageData(
                        it,
                        credentialsManager,
                        ::resolveDisplayName
                    )
                }
            },
            hasMoreRows = result.hasMoreRows,
            cursor = result.cursor
        )
    }

    override suspend fun loadFullMessage(conversationId: Uuid, messageId: Uuid): String? {

        val header = getMessage(messageId) ?: return null

        val descriptor = header.payloads?.firstOrNull { it.key == ChatProtocol.DefaultPayloadKey }

        if (descriptor == null) {
            return null
        }

        val payloadIv = Base64.decode(
            descriptor.iv ?: throw IllegalStateException(
                "encrypted payload requires key header"
            )
        )

        val fullMessage = withRetry(tag = "ChatMessageStream") {


            val response = driveFileProvider.getPayloadBytesDecrypted(
                driveId = chatDrive,
                fileId = header.fileId,
                key = descriptor.key,
                keyHeader = KeyHeader(
                    iv = payloadIv,
                    aesKey = header.keyHeader.aesKey
                )
            ) ?: return@withRetry null

            try {
                val payloadJson = response.bytes.decodeToString()
                val payload =
                    OdinSystemSerializer.deserialize<ChatMessagePayload>(payloadJson)

                payload.message
            } catch (t: Throwable) {
                Logger.e(t) { "Failed to deserialize payload for message $messageId" }
                null
            }
        } ?: return null


        // ---- update loaded conversation in memory ----

//        val current = conversationState.messages.value[conversationId] ?: return fullMessage
//
//        val updated =
//            current.map {
//                if (it.id == messageId) {
//                    it.copy(
//                        content = fullMessage,
//                        hasMore = false
//                    )
//                } else it
//            }
//
//        conversationState.set(conversationId, updated)

        return fullMessage
    }

    private suspend fun resolveDisplayName(file: HomebaseFile): String {
        val author = file.fileMetadata.originalAuthor ?: return ""

        return contactService.resolveByOdinId(author)?.name ?: author.domainName
    }

}

sealed interface ChatMessagesData {
    data object Initializing : ChatMessagesData
    data class Messages(val messages: List<MessageUiModel>) : ChatMessagesData
}
