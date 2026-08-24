package id.homebase.chat.services.convo

import id.homebase.api.common.time.UnixTimeUtc
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.uuid.Uuid

@Serializable
data class ConversationLocalAppDataJson(
    /**
     * DEPRECATED: But we still needed for backwards compatibility. Remove it after April Launch
     * 2026
     */
    @Transient
    val conversationId: Uuid =
        Uuid.Companion.NIL, // TODO: Obsolete, ignore. Same as uniqueId for conversation
    val lastReadTime: UnixTimeUtc? = null,
    val lastExitedAt: UnixTimeUtc? = null,
    /**
     * Sort key for the conversation list: the userDate of the most recent
     * message known the last time this conversation was stamped. It rides
     * along with the [lastReadTime] writeback (no separate push), so it is
     * refreshed whenever the conversation is read or sent to.
     *
     * [ConversationMapper.mapToBasic] reads it so the cold-load / post-sync
     * basic list lands in last-message order without waiting for the enrich
     * JOIN. Null for never-stamped or message-less threads, which fall back to
     * `fileMetadata.created`. May lag for a thread that received an unread
     * message since its last stamp — the enrich pass corrects that.
     */
    val latestMessageTimestamp: UnixTimeUtc? = null,
    /**
     * Unsent composer text for this conversation (markdown), persisted so it
     * survives leaving the thread / an app kill and syncs across the owner's own
     * devices — never distributed to peers (it rides `localAppData.content`, not
     * `appData.content`). Null when there is no draft. Paired with
     * [draftUpdatedAt] for last-write-wins conflict resolution (#1122).
     */
    val draft: String? = null,
    /** When [draft] was last written, for last-write-wins across devices. */
    val draftUpdatedAt: UnixTimeUtc? = null,
)

/**
 * Apply a draft edit with last-write-wins semantics: a stamp whose [updatedAt]
 * is older than the draft we already hold is dropped (a stale device replaying
 * an edit can't clobber a fresher one); an equal-or-newer stamp wins. A blank
 * draft normalises to null (the cleared state), so clearing on send — which
 * stamps `now()` — always wins. #1122.
 */
fun ConversationLocalAppDataJson.withDraftLww(
    draft: String?,
    updatedAt: UnixTimeUtc,
): ConversationLocalAppDataJson =
    if (draftUpdatedAt != null && updatedAt.milliseconds < draftUpdatedAt.milliseconds) this
    else copy(draft = draft?.ifBlank { null }, draftUpdatedAt = updatedAt)
