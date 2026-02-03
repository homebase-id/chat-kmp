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
    val lastReadTime: UnixTimeUtc?
)