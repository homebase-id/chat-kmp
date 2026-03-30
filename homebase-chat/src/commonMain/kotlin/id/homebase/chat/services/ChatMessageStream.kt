package id.homebase.chat.services

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.drives.files.ArchivalStatus
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.query.QueryBatchCursor
import id.homebase.api.client.drives.query.TimeRowCursor
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.client.withRetry
import id.homebase.api.common.BatchResult
import id.homebase.api.common.OdinId
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.QueryBatch
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.PaginatedConversationState.Companion.PAGE_SIZE
import id.homebase.chat.services.convo.contact.ContactService
import id.homebase.core.config.chatTargetDrive
import id.homebase.core.localization.TranslationUtil
import id.homebase.resources.MR
import id.homebase.resources.someone
import id.homebase.resources.system_conversation_admin_added
import id.homebase.resources.system_conversation_admin_name_added
import id.homebase.resources.system_conversation_admin_name_removed
import id.homebase.resources.system_conversation_admin_removed
import id.homebase.resources.system_conversation_member_added
import id.homebase.resources.system_conversation_member_name_added
import id.homebase.resources.system_conversation_member_name_removed
import id.homebase.resources.system_conversation_member_removed
import id.homebase.resources.system_conversation_photo_updated
import id.homebase.resources.system_conversation_title_updated
import id.homebase.resources.system_group_conversation_member_left
import id.homebase.resources.system_group_conversation_started
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

class ChatMessageStream(
    private val credentialsManager: CredentialsManager,
    private val contactService: ContactService,
    private val dbm: DatabaseManager,
    private val eventBus: EventBus,
    private val scope: CoroutineScope,
    private val driveFileProvider: DriveFileProvider
) {

    private val paginatedState = PaginatedConversationState()
    private val chatDrive = chatTargetDrive.alias
    private val loadOlderMutex = Mutex()
    private val loadNewerMutex = Mutex()
    private var isSyncing = false
    private var syncedMessageCount = 0
    private val loadedConversations = mutableSetOf<Uuid>()

    init {
        scope.launch {
            eventBus.events.collect { event ->
                if (event is BackendEvent.OutboxEvent.OptimisticRollback && event.driveId == chatDrive) {
                    paginatedState.removeMessage(event.uniqueId)
                    return@collect
                }

                if (event !is BackendEvent.DriveEvent || event.driveId != chatDrive) return@collect

                when (event) {
                    is BackendEvent.DriveEvent.Started -> {
                        isSyncing = true
                        syncedMessageCount = 0
                    }

                    is BackendEvent.DriveEvent.Stopped -> {
                        isSyncing = false
                        if (event.totalCount > 0) {
                            Logger.d("ChatMessageStream: Stopped with totalCount=${event.totalCount}, syncedMessageCount=$syncedMessageCount")
                            refreshLoadedConversations()
                        } else {
                            Logger.d("ChatMessageStream: Stopped with totalCount=0, skipping refresh")
                        }
                    }

                    is BackendEvent.DriveEvent.BatchReceived -> {
                        if (!isSyncing) {
                            processIncrementalBatch(event.batchData)
                        } else {
                            val messageFiles = event.batchData.count {
                                it.fileMetadata.appData.fileType == ChatProtocol.MessageFileType
                            }
                            val conversationFiles = event.batchData.count {
                                it.fileMetadata.appData.fileType == ChatProtocol.ConversationFileType
                            }
                            val otherFiles = event.batchData.size - messageFiles - conversationFiles
                            syncedMessageCount += messageFiles
                            Logger.d(
                                "ChatMessageStream: BatchReceived suppressed (isSyncing=true), " +
                                "${event.batchData.size} files (messages=$messageFiles, " +
                                "conversations=$conversationFiles, other=$otherFiles)"
                            )
                        }
                    }
                }
            }
        }
    }

    // ---------- PUBLIC API ----------

    fun observeMessages(conversationId: Uuid): StateFlow<ChatMessagesData> =
        paginatedState
            .windows
            .map { windows ->
                val window = windows[conversationId]
                if (window != null) ChatMessagesData.Messages(window)
                else ChatMessagesData.Initializing
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), ChatMessagesData.Initializing)

    suspend fun loadConversation(conversationId: Uuid) {
        Logger.d("ChatMessageStream: loadConversation($conversationId)")
        loadedConversations += conversationId
        withContext(Dispatchers.Default) {
            val result = fetchMessages(conversationId, limit = PAGE_SIZE)
            Logger.d("ChatMessageStream: loadConversation($conversationId) → ${result.records.size} messages")
            paginatedState.setInitialWindow(
                conversationId = conversationId,
                messages = result.records,
                olderCursor = if (result.hasMoreRows) result.cursor else null,
                hasOlderMessages = result.hasMoreRows,
                newerCursor = null,
                hasNewerMessages = false,
            )
        }
    }

    suspend fun loadConversationAroundMessage(conversationId: Uuid, messageUniqueId: Uuid) {
        Logger.d("ChatMessageStream: loadConversationAroundMessage($conversationId, $messageUniqueId)")
        loadedConversations += conversationId

        val targetMessage = getMessage(messageUniqueId)
        if (targetMessage == null) {
            Logger.w("ChatMessageStream: target message $messageUniqueId not found, falling back to loadConversation")
            loadConversation(conversationId)
            return
        }

        withContext(Dispatchers.Default) {
            val halfPage = PAGE_SIZE / 2
            val targetTime = UnixTimeUtc(targetMessage.created)

            // Fetch older messages (at and before the target)
            val olderCursor = QueryBatchCursor(
                paging = TimeRowCursor(targetTime, Long.MAX_VALUE)
            )
            val olderResult = fetchMessages(
                conversationId, limit = halfPage, cursor = olderCursor,
                sortOrder = QueryBatchSortOrder.NewestFirst
            )

            // Fetch newer messages (after the target)
            val newerCursor = QueryBatchCursor(
                paging = TimeRowCursor(targetTime, 0L)
            )
            val newerResult = fetchMessages(
                conversationId, limit = halfPage, cursor = newerCursor,
                sortOrder = QueryBatchSortOrder.OldestFirst
            )

            // Combine, dedup, sort
            val combined = (olderResult.records + newerResult.records)
                .distinctBy { it.id }
                .sortedBy { it.created }

            Logger.d("ChatMessageStream: loadConversationAroundMessage → ${combined.size} messages (older=${olderResult.records.size}, newer=${newerResult.records.size})")

            paginatedState.setInitialWindow(
                conversationId = conversationId,
                messages = combined,
                olderCursor = if (olderResult.hasMoreRows) olderResult.cursor else null,
                hasOlderMessages = olderResult.hasMoreRows,
                newerCursor = if (newerResult.hasMoreRows) newerResult.cursor else null,
                hasNewerMessages = newerResult.hasMoreRows,
            )
        }
    }

    suspend fun loadOlderMessages(conversationId: Uuid) {
        if (!loadOlderMutex.tryLock()) return
        try {
            val window = paginatedState.getWindow(conversationId) ?: return
            if (window.isLoadingOlder || !window.hasOlderMessages) return

            paginatedState.setLoadingOlder(conversationId, true)
            try {
                withContext(Dispatchers.Default) {
                    val result = fetchMessages(
                        conversationId, limit = PAGE_SIZE, cursor = window.olderCursor,
                        sortOrder = QueryBatchSortOrder.NewestFirst
                    )
                    Logger.d("ChatMessageStream: loadOlderMessages($conversationId) → ${result.records.size} messages")
                    paginatedState.prependOlderMessages(
                        conversationId = conversationId,
                        olderMessages = result.records,
                        olderCursor = if (result.hasMoreRows) result.cursor else null,
                        hasMore = result.hasMoreRows,
                    )
                }
            } catch (e: Exception) {
                Logger.e(e) { "ChatMessageStream: loadOlderMessages failed" }
                paginatedState.setLoadingOlder(conversationId, false)
            }
        } finally {
            loadOlderMutex.unlock()
        }
    }

    suspend fun loadNewerMessages(conversationId: Uuid) {
        if (!loadNewerMutex.tryLock()) return
        try {
            val window = paginatedState.getWindow(conversationId) ?: return
            if (window.isLoadingNewer || !window.hasNewerMessages) return

            paginatedState.setLoadingNewer(conversationId, true)
            try {
                withContext(Dispatchers.Default) {
                    val newestMessage = window.messages.lastOrNull()
                    val cursor = if (window.newerCursor != null) {
                        window.newerCursor
                    } else if (newestMessage != null) {
                        QueryBatchCursor(paging = TimeRowCursor(UnixTimeUtc(newestMessage.created), 0L))
                    } else {
                        null
                    }

                    val result = fetchMessages(
                        conversationId, limit = PAGE_SIZE, cursor = cursor,
                        sortOrder = QueryBatchSortOrder.OldestFirst
                    )
                    Logger.d("ChatMessageStream: loadNewerMessages($conversationId) → ${result.records.size} messages")
                    paginatedState.appendNewerMessages(
                        conversationId = conversationId,
                        newerMessages = result.records,
                        newerCursor = if (result.hasMoreRows) result.cursor else null,
                        hasMore = result.hasMoreRows,
                    )
                }
            } catch (e: Exception) {
                Logger.e(e) { "ChatMessageStream: loadNewerMessages failed" }
                paginatedState.setLoadingNewer(conversationId, false)
            }
        } finally {
            loadNewerMutex.unlock()
        }
    }

// ---------- EVENT HANDLING ----------

    private suspend fun processIncrementalBatch(files: List<HomebaseFile>) {
        val messages =
            files
                .filter { it.fileMetadata.appData.fileType == ChatProtocol.MessageFileType }
                .mapNotNull { mapToMessageData(it, credentialsManager, ::resolveDisplayName) }

        messages.groupBy { it.conversationId }.forEach { (conversationId, msgs) ->
            val window = paginatedState.getWindow(conversationId) ?: return@forEach
            if (!window.hasNewerMessages) {
                // User is at the bottom - add messages to the window
                paginatedState.upsertMessage(conversationId, msgs)
            }
            // If user has scrolled up (hasNewerMessages=true), new messages are beyond the
            // current window and will be loaded when they scroll back down
        }
    }

    private suspend fun refreshLoadedConversations() {
        val snapshot = loadedConversations.toSet()
        Logger.d("ChatMessageStream: refreshLoadedConversations called, ${snapshot.size} active conversations")
        snapshot.forEach { conversationId ->
            val window = paginatedState.getWindow(conversationId)
            if (window == null || !window.hasNewerMessages) {
                // At bottom or no window yet - reload latest page
                val result = fetchMessages(conversationId, limit = PAGE_SIZE)
                Logger.d("ChatMessageStream: refreshLoadedConversations($conversationId) → ${result.records.size} messages")
                paginatedState.setInitialWindow(
                    conversationId = conversationId,
                    messages = result.records,
                    olderCursor = if (result.hasMoreRows) result.cursor else null,
                    hasOlderMessages = result.hasMoreRows,
                    newerCursor = null,
                    hasNewerMessages = false,
                )
            } else {
                // User is mid-history - reload around the anchor (middle of current window)
                val anchorMessage = window.messages.getOrNull(window.messages.size / 2)
                if (anchorMessage != null) {
                    loadConversationAroundMessage(conversationId, anchorMessage.id)
                } else {
                    loadConversation(conversationId)
                }
            }
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

        val messageFile = result.records.singleOrNull() ?: return null
        return mapToMessageData(messageFile, credentialsManager, ::resolveDisplayName)
    }

    suspend fun getMessageFile(messageId: Uuid): HomebaseFile? {
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

        return result.records.singleOrNull()
    }

    suspend fun fetchMessages(
        conversationId: Uuid,
        limit: Int = PAGE_SIZE,
        cursor: QueryBatchCursor? = null,
        sortOrder: QueryBatchSortOrder = QueryBatchSortOrder.NewestFirst,
    ): BatchResult<MessageUiModel> {

        val c = credentialsManager.requireActiveCredentials()
        val queryBatch = QueryBatch(c.getIdentityId())

        val result =
            queryBatch.queryBatchAsync(
                dbm = dbm,
                driveId = chatDrive,
                noOfItems = limit,
                cursor = cursor,
                sortOrder = sortOrder,
                sortField = QueryBatchSortField.CreatedDate,
                fileSystemType = 0,
                filetypesAnyOf = listOf(ChatProtocol.MessageFileType),
                groupIdAnyOf = listOf(conversationId)
            )

        return BatchResult(
            records =
                result.records.mapNotNull { header ->
                    mapToMessageData(header, credentialsManager, ::resolveDisplayName)
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
            )

        // TODO - remove simple content search filter when actual query does filtering
        return BatchResult(
            records =
                result.records
                    .filter {
                        it.fileMetadata.appData.content?.contains(
                            searchQuery,
                            ignoreCase = true
                        ) == true
                    }
                    .mapNotNull { mapToMessageData(it, credentialsManager, ::resolveDisplayName) },
            hasMoreRows = result.hasMoreRows,
            cursor = result.cursor
        )
    }

    suspend fun getMessages(
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
            records = result.records.mapNotNull {
                mapToMessageData(
                    it,
                    credentialsManager,
                    ::resolveDisplayName
                )
            },
            hasMoreRows = result.hasMoreRows,
            cursor = result.cursor
        )
    }

    suspend fun loadFullMessage(conversationId: Uuid, messageId: Uuid): String? {

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

    companion object {

        private fun getDeliveryStatus(header: HomebaseFile): ChatDeliveryStatus {

            val count = header.serverMetadata.originalRecipientCount
            val transferSummary =
                header.serverMetadata.transferHistory?.summary ?: return ChatDeliveryStatus.Sent

            if (header.fileMetadata.appData.groupId == ChatProtocol.ConversationWithYourselfId) {
                return ChatDeliveryStatus.Read
            }

            return when {
                transferSummary.totalFailed > 0 -> ChatDeliveryStatus.Failed
                transferSummary.totalReadByRecipient >= count -> ChatDeliveryStatus.Read
                transferSummary.totalDelivered >= count -> ChatDeliveryStatus.Delivered
                else -> ChatDeliveryStatus.Sent
            }
        }

        suspend fun mapToMessageData(
            header: HomebaseFile,
            credentialsManager: CredentialsManager,
            displayNameResolver: suspend (HomebaseFile) -> String = {
                it.fileMetadata.originalAuthor?.domainName ?: ""
            }
        ): MessageUiModel? {

            val domain = credentialsManager.requireActiveDomain()

            val metadata = header.fileMetadata
            val appData = metadata.appData
            val isStatusMessage = appData.dataType == ChatProtocol.ChatStatusMessageDataType
            val hasMore =
                metadata.payloads?.any { it.key == ChatProtocol.DefaultPayloadKey } == true
            val isPendingSend =
                metadata.localAppData?.tags?.contains(ChatProtocol.isPendingSendTag)
                    ?: false

            val localReadTimestamp = metadata.localAppData?.readTime

            try {
                require(appData.fileType == ChatProtocol.MessageFileType)

                val versionTag = header.fileMetadata.versionTag ?: Uuid.NIL
                val content = appData.content
                val isDeleted = header.fileState == FileState.Deleted ||
                        header.fileMetadata.appData.archivalStatus == ArchivalStatus.Removed

                if (isDeleted) {
                    return MessageUiModel(
                        id = appData.uniqueId ?: header.fileId,
                        globalTransitId = metadata.globalTransitId,
                        fileId = header.fileId,
                        conversationId = appData.groupId!!,
                        created = if (appData.userDate == null)
                            metadata.created.toInstant() else
                            UnixTimeUtc(appData.userDate!!).toInstant(),
                        modified = metadata.updated.toInstant(),
                        originalAuthor = metadata.originalAuthor,
                        displayName = metadata.originalAuthor?.domainName ?: "",
                        localReadTimestamp = localReadTimestamp,
                        isEdited = false,
                        content = "Deleted File",
                        messageAppData = MessageAppData(),
                        reactionPreview = metadata.reactionPreview,
                        previewThumbnail = metadata.appData.previewThumbnail,
                        payloads = metadata.payloads?.toPersistentList(),
                        keyHeader = header.keyHeader,
                        isDeleted = true,
                        versionTag = versionTag,
                        isPendingSend = isPendingSend,
                        isStatusMessage = isStatusMessage,
                        hasMore = hasMore
                    )
                }

                require(content != null)
                require(appData.uniqueId != null)
                require(appData.groupId != null)

                val delivery = getDeliveryStatus(header).value

                val messageAppData: MessageAppData

                if (isStatusMessage) {
                    val status = OdinSystemSerializer.deserialize<StatusMessageData>(content)
                    val rendered = renderStatusMessage(
                        author = metadata.originalAuthor,
                        status = status
                    )
                    messageAppData = MessageAppData(
                        message = JsonPrimitive(rendered),
                        deliveryStatus = delivery,
                        isEdited = false
                    )
                } else {
                    val source = OdinSystemSerializer.deserialize<MessageAppData>(content)
                    messageAppData = source.copy(
                        deliveryStatus = delivery
                    )
                }

                val displayName = displayNameResolver(header)

                val isAuthor = domain == metadata.originalAuthor
                val authorSpecificDate = if (isAuthor)
                    metadata.created
                else
                    metadata.transitCreated

                val created =
                    if (messageAppData.version == null) {
                        // older edited messages; use older logic that seems to drop the
                        // appData.userDate when a message is edited
                        if (messageAppData.isEdited) {
                            authorSpecificDate
                        } else {
                            if (appData.userDate == null) {
                                Logger.w { "Message (uid: ${appData.uniqueId}) with no version and not edited has null userDate. using authorSpecificDate" }
                                Logger.w { "See File here: https://${domain}/owner/drives/9ff813aff2d61e2f9b9db189e72d1a11_66ea8355ae4155c39b5a719166b510e3/${appData.uniqueId}" }
                                authorSpecificDate
                            } else
                                UnixTimeUtc(appData.userDate!!)
                        }

                    } else {
                        if (appData.userDate == null) {
                            Logger.w { "Message (uid: ${appData.uniqueId}) with version ${messageAppData.version} has null userDate. using authorSpecificDate" }
                            Logger.w { "See File here: https://${domain}/owner/drives/9ff813aff2d61e2f9b9db189e72d1a11_66ea8355ae4155c39b5a719166b510e3/${appData.uniqueId}" }
                            authorSpecificDate
                        } else
                            UnixTimeUtc(appData.userDate!!)
                    }

                return MessageUiModel(
                    id = appData.uniqueId!!,
                    globalTransitId = metadata.globalTransitId,
                    fileId = header.fileId,
                    conversationId = appData.groupId!!,
                    content = messageAppData.getMessage(),
                    created = created.toInstant(),
                    modified = metadata.updated.toInstant(),
                    originalAuthor = metadata.originalAuthor,
                    displayName = displayName,
                    isEdited = messageAppData.isEdited,
                    localReadTimestamp = localReadTimestamp,
                    messageAppData = messageAppData,
                    reactionPreview = metadata.reactionPreview,
                    previewThumbnail = metadata.appData.previewThumbnail,
                    payloads = metadata.payloads?.toPersistentList(),
                    keyHeader = header.keyHeader,
                    versionTag = versionTag,
                    isPendingSend = isPendingSend,
                    isStatusMessage = isStatusMessage,
                    hasMore = hasMore
                )

            } catch (t: Throwable) {

                Logger.e(t) {
                    "failed while mapping a message with uniqueId ${appData.uniqueId} and fileId ${header.fileId} appData=[${appData}]. Message: ${t.message}"
                }

                try {
                    return MessageUiModel(
                        id = appData.uniqueId!!,
                        globalTransitId = metadata.globalTransitId,
                        fileId = header.fileId,
                        conversationId = appData.groupId!!,
                        content = "Failed to parse message from server",
                        created = metadata.created.toInstant(),
                        modified = metadata.updated.toInstant(),
                        originalAuthor = metadata.originalAuthor,
                        displayName = metadata.originalAuthor?.domainName ?: "",
                        messageAppData = MessageAppData(),
                        localReadTimestamp = localReadTimestamp,
                        reactionPreview = metadata.reactionPreview,
                        previewThumbnail = metadata.appData.previewThumbnail,
                        payloads = metadata.payloads?.toPersistentList(),
                        keyHeader = header.keyHeader,
                        versionTag = Uuid.NIL,
                        isPendingSend = false,
                        isStatusMessage = isStatusMessage,
                        hasMore = hasMore
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

        fun renderStatusMessage(author: OdinId?, status: StatusMessageData): String {
            val name = author?.domainName ?: TranslationUtil.getString(MR.string.someone)
            val subject = status.subject?.domainName

            return when (status.statusMessage) {
                StatusMessage.ConversationTitleUpdated ->
                    TranslationUtil.getString(MR.string.system_conversation_title_updated, name)

                StatusMessage.ConversationPhotoUpdated ->
                    TranslationUtil.getString(MR.string.system_conversation_photo_updated, name)

                StatusMessage.ConversationMemberAdded ->
                    subject?.let {
                        TranslationUtil.getString(
                            MR.string.system_conversation_member_name_added,
                            name,
                            it
                        )
                    }
                        ?: TranslationUtil.getString(
                            MR.string.system_conversation_member_added,
                            name
                        )

                StatusMessage.ConversationMemberRemoved ->
                    subject?.let {
                        TranslationUtil.getString(
                            MR.string.system_conversation_member_name_removed,
                            name,
                            it
                        )
                    }
                        ?: TranslationUtil.getString(
                            MR.string.system_conversation_member_removed,
                            name
                        )

                StatusMessage.ConversationAdminAdded ->
                    subject?.let {
                        TranslationUtil.getString(
                            MR.string.system_conversation_admin_name_added,
                            name,
                            it
                        )
                    }
                        ?: TranslationUtil.getString(
                            MR.string.system_conversation_admin_added,
                            name
                        )

                StatusMessage.ConversationAdminRemoved ->
                    subject?.let {
                        TranslationUtil.getString(
                            MR.string.system_conversation_admin_name_removed,
                            name,
                            it
                        )
                    }
                        ?: TranslationUtil.getString(
                            MR.string.system_conversation_admin_removed,
                            name
                        )

                StatusMessage.GroupConversationStarted ->
                    TranslationUtil.getString(MR.string.system_group_conversation_started, name)

                StatusMessage.ConversationMemberLeft ->
                    TranslationUtil.getString(MR.string.system_group_conversation_member_left, name)
            }
        }
    }
}

sealed interface ChatMessagesData {
    data object Initializing : ChatMessagesData
    data class Messages(val window: MessageWindow) : ChatMessagesData
}
