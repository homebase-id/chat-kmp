package id.homebase.chat.services.convo

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.connections.IntroductionGroup
import id.homebase.api.client.connections.IntroductionSender
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.ArchivalStatus
import id.homebase.api.client.drives.files.DeleteFilesByGroupIdOutboxRequest
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.files.ThumbnailFile
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.client.drives.upload.FileIdFileIdentifier
import id.homebase.api.client.drives.upload.FileUpdateInstructionSet
import id.homebase.api.client.drives.upload.TransitOptions
import id.homebase.api.client.drives.upload.UpdateFileByUniqueIdRequest
import id.homebase.api.client.drives.upload.UpdateLocalMetadataTagsOutboxRequest
import id.homebase.api.client.drives.upload.UpdateLocale
import id.homebase.api.client.drives.upload.UpdateManifest
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.common.OdinId
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.OutboxSync
import id.homebase.api.toBase64
import id.homebase.chat.data.ConversationState
import id.homebase.chat.data.ConversationUiModel
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.PayloadBundle
import id.homebase.chat.services.PayloadBundleEncryptor
import id.homebase.chat.services.StatusMessage
import id.homebase.chat.services.StatusMessageData
import id.homebase.chat.services.XorIdUtil
import id.homebase.chat.services.outbox.OptimisticWriter
import id.homebase.core.config.chatTargetDrive
import kotlinx.coroutines.CoroutineScope
import kotlin.uuid.Uuid

class ConversationService(
    private val credentialsManager: CredentialsManager,
    private val payloadBundleEncryptionService: PayloadBundleEncryptor,
    private val dbm: DatabaseManager,
    private val introductionProvider: IntroductionSender,
    private val scope: CoroutineScope,
    private val outboxSync: OutboxSync,
    private val chatMessageSenderService: StatusMessageSender,
    private val optimisticWriter: OptimisticWriter,
    private val conversationStream: ConversationLoader,
) : LocalLastReadUpdater {
    private val chatDrive = chatTargetDrive.alias

    private val mapper: ConversationMapper = ConversationMapper(
        credentialsManager = credentialsManager,
        dbm = dbm
    )

    suspend fun createConversation(
        recipients: List<OdinId>,
        title: String?,
        payloadBundle: PayloadBundle?
    ): CreateConversationResult {

        val domain = credentialsManager.requireActiveDomain()

        // I know, this is illogical but somehow a null made it in so #paranoid
        require(recipients.none {
            @Suppress("SENSELESS_COMPARISON")
            it == null
        }) {
            "Conversation recipients contained null"
        }

        val normalizedRecipients =
            recipients
                .filterNot { it == domain }
                .distinct()

        require(normalizedRecipients.isNotEmpty()) {
            "Conversation must have at least one recipient other than self"
        }

        val isGroup = normalizedRecipients.size > 1

        val newConversationId: Uuid =
            if (isGroup) {
                Uuid.random()
            } else {
                XorIdUtil.getNewXorId(domain.domainName, normalizedRecipients.first().domainName)
            }

        // The deterministic 1:1 uniqueId may already exist server-side from a prior
        // (possibly deleted or corrupted) conversation. Check for an existing file
        // without requiring a clean parse — some older files may be missing
        // participant data and would otherwise throw.
        val existingFile = getConversationHomebaseFile(newConversationId)
        if (existingFile != null) {
            val existingState: ConversationState? = try {
                mapper.mapToConversationUi(existingFile, null).conversationState
            } catch (e: Exception) {
                Logger.w(e) { "Existing conversation file $newConversationId failed to map — will overwrite" }
                null
            }

            Logger.d("createConversation: $newConversationId found existing file in local DB, state=$existingState")

            val needsRevive = existingState == null ||
                    existingState == ConversationState.Deleted ||
                    existingState == ConversationState.Invalid

            if (needsRevive) {
                Logger.d("createConversation: $newConversationId reviving (state=$existingState)")
                // Revive by clearing the Removed archival flag and pushing a fresh
                // participant list from the caller. updateConversationInternal uses
                // replaceEnqueue, so this supersedes any stale pending update.
                updateConversationInternal(
                    conversationId = newConversationId,
                    title = title ?: "",
                    participants = (normalizedRecipients + domain).distinct(),
                    archivalStatus = ArchivalStatus.None,
                    distribute = true,
                )
            }
            return CreateConversationResult(newConversationId, wasNewlyCreated = false)
        }

        Logger.d("createConversation: $newConversationId no local file found — creating new (recipients=$normalizedRecipients)")
        val allParticipants = (normalizedRecipients + domain).distinct()
        val success = writeConversationFile(
            conversationId = newConversationId,
            allParticipants = allParticipants,
            transitRecipients = normalizedRecipients,
            title = title,
            isGroup = isGroup,
            payloadBundle = payloadBundle
        )

        if (!success) {
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
                previousMessageUniqueId = newConversationId,
                statusMessage = StatusMessageData(
                    statusMessage = StatusMessage.GroupConversationStarted,
                    subject = null
                ),

            )
        }

        return CreateConversationResult(newConversationId, wasNewlyCreated = true)
    }

    /**
     * Result of [createConversation]. [wasNewlyCreated] is true when a fresh conversation file
     * was written; false when an existing file (active or revived) satisfied the request. Use
     * this to decide whether to post "conversation started" status messages — skip if false.
     */
    data class CreateConversationResult(
        val conversationId: Uuid,
        val wasNewlyCreated: Boolean
    )

    /**
     * Creates a conversation file locally and enqueues it for server upload.
     * Shared by [createConversation] and [ensureNoteToSelfExists].
     *
     * @return true if the file was successfully enqueued for upload
     */
    private suspend fun writeConversationFile(
        conversationId: Uuid,
        allParticipants: List<OdinId>,
        transitRecipients: List<OdinId>,
        title: String?,
        isGroup: Boolean,
        payloadBundle: PayloadBundle? = null
    ): Boolean {
        val keyHeader = KeyHeader.newRandom16()

        val content = ConversationAppDataJson(
            title = title ?: "",
            recipients = allParticipants,
            version = 1
        )

        val encryptedBundle = payloadBundleEncryptionService.encryptBundle(
            conversationId,
            payloadBundle,
            keyHeader.aesKey,
            scope
        )

        val metadata = UploadFileMetadata(
            allowDistribution = true,
            isEncrypted = true,
            appData = UploadAppFileMetaData(
                uniqueId = conversationId,
                tags = if (isGroup) listOf(ChatProtocol.ConversationGroupTag) else null,
                fileType = ChatProtocol.ConversationFileType,
                content = OdinSystemSerializer.serialize(content),
                previewThumbnail = encryptedBundle.previewThumbs.minByOrNull { it.pixelWidth }
            ),
        )

        val request = UploadFileRequest(
            driveId = chatDrive,
            keyHeader = keyHeader,
            metadata = metadata.encryptContent(keyHeader),
            transitOptions = TransitOptions(
                recipients = transitRecipients,
                useAppNotification = false
            ),
            payloads = encryptedBundle.payloads,
            thumbnails = encryptedBundle.thumbnails
        )

        optimisticWriter.writeNewFile(
            driveId = chatDrive,
            keyHeader = keyHeader,
            unecryptedMetadata = metadata,
            originalRecipientCount = transitRecipients.size,
            fileSystemType = FileSystemType.Standard,
        )
        conversationStream.loadConversation(conversationId)

        return outboxSync.tryEnqueue(request)
    }

    /**
     * Ensures a real note-to-self conversation file exists.
     * Uses [ChatProtocol.ConversationWithYourselfId] as the conversation ID,
     * then creates and pins the conversation if it doesn't already exist in the DB.
     */
    suspend fun ensureNoteToSelfExists() {
        val domain = credentialsManager.requireActiveDomain()
        val noteToSelfId = ChatProtocol.ConversationWithYourselfId

        val existing = getConversation(noteToSelfId)
        if (existing != null && existing.conversationState != ConversationState.Deleted) {
            return
        }

        if (existing != null) {
            // Conversation was soft-deleted — undelete it by clearing archivalStatus.
            // We can't create a new file because the server still has the old one.
            Logger.d("ConversationService: undeleting note-to-self conversation $noteToSelfId")
            updateConversationInternal(
                conversationId = noteToSelfId,
                title = "",
                participants = listOf(domain),
                archivalStatus = ArchivalStatus.None,
                distribute = false
            )
            pinConversation(noteToSelfId)
            return
        }

        // First-ever creation — no file exists locally or on the server
        Logger.d("ConversationService: creating note-to-self conversation $noteToSelfId")

        val success = writeConversationFile(
            conversationId = noteToSelfId,
            allParticipants = listOf(domain),
            transitRecipients = emptyList(),
            title = "",
            isGroup = false
        )

        if (success) {
            pinConversation(noteToSelfId)
        }
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

        if (conversation.isLegacyGroup) {
            throw IllegalStateException("Admin management is not available for legacy groups")
        }

        Logger.d { "updateAdmins: conversationId=$conversationId add=$add remove=$remove currentAdmins=${conversation.admins} participants=${conversation.participants}" }

        requireCallerIsGroupAdmin(conversation)

        val recipients = conversation.participants
        val admins = conversation.admins.toMutableSet()

        // additions must already be participants
        require(add.all { recipients.contains(it) }) {
            "Admins must be recipients"
        }

        admins.addAll(add)
        admins.removeAll(remove)

        if (admins.isEmpty()) {
            if (remove.contains(domain)) {
                throw IllegalStateException("Cannot remove the last admin. You must first add another to replace you.")
            } else {
                throw IllegalStateException("Conversation must have at least one admin")
            }
        }

        Logger.d { "updateAdmins: resolved admins=$admins recipients=${recipients.filterNot { it == domain }}" }

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

        if (conversation.isLegacyGroup) {
            throw IllegalStateException("Member management is not available for legacy groups")
        }

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

            trySendIntroductions((current - domain).toList(), "You share a group chat with $domain")
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

    /**
     * @param forceLocalOnly caller has determined the group is isolated (no reachable
     *  participant to receive distributed updates). Skips the admin-distribution protocol
     *  and just flips [ChatProtocol.ConversationLeftTag] locally, matching the legacy-group
     *  path. Also bypasses the sole-admin guard since there is no one to promote.
     */
    suspend fun leaveGroup(conversationId: Uuid, forceLocalOnly: Boolean = false) {
        val conversation = requireConversation(conversationId)
        val domain = credentialsManager.requireActiveDomain()
        val leaveFile = getConversationHomebaseFile(conversationId)
        Logger.d { "leaveGroup START: conversationId=$conversationId forceLocalOnly=$forceLocalOnly isEncrypted=${leaveFile?.fileMetadata?.isEncrypted} aesKey=${leaveFile?.keyHeader?.aesKey?.unsafeBytes?.toBase64() ?: "NO FILE"}" }

        if (!conversation.isGroupConversation) {
            throw IllegalStateException("Can only leave group conversations")
        }

        if (conversation.isLegacyGroup || forceLocalOnly) {
            // Legacy groups don't support the full leave protocol, and isolated groups
            // (no reachable participant) have nobody to distribute to — just mark locally.
            updateConversationTags(conversationId, dependencyUniqueId = conversationId) {
                it + ChatProtocol.ConversationLeftTag
            }
            return
        }

        if (conversation.admins.contains(domain) && (conversation.admins - domain).isEmpty()) {
            throw IllegalStateException("You are the only admin. Assign another admin before leaving.")
        }

        val remaining = conversation.participants.filterNot { it == domain }

        val messageId = Uuid.random()

        // 1. Notify the group first so they see the leave message
        try {
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

        // 2. Remove self from participants — chained after status message.
        // If this fails, roll back the optimistic status message AND its outbox entry
        // so a ghost "X left" message is not sent to the group while the leave didn't complete.
        try {
            updateConversationInternal(
                conversationId = conversationId,
                title = conversation.name,
                participants = remaining,
                dependencyUniqueId = messageId
            )
        } catch (t: Throwable) {
            optimisticWriter.removeOptimisticFile(chatDrive, messageId)
            dbm.outbox.deleteBy(chatDrive, messageId)
            throw t
        }

        // 3. Remove self from admins (separate file)
        if (conversation.admins.contains(domain)) {
            val updatedAdmins = conversation.admins - domain
            updateAdminFile(
                conversationId = conversationId,
                admins = updatedAdmins.toList(),
                recipients = remaining.filterNot { it == domain }
            )
        }

        // 4. Mark as left locally — preserves history and blocks sending.
        // Depend on conversationId so the tags update is only sent to the server AFTER the
        // participant-removal file update (UpdateFileByUniqueIdRequest, uniqueId=conversationId)
        // has been processed. Without this ordering, the server could briefly see the LeftTag
        // while domain is still in participants, causing a spurious RejoinPending state.
        updateConversationTags(conversationId, dependencyUniqueId = conversationId) {
            it + ChatProtocol.ConversationLeftTag
        }

//        optimisticWriter.stampConversationExitedAt(chatDrive, conversationId)
//            ?.let {
//                outboxSync.tryEnqueue(it)
//            }

//        val postLeaveFile = getConversationHomebaseFile(conversationId)
//        Logger.d { "leaveGroup END: conversationId=$conversationId aesKey=${postLeaveFile?.keyHeader?.aesKey?.unsafeBytes?.toBase64() ?: "NO FILE"}" }
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

        // 3. Keep the left tag locally — same ordering dependency as leaveGroup
        updateConversationTags(conversationId, dependencyUniqueId = conversationId) {
            it + ChatProtocol.ConversationLeftTag
        }

        optimisticWriter.stampConversationExitedAt(chatDrive, conversationId)
            ?.let { outboxSync.tryEnqueue(it) }
    }

    suspend fun updateConversationInternal(
        conversationId: Uuid,
        title: String?,
        participants: List<OdinId>,
        payloadBundle: PayloadBundle? = null,
        dependencyUniqueId: Uuid? = null,
        archivalStatus: ArchivalStatus? = null,
        distribute: Boolean = true,
        additionalDistributionRecipients: List<OdinId> = emptyList(),
        /** When true, ensures [ChatProtocol.ConversationGroupTag] is present in the file's
         *  tags (used by recovery/revive paths to heal legacy or untagged group files).
         *  null = preserve existing tags as-is. */
        isGroup: Boolean? = null
    ) {
        val credentials = credentialsManager.requireActiveCredentials()
        val domain = credentials.domain

        val conversationFile = getConversationHomebaseFile(conversationId)
            ?: error("No conversation found")

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
        val mergedTags = if (isGroup == true) {
            val existing = existingAppData.tags.orEmpty()
            if (existing.contains(ChatProtocol.ConversationGroupTag)) existing
            else existing + ChatProtocol.ConversationGroupTag
        } else {
            existingAppData.tags
        }
        val metadata =
            UploadFileMetadata(
                allowDistribution = distribute, // conversationFile.serverMetadata.allowDistribution,
                isEncrypted = true, // we always encrypt conversation files
                accessControlList = conversationFile.serverMetadata.accessControlList,
                referencedFile = conversationFile.fileMetadata.referencedFile,
                versionTag = conversationFile.fileMetadata.versionTag,
                appData =
                    UploadAppFileMetaData(
                        uniqueId = conversationId,
                        tags = mergedTags,
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
//        optimisticWriter.writeUpdate(
//            driveId = chatDrive,
//            keyHeader = keyHeader,
//            unecryptedMetadata = metadata
//        )

        val enqueued = outboxSync.replaceEnqueue(request, dependencyUniqueId = dependencyUniqueId)
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

    // region Recovery: unified conversation recovery
    /**
     * Single entry point for recovering a conversation that is missing or soft-deleted.
     * Determines 1:1 vs group via the XOR algorithm, reads existing participants
     * from the file when available, and either revives or creates the file using
     * the ORIGINAL [conversationId] (never recomputes it).
     */
    suspend fun recoverConversation(conversationId: Uuid, originalAuthor: OdinId) {
        val domain = credentialsManager.requireActiveDomain()
        val isNoteToSelf = conversationId == ChatProtocol.ConversationWithYourselfId

        if (isNoteToSelf) {
            Logger.i("ConversationService: recoverConversation($conversationId) — note-to-self, delegating to ensureNoteToSelfExists()")
            ensureNoteToSelfExists()
            return
        }

        val isOneToOne = conversationId == XorIdUtil.getNewXorId(
            domain.domainName, originalAuthor.domainName
        )

        Logger.i("ConversationService: recoverConversation($conversationId) author=${originalAuthor.domainName} isOneToOne=$isOneToOne")

        val existingFile = getConversationHomebaseFile(conversationId)

        if (existingFile != null) {
            val existingState: ConversationState? = try {
                mapper.mapToConversationUi(existingFile, null).conversationState
            } catch (e: Exception) {
                Logger.w(e) { "ConversationService: recoverConversation($conversationId) — existing file failed to map, will overwrite" }
                null
            }

            Logger.d("ConversationService: recoverConversation($conversationId) existingState=$existingState")

            val needsRevive = existingState == null
                || existingState == ConversationState.Deleted
                || existingState == ConversationState.Invalid

            if (!needsRevive) {
                Logger.d("ConversationService: recoverConversation($conversationId) — file exists and is $existingState, no action needed")
                return
            }

            // Read existing participants from file if possible (preserves group membership)
            val existingContent = existingFile.fileMetadata.appData.content?.let {
                try {
                    OdinSystemSerializer.deserialize<ConversationAppDataJson>(it)
                } catch (e: Exception) { null }
            }
            val participants = existingContent?.recipients
                ?.filterNotNull()?.distinct()
                ?.takeIf { it.isNotEmpty() }
                ?: listOf(originalAuthor, domain).distinct()

            Logger.i("ConversationService: recoverConversation($conversationId) — reviving (state=$existingState) participants=${participants.map { it.domainName }}")

            updateConversationInternal(
                conversationId = conversationId,
                title = existingContent?.title ?: "",
                participants = participants,
                archivalStatus = ArchivalStatus.None,
                distribute = false,
                isGroup = !isOneToOne
            )
            return
        }

        // No local file — create one with the ORIGINAL conversationId
        val allParticipants = listOf(originalAuthor, domain).distinct()
        Logger.i("ConversationService: recoverConversation($conversationId) — no local file, creating new (isGroup=${!isOneToOne}) participants=${allParticipants.map { it.domainName }}")

        writeConversationFile(
            conversationId = conversationId,
            allParticipants = allParticipants,
            transitRecipients = listOf(originalAuthor),
            title = "",
            isGroup = !isOneToOne,
            payloadBundle = null
        )
    }
    // endregion

    suspend fun deleteConversation(conversationId: Uuid) {
        val conversation = requireConversation(conversationId)

        if (conversation.isGroupConversation && !(
                    conversation.conversationState == ConversationState.Left ||
                            conversation.conversationState == ConversationState.RejoinPending ||
                            conversation.conversationState == ConversationState.Removed ||
                            conversation.conversationState == ConversationState.Archived
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

    suspend fun getConversationHomebaseFile(conversationId: Uuid): HomebaseFile? {
        val c = credentialsManager.requireActiveCredentials()
        return dbm.driveMainIndex.selectHomebaseFileByUnique(c.getIdentityId(), chatDrive, conversationId)
    }

    suspend fun getConversationAdminHomebaseFile(conversationId: Uuid): HomebaseFile? {
        val c = credentialsManager.requireActiveCredentials()
        val adminUniqueId = ChatProtocol.getAdminFileUniqueId(conversationId)
        return dbm.driveMainIndex.selectHomebaseFileByUnique(c.getIdentityId(), chatDrive, adminUniqueId)
    }

    /**
     * Manually re-distribute the group files (main conversation file + admin file) to all
     * current participants, for any of those files the caller authored. This is a recovery
     * aid for groups where one or more recipients did not receive (or lost) the original
     * group file. Per-file authorship is checked individually — the caller may have
     * authored only one of the two and the other will be left untouched.
     *
     * Each step is debug-logged so a failure can be diagnosed in homebase.log.
     */
    suspend fun healGroupDistribution(conversationId: Uuid): HealGroupResult {
        val conversation = requireConversation(conversationId)
        if (!conversation.isGroupConversation) {
            throw IllegalStateException("healGroupDistribution: not a group conversation $conversationId")
        }

        val domain = credentialsManager.requireActiveDomain()
        val recipients = conversation.participants.filterNot { it == domain }.distinct()

        Logger.i { "healGroupDistribution: START conversationId=$conversationId domain=$domain participants=${conversation.participants} recipients=$recipients admins=${conversation.admins}" }

        var mainHealed = false
        var adminHealed = false

        // Main conversation file
        val mainFile = getConversationHomebaseFile(conversationId)
        if (mainFile == null) {
            Logger.w { "healGroupDistribution: no local main conversation file for $conversationId — skipping main" }
        } else {
            val mainAuthor = mainFile.fileMetadata.originalAuthor ?: mainFile.fileMetadata.senderOdinId
            val mainPending = mainFile.fileMetadata.localAppData?.tags?.contains(ChatProtocol.isPendingSendTag) == true
            Logger.d { "healGroupDistribution: main file fileId=${mainFile.fileId} sender=${mainFile.fileMetadata.senderOdinId} originalAuthor=${mainFile.fileMetadata.originalAuthor} resolvedAuthor=$mainAuthor versionTag=${mainFile.fileMetadata.versionTag} isPending=$mainPending localTags=${mainFile.fileMetadata.localAppData?.tags}" }
            if (mainAuthor == domain) {
                try {
                    Logger.i { "healGroupDistribution: redistributing main conversation file $conversationId to recipients=$recipients" }
                    updateConversationInternal(
                        conversationId = conversationId,
                        title = conversation.name,
                        participants = conversation.participants,
                        distribute = true
                    )
                    mainHealed = true
                    Logger.i { "healGroupDistribution: main conversation file enqueued for redistribute $conversationId" }
                } catch (t: Throwable) {
                    Logger.e("healGroupDistribution: main conversation file redistribute FAILED for $conversationId", t)
                    throw t
                }
            } else {
                Logger.d { "healGroupDistribution: skipping main — caller is not the original author (author=$mainAuthor, caller=$domain)" }
            }
        }

        // Admin file
        val adminFile = getConversationAdminHomebaseFile(conversationId)
        if (adminFile == null) {
            Logger.w { "healGroupDistribution: no local admin file for $conversationId — skipping admin" }
        } else {
            val adminAuthor = adminFile.fileMetadata.originalAuthor ?: adminFile.fileMetadata.senderOdinId
            val adminPending = adminFile.fileMetadata.localAppData?.tags?.contains(ChatProtocol.isPendingSendTag) == true
            Logger.d { "healGroupDistribution: admin file fileId=${adminFile.fileId} sender=${adminFile.fileMetadata.senderOdinId} originalAuthor=${adminFile.fileMetadata.originalAuthor} resolvedAuthor=$adminAuthor versionTag=${adminFile.fileMetadata.versionTag} isPending=$adminPending localTags=${adminFile.fileMetadata.localAppData?.tags}" }
            if (adminAuthor == domain) {
                try {
                    Logger.i { "healGroupDistribution: redistributing admin file $conversationId admins=${conversation.admins} recipients=$recipients" }
                    updateAdminFile(
                        conversationId = conversationId,
                        admins = conversation.admins.toList(),
                        recipients = recipients
                    )
                    adminHealed = true
                    Logger.i { "healGroupDistribution: admin file enqueued for redistribute $conversationId" }
                } catch (t: Throwable) {
                    Logger.e("healGroupDistribution: admin file redistribute FAILED for $conversationId", t)
                    throw t
                }
            } else {
                Logger.d { "healGroupDistribution: skipping admin — caller is not the original author (author=$adminAuthor, caller=$domain)" }
            }
        }

        Logger.i { "healGroupDistribution: DONE conversationId=$conversationId mainHealed=$mainHealed adminHealed=$adminHealed" }
        return HealGroupResult(mainHealed = mainHealed, adminHealed = adminHealed)
    }

    data class HealGroupResult(val mainHealed: Boolean, val adminHealed: Boolean) {
        val didAnything: Boolean get() = mainHealed || adminHealed
    }

    /** Reads the admin list from the dedicated admin file, falling back to originalAuthor. */
    suspend fun getAdmins(conversationId: Uuid): Set<OdinId> {
        val fromFile = ConversationAdminInfo.queryFromDb(
            credentialsManager, dbm, chatDrive, conversationId
        )
        if (!fromFile.isNullOrEmpty()) return fromFile

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

        // Chain the admin file behind the main conversation file so it is not released
        // from the local outbox until the conversation file's upload to our own server
        // has been acknowledged. Without this, the conversation file and the admin file
        // race in parallel through Transit, and recipients can see the admin file land
        // before the conversation file — which used to push them into the orphan-recovery
        // path and create stale placeholders. (See Shelly's Apr 19 log on conversation
        // 0e684619 for the original failure mode.) The dependency does NOT enforce
        // ordering across the recipient's network — Transit distribution is still
        // parallel — but it removes the local-outbox half of the race, which is the
        // half we control.
        val enqueued = outboxSync.tryEnqueue(request, dependencyUniqueId = conversationId)
        if (!enqueued) {
            Logger.w { "uploadAdminFile: outbox enqueue returned false for $conversationId — likely UNIQUE conflict on adminUniqueId=$adminUniqueId; the file was NOT scheduled for upload" }
        } else {
            Logger.d { "uploadAdminFile: enqueued upload for adminUniqueId=$adminUniqueId dependencyUniqueId=$conversationId" }
        }
    }

    /** Updates an existing admin file (or creates one if it doesn't exist yet). */
    private suspend fun updateAdminFile(
        conversationId: Uuid,
        admins: List<OdinId>,
        recipients: List<OdinId>
    ) {
        val existingFile = getConversationAdminHomebaseFile(conversationId)
        Logger.d { "updateAdminFile: conversationId=$conversationId existingFile=${existingFile?.fileMetadata?.appData?.uniqueId} versionTag=${existingFile?.fileMetadata?.versionTag} admins=$admins recipients=$recipients" }

        if (existingFile == null) {
            Logger.d { "updateAdminFile: no existing file, uploading new admin file" }
            uploadAdminFile(conversationId, admins, recipients)
            return
        }

        // If the file was never confirmed by the server (still pending), the local optimistic
        // record is stale. Remove it and re-upload so the server sees a fresh create.
        val isPending = existingFile.fileMetadata.localAppData?.tags
            ?.contains(ChatProtocol.isPendingSendTag) == true
        Logger.d { "updateAdminFile: isPending=$isPending localTags=${existingFile.fileMetadata.localAppData?.tags}" }
        if (isPending) {
            Logger.d { "updateAdminFile: stale optimistic file detected, removing and re-uploading" }
            optimisticWriter.removeOptimisticFile(chatDrive, ChatProtocol.getAdminFileUniqueId(conversationId))
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

        // Same chaining as uploadAdminFile — the admin file's update should not race
        // ahead of the conversation file in the local outbox. Even when the admin file
        // is updated standalone (admin add/remove), chaining behind the conversation
        // file is benign: the conversation file's outbox row, if any, drains first,
        // otherwise the dependency resolves immediately.
        val enqueued = outboxSync.tryEnqueue(request, dependencyUniqueId = conversationId)
        if (!enqueued) {
            Logger.w { "updateAdminFile: outbox enqueue returned false for $conversationId — likely UNIQUE conflict on adminUniqueId=$adminUniqueId (something already pending); the update was NOT scheduled" }
        } else {
            Logger.d { "updateAdminFile: enqueued update for adminUniqueId=$adminUniqueId versionTag=${existingFile.fileMetadata.versionTag} dependencyUniqueId=$conversationId" }
        }
    }

    override suspend fun updateLocalLastReadTime(conversationId: Uuid, newLastReadTime: UnixTimeUtc) {

        Logger.d(tag = "MarkAsRead") {
            "ConversationService.updateLocalLastReadTime: enter convo=$conversationId newMs=${newLastReadTime.milliseconds}"
        }

        val convo = requireConversation(conversationId)
        val currentMs = UnixTimeUtc(convo.lastRead).milliseconds
        val willAdvance = newLastReadTime > UnixTimeUtc(convo.lastRead)
        Logger.d(tag = "MarkAsRead") {
            "ConversationService.updateLocalLastReadTime: convo=$conversationId currentMs=$currentMs " +
                    "newMs=${newLastReadTime.milliseconds} willAdvance=$willAdvance"
        }

        if (!willAdvance) return

        val request = optimisticWriter.stampConversationLastReadTime(
            driveId = chatDrive,
            conversationId = conversationId,
            newLastReadTime = newLastReadTime,
        )
        if (request == null) {
            Logger.w(tag = "MarkAsRead") {
                "ConversationService.updateLocalLastReadTime: stampConversationLastReadTime returned null — conversation file missing or optimistic write failed; convo=$conversationId"
            }
            return
        }

        outboxSync.tryEnqueue(request)
        dbm.chatReadCount.upsertLastReadTime(conversationId, newLastReadTime)
    }

}
