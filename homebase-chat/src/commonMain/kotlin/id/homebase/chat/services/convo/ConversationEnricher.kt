package id.homebase.chat.services.convo

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.OwnerSession
import id.homebase.api.client.auth.initials
import id.homebase.api.client.connections.ConnectionStatus
import id.homebase.api.client.connections.RedactedIdentityConnectionRegistration
import id.homebase.api.common.OdinId
import id.homebase.chat.data.*
import id.homebase.chat.services.convo.contact.ContactConnectionState
import id.homebase.core.avatars.ConversationAvatarModel

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
 * Patches the note-to-self conversation's [ConversationAvatarModel] with the
 * owner's initials.
 *
 * imageData is deliberately left null so [id.homebase.core.avatars.OwnerAvatar]
 * falls through to [id.homebase.core.avatars.PublicAvatar] →
 * `https://{odinId}/pub/image`, the full-res source the chat header already
 * renders successfully. Attaching the tiny base64
 * [OwnerSession.profileImagePreviewThumbnail] here instead blew a 20px preview up
 * into the avatar circle — blurry (#956). Initials remain the fallback until the
 * photo loads (or when the owner has none).
 */
internal fun ConversationUiModel.withOwnerProfileAvatar(
    ownerSession: OwnerSession,
): ConversationUiModel {
    if (avatarModel.type != ConversationAvatarModel.Type.Owner) return this
    return copy(avatarModel = avatarModel.copy(initials = ownerSession.initials()))

    // TODO(#956, option 2): once we fetch the owner's real profile image data
    // (real profileImageFileId/profileImageFileKey with an upgrade path), attach
    // it here so the preview is only a placeholder that upgrades to full-res —
    // instead of relying solely on /pub/image. The synthetic preview-only shape
    // that produced the 20px blur is kept below for reference; it must NOT ship as-is.
    // Restore imports KeyHeader, EmbeddedThumb, HomebaseImageData, kotlin.uuid.Uuid when reviving.
    //
    // val ownerImageData = HomebaseImageData(
    //     driveId = Uuid.NIL,
    //     fileId = Uuid.NIL,
    //     payloadKey = "",
    //     isEncrypted = false,
    //     previewThumbnail = EmbeddedThumb(
    //         pixelWidth = 0,
    //         pixelHeight = 0,
    //         contentType = "image/webp",
    //         content = ownerSession.profileImagePreviewThumbnail.orEmpty(),
    //     ),
    //     lastModified = ownerSession.profileImageLastModified,
    //     keyHeader = KeyHeader.newRandom16(),
    // )
    // return copy(avatarModel = avatarModel.copy(
    //     initials = ownerSession.initials(),
    //     imageData = ownerImageData,
    // ))
}
