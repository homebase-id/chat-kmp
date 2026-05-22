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
)