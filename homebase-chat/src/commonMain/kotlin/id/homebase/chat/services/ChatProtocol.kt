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

    /** Ambush embargo on [StatusMessage.EmergencyLocateRequested]: how long the recipient's
     *  client hides the request notice (see StatusMessageData.emergencyLocateEmbargoUntilMs). */
    const val EMERGENCY_LOCATE_AMBUSH_DELAY_MS = 24L * 60 * 60 * 1000

    /** Cap on the requester's free-text justification riding the status-message header. */
    const val EMERGENCY_LOCATE_EXPLANATION_MAX_CODEPOINTS = 280

    /**
     * Rich-content message kinds that ride on the message header (no payload fetch
     * on scroll). The full JSON object lives in `appData.content`; receivers branch
     * off `appData.dataType` to choose a renderer. Poll is 214; pick the next free
     * integer when adding one.
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

    /**
     * Dice-roll message kind. Like [ChatEventMessageDataType], the full descriptor
     * (face count, per-die results, source) lives in `appData.content` — no
     * payloads, no fetch on scroll. See [id.homebase.chat.dice.DiceRollDescriptor].
     */
    const val ChatDiceRollMessageDataType = 212

    /**
     * Groodle (group-scheduling poll) message kind. Like [ChatEventMessageDataType],
     * the full descriptor (title, candidate time slots, deadline) lives in
     * `appData.content` — no payloads, no fetch on scroll. Votes ride as ordinary
     * chat reactions encoded by [id.homebase.chat.groodle.GroodleVote]. See
     * [id.homebase.chat.groodle.GroodleDescriptor].
     */
    const val ChatGroodleMessageDataType = 213

    /**
     * Poll message kind — question + options; votes are chat reactions encoded by
     * [id.homebase.chat.poll.PollVote] (e.g. `_p0`). The full descriptor lives in
     * `appData.content` — no payloads, no fetch on scroll. See
     * [id.homebase.chat.poll.PollDescriptor] and ADDING_TYPED_MESSAGE_KIND.md.
     */
    const val ChatPollMessageDataType = 214

    const val MessageFileType = 7878

    /** Derives a deterministic uniqueId for the admin file from a conversationId. */
    suspend fun getAdminFileUniqueId(conversationId: Uuid): Uuid {
        // never change this; period - full stop
        return ByteArrayUtil.reduceSha256Hash("admin$conversationId")
    }


    /** Indicates a file was optimistically written and not coming from the server */
    val isPendingSendTag = Uuid.parse("6e87beb3-412a-4a8c-aaec-b21a7ec620a7")

    /**
     * Local metadata tag: the optimistic send was permanently dropped by the outbox
     * (permanent failure or retries exhausted). Set by [ChatMessageStream] on
     * [id.homebase.api.client.eventbus.BackendEvent.OutboxEvent.OutboxItemDropped],
     * replacing [isPendingSendTag]. The mapper reads it to show the Failed delivery
     * icon instead of the pending spinner — without it a never-uploaded message maps
     * to Sent (no server transfer history → checkmark). Local-only; never sent to the
     * server.
     */
    val isFailedSendTag = Uuid.parse("7a1c0e3d-4b6f-4c2a-9e8d-5f3b2a1c0d4e")

    /** Local metadata tag: conversation has been archived by the user */
    val ConversationArchivedTag = Uuid.parse("a569e5cd-6fd8-41e0-8ccc-b6b31dac6b73")

    /** Local metadata tag: user has left this group conversation */
    val ConversationLeftTag = Uuid.parse("f3a7c2e1-9b4d-4e8f-a1c5-7d2e3f4b5c6d")

    /** Local metadata tag: conversation has been pinned by the user */
    val ConversationPinnedTag = Uuid.parse("3f7e4c1d-5a2b-4f89-b3e7-9c1d2e3f4a5b")

    /**
     * Local metadata tag: an individual message has been pinned by the user into
     * the per-conversation pinned-messages bar. Personal + synced (rides the same
     * `localAppData.tags` lane as [ConversationPinnedTag], so it propagates to the
     * user's other devices via the update-local-metadata-tags endpoint) but never
     * shared with peers.
     */
    val MessagePinnedTag = Uuid.parse("2595aec2-0852-4d3d-a20e-d955cb4553b1")

    /**
     * Local metadata tag: the user has manually unpinned an auto-pin-eligible message
     * (Poll/Event/Groodle/live-location). Durable + synced (same `localAppData.tags`
     * lane as [MessagePinnedTag]) so auto-pin never resurrects a message the user
     * dismissed — on this device after a restart, or on another device. Distinguishes
     * "user dismissed" from "never evaluated", which the per-session in-memory set
     * cannot. Set on a user unpin, cleared on a manual re-pin; NOT set by an auto-expiry
     * unpin (an ended event is already blocked by [ChatMessageStream.shouldAutoPin]).
     */
    val AutoPinDismissedTag = Uuid.parse("9d4f1a2b-8c3e-4a5f-b6d7-1e2c3a4b5d6e")

    /**
     * Local metadata tag: the pin was created by a deliberate **manual** pin (menu),
     * not by auto-pin. Durable + synced (same `localAppData.tags` lane as
     * [MessagePinnedTag]). Marks the pin **sticky** — the on-open auto-expiry prune
     * (ended events, stale live-shares) leaves a manually-pinned message alone, so a
     * user who pins an already-ended event keeps it pinned. Set only by a manual pin,
     * cleared by any unpin.
     */
    val ManualPinnedTag = Uuid.parse("c7a2f5e9-3b18-4d6a-9f21-8e4c1b0a5d3f")

    /** Server-side appData tag: conversation was originally created as a group (never removed) */
    val ConversationGroupTag = Uuid.parse("b4e3c2d1-7f6a-4e8b-9c5d-1a2b3c4d5e6f")

    const val ARCHIVAL_STATUS_DELETED = 2

    // Single source of truth lives in homebase-upload's UploadProtocol; delegated here so
    // existing chat call sites stay unchanged.
    const val DEFAULT_PAYLOAD_DESCRIPTOR_KEY = id.homebase.upload.UploadProtocol.DEFAULT_PAYLOAD_DESCRIPTOR_KEY

    const val PAYLOAD_KEY_MESSAGE_WEB = "chat_web"
    const val PAYLOAD_KEY_LINKS = "chat_links"
    // Server-side constraint: payload keys must match `^[a-z0-9_]{8,10}$`. "chat_location"
    // (13 chars) was rejected with 400 "Missing payload key"; "chat_loc" (8 chars) fits.
    const val PAYLOAD_KEY_LOCATION = "chat_loc"

    const val DefaultPayloadKey = "dflt_key"
    const val MaxDescriptorContentLength = 1024
    const val MaxHeaderContentBytes = 7000

}
