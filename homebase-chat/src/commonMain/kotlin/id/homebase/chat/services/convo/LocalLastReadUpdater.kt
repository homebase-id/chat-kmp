package id.homebase.chat.services.convo

import id.homebase.api.common.time.UnixTimeUtc
import kotlin.uuid.Uuid

/**
 * Narrow seam for advancing the caller's local last-read watermark on a
 * conversation. ConversationService is the production implementation;
 * tests for mark-as-read flows can swap in a fake without constructing
 * the full service.
 */
interface LocalLastReadUpdater {
    suspend fun updateLocalLastReadTime(conversationId: Uuid, newLastReadTime: UnixTimeUtc)
}
