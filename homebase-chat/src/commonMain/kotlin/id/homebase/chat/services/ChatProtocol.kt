package id.homebase.chat.services

import id.homebase.api.crypto.ByteArrayUtil
import kotlin.uuid.Uuid

object ChatProtocol {

    val ChatAppId = Uuid.parse("2d781401-3804-4b57-b4aa-d8e4e2ef39f4")

    const val ContactFileType = 100

    const val MessageVersionNumberOne = 1

    val ConversationWithYourselfId: Uuid = Uuid.parse("e4ef2382-ab3c-405d-a8b5-ad3e09e980dd")

    const val ConversationPayloadKey = "convo_pk" // TODO: Explain what this represents
    const val ConversationImageKey = "convo_img"

    const val CHAT_CONVERSATION_LOCAL_METADATA_FILE_TYPE = 8889;

    const val ConversationFileType = 8888
    const val ConversationAdminFileType = 8890
    const val ChatStatusMessageDataType = 202

    /**
     * Rich-content message kinds that ride on the message header (no payload fetch
     * on scroll). The full JSON object lives in `appData.content`; receivers branch
     * off `appData.dataType` to choose a renderer. Polls and doodles will follow
     * the same shape — pick the next free integer when adding one.
     */
    const val ChatEventMessageDataType = 210

    /**
     * Header-level kind tag for messages whose primary attachment is a Location
     * preview ([PAYLOAD_KEY_LOCATION] payload). The receiver still dispatches off
     * the payload key (the descriptor + map image live there), so this is purely a
     * server-queryable kind axis: lets `QueryBatch` filter by location messages,
     * lets future "all locations shared" features land without scanning every
     * payload metadata blob client-side.
     *
     * Pre-existing Location messages on the wire have `dataType = 0` and remain
     * indistinguishable at the header level until a defragger backfill pass tags
     * them. New sends are stamped from now on.
     */
    const val ChatLocationMessageDataType = 211

    const val MessageFileType = 7878

    /** Derives a deterministic uniqueId for the admin file from a conversationId. */
    suspend fun getAdminFileUniqueId(conversationId: Uuid): Uuid {
        // never change this; period - full stop
        return ByteArrayUtil.reduceSha256Hash("admin$conversationId")
    }


    /** Indicates a file was optimistically written and not coming from the server */
    val isPendingSendTag = Uuid.parse("6e87beb3-412a-4a8c-aaec-b21a7ec620a7")

    /** Local metadata tag: conversation has been archived by the user */
    val ConversationArchivedTag = Uuid.parse("a569e5cd-6fd8-41e0-8ccc-b6b31dac6b73")

    /** Local metadata tag: user has left this group conversation */
    val ConversationLeftTag = Uuid.parse("f3a7c2e1-9b4d-4e8f-a1c5-7d2e3f4b5c6d")

    /** Local metadata tag: conversation has been pinned by the user */
    val ConversationPinnedTag = Uuid.parse("3f7e4c1d-5a2b-4f89-b3e7-9c1d2e3f4a5b")

    /** Local metadata tag: this GroupHealRequested status message has already been
     *  applied on this recipient's drive. Idempotency gate for
     *  `ConversationService.handleIncomingHealRequest` — without it, a heal status
     *  message that is reprocessed (cursor reset, full re-sync, sibling device)
     *  could re-classify a now-canonical local file as broken and hard-delete it
     *  against a stale canonical-versionTag snapshot. The marker rides on the
     *  message file's localAppData and syncs across the recipient's devices. */
    val HealAppliedTag = Uuid.parse("c5b2e1d4-8a7f-4d6e-a3c2-1b9e8f7d6c5a")

    /** Server-side appData tag: conversation was originally created as a group (never removed) */
    val ConversationGroupTag = Uuid.parse("b4e3c2d1-7f6a-4e8b-9c5d-1a2b3c4d5e6f")

    const val ARCHIVAL_STATUS_DELETED = 2

    const val DEFAULT_PAYLOAD_DESCRIPTOR_KEY = "pld_desc"

    const val PAYLOAD_KEY_MESSAGE_WEB = "chat_web"
    const val PAYLOAD_KEY_LINKS = "chat_links"
    // Server-side constraint: payload keys must match `^[a-z0-9_]{8,10}$`. "chat_location"
    // (13 chars) was rejected with 400 "Missing payload key"; "chat_loc" (8 chars) fits.
    const val PAYLOAD_KEY_LOCATION = "chat_loc"

    const val DefaultPayloadKey = "dflt_key"
    const val MaxDescriptorContentLength = 1024
    const val MaxHeaderContentBytes = 7000

}
