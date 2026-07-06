package id.homebase.api.client.liverelay

import kotlinx.serialization.Serializable

/**
 * One **share entry**: a recipient plus the absolute UTC time (ms) at which that particular share
 * should stop. The roster is a flat list of these — one entry per share action, **duplicates
 * allowed**: the same recipient can appear more than once with different end-times (e.g. two
 * "share for 15 min" actions to the same friend from two conversations). Keeping the entries
 * distinct is deliberate, so a UX can store an entry's end-time on its chat bubble and later remove
 * exactly that entry without disturbing the other share to the same person.
 *
 * Deduplication to unique identities happens **only at send time** ([liveRecipientIds]) — we never
 * send the same coordinate to the same identity twice. End-times are purely sender-side bookkeeping;
 * they are NOT sent over the wire. The relay stays ephemeral/last-value-wins.
 */
@Serializable
data class TimedRecipient(
    /** Recipient identity as its domain string (OdinId.domainName). */
    val odinId: String,
    /** Absolute UTC epoch-ms after which THIS share entry is dropped. */
    val endTimeMs: Long,
)

/** Pure roster math for the live-share recipient set. No I/O — trivially unit-testable. */
object LiveShareRoster {

    /**
     * Append [add] (each a new share entry ending at [endTimeMs]) to [current], dropping entries
     * already expired at [nowMs]. **Does not collapse duplicates** — a recipient added again gets a
     * second entry, so each share remains individually removable.
     */
    fun add(
        current: List<TimedRecipient>,
        add: List<String>,
        endTimeMs: Long,
        nowMs: Long,
    ): List<TimedRecipient> =
        current.filter { it.endTimeMs > nowMs } + add.map { TimedRecipient(it, endTimeMs) }

    /**
     * Remove exactly the entries belonging to one share — those whose [TimedRecipient.odinId] is in
     * [recipients] **and** whose [TimedRecipient.endTimeMs] equals [endTimeMs]. The `{recipient,
     * end-time}` pair is the share's key: a chat bubble stores its share's absolute end-time, and
     * "stop sharing" passes that exact end-time back here so only this share's entries drop — any other
     * live share to the same person (a different end-time) is left untouched. No-op if nothing matches.
     */
    fun remove(
        current: List<TimedRecipient>,
        recipients: List<String>,
        endTimeMs: Long,
    ): List<TimedRecipient> =
        current.filterNot { it.endTimeMs == endTimeMs && it.odinId in recipients }

    /**
     * Remove **every** entry belonging to any of [recipients], regardless of end-time — the
     * "stop sharing with this person entirely" primitive. Unlike [remove] (which keys on a single
     * `{recipient, end-time}` share), this drops all of a recipient's overlapping shares at once, so
     * the deduped Dashboard row that represents a person disappears in one action. No-op for
     * recipients with no entries.
     */
    fun removeRecipients(
        current: List<TimedRecipient>,
        recipients: List<String>,
    ): List<TimedRecipient> =
        current.filterNot { it.odinId in recipients }

    /** The still-live entries at [nowMs] (end-time strictly in the future). May contain duplicates. */
    fun live(roster: List<TimedRecipient>, nowMs: Long): List<TimedRecipient> =
        roster.filter { it.endTimeMs > nowMs }

    /**
     * The **unique** recipient identities to actually fan out to at [nowMs] — one entry per identity
     * even if several share entries name them. This is the list to pass to the relay.
     */
    fun liveRecipientIds(roster: List<TimedRecipient>, nowMs: Long): List<String> =
        live(roster, nowMs).map { it.odinId }.distinct()
}
