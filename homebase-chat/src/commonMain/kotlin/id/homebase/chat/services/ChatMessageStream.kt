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
import id.homebase.chat.services.convo.contact.ContactService
import id.homebase.core.config.chatTargetDrive
import id.homebase.core.localization.TranslationUtil
import id.homebase.resources.MR
import id.homebase.resources.someone
import id.homebase.resources.system_conversation_admin_added
import id.homebase.resources.system_conversation_admin_added_you
import id.homebase.resources.system_conversation_admin_name_added
import id.homebase.resources.system_conversation_admin_name_removed
import id.homebase.resources.system_conversation_admin_removed
import id.homebase.resources.system_conversation_admin_removed_you
import id.homebase.resources.system_conversation_admin_you_added
import id.homebase.resources.system_conversation_admin_you_removed
import id.homebase.resources.system_conversation_member_added
import id.homebase.resources.system_conversation_member_added_you
import id.homebase.resources.system_conversation_member_name_added
import id.homebase.resources.system_conversation_member_name_removed
import id.homebase.resources.system_conversation_member_removed
import id.homebase.resources.system_conversation_member_removed_you
import id.homebase.resources.system_conversation_member_you_added
import id.homebase.resources.system_conversation_member_you_removed
import id.homebase.resources.system_conversation_photo_updated
import id.homebase.resources.system_conversation_photo_updated_you
import id.homebase.resources.system_conversation_title_updated
import id.homebase.resources.system_conversation_title_updated_you
import id.homebase.resources.system_group_conversation_member_declined_rejoin
import id.homebase.resources.system_group_conversation_member_declined_rejoin_you
import id.homebase.resources.system_group_conversation_member_left
import id.homebase.resources.system_group_conversation_member_left_you
import id.homebase.resources.system_group_conversation_started
import id.homebase.resources.system_group_conversation_started_you
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
                    is BackendEvent.DriveEvent.Started -> { }

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

    // Full message load from local DB for a single conversation.
    // Called when the user opens a conversation (ConversationListViewModel.selectConversation).
    // Do NOT call from DriveEvent.Stopped or other sync events — see init block above.
    suspend fun loadConversation(conversationId: Uuid) {
        Logger.d("ChatMessageStream: loadConversation($conversationId)")
        val result = fetchMessages(conversationId)
        Logger.d("ChatMessageStream: loadConversation($conversationId) → ${result.records.size} messages")
        conversationState.set(conversationId, result.records)
    }

// ---------- EVENT HANDLING ----------

    private suspend fun processIncrementalBatch(files: List<HomebaseFile>) {
        val messages =
            files
                .filter { it.fileMetadata.appData.fileType == ChatProtocol.MessageFileType }
                .mapNotNull { mapToMessageData(it, credentialsManager, ::resolveDisplayName) }

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

            if (header.fileMetadata.appData.groupId == ChatProtocol.ConversationWithYourselfId) {
                return ChatDeliveryStatus.Read
            }

            val count = header.serverMetadata.originalRecipientCount
            val transferSummary =
                header.serverMetadata.transferHistory?.summary ?: return ChatDeliveryStatus.Sent

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
                    val deletedUserDate = if (appData.userDate == null)
                        metadata.created
                    else
                        minOf(UnixTimeUtc(appData.userDate!!), metadata.created)

                    return MessageUiModel(
                        id = appData.uniqueId ?: header.fileId,
                        globalTransitId = metadata.globalTransitId,
                        fileId = header.fileId,
                        conversationId = appData.groupId!!,
                        userDate = deletedUserDate.toInstant(),
                        modified = metadata.updated.toInstant(),
                        created = metadata.created.toInstant(),
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
                        status = status,
                        currentUser = domain
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

                val rawUserDate =
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

                // Clamp: userDate should never exceed the server-side timestamp
                val userDate = minOf(rawUserDate, authorSpecificDate)

                return MessageUiModel(
                    id = appData.uniqueId!!,
                    globalTransitId = metadata.globalTransitId,
                    fileId = header.fileId,
                    conversationId = appData.groupId!!,
                    content = messageAppData.getMessage(),
                    userDate = userDate.toInstant(),
                    modified = metadata.updated.toInstant(),
                    created = metadata.created.toInstant(),
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
                        userDate = metadata.created.toInstant(),
                        modified = metadata.updated.toInstant(),
                        created = metadata.created.toInstant(),
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

        fun renderStatusMessage(author: OdinId?, status: StatusMessageData, currentUser: OdinId? = null): String {
            val authorIsYou = currentUser != null && author == currentUser
            val subjectIsYou = currentUser != null && status.subject != null && status.subject == currentUser
            val name = author?.domainName ?: TranslationUtil.getString(MR.string.someone)
            val subject = status.subject?.domainName

            return when (status.statusMessage) {
                StatusMessage.ConversationTitleUpdated ->
                    if (authorIsYou) TranslationUtil.getString(MR.string.system_conversation_title_updated_you)
                    else TranslationUtil.getString(MR.string.system_conversation_title_updated, name)

                StatusMessage.ConversationPhotoUpdated ->
                    if (authorIsYou) TranslationUtil.getString(MR.string.system_conversation_photo_updated_you)
                    else TranslationUtil.getString(MR.string.system_conversation_photo_updated, name)

                StatusMessage.ConversationMemberAdded ->
                    when {
                        authorIsYou && subject != null ->
                            TranslationUtil.getString(MR.string.system_conversation_member_you_added, subject)
                        subjectIsYou ->
                            TranslationUtil.getString(MR.string.system_conversation_member_added_you, name)
                        subject != null ->
                            TranslationUtil.getString(MR.string.system_conversation_member_name_added, name, subject)
                        else ->
                            TranslationUtil.getString(MR.string.system_conversation_member_added, name)
                    }

                StatusMessage.ConversationMemberRemoved ->
                    when {
                        authorIsYou && subject != null ->
                            TranslationUtil.getString(MR.string.system_conversation_member_you_removed, subject)
                        subjectIsYou ->
                            TranslationUtil.getString(MR.string.system_conversation_member_removed_you, name)
                        subject != null ->
                            TranslationUtil.getString(MR.string.system_conversation_member_name_removed, name, subject)
                        else ->
                            TranslationUtil.getString(MR.string.system_conversation_member_removed, name)
                    }

                StatusMessage.ConversationAdminAdded ->
                    when {
                        authorIsYou && subject != null ->
                            TranslationUtil.getString(MR.string.system_conversation_admin_you_added, subject)
                        subjectIsYou ->
                            TranslationUtil.getString(MR.string.system_conversation_admin_added_you, name)
                        subject != null ->
                            TranslationUtil.getString(MR.string.system_conversation_admin_name_added, name, subject)
                        else ->
                            TranslationUtil.getString(MR.string.system_conversation_admin_added, name)
                    }

                StatusMessage.ConversationAdminRemoved ->
                    when {
                        authorIsYou && subject != null ->
                            TranslationUtil.getString(MR.string.system_conversation_admin_you_removed, subject)
                        subjectIsYou ->
                            TranslationUtil.getString(MR.string.system_conversation_admin_removed_you, name)
                        subject != null ->
                            TranslationUtil.getString(MR.string.system_conversation_admin_name_removed, name, subject)
                        else ->
                            TranslationUtil.getString(MR.string.system_conversation_admin_removed, name)
                    }

                StatusMessage.GroupConversationStarted ->
                    if (authorIsYou) TranslationUtil.getString(MR.string.system_group_conversation_started_you)
                    else TranslationUtil.getString(MR.string.system_group_conversation_started, name)

                StatusMessage.ConversationMemberLeft ->
                    if (authorIsYou) TranslationUtil.getString(MR.string.system_group_conversation_member_left_you)
                    else TranslationUtil.getString(MR.string.system_group_conversation_member_left, name)

                StatusMessage.ConversationMemberDeclinedRejoin ->
                    if (authorIsYou) TranslationUtil.getString(MR.string.system_group_conversation_member_declined_rejoin_you)
                    else TranslationUtil.getString(MR.string.system_group_conversation_member_declined_rejoin, name)
            }
        }
    }
}

sealed interface ChatMessagesData {
    data object Initializing : ChatMessagesData
    data class Messages(val messages: List<MessageUiModel>) : ChatMessagesData
}
