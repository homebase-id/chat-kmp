package id.homebase.chat.services.livelocation

import id.homebase.api.common.OdinId

/**
 * How fresh a received live-relay point must be for its sender to still count as "sharing live with
 * me" for the purpose of the conversation pin.
 *
 * The receive side has no share-window deadline — [LiveLocationReceiveStore] only stamps each point's
 * receipt time, and it never evicts an entry (last-value-wins until logout). So a sharing peer is
 * distinguished from one who stopped purely by age: a point newer than this keeps the pin lit,
 * anything older lets it clear. The threshold is deliberately generous (1 h) — the pin is a soft
 * "recently sharing with me" hint, not a precise window, and tapping it opens the live map where each
 * marker's exact age is shown, so an occasionally-lingering pin costs nothing.
 */
const val INCOMING_SHARE_STALE_MS: Long = 60 * 60 * 1000L

/**
 * When someone — anyone — is sharing their live location with me, and until when: the latest
 * `receivedAtMs + [staleMs]` among senders with a still-fresh point, or null when none is fresh.
 * Unscoped (no conversation) — drives the single chat-overview top-bar pin ("is anyone live-sharing
 * with me at all"), mirroring the send-side [liveShareAnyUntilMs] with `recipientIds == null`.
 *
 * Returning `receivedAtMs + staleMs` as a synthetic deadline lets [id.homebase.chat.widget.LiveShareIndicator]
 * drive itself unchanged: each new packet pushes the deadline forward and keeps the pin lit; once the
 * peer stops, the deadline stops advancing and the indicator's own ticker clears the pin at staleness.
 */
fun incomingLiveShareAnyUntilMs(
    positions: Map<OdinId, LivePosition>,
    staleMs: Long,
    nowMs: Long,
): Long? =
    positions.values
        .filter { nowMs - it.receivedAtMs <= staleMs }
        .maxOfOrNull { it.receivedAtMs + staleMs }

/**
 * When one of [otherParticipants] (a conversation's participants minus me) is sharing their live
 * location with me, and until when — scoped to a single conversation. Drives the in-chat and details
 * pins. Empty participants (e.g. note-to-self) → null.
 */
fun incomingLiveShareUntilMs(
    positions: Map<OdinId, LivePosition>,
    otherParticipants: Collection<OdinId>,
    staleMs: Long,
    nowMs: Long,
): Long? {
    if (otherParticipants.isEmpty()) return null
    val participants = otherParticipants.toHashSet()
    return positions.values
        .filter { it.senderOdinId in participants && nowMs - it.receivedAtMs <= staleMs }
        .maxOfOrNull { it.receivedAtMs + staleMs }
}
