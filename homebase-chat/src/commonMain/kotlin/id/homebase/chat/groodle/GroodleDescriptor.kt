package id.homebase.chat.groodle

import kotlinx.serialization.Serializable

/**
 * Wire format for a Groodle (group-scheduling poll) message. Serialized as JSON
 * into the chat message header's `appData.content` field (alongside
 * `appData.dataType = ChatProtocol.ChatGroodleMessageDataType`), so it loads
 * with the message index — no payload fetch on scroll.
 *
 * The sender proposes 1..10 candidate time [slots]; recipients vote Yes / No /
 * Maybe per slot. Votes are recorded as ordinary chat reactions encoded by
 * [GroodleVote] (e.g. `_1Y`), not as message payloads, so they ride the existing
 * reaction sync for free.
 *
 * Identity / sender / creation time are NOT in the descriptor — read them from
 * the surrounding HomebaseFile envelope (`uniqueId`,
 * `fileMetadata.originalAuthor`, `userDate`). See the "Don't duplicate envelope
 * fields in the descriptor" rule under "Adding a New Typed Message Kind" in
 * `CLAUDE.md`.
 *
 * Worst case (full title/description + 10 slots with end times) is ~900 bytes,
 * comfortably under [id.homebase.chat.services.ChatProtocol.MaxHeaderContentBytes].
 */
@Serializable
data class GroodleDescriptor(
    val schemaVersion: Int = 1,
    val title: String,
    val description: String = "",
    /** IANA zone the slots were authored in, e.g. "Europe/Copenhagen". */
    val timezone: String,
    /** When false the poll is Yes/No only — the Maybe option is hidden. */
    val allowMaybe: Boolean = true,
    /** Voting deadline in UTC millis; null = no deadline. Past it, voting locks. */
    val deadlineUtcMs: Long? = null,
    val slots: List<GroodleSlot>,
) {
    /** One-liner for the conversation-list preview / push notification / search index. */
    fun summaryLine(): String = title

    /**
     * Strict bounds check. The parser treats an invalid descriptor exactly like
     * malformed JSON — it renders the unparseable chip — so be conservative here.
     */
    fun isValid(): Boolean {
        if (schemaVersion < 1) return false
        if (title.isBlank()) return false
        if (title.codePointLength() !in 1..MAX_TITLE_CODEPOINTS) return false
        if (description.codePointLength() > MAX_DESCRIPTION_CODEPOINTS) return false
        if (timezone.isBlank()) return false
        if (slots.size !in 1..MAX_SLOTS) return false

        for (i in slots.indices) {
            val slot = slots[i]
            // End (when present) must be strictly after start.
            if (slot.endUtcMs != null && slot.endUtcMs <= slot.startUtcMs) return false
            // Ascending by start; equal starts are allowed (same date, different
            // duration), but the full-slot duplicate check below rejects exact ties.
            if (i > 0 && slot.startUtcMs < slots[i - 1].startUtcMs) return false
        }

        // No two identical slots (same start AND same end). Duplicates would make
        // two vote columns indistinguishable.
        val seen = HashSet<Pair<Long, Long?>>(slots.size)
        for (slot in slots) {
            if (!seen.add(slot.startUtcMs to slot.endUtcMs)) return false
        }

        // A deadline can't fall after voting would already be moot.
        if (deadlineUtcMs != null && deadlineUtcMs > slots.first().startUtcMs) return false

        return true
    }

    companion object {
        const val MAX_TITLE_CODEPOINTS = 80
        const val MAX_DESCRIPTION_CODEPOINTS = 280
        const val MAX_SLOTS = 10
    }
}

@Serializable
data class GroodleSlot(
    val startUtcMs: Long,
    /** Optional end; null = unspecified duration. When set, must be > [startUtcMs]. */
    val endUtcMs: Long? = null,
)

/**
 * Counts Unicode code points (not UTF-16 chars), so emoji and other non-BMP
 * characters in user text count as one each — matching `truncateToCodePoints`.
 */
private fun String.codePointLength(): Int {
    var count = 0
    var i = 0
    while (i < length) {
        i += if (i + 1 < length && this[i].isHighSurrogate() && this[i + 1].isLowSurrogate()) 2 else 1
        count += 1
    }
    return count
}
