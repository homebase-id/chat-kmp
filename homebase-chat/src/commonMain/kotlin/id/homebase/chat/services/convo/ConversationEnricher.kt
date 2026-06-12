package id.homebase.chat.services.convo

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.OwnerSession
import id.homebase.api.client.connections.ConnectionStatus
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.client.connections.RedactedIdentityConnectionRegistration
import id.homebase.api.common.OdinId
import id.homebase.chat.data.*
import id.homebase.chat.services.convo.contact.ContactConnectionState
import id.homebase.core.avatars.ConversationAvatarModel
import id.homebase.core.image.HomebaseImageData
import kotlin.uuid.Uuid

class ConversationEnricher {

    /**
     * Enriches a basic [ConversationUiModel] with resolved contact/
     * connection data for display.
     *
     * Empty `contactMap` / `connectionMap` inputs are tolerated —
     * display names fall back to `odinId.domainName` at the UI layer.
     */
    fun enrich(
        convo: ConversationUiModel,
        contactMap: Map<OdinId, ContactUiModel>,
        ownerSession: OwnerSession,
        connectionMap: Map<OdinId, RedactedIdentityConnectionRegistration> = emptyMap(),
        incomingRequestSenders: Set<OdinId> = emptySet(),
        outgoingRequestRecipients: Set<OdinId> = emptySet(),
        connectionStatusKnown: Boolean = true,
    ): EnrichedConversationUiModel {

        val currentUser = ownerSession.odinId

        if (convo.isWithSelf) {
            return EnrichedConversationUiModel(
                conversation = convo.withOwnerProfileAvatar(ownerSession),
                participants = emptyList(),
                missingConnections = emptyList()
            )
        }

        val otherParticipants = convo.participants.filter { it != currentUser }

        val participants = otherParticipants.mapNotNull { odinId ->
            contactMap[odinId]
        }

        val missingConnections =
            if (otherParticipants.size > 1) {
                otherParticipants.filter { odinId ->
                    val contact = contactMap[odinId]

                    contact == null ||
                            contact.connectionState != ContactConnectionState.Connected
                }
            } else {
                emptyList()
            }

        val oneOnOneConnectionStatus = if (otherParticipants.size == 1) {
            val other = otherParticipants.first()
            val connection = connectionMap[other]
            val connected = connection?.status == ConnectionStatus.Connected

            val status = when {
                connected -> OneOnOneConnectionStatus.Connected(other)
                incomingRequestSenders.contains(other) ->
                    OneOnOneConnectionStatus.IncomingRequestPending(other)
                outgoingRequestRecipients.contains(other) ->
                    OneOnOneConnectionStatus.OutgoingRequestPending(other)
                !connectionStatusKnown -> OneOnOneConnectionStatus.Unknown(other)
                else -> OneOnOneConnectionStatus.NotConnected(other)
            }
            if (status !is OneOnOneConnectionStatus.Connected) {
                Logger.d(tag = "ConversationEnricher") {
                    "1:1 convo=${convo.id} other=$other " +
                            "connectionStatus=${connection?.status} " +
                            "inIncoming=${incomingRequestSenders.contains(other)} " +
                            "inOutgoing=${outgoingRequestRecipients.contains(other)} " +
                            "incomingSet=$incomingRequestSenders " +
                            "outgoingSet=$outgoingRequestRecipients " +
                            "-> $status"
                }
            }
            status
        } else null

        return EnrichedConversationUiModel(
            conversation = convo,
            participants = participants,
            missingConnections = missingConnections,
            oneOnOneConnectionStatus = oneOnOneConnectionStatus,
        )
    }
}

/**
 * Patches the note-to-self conversation's [ConversationAvatarModel] so the
 * Owner avatar renders the user's own profile photo.
 *
 * [ConversationMapper.buildConversationAvatarModel] builds the self avatar with
 * `imageData = null`, which makes [id.homebase.core.avatars.OwnerAvatar] fall
 * through to [id.homebase.core.avatars.PublicAvatar] — and the owner's own
 * `https://{odinId}/pub/image` is empty, so the avatar shows blank. The owner's
 * profile-image bytes never live on the chat drive, but [OwnerSession] already
 * carries the base64 [OwnerSession.profileImagePreviewThumbnail] that renders
 * without any decryption keys. We attach it as the avatar's
 * [HomebaseImageData.previewThumbnail] so the photo shows immediately.
 *
 * The synthetic image data is deliberately preview-only: [HomebaseImageData.fileId]
 * is [Uuid.NIL] so [OwnerAvatar] paints the embedded preview directly instead of
 * attempting a server fetch on the chat drive (which has no such file). When the
 * owner has no profile photo (preview is null), the avatar is left untouched so
 * the existing [PublicAvatar] fallback still applies.
 */
internal fun ConversationUiModel.withOwnerProfileAvatar(
    ownerSession: OwnerSession,
): ConversationUiModel {
    val previewContent = ownerSession.profileImagePreviewThumbnail?.takeIf { it.isNotBlank() }
        ?: return this

    if (avatarModel.type != ConversationAvatarModel.Type.Owner) return this

    val ownerImageData = HomebaseImageData(
        driveId = Uuid.NIL,
        fileId = Uuid.NIL,
        payloadKey = "",
        isEncrypted = false,
        previewThumbnail = EmbeddedThumb(
            pixelWidth = 0,
            pixelHeight = 0,
            contentType = "image/webp",
            content = previewContent,
        ),
        lastModified = ownerSession.profileImageLastModified,
        keyHeader = KeyHeader.newRandom16(),
    )

    return copy(avatarModel = avatarModel.copy(imageData = ownerImageData))
}
