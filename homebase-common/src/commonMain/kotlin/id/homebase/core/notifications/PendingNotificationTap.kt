package id.homebase.core.notifications

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Holds the most recent unresolved notification tap so resolution can retry
 * across drive-sync completions. Only populated when the payload carries
 * **both** a conversationId and a messageId — a messageId-less payload is
 * treated as ambient "new activity" and does not auto-navigate.
 *
 * Lifetime: one entry at a time; a new [set] replaces any prior tap. The
 * holder auto-expires the entry after [ttl] (default 10s) so a tap for a
 * conversation that never syncs cannot auto-navigate the user later — without
 * the TTL, a stale tap could yank the user into a long-forgotten conversation
 * after a delayed resolution.
 *
 * Cleared explicitly on:
 *   - successful navigation (caller invokes [clearIfMatches] from the resolver),
 *   - user picks a different conversation (caller invokes [clear]),
 *   - VM teardown (caller invokes [clear] from `onCleared`),
 *   - irrecoverable failure to resolve (the [ttl] expiry below).
 */
@OptIn(ExperimentalTime::class)
class PendingNotificationTap(
    /** How long a tap remains retrievable before the holder auto-clears it. */
    private val ttl: Duration = DEFAULT_TTL,
    /**
     * Scope for the auto-expiry coroutine. The holder owns its own scope by default
     * because the expiry timer must outlive any single ViewModel — a CLVM rebuild
     * mid-flight (config change, process death recovery) shouldn't reset the TTL.
     * Tests pass `backgroundScope` from `runTest` so virtual time advances expiries.
     */
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    /**
     * Time source for `createdAt` timestamps and the consume-dedup window. Defaults
     * to wall clock; tests inject a virtual-time clock so the dedup window advances
     * with `advanceTimeBy(...)` rather than real elapsed time.
     */
    private val now: () -> Instant = { Clock.System.now() },
) {

    data class Tap(
        val conversationId: Uuid,
        val messageId: Uuid,
        /** Captured for diagnostic logging; not part of the equality contract. */
        val createdAt: Instant,
    )

    private val _state = MutableStateFlow<Tap?>(null)
    val state: StateFlow<Tap?> = _state.asStateFlow()

    @Volatile
    private var expiryJob: Job? = null

    /**
     * Last `(conv, msg, consumedAt)` tuple from a *user-resolved* tap (set then
     * cleared via [clear] or [clearIfMatches] — NOT a TTL expiry). Within
     * [DEDUP_WINDOW] of consumedAt, a duplicate `set(conv, msg)` for the same
     * pair is treated as an Intent replay and dropped.
     */
    @Volatile
    private var lastConsumed: ConsumeRecord? = null

    private data class ConsumeRecord(
        val conversationId: Uuid,
        val messageId: Uuid,
        val consumedAt: Instant,
    )

    /**
     * Set a fresh pending tap. Any previous tap is overwritten and its TTL timer
     * cancelled — the new tap starts a new countdown. Safe to call from any context;
     * non-suspend by design so action handlers (e.g. `onAction.ConversationClicked`)
     * can call it without a coroutine.
     */
    fun set(conversationId: Uuid, messageId: Uuid) {
        val recent = lastConsumed
        if (recent != null &&
            recent.conversationId == conversationId &&
            recent.messageId == messageId &&
            now() - recent.consumedAt <= DEDUP_WINDOW
        ) {
            Logger.i(tag = TAG) {
                "set ignored as duplicate within ${DEDUP_WINDOW.inWholeSeconds}s of consume " +
                    "(conv=$conversationId msg=$messageId)"
            }
            return
        }

        expiryJob?.cancel()
        val tap = Tap(conversationId, messageId, now())
        _state.value = tap
        expiryJob = scope.launch {
            delay(ttl)
            // compareAndSet: only clear if THIS exact tap is still the active one.
            // A racing set() that landed between our delay and now would have
            // replaced _state.value already; we must not clobber the newer tap.
            // TTL expiry intentionally does NOT seed `lastConsumed` — a tap the
            // user never actually saw resolve shouldn't dedup their next try.
            if (_state.compareAndSet(tap, null)) {
                Logger.i(tag = TAG) {
                    "expired after ${ttl.inWholeSeconds}s without resolution " +
                        "(conv=${tap.conversationId} msg=${tap.messageId})"
                }
            }
        }
    }

    fun clear() {
        val previous = _state.value
        if (previous != null) {
            // DEBUG so the per-action chatter doesn't pollute INFO logs. The interesting
            // case is "tap was set then cleared without resolving" — pair this with the
            // `Setting pendingNotificationTap` line at INFO to reconstruct what happened.
            Logger.d(tag = TAG) { "cleared (was conv=${previous.conversationId})" }
            seedConsumed(previous)
        }
        expiryJob?.cancel()
        _state.value = null
    }

    /**
     * Intended for the resolver in `ConversationListViewModel`: clear the tap iff
     * the one we're clearing is still the active one. The compareAndSet inside
     * protects against a racing fresh `set()` between read and clear — a newer
     * tap from a second notification mid-resolution must not be clobbered.
     */
    fun clearIfMatches(conversationId: Uuid) {
        val current = _state.value ?: return
        if (current.conversationId == conversationId) {
            Logger.d(tag = TAG) { "cleared on match (conv=$conversationId)" }
            expiryJob?.cancel()
            // compareAndSet: another set() may have replaced the tap between our read
            // and now — leaving the newer tap intact is the correct behavior.
            if (_state.compareAndSet(current, null)) {
                seedConsumed(current)
            }
        }
    }

    private fun seedConsumed(tap: Tap) {
        lastConsumed = ConsumeRecord(tap.conversationId, tap.messageId, now())
    }

    companion object {
        private const val TAG = "PendingNotificationTap"

        /**
         * Default time-to-live for a pending tap. Long enough to cover a typical
         * cold-start sync of a brand-new conversation; short enough that a stale tap
         * won't surprise the user after they've moved on.
         */
        val DEFAULT_TTL: Duration = 10.seconds

        /**
         * After a `(conv, msg)` tap resolves, a duplicate `set(conv, msg)` for the
         * same pair within this window is treated as a stale Intent replay
         * (e.g. onCreate + onNewIntent both feeding the same payload) and ignored.
         * A real new message in the same conv arrives with a different msgId and
         * is unaffected.
         */
        val DEDUP_WINDOW: Duration = 30.seconds
    }
}
