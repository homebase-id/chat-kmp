package id.homebase.chat.services.livelocation

import id.homebase.api.client.liverelay.TimedRecipient
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
 * Bucket size for stabilising the incoming synthetic deadline. Because the deadline is
 * `receivedAtMs + stale`, a continuously-streaming peer would otherwise push a new (ever-advancing)
 * value on every relay packet — changing the UiState every few seconds and forcing a top-bar
 * recomposition + [id.homebase.chat.widget.LiveShareIndicator] ticker restart for the whole share
 * (#1012 review). Rounding the deadline up to a 1-minute bucket keeps it identical across packets so
 * StateFlow suppresses the no-op emissions; the ≤1-min later clear is negligible against a 1-h window.
 */
const val INCOMING_SHARE_QUANTUM_MS: Long = 60 * 1000L

/** Round [untilMs] up to the next [quantumMs] boundary (never into the past) to stabilise it across
 *  packets; null passes through. See [INCOMING_SHARE_QUANTUM_MS]. */
fun quantizeLiveShareDeadlineUp(untilMs: Long?, quantumMs: Long = INCOMING_SHARE_QUANTUM_MS): Long? =
    untilMs?.let { ((it + quantumMs - 1) / quantumMs) * quantumMs }

/** The single pin deadline for a surface: the later of my outgoing share and an incoming share, or
 *  null when neither is active. Shared by the chat-list, in-chat, and details pins (#1012). */
fun liveSharePinUntilMs(ownUntilMs: Long?, incomingUntilMs: Long?): Long? =
    listOfNotNull(ownUntilMs, incomingUntilMs).maxOrNull()

/**
 * The complete pin deadline for a CONVERSATION surface (in-chat top bar, details screen): my
 * outgoing share to any of [otherParticipants] OR one of them sharing with me (quantized incoming
 * freshness), whichever ends later; null when neither. The single source of truth for the
 * per-conversation pin math — the in-chat and details pins call this same function so they can
 * never diverge (#1012 review).
 */
fun conversationLiveSharePinUntilMs(
    roster: List<TimedRecipient>,
    positions: Map<OdinId, LivePosition>,
    otherParticipants: Collection<OdinId>,
    nowMs: Long,
): Long? = liveSharePinUntilMs(
    liveShareAnyUntilMs(roster, otherParticipants.map { it.domainName }, nowMs),
    quantizeLiveShareDeadlineUp(
        incomingLiveShareUntilMs(positions, otherParticipants, INCOMING_SHARE_STALE_MS, nowMs),
    ),
)

/**
 * The complete pin deadline for the GLOBAL chat-overview surface: I'm sharing with anyone at all OR
 * anyone is sharing with me, whichever ends later; null when neither. Counterpart of
 * [conversationLiveSharePinUntilMs] for the unscoped list pin.
 */
fun globalLiveSharePinUntilMs(
    roster: List<TimedRecipient>,
    positions: Map<OdinId, LivePosition>,
    nowMs: Long,
): Long? = liveSharePinUntilMs(
    liveShareAnyUntilMs(roster, null, nowMs),
    quantizeLiveShareDeadlineUp(
        incomingLiveShareAnyUntilMs(positions, INCOMING_SHARE_STALE_MS, nowMs),
    ),
)

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
