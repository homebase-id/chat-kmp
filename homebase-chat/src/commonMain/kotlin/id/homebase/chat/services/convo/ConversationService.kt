package id.homebase.chat.services.convo

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.connections.ConnectionIntroductionProvider
import id.homebase.api.client.connections.IntroductionGroup
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.drives.files.ArchivalStatus
import id.homebase.api.client.drives.files.DeleteFilesByGroupIdOutboxRequest
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.files.ThumbnailFile
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.client.drives.upload.FileIdFileIdentifier
import id.homebase.api.client.drives.upload.UpdateLocalMetadataTagsOutboxRequest
import id.homebase.api.client.drives.upload.FileUpdateInstructionSet
import id.homebase.api.client.drives.upload.TransitOptions
import id.homebase.api.client.drives.upload.UpdateFileByUniqueIdRequest
import id.homebase.api.client.drives.upload.UpdateLocale
import id.homebase.api.client.drives.upload.UpdateManifest
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.common.OdinId
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.OutboxSync
import id.homebase.api.sync.database.QueryBatch
import id.homebase.api.toBase64
import id.homebase.chat.data.ConversationState
import id.homebase.chat.data.ConversationUiModel
import id.homebase.chat.services.ChatMessageSenderService
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.PayloadBundle
import id.homebase.chat.services.PayloadBundleEncryptionService
import id.homebase.chat.services.StatusMessage
import id.homebase.chat.services.StatusMessageData
import id.homebase.chat.services.XorIdUtil
import id.homebase.chat.services.convo.contact.ContactService
import id.homebase.chat.services.outbox.OptimisticWriter
import id.homebase.core.config.chatTargetDrive
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlin.collections.plus

class ConversationService(
    private val credentialsManager: CredentialsManager,
    private val payloadBundleEncryptionService: PayloadBundleEncryptionService,
    private val dbm: DatabaseManager,
    private val introductionProvider: ConnectionIntroductionProvider,
    private val scope: CoroutineScope,
    private val outboxSync: OutboxSync,
    private val chatMessageSenderService: ChatMessageSenderService,
    private val optimisticWriter: OptimisticWriter
) {
    private val chatDrive = chatTargetDrive.alias

    private val mapper: ConversationMapper = ConversationMapper(
        credentialsManager = credentialsManager,
        dbm = dbm
    )

    suspend fun createConversation(
        recipients: List<OdinId>,
        title: String?,
        payloadBundle: PayloadBundle?
    ): Uuid {

        val domain = credentialsManager.requireActiveDomain()

        // I know, this is illogical but somehow a null made it in so #paranoid
        require(recipients.none { it == null }) {
            "Conversation recipients contained null"
        }

        val normalizedRecipients =
            recipients
                .filterNotNull()
                .filterNot { it == domain }
                .distinct()

        require(normalizedRecipients.isNotEmpty()) {
            "Conversation must have at least one recipient other than self"
        }

        val isGroup = normalizedRecipients.size > 1
        val keyHeader = KeyHeader.newRandom16()

        val newConversationId: Uuid =
            if (isGroup) {
                Uuid.random()
            } else {
                XorIdUtil.getNewXorId(domain.domainName, normalizedRecipients.first().domainName)
            }

        val existingConversation = getConversation(newConversationId)
        if (existingConversation != null) {
            return newConversationId
        }

        val content =
            ConversationAppDataJson(
                title = title ?: "",
                recipients = (normalizedRecipients + domain).distinct(),
                version = 1
            )

        val encryptedBundle =
            payloadBundleEncryptionService.encryptBundle(
                newConversationId,
                payloadBundle,
                keyHeader.aesKey,
                scope
            )

        val metadata =
            UploadFileMetadata(
                allowDistribution = true,
                isEncrypted = true,
                appData =
                    UploadAppFileMetaData(
                        uniqueId = newConversationId,
                        tags = if (isGroup) listOf(ChatProtocol.ConversationGroupTag) else null,
                        fileType = ChatProtocol.ConversationFileType,
                        content = OdinSystemSerializer.serialize(content),
                        previewThumbnail =
                            encryptedBundle.previewThumbs.minByOrNull {
                                it.pixelWidth
                            }
                    ),
            )

        val request =
            UploadFileRequest(
                driveId = chatDrive,
                keyHeader = keyHeader,
                metadata = metadata.encryptContent(keyHeader),
                transitOptions =
                    TransitOptions(recipients = normalizedRecipients, useAppNotification = false),
                payloads = encryptedBundle.payloads,
                thumbnails = encryptedBundle.thumbnails
            )

        val enqueued = outboxSync.tryEnqueue(request)

        if (!enqueued) {
            error("failed to create conversation")
        }

        // Create separate admin file for groups
        if (isGroup) {
            uploadAdminFile(
                conversationId = newConversationId,
                admins = listOf(domain),
                recipients = normalizedRecipients
            )
        }

        if (isGroup) {
            trySendIntroductions(normalizedRecipients, "$domain has added you to a group chat")

            chatMessageSenderService.sendStatusMessage(
                messageUniqueId = Uuid.random(),
                conversationId = newConversationId,
                statusMessage = StatusMessageData(
                    statusMessage = StatusMessage.GroupConversationStarted,
                    subject = null
                )
            )
        }

        return newConversationId
    }

    suspend fun requireConversation(conversationId: Uuid): ConversationUiModel {
        return getConversation(conversationId)
            ?: throw IllegalStateException("No conversation found")
    }

    suspend fun requireConversationFileId(conversationId: Uuid): Uuid {
        return getConversationHomebaseFile(conversationId)?.fileId
            ?: throw IllegalStateException("No conversation found")
    }

    suspend fun getConversation(conversationId: Uuid): ConversationUiModel? {
        val file = getConversationHomebaseFile(conversationId) ?: return null
        return mapper.mapToConversationUi(file, null)
    }

    suspend fun updateAdmins(
        conversationId: Uuid,
        add: List<OdinId> = emptyList(),
        remove: List<OdinId> = emptyList()
    ) {
        val conversation = requireConversation(conversationId)
        val domain = credentialsManager.requireActiveDomain()

        requireCallerIsGroupAdmin(conversation)

        val recipients = conversation.participants
        val admins = conversation.admins.toMutableSet()

        // additions must already be participants
        require(add.all { recipients.contains(it) }) {
            "Admins must be recipients"
        }

        //TODO: reconcile the data here
        admins.addAll(add)
        admins.removeAll(remove)

        if (admins.isEmpty()) {
            throw IllegalStateException("Conversation must have at least one admin")
        }

        // forbid removing yourself if you would leave zero admins
        if (remove.contains(domain) && !admins.contains(domain)) {
            if (admins.size == 0) {
                throw IllegalStateException("Cannot remove the last admin.  You must first add another to replace you")
            }
        }

        updateAdminFile(
            conversationId = conversationId,
            admins = admins.toList(),
            recipients = recipients.filterNot { it == domain }
        )

        var previousMessageId: Uuid? = null
        add.forEach { user ->
            val messageId = Uuid.random()
            chatMessageSenderService.sendStatusMessage(
                messageUniqueId = messageId,
                conversationId = conversationId,
                statusMessage = StatusMessageData(
                    statusMessage = StatusMessage.ConversationAdminAdded,
                    subject = user
                ),
                previousMessageUniqueId = previousMessageId
            )

            previousMessageId = messageId
        }

        remove.forEach { user ->
            val messageId = Uuid.random()
            chatMessageSenderService.sendStatusMessage(
                messageUniqueId = messageId,
                conversationId = conversationId,
                statusMessage = StatusMessageData(
                    statusMessage = StatusMessage.ConversationAdminRemoved,
                    subject = user
                ),
                previousMessageUniqueId = previousMessageId
            )

            previousMessageId = messageId
        }

    }

    suspend fun updateGroupMembers(
        conversationId: Uuid,
        add: List<OdinId> = emptyList(),
        remove: List<OdinId> = emptyList()
    ) {
        val conversation = requireConversation(conversationId)
        requireCallerIsGroupAdmin(conversation)

        val domain = credentialsManager.requireActiveDomain()

        val adminsInRemoveList = conversation.admins.intersect(remove.toSet())
        require(adminsInRemoveList.isEmpty()) {
            "Cannot remove admins via updateGroupMembers. Use updateAdmins first to remove their admin role: $adminsInRemoveList"
        }

        val current = conversation.participants.toMutableSet()

        val removed = current.intersect(remove.toSet())
        current.removeAll(remove)

        val added = add.filterNot { current.contains(it) }
        current.addAll(added)

        var previousMessageId: Uuid? = null

        removed.forEach { user ->
            val messageId = Uuid.random()
            chatMessageSenderService.sendStatusMessage(
                messageUniqueId = messageId,
                conversationId = conversationId,
                statusMessage = StatusMessageData(
                    statusMessage = StatusMessage.ConversationMemberRemoved,
                    subject = user
                ),
                previousMessageUniqueId = previousMessageId
            )

            previousMessageId = messageId
        }

        val normalized = (current + domain).distinct()

        updateConversationInternal(
            conversationId = conversationId,
            title = conversation.name,
            participants = normalized,
            additionalDistributionRecipients = removed.toList()
        )

        // tell the group who was added after we update the conversation so
        // the new people will get the message too
        if (added.isNotEmpty()) {

            // ensure the message is sent to added after they get the new conversation file
            previousMessageId = conversationId

            added.forEach { user ->
                val messageId = Uuid.random()
                chatMessageSenderService.sendStatusMessage(
                    messageUniqueId = messageId,
                    conversationId = conversationId,
                    statusMessage = StatusMessageData(
                        statusMessage = StatusMessage.ConversationMemberAdded,
                        subject = user
                    ),
                    previousMessageUniqueId = previousMessageId,
                    additionalRecipients = listOf(user)
                )

                previousMessageId = messageId
            }

            trySendIntroductions(added, "$domain has added you to a group chat")
        }
    }

    suspend fun updateConversation(
        conversationId: Uuid,
        title: String?,
        payloadBundle: PayloadBundle? = null
    ) {
        val conversation = requireConversation(conversationId)

        if (conversation.isGroupConversation) {
            requireCallerIsGroupAdmin(conversation)
        }

        updateConversationInternal(
            conversationId = conversationId,
            title = title,
            participants = conversation.participants,
            payloadBundle = payloadBundle
        )

        var previousMessageId: Uuid? = null
        if (title != null && title != conversation.name) {

            val messageId = Uuid.random()

            chatMessageSenderService.sendStatusMessage(
                messageUniqueId = messageId,
                conversationId = conversationId,
                statusMessage = StatusMessageData(
                    statusMessage = StatusMessage.ConversationTitleUpdated,
                ),
                previousMessageUniqueId = previousMessageId
            )

            previousMessageId = messageId
        }

        if (payloadBundle != null) {

            val messageId = Uuid.random()

            chatMessageSenderService.sendStatusMessage(
                messageUniqueId = messageId,
                conversationId = conversationId,
                statusMessage = StatusMessageData(
                    statusMessage = StatusMessage.ConversationPhotoUpdated,
                ),
                previousMessageUniqueId = previousMessageId
            )

            previousMessageId = messageId
        }

    }

    suspend fun leaveGroup(conversationId: Uuid) {
        val conversation = requireConversation(conversationId)
        val domain = credentialsManager.requireActiveDomain()
        val leaveFile = getConversationHomebaseFile(conversationId)
        Logger.d { "leaveGroup START: conversationId=$conversationId isEncrypted=${leaveFile?.fileMetadata?.isEncrypted} aesKey=${leaveFile?.keyHeader?.aesKey?.unsafeBytes?.toBase64() ?: "NO FILE"}" }

        if (!conversation.isGroupConversation) {
            throw IllegalStateException("Can only leave group conversations")
        }

        if (conversation.admins.contains(domain) && (conversation.admins - domain).isEmpty()) {
            throw IllegalStateException("You are the only admin. Assign another admin before leaving.")
        }

        val remaining = conversation.participants.filterNot { it == domain }

        val messageId = Uuid.random()

        // this is not critical for leaving a group so don't block
        try {
            // 1. Notify the group first
            chatMessageSenderService.sendStatusMessage(
                messageUniqueId = messageId,
                conversationId = conversationId,
                statusMessage = StatusMessageData(
                    statusMessage = StatusMessage.ConversationMemberLeft,
                    subject = domain
                )
            )
        } catch (t: Throwable) {
            Logger.e("Failed to send leave status message", t)
        }

        // 2. Remove self from participants — chained after status message
        updateConversationInternal(
            conversationId = conversationId,
            title = conversation.name,
            participants = remaining,
            dependencyUniqueId = messageId
        )

        // 3. Remove self from admins (separate file)
        if (conversation.admins.contains(domain)) {
            val updatedAdmins = conversation.admins - domain
            updateAdminFile(
                conversationId = conversationId,
                admins = updatedAdmins.toList(),
                recipients = remaining.filterNot { it == domain }
            )
        }

        // 3. Mark as left locally — preserves history and blocks sending.
        // Depend on conversationId so the tags update is only sent to the server AFTER the
        // participant-removal file update (UpdateFileByUniqueIdRequest, uniqueId=conversationId)
        // has been processed. Without this ordering, the server could briefly see the LeftTag
        // while domain is still in participants, causing a spurious RejoinPending state.
        updateConversationTags(conversationId, dependencyUniqueId = conversationId) {
            it + ChatProtocol.ConversationLeftTag
        }

        val postLeaveFile = getConversationHomebaseFile(conversationId)
        Logger.d { "leaveGroup END: conversationId=$conversationId aesKey=${postLeaveFile?.keyHeader?.aesKey?.unsafeBytes?.toBase64() ?: "NO FILE"}" }
    }

    suspend fun acceptRejoin(conversationId: Uuid) {
        val conversation = requireConversation(conversationId)
        if (conversation.conversationState != ConversationState.RejoinPending) {
            throw IllegalStateException("Conversation is not in RejoinPending state")
        }
        // Clear the left tag — mapper will produce Active state on next load
        updateConversationTags(conversationId) {
            it - ChatProtocol.ConversationLeftTag
        }
    }

    suspend fun declineRejoin(conversationId: Uuid) {
        val conversation = requireConversation(conversationId)
        val domain = credentialsManager.requireActiveDomain()

        if (conversation.conversationState != ConversationState.RejoinPending) {
            throw IllegalStateException("Conversation is not in RejoinPending state")
        }

        val remaining = conversation.participants.filterNot { it == domain }

        // 1. Tell the group this person declined — distinct from a voluntary leave
        val messageId = Uuid.random()
        chatMessageSenderService.sendStatusMessage(
            messageUniqueId = messageId,
            conversationId = conversationId,
            statusMessage = StatusMessageData(
                statusMessage = StatusMessage.ConversationMemberDeclinedRejoin,
                subject = domain
            )
        )

        // 2. Remove self from participants — chained after status message
        updateConversationInternal(
            conversationId = conversationId,
            title = conversation.name,
            participants = remaining,
            dependencyUniqueId = messageId
        )

        // 3. Remove self from admins (separate file)
        if (conversation.admins.contains(domain)) {
            val updatedAdmins = conversation.admins - domain
            updateAdminFile(
                conversationId = conversationId,
                admins = updatedAdmins.toList(),
                recipients = remaining.filterNot { it == domain }
            )
        }

        // 4. Keep the left tag locally — same ordering dependency as leaveGroup
        updateConversationTags(conversationId, dependencyUniqueId = conversationId) {
            it + ChatProtocol.ConversationLeftTag
        }
    }

    suspend fun updateConversationInternal(
        conversationId: Uuid,
        title: String?,
        participants: List<OdinId>,
        payloadBundle: PayloadBundle? = null,
        dependencyUniqueId: Uuid? = null,
        archivalStatus: ArchivalStatus? = null,
        distribute: Boolean = true,
        additionalDistributionRecipients: List<OdinId> = emptyList()
    ) {
        val credentials = credentialsManager.requireActiveCredentials()
        val domain = credentials.domain

        val conversationFile = getConversationHomebaseFile(conversationId)
            ?: error("No conversation found")

        val conversation = requireConversation(conversationId)

        Logger.d { "updateConversationInternal: conversationId=$conversationId isEncrypted=${conversationFile.fileMetadata.isEncrypted} aesKey=${conversationFile.keyHeader.aesKey.unsafeBytes.toBase64()} ivLen=${conversationFile.keyHeader.iv.size} keyLen=${conversationFile.keyHeader.aesKey.unsafeBytes.size}" }

        val keyHeader = KeyHeader(
            iv = ByteArrayUtil.getRndByteArray(16),
            aesKey = conversationFile.keyHeader.aesKey
        )

        val content =
            ConversationAppDataJson(
                title = title ?: "",
                recipients = participants,
                version = 1 // logical version; server enforces via versionTag
            )

        var manifest: UpdateManifest
        var previewThumb: EmbeddedThumb?
        var payloads: List<PayloadFile>?
        var thumbs: List<ThumbnailFile>?

        if (payloadBundle == null) {
            manifest = UpdateManifest.build(
                payloads = null,
                toDeletePayloads = null,
                thumbnails = null,
                generatePayloadIv = false
            )

            payloads = emptyList()
            thumbs = emptyList()
            previewThumb = conversationFile.fileMetadata.appData.previewThumbnail

        } else {

            val encryptedBundle =
                payloadBundleEncryptionService.encryptBundle(
                    conversationId,
                    payloadBundle,
                    keyHeader.aesKey,
                    scope
                )

            payloads = encryptedBundle.payloads
            thumbs = encryptedBundle.thumbnails

            manifest =
                UpdateManifest.build(
                    payloads = payloads,
                    toDeletePayloads = null,
                    thumbnails = encryptedBundle.thumbnails,
                    generatePayloadIv = false
                )

            previewThumb = encryptedBundle.previewThumbs.minByOrNull {
                it.pixelWidth
            }
        }

        val existingAppData = conversationFile.fileMetadata.appData
        val metadata =
            UploadFileMetadata(
                allowDistribution = conversationFile.serverMetadata.allowDistribution,
                isEncrypted = true, // we always encrypt conversation files
                accessControlList = conversationFile.serverMetadata.accessControlList,
                referencedFile = conversationFile.fileMetadata.referencedFile,
                versionTag = conversationFile.fileMetadata.versionTag,
                appData =
                    UploadAppFileMetaData(
                        uniqueId = conversationId,
                        tags = existingAppData.tags,
                        fileType = existingAppData.fileType,
                        dataType = existingAppData.dataType,
                        groupId = existingAppData.groupId,
                        userDate = existingAppData.userDate,
                        content = OdinSystemSerializer.serialize(content),
                        previewThumbnail = previewThumb,
                        archivalStatus = archivalStatus ?: existingAppData.archivalStatus
                    )
            )

        val instructions =
            FileUpdateInstructionSet(
                transferIv = ByteArrayUtil.getRndByteArray(16),
                locale = UpdateLocale.Local,
                recipients = if (distribute) (participants + additionalDistributionRecipients).filterNot { it == domain }
                    .distinct() else emptyList(),
                manifest = manifest
            )

        Logger.d { "updateConversationInternal PRE-REQUEST: conversationId=$conversationId aesKey=${keyHeader.aesKey.unsafeBytes.toBase64()} versionTag=${conversationFile.fileMetadata.versionTag}" }

        val request =
            UpdateFileByUniqueIdRequest(
                driveId = chatDrive,
                uniqueId = conversationId,
                keyHeader = keyHeader,
                instructions = instructions,
                metadata = metadata.encryptContent(keyHeader),
                payloads = payloads,
                thumbnails = thumbs
            )

        Logger.d { "updateConversationInternal POST-ENCRYPT: conversationId=$conversationId aesKey=${keyHeader.aesKey.unsafeBytes.toBase64()} requestKeyHeader=${request.keyHeader?.aesKey?.unsafeBytes?.toBase64()}" }

        // Optimistically apply the participant/content change to the local DB immediately.
        // This ensures that any code running after this call (e.g. updateConversationTags)
        // sees the updated participant list when it reads the file, preventing a false
        // RejoinPending detection caused by the outbox/localTags race.
        optimisticWriter.writeUpdate(
            driveId = chatDrive,
            keyHeader = keyHeader,
            unecryptedMetadata = metadata
        )

        val enqueued = outboxSync.tryEnqueue(request, dependencyUniqueId = dependencyUniqueId)
        if (!enqueued) {
            error("Failed to update conversation")
        }
    }

    suspend fun introduceEveryone(conversationId: Uuid, message: String?) {
        val conversation = requireConversation(conversationId)
        trySendIntroductions(conversation.participants, message ?: "")
    }

    private suspend fun trySendIntroductions(
        recipients: List<OdinId>,
        message: String
    ) {
        try {
            // send introductions
            introductionProvider.sendIntroductions(
                group = IntroductionGroup(
                    recipients = recipients,
                    message = message
                )
            )
        } catch (t: Throwable) {
            Logger.e("Failed sending introductions", t)
        }
    }

    private suspend fun requireCallerIsGroupAdmin(conversation: ConversationUiModel) {
        val domain = credentialsManager.requireActiveDomain()

        if (!conversation.isGroupConversation) {
            throw IllegalStateException("Must be a group conversations")
        }

        if (!conversation.admins.contains(domain)) {
            throw IllegalStateException("Only group admins can perform this action")
        }
    }

    suspend fun archiveConversation(conversationId: Uuid) {
        updateConversationTags(conversationId) { it + ChatProtocol.ConversationArchivedTag }
    }

    suspend fun unarchiveConversation(conversationId: Uuid) {
        updateConversationTags(conversationId) { it - ChatProtocol.ConversationArchivedTag }
    }

    suspend fun pinConversation(conversationId: Uuid) {
        updateConversationTags(conversationId) { it + ChatProtocol.ConversationPinnedTag }
    }

    suspend fun unpinConversation(conversationId: Uuid) {
        updateConversationTags(conversationId) { it - ChatProtocol.ConversationPinnedTag }
    }

    suspend fun deleteConversation(conversationId: Uuid) {
        val conversation = requireConversation(conversationId)

        if (conversation.isGroupConversation && !(
                    conversation.conversationState == ConversationState.Left ||
                            conversation.conversationState == ConversationState.RejoinPending ||
                            conversation.conversationState == ConversationState.Removed
                    )
        ) {
            throw IllegalStateException("You must leave the group before deleting it")
        }

        val deleteFile = getConversationHomebaseFile(conversationId)
        Logger.d { "deleteConversation: conversationId=$conversationId isEncrypted=${deleteFile?.fileMetadata?.isEncrypted} aesKey=${deleteFile?.keyHeader?.aesKey?.unsafeBytes?.toBase64() ?: "NO FILE"}" }

        outboxSync.tryEnqueue(
            DeleteFilesByGroupIdOutboxRequest(
                driveId = chatDrive,
                groupIds = listOf(conversationId)
            )
        )

        updateConversationInternal(
            conversationId = conversationId,
            title = conversation.name,
            participants = conversation.participants,
            archivalStatus = ArchivalStatus.Removed,
            distribute = false
        )
    }

    suspend fun clearConversation(conversationId: Uuid) {
        outboxSync.tryEnqueue(
            DeleteFilesByGroupIdOutboxRequest(
                driveId = chatDrive,
                groupIds = listOf(conversationId)
            )
        )
    }

    private suspend fun updateConversationTags(
        conversationId: Uuid,
        dependencyUniqueId: Uuid? = null,
        transform: (Set<Uuid>) -> Set<Uuid>
    ) {
        val file = getConversationHomebaseFile(conversationId)
            ?: error("Conversation not found: $conversationId")

        val currentTags = file.fileMetadata.localAppData?.tags?.toSet() ?: emptySet()
        val newTags = transform(currentTags)

        optimisticWriter.updateLocalTags(
            driveId = chatDrive,
            uniqueId = conversationId,
            newTags = newTags.toList()
        )

        // Use a random uniqueId so this request does not conflict with a concurrent
        // UpdateFileByUniqueIdRequest that also uses uniqueId=conversationId.
        // The UNIQUE(driveId, uniqueId) outbox constraint would otherwise silently drop this
        // enqueue while the file update is still pending, causing the LeftTag to never reach
        // the server.  The dependencyUniqueId still ensures correct ordering when provided.
        outboxSync.tryEnqueue(
            request = UpdateLocalMetadataTagsOutboxRequest(
                file = FileIdFileIdentifier(
                    fileId = file.fileId.toString(),
                    targetDrive = chatTargetDrive
                ),
                versionTag = file.fileMetadata.localAppData?.versionTag?.toString(),
                tags = newTags.map { it.toString() }
            ),
            driveId = chatDrive,
            uniqueId = Uuid.random(),
            dependencyUniqueId = dependencyUniqueId
        )
    }

    private suspend fun getConversationHomebaseFile(conversationId: Uuid): HomebaseFile? {

        val c = credentialsManager.requireActiveCredentials()
        val queryBatch = QueryBatch(c.getIdentityId())

        val result =
            queryBatch.queryBatchAsync(
                dbm = dbm,
                driveId = chatDrive,
                noOfItems = 1,
                cursor = null,
                sortOrder = QueryBatchSortOrder.NewestFirst,
                sortField = QueryBatchSortField.CreatedDate,
                fileSystemType = 0,
                uniqueIdAnyOf = listOf(conversationId),
                filetypesAnyOf = listOf(ChatProtocol.ConversationFileType),
            )

        val file = result.records.firstOrNull()

        return file
    }

    suspend fun getConversationAdminHomebaseFile(conversationId: Uuid): HomebaseFile? {
        val c = credentialsManager.requireActiveCredentials()
        val queryBatch = QueryBatch(c.getIdentityId())
        val adminUniqueId = ChatProtocol.getAdminFileUniqueId(conversationId)

        val result = queryBatch.queryBatchAsync(
            dbm = dbm,
            driveId = chatDrive,
            noOfItems = 1,
            cursor = null,
            sortOrder = QueryBatchSortOrder.NewestFirst,
            sortField = QueryBatchSortField.CreatedDate,
            fileSystemType = 0,
            uniqueIdAnyOf = listOf(adminUniqueId),
            filetypesAnyOf = listOf(ChatProtocol.ConversationAdminFileType),
        )

        return result.records.firstOrNull()
    }

    /** Reads the admin list from the dedicated admin file, falling back to originalAuthor. */
    suspend fun getAdmins(conversationId: Uuid): Set<OdinId> {
        val adminFile = getConversationAdminHomebaseFile(conversationId)
        if (adminFile != null) {
            val content = adminFile.fileMetadata.appData.content
            if (!content.isNullOrEmpty()) {
                val adminInfo = OdinSystemSerializer.deserialize<ConversationAdminInfo>(content)
                if (!adminInfo.admins.isNullOrEmpty()) {
                    return adminInfo.admins.toSet()
                }
            }
        }

        // Fallback: originalAuthor from conversation file
        val conversationFile = getConversationHomebaseFile(conversationId)
        val author = conversationFile?.fileMetadata?.originalAuthor
            ?: conversationFile?.fileMetadata?.senderOdinId
            ?: credentialsManager.requireActiveDomain()
        return setOf(author)
    }

    /** Creates a new admin file for a conversation. */
    private suspend fun uploadAdminFile(
        conversationId: Uuid,
        admins: List<OdinId>,
        recipients: List<OdinId>
    ) {
        val keyHeader = KeyHeader.newRandom16()
        val adminUniqueId = ChatProtocol.getAdminFileUniqueId(conversationId)
        val content = OdinSystemSerializer.serialize(ConversationAdminInfo(admins = admins))

        val metadata = UploadFileMetadata(
            allowDistribution = true,
            isEncrypted = true,
            appData = UploadAppFileMetaData(
                uniqueId = adminUniqueId,
                fileType = ChatProtocol.ConversationAdminFileType,
                groupId = conversationId,
                content = content,
            ),
        )

        val request = UploadFileRequest(
            driveId = chatDrive,
            keyHeader = keyHeader,
            metadata = metadata.encryptContent(keyHeader),
            transitOptions = TransitOptions(recipients = recipients, useAppNotification = false),
        )

        optimisticWriter.writeNewFile(
            driveId = chatDrive,
            keyHeader = keyHeader,
            unecryptedMetadata = metadata,
            originalRecipientCount = recipients.size,
            fileSystemType = FileSystemType.Standard,
        )

        outboxSync.tryEnqueue(request)
    }

    /** Updates an existing admin file (or creates one if it doesn't exist yet). */
    private suspend fun updateAdminFile(
        conversationId: Uuid,
        admins: List<OdinId>,
        recipients: List<OdinId>
    ) {
        val existingFile = getConversationAdminHomebaseFile(conversationId)
        if (existingFile == null) {
            uploadAdminFile(conversationId, admins, recipients)
            return
        }

        val keyHeader = KeyHeader(
            iv = ByteArrayUtil.getRndByteArray(16),
            aesKey = existingFile.keyHeader.aesKey
        )

        val content = OdinSystemSerializer.serialize(ConversationAdminInfo(admins = admins))

        val metadata = UploadFileMetadata(
            allowDistribution = existingFile.serverMetadata.allowDistribution,
            isEncrypted = existingFile.serverFileIsEncrypted,
            versionTag = existingFile.fileMetadata.versionTag,
            appData = UploadAppFileMetaData(
                uniqueId = existingFile.fileMetadata.appData.uniqueId,
                fileType = ChatProtocol.ConversationAdminFileType,
                groupId = conversationId,
                content = content,
            ),
        )

        val adminUniqueId = ChatProtocol.getAdminFileUniqueId(conversationId)
        val domain = credentialsManager.requireActiveDomain()

        val instructions = FileUpdateInstructionSet(
            transferIv = ByteArrayUtil.getRndByteArray(16),
            locale = UpdateLocale.Local,
            recipients = recipients.filterNot { it == domain },
            manifest = UpdateManifest.build(
                payloads = null,
                toDeletePayloads = null,
                thumbnails = null,
                generatePayloadIv = false
            )
        )

        val request = UpdateFileByUniqueIdRequest(
            driveId = chatDrive,
            uniqueId = adminUniqueId,
            keyHeader = keyHeader,
            instructions = instructions,
            metadata = metadata.encryptContent(keyHeader),
        )

        optimisticWriter.writeUpdate(
            driveId = chatDrive,
            keyHeader = keyHeader,
            unecryptedMetadata = metadata
        )

        outboxSync.tryEnqueue(request)
    }
}
