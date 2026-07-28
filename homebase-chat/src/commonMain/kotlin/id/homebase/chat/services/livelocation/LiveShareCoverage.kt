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

/**
 * When my live share reaches ANY of [recipientIds] (or anyone at all when null), and until when:
 * the latest `endTimeMs` among matching active roster entries, or null when none. A scoped call
 * with an EMPTY recipient list returns null (a conversation with no participants), distinct from
 * `null` = unscoped (the chat-list pin: "am I sharing with anyone at all").
 *
 * Deliberately a different predicate from [liveShareCoverageUntilMs]: the sharing INDICATOR (#816
 * pin) answers "am I sharing with anyone here" (ANY), while offer suppression answers "would
 * starting a share still add someone" (FULL). Don't unify them.
 */
fun liveShareAnyUntilMs(
    roster: List<TimedRecipient>,
    recipientIds: List<String>? = null,
    nowMs: Long,
): Long? {
    if (recipientIds != null && recipientIds.isEmpty()) return null
    return roster
        .filter { it.endTimeMs > nowMs && (recipientIds == null || it.odinId in recipientIds) }
        .maxOfOrNull { it.endTimeMs }
}
