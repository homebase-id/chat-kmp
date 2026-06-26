package id.homebase.chat.services

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.reactions.ReactionContent
import id.homebase.api.common.OdinId
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.content.MessageContent
import id.homebase.chat.services.content.MessageContentParser
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
import id.homebase.resources.system_group_heal_local_cleanup_admin
import id.homebase.resources.system_group_heal_local_cleanup_both
import id.homebase.resources.system_group_heal_local_cleanup_main
import id.homebase.resources.system_group_heal_requested
import id.homebase.resources.system_group_heal_requested_you
import id.homebase.resources.system_emergency_contact_designated
import id.homebase.resources.system_emergency_contact_designated_you
import id.homebase.resources.system_emergency_contact_designated_you_unknown
import id.homebase.resources.system_emergency_contact_revoked
import id.homebase.resources.system_emergency_contact_revoked_you
import id.homebase.resources.system_emergency_contact_revoked_you_unknown
import id.homebase.resources.chat_poll_ended_other
import id.homebase.resources.chat_poll_ended_self
import kotlinx.collections.immutable.toPersistentList
import kotlinx.serialization.json.JsonPrimitive
import kotlin.uuid.Uuid

/**
 * Decoder pipeline that turns a `HomebaseFile` (encrypted, fresh from the
 * drive) into a `MessageUiModel?`. Extracted from `ChatMessageStream`'s
 * companion object so the eventual paging PR doesn't bloat that file —
 * stream/lifecycle concerns stay in `ChatMessageStream`, while everything
 * about *how* a file becomes a UI message lives here.
 *
 * The decoder is intentionally stateless and free of injected services: it
 * takes a [CredentialsManager] for the active-domain check and a
 * [displayNameResolver] lambda so the caller can hand it the contact-service
 * lookup it needs (the stream owns the contact service; the mapper does not).
 */

internal fun getDeliveryStatus(header: HomebaseFile): ChatDeliveryStatus {

    if (header.fileMetadata.appData.groupId == ChatProtocol.ConversationWithYourselfId) {
        return ChatDeliveryStatus.Read
    }

    val count = header.serverMetadata.originalRecipientCount
    if (count == 0) {
        return ChatDeliveryStatus.Read
    }
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
    val localTags = metadata.localAppData?.tags
    // A permanently-dropped send (isFailedSendTag) wins over pending: the bubble must
    // show the Failed icon, not keep spinning. See ChatProtocol.isFailedSendTag.
    val isFailedSend = localTags?.contains(ChatProtocol.isFailedSendTag) ?: false
    val isPendingSend =
        (localTags?.contains(ChatProtocol.isPendingSendTag) ?: false) && !isFailedSend

    val localReadTimestamp = metadata.localAppData?.readTime
    // localReactions on the wire are JSON-encoded ReactionContent objects
    // (`{"emoji":"X"}`). Decode to bare emoji here so the rest of the UI
    // can compare against reactionPreview entries by simple string match.
    val ownReactions = metadata.localAppData?.localReactions
        .orEmpty()
        .mapNotNull { raw ->
            runCatching { OdinSystemSerializer.deserialize<ReactionContent>(raw).emoji }.getOrNull()
        }
        .toPersistentList()

    try {
        require(appData.fileType == ChatProtocol.MessageFileType)

        val versionTag = header.fileMetadata.versionTag ?: Uuid.NIL
        val content = appData.content
        val isDeleted = header.isSoftDeleted()

        if (isDeleted) {
            // A consumed status (system) message leaves no trace: unlike a deleted user
            // message — where the "This message was deleted" tombstone is the point — a status
            // message such as an emergency-contact designation is soft-deleted by the receiver
            // purely to neutralise re-delivery (EmergencyContactReceiveService.consume). The user
            // never authored or saw it, so render nothing rather than a "Deleted File" tombstone.
            // appData survives the local soft-delete (the branch below reads groupId/uniqueId/
            // userDate from it), so isStatusMessage (appData.dataType) is reliable here.
            if (isStatusMessage) return null

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
                sender = metadata.senderOdinId,
                displayName = metadata.originalAuthor?.domainName ?: "",
                localReadTimestamp = localReadTimestamp,
                ownReactions = ownReactions,
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

        val delivery =
            if (isFailedSend) ChatDeliveryStatus.Failed.value else getDeliveryStatus(header).value

        val messageAppData: MessageAppData
        // Try the typed-content parser first. Returns non-null when
        // appData.dataType matches a known kind (event today; poll,
        // doodle, dice when those land) AND the content JSON parses.
        // New kinds plug in via MessageContentParser — zero mapper edits.
        val messageContent: MessageContent? =
            if (isStatusMessage) null
            else MessageContentParser.parse(appData.dataType, content)

        when {
            isStatusMessage -> {
                val status = OdinSystemSerializer.deserialize<StatusMessageData>(content)
                // GroupHealLocalCleanup is a private "I cleaned up MY broken copy"
                // marker — only meaningful to the receiver that did the cleanup. A
                // fixed sender writes it local-only, but a peer still on an older
                // build fans it out over the wire. Drop any peer-authored copy so it
                // never lands in our chat as if our own copy had auto-healed.
                if (status.statusMessage == StatusMessage.GroupHealLocalCleanup &&
                    metadata.originalAuthor != domain
                ) {
                    return null
                }
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
            }
            messageContent != null -> {
                // Display label feeds notifications, conversation-list previews,
                // search. Each kind contributes its own (event title, poll
                // question, etc.) via MessageContent.displayLabel.
                messageAppData = MessageAppData(
                    message = JsonPrimitive(messageContent.displayLabel),
                    deliveryStatus = delivery,
                    isEdited = false
                )
            }
            else -> {
                // Plain text + media path. Also catches malformed typed
                // content (parser returned null) — falls back rather than
                // dropping the message.
                val source = OdinSystemSerializer.deserialize<MessageAppData>(content)
                messageAppData = source.copy(
                    deliveryStatus = delivery
                )
            }
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
                        // Debug-level: legacy messages from older web/RN clients
                        // that never captured userDate. Fallback to the
                        // server-stamped authorSpecificDate is correct; no
                        // action is required. Logged because when debugging
                        // display-time issues it's useful to identify which
                        // messages took the fallback path. Was Warn; demoted
                        // to Debug because it fires ~4k times per login.
                        Logger.d {
                            "Message (uid: ${appData.uniqueId}) with no version and not edited has null userDate. " +
                                "using authorSpecificDate. See file: https://${domain}/owner/drives/9ff813aff2d61e2f9b9db189e72d1a11_66ea8355ae4155c39b5a719166b510e3/${appData.uniqueId}"
                        }
                        authorSpecificDate
                    } else
                        UnixTimeUtc(appData.userDate!!)
                }

            } else {
                if (appData.userDate == null) {
                    Logger.d { "Message (uid: ${appData.uniqueId}) with version ${messageAppData.version} has null userDate. using authorSpecificDate" }
                    Logger.d { "See File here: https://${domain}/owner/drives/9ff813aff2d61e2f9b9db189e72d1a11_66ea8355ae4155c39b5a719166b510e3/${appData.uniqueId}" }
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
            sender = metadata.senderOdinId,
            displayName = displayName,
            isEdited = messageAppData.isEdited,
            localReadTimestamp = localReadTimestamp,
            ownReactions = ownReactions,
            messageAppData = messageAppData,
            reactionPreview = metadata.reactionPreview,
            previewThumbnail = metadata.appData.previewThumbnail,
            payloads = metadata.payloads?.toPersistentList(),
            keyHeader = header.keyHeader,
            versionTag = versionTag,
            isPendingSend = isPendingSend,
            isFailedSend = isFailedSend,
            isStatusMessage = isStatusMessage,
            messageContent = messageContent,
            hasMore = hasMore
        )

    } catch (t: Throwable) {

        Logger.e(t) {
            "failed while mapping a message with uniqueId ${appData.uniqueId} and fileId ${header.fileId} " +
                    "created=${metadata.created} updated=${metadata.updated} transitCreated=${metadata.transitCreated} " +
                    "originalAuthor=${metadata.originalAuthor?.domainName} senderOdinId=${metadata.senderOdinId?.domainName} " +
                    "versionTag=${metadata.versionTag} globalTransitId=${metadata.globalTransitId} " +
                    "appData=[${appData}]. Message: ${t.message}"
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
                sender = metadata.senderOdinId,
                displayName = metadata.originalAuthor?.domainName ?: "",
                messageAppData = MessageAppData(),
                localReadTimestamp = localReadTimestamp,
                ownReactions = ownReactions,
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

internal suspend fun renderStatusMessage(
    author: OdinId?,
    status: StatusMessageData,
    currentUser: OdinId? = null
): String {
    val authorIsYou = currentUser != null && author == currentUser
    val subjectIsYou =
        currentUser != null && status.subject != null && status.subject == currentUser
    val name = author?.domainName ?: TranslationUtil.getString(MR.string.someone)
    val subject = status.subject?.domainName

    return when (status.statusMessage) {
        StatusMessage.ConversationTitleUpdated ->
            if (authorIsYou) TranslationUtil.getString(MR.string.system_conversation_title_updated_you)
            else TranslationUtil.getString(
                MR.string.system_conversation_title_updated,
                name
            )

        StatusMessage.ConversationPhotoUpdated ->
            if (authorIsYou) TranslationUtil.getString(MR.string.system_conversation_photo_updated_you)
            else TranslationUtil.getString(
                MR.string.system_conversation_photo_updated,
                name
            )

        StatusMessage.ConversationMemberAdded ->
            when {
                authorIsYou && subject != null ->
                    TranslationUtil.getString(
                        MR.string.system_conversation_member_you_added,
                        subject
                    )

                subjectIsYou ->
                    TranslationUtil.getString(
                        MR.string.system_conversation_member_added_you,
                        name
                    )

                subject != null ->
                    TranslationUtil.getString(
                        MR.string.system_conversation_member_name_added,
                        name,
                        subject
                    )

                else ->
                    TranslationUtil.getString(
                        MR.string.system_conversation_member_added,
                        name
                    )
            }

        StatusMessage.ConversationMemberRemoved ->
            when {
                authorIsYou && subject != null ->
                    TranslationUtil.getString(
                        MR.string.system_conversation_member_you_removed,
                        subject
                    )

                subjectIsYou ->
                    TranslationUtil.getString(
                        MR.string.system_conversation_member_removed_you,
                        name
                    )

                subject != null ->
                    TranslationUtil.getString(
                        MR.string.system_conversation_member_name_removed,
                        name,
                        subject
                    )

                else ->
                    TranslationUtil.getString(
                        MR.string.system_conversation_member_removed,
                        name
                    )
            }

        StatusMessage.ConversationAdminAdded ->
            when {
                authorIsYou && subject != null ->
                    TranslationUtil.getString(
                        MR.string.system_conversation_admin_you_added,
                        subject
                    )

                subjectIsYou ->
                    TranslationUtil.getString(
                        MR.string.system_conversation_admin_added_you,
                        name
                    )

                subject != null ->
                    TranslationUtil.getString(
                        MR.string.system_conversation_admin_name_added,
                        name,
                        subject
                    )

                else ->
                    TranslationUtil.getString(
                        MR.string.system_conversation_admin_added,
                        name
                    )
            }

        StatusMessage.ConversationAdminRemoved ->
            when {
                authorIsYou && subject != null ->
                    TranslationUtil.getString(
                        MR.string.system_conversation_admin_you_removed,
                        subject
                    )

                subjectIsYou ->
                    TranslationUtil.getString(
                        MR.string.system_conversation_admin_removed_you,
                        name
                    )

                subject != null ->
                    TranslationUtil.getString(
                        MR.string.system_conversation_admin_name_removed,
                        name,
                        subject
                    )

                else ->
                    TranslationUtil.getString(
                        MR.string.system_conversation_admin_removed,
                        name
                    )
            }

        StatusMessage.GroupConversationStarted,
        StatusMessage.ConversationStarted ->
            if (authorIsYou) TranslationUtil.getString(MR.string.system_group_conversation_started_you)
            else TranslationUtil.getString(
                MR.string.system_group_conversation_started,
                name
            )

        StatusMessage.ConversationMemberLeft ->
            if (authorIsYou) TranslationUtil.getString(MR.string.system_group_conversation_member_left_you)
            else TranslationUtil.getString(
                MR.string.system_group_conversation_member_left,
                name
            )

        StatusMessage.ConversationMemberDeclinedRejoin ->
            if (authorIsYou) TranslationUtil.getString(MR.string.system_group_conversation_member_declined_rejoin_you)
            else TranslationUtil.getString(
                MR.string.system_group_conversation_member_declined_rejoin,
                name
            )

        StatusMessage.GroupHealRequested ->
            if (authorIsYou) TranslationUtil.getString(MR.string.system_group_heal_requested_you)
            else TranslationUtil.getString(
                MR.string.system_group_heal_requested,
                name
            )

        StatusMessage.GroupHealLocalCleanup -> {
            val cleanup = status.groupHealCleanup
            val main = cleanup?.cleanedUpMain == true
            val admin = cleanup?.cleanedUpAdmin == true
            when {
                main && admin -> TranslationUtil.getString(MR.string.system_group_heal_local_cleanup_both)
                main -> TranslationUtil.getString(MR.string.system_group_heal_local_cleanup_main)
                admin -> TranslationUtil.getString(MR.string.system_group_heal_local_cleanup_admin)
                // Defensive: cleanup status with neither flag set —
                // shouldn't happen in practice; render the "both"
                // string so we don't render an empty line.
                else -> TranslationUtil.getString(MR.string.system_group_heal_local_cleanup_both)
            }
        }

        StatusMessage.PollEnded -> {
            val q = status.pollQuestion ?: ""
            if (authorIsYou) TranslationUtil.getString(MR.string.chat_poll_ended_self, q)
            else TranslationUtil.getString(MR.string.chat_poll_ended_other, name, q)
        }

        StatusMessage.EmergencyContactDesignated ->
            when {
                authorIsYou && subject != null ->
                    TranslationUtil.getString(MR.string.system_emergency_contact_designated_you, subject)
                authorIsYou ->
                    TranslationUtil.getString(MR.string.system_emergency_contact_designated_you_unknown)
                else ->
                    TranslationUtil.getString(MR.string.system_emergency_contact_designated, name)
            }

        StatusMessage.EmergencyContactRevoked ->
            when {
                authorIsYou && subject != null ->
                    TranslationUtil.getString(MR.string.system_emergency_contact_revoked_you, subject)
                authorIsYou ->
                    TranslationUtil.getString(MR.string.system_emergency_contact_revoked_you_unknown)
                else ->
                    TranslationUtil.getString(MR.string.system_emergency_contact_revoked, name)
            }
    }
}
