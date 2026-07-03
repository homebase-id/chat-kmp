package id.homebase.chat.services.livelocation

import id.homebase.api.client.liverelay.TimedRecipient

/**
 * When my live-location share fully covers a conversation, and until when. Returns the moment
 * full coverage ends — the minimum, across [recipientIds], of each recipient's latest active
 * roster entry — or null when the conversation is not fully covered (any recipient without an
 * active entry) or has no recipients.
 *
 * Used to hide the "share live location" offers on a conversation's bubbles while a share to
 * everyone in it is already running (#966 follow-up): partial coverage keeps the offers, because
 * starting a share then still adds someone (duplicate roster entries for the already-covered
 * recipients are harmless — the relay de-duplicates per identity).
 */
fun liveShareCoverageUntilMs(
    roster: List<TimedRecipient>,
    recipientIds: List<String>,
    nowMs: Long,
): Long? {
    if (recipientIds.isEmpty()) return null
    var coverageEnd = Long.MAX_VALUE
    for (recipient in recipientIds) {
        val end = roster
            .filter { it.odinId == recipient && it.endTimeMs > nowMs }
            .maxOfOrNull { it.endTimeMs }
            ?: return null
        if (end < coverageEnd) coverageEnd = end
    }
    return coverageEnd
}
