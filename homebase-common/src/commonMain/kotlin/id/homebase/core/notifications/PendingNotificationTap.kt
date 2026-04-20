package id.homebase.core.notifications

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Holds the most recent unresolved notification tap so resolution can retry
 * across drive-sync completions. Only populated when the payload carries
 * **both** a conversationId and a messageId — a messageId-less payload is
 * treated as ambient "new activity" and does not auto-navigate.
 *
 * Lifetime: one entry at a time; a new `set` replaces any prior tap.
 * Cleared when resolution succeeds or the user manually navigates.
 */
@OptIn(ExperimentalTime::class)
class PendingNotificationTap {

    data class Tap(
        val conversationId: Uuid,
        val messageId: Uuid,
        val createdAt: Instant,
    )

    private val _state = MutableStateFlow<Tap?>(null)
    val state: StateFlow<Tap?> = _state.asStateFlow()

    fun set(conversationId: Uuid, messageId: Uuid) {
        _state.value = Tap(conversationId, messageId, Clock.System.now())
    }

    fun clear() {
        _state.value = null
    }

    fun clearIfMatches(conversationId: Uuid) {
        val current = _state.value ?: return
        if (current.conversationId == conversationId) _state.value = null
    }
}
