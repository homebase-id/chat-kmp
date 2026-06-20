package id.homebase.api.client.liverelay

import kotlinx.serialization.Serializable

/**
 * One recipient of a live share plus the absolute UTC time (ms) at which sharing to them should
 * stop. Modelling the roster as {identity, end-time} pairs (rather than a flat on/off list) makes
 * the multi-share case correct:
 *
 *  - **Same recipient in two requests** → one entry, keeping the LATEST end-time (longest window wins).
 *  - **Overlapping shares** → the union of recipients, each with its own expiry.
 *  - **No manual stop needed** → a recipient simply drops off the roster once their end-time passes.
 *
 * End-times are purely sender-side bookkeeping — they are NOT sent over the wire. The relay stays
 * ephemeral/last-value-wins; the sender just decides who to fan out to, and for how long.
 */
@Serializable
data class TimedRecipient(
    /** Recipient identity as its domain string (OdinId.domainName). */
    val odinId: String,
    /** Absolute UTC epoch-ms after which this recipient is dropped from the live share. */
    val endTimeMs: Long,
)

/** Pure roster math for the live-share recipient set. No I/O — trivially unit-testable. */
object LiveShareRoster {

    /**
     * Merge [add] (each sharing until [endTimeMs]) into [current], at wall-clock [nowMs]:
     *  - already-expired entries in [current] are dropped,
     *  - a recipient present more than once collapses to a single entry with the **max** end-time.
     */
    fun merge(
        current: List<TimedRecipient>,
        add: List<String>,
        endTimeMs: Long,
        nowMs: Long,
    ): List<TimedRecipient> {
        val byId = LinkedHashMap<String, Long>()
        for (r in current) if (r.endTimeMs > nowMs) byId[r.odinId] = r.endTimeMs
        for (id in add) byId[id] = maxOf(byId[id] ?: Long.MIN_VALUE, endTimeMs)
        return byId.map { (id, end) -> TimedRecipient(id, end) }
    }

    /** The still-live recipients at [nowMs] (end-time strictly in the future). */
    fun live(roster: List<TimedRecipient>, nowMs: Long): List<TimedRecipient> =
        roster.filter { it.endTimeMs > nowMs }
}
