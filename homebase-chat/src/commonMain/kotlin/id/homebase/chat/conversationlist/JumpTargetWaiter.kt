package id.homebase.chat.conversationlist

import co.touchlab.kermit.Logger
import id.homebase.resources.MR
import id.homebase.resources.conversation_jump_message_not_arrived
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.StringResource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

/** How a bounded wait for a notification-tap jump target ended. */
enum class JumpTargetOutcome {
    /** The row was already in SQL — the wait never started. */
    AlreadyLocal,

    /** The row landed (WS push or sync round) inside the budget. */
    Arrived,

    /** The budget expired with the row still absent. */
    TimedOut,
}

/**
 * Should a jump-to-message miss wait for the message, or report it unavailable now?
 *
 * Only a notification tap waits. A push notification is by definition an announcement
 * of something you have **not** synced yet, so a local miss there is overwhelmingly
 * "not on disk *yet*". Every other jump (search hit, album item, reply-preview) was
 * rendered from local data moments earlier, so a miss there really is a deletion and
 * keeps the immediate report.
 */
fun shouldWaitForJumpTarget(trigger: ConversationLoadTrigger): Boolean =
    trigger == ConversationLoadTrigger.NotificationResolved

/**
 * Bounded "waiting for this message" wait for a notification-tap jump target that
 * isn't in the local drive index yet (#1158).
 *
 * ## Why this exists
 *
 * `ChatMessageStream.loadConversationAroundMessage` opens with `getMessage(uid)`, a
 * pure local SQL read, and returns false on a miss. The caller turned that false into
 * *"That message is no longer available"* — an assertion of deletion that was never
 * verified. The miss is usually a real, documented race:
 * `AuthConnectionCoordinator.onConnected` fires `processAllInboxes()` and then
 * `syncAll()` ~15 ms later without awaiting the server-side inbox processing it just
 * asked for, so QueryBatch legitimately returns 0 records, the round reports Completed,
 * and the row arrives seconds-to-minutes later over WS push.
 *
 * ## Why the terminal condition is the timeout and not "a completed sync round"
 *
 * A completed round is exactly the false-negative that caused this bug — in the
 * reported log the chat drive reported `Syncing -> Completed` with 0 records at
 * 03:02:09.465 while WSPush delivered rows at +0.7 s, +3 s, +30 s and +40 s after it.
 * So a finished round is necessary-but-not-sufficient evidence of absence and must not
 * end the wait on its own. The only honest terminal signal is elapsed time.
 *
 * ## Absent row vs. tombstone
 *
 * Already free, no extra lookup: `selectHomebaseFileByUnique` does no `fileState`
 * filtering and `mapToMessageData` maps a soft-deleted header to a non-null
 * `MessageUiModel(isDeleted = true)`. So a genuinely deleted message resolves
 * `getMessage()` fine, `loadConversationAroundMessage` returns true, and this waiter
 * is never reached. Arriving here already proves there is no row at all.
 *
 * Collaborators are lambdas (same shape as `StickerCreator` / `MediaDownloadHandler`)
 * so the policy is exercised directly on the test scheduler.
 */
class JumpTargetWaiter(
    /**
     * Re-emits once per chat-drive data-arrival signal — a WS-pushed batch
     * (`DataEvent.BatchReceived`) or a finished sync round (`DriveEvent.Stopped`).
     * Mirrors the "re-emit and retry" shape the conversation resolver already uses;
     * these are the only two routes by which a row reaches `DriveMainIndex`.
     */
    private val arrivals: Flow<Unit>,
    /** True once the message has a row in the local drive index. */
    private val isMessageLocal: suspend (messageId: Uuid) -> Boolean,
    /** Targeted kick for the message's drive. Must swallow its own failures. */
    private val requestSync: suspend (messageId: Uuid) -> Unit,
    /**
     * Drives the pending-jump affordance. Carries the message id in both directions so the
     * clear can be a compare-and-clear — a cancelled wait's teardown must not wipe the
     * affordance of a newer conversation that already armed its own jump.
     */
    private val setWaiting: (messageId: Uuid, waiting: Boolean) -> Unit,
    /** Re-seed a window centered on the message once it lands. */
    private val seedWindowAround: suspend (conversationId: Uuid, messageId: Uuid) -> Unit,
    /** Snackbar sink. Only ever fed on the timeout path. */
    private val sendInfo: (StringResource) -> Unit,
    private val timeout: Duration = DEFAULT_TIMEOUT,
) {

    /**
     * Wait (bounded by [timeout]) for [messageId] to land locally, then re-seed the
     * window centered on it so the caller's still-armed scroll target resolves on the
     * next emission.
     *
     * Suspends, so callers must launch it rather than block the conversation load —
     * the conversation stays fully usable at the bottom of the latest page while the
     * jump is pending.
     */
    suspend fun awaitJumpTarget(conversationId: Uuid, messageId: Uuid): JumpTargetOutcome {
        // Fast path: the row may have landed between the caller's miss and this call.
        // Costs one indexed read and never touches the affordance, so an already-synced
        // message adds no frame of delay.
        if (isMessageLocal(messageId)) {
            Logger.i(tag = NOTIF_TAP) {
                "message stage: convo=$conversationId msg=$messageId already local — no wait"
            }
            return JumpTargetOutcome.AlreadyLocal
        }

        val start = TimeSource.Monotonic.markNow()
        Logger.i(tag = NOTIF_TAP) {
            "message stage: convo=$conversationId msg=$messageId NOT in local DB — " +
                "waiting up to ${timeout.inWholeSeconds}s (no row at all, so not a tombstone/deletion)"
        }
        setWaiting(messageId, true)
        try {
            requestSync(messageId)

            val arrived = withTimeoutOrNull(timeout) {
                // Re-check on every arrival signal rather than trusting any single one:
                // a round that reports Completed with 0 records is not proof of absence.
                //
                // The poll is a backstop, not the mechanism. [arrivals] is a plain Flow, so
                // the instant our collector actually subscribes isn't observable — a row
                // landing between the miss above and that instant emits its signal into the
                // void, and nothing re-checks until the *next* signal, which may never come.
                // Polling makes the outcome independent of subscription timing.
                // ponytail: fixed interval; delete the poll if `arrivals` ever becomes a
                // SharedFlow we can hook with `onSubscription`.
                merge(arrivals, pollTicks()).first { isMessageLocal(messageId) }
                true
            } != null

            if (!arrived) {
                Logger.w(tag = NOTIF_TAP) {
                    "message stage: convo=$conversationId msg=$messageId still absent after " +
                        "${start.elapsedNow().inWholeMilliseconds}ms — reporting 'hasn't arrived yet'"
                }
                sendInfo(MR.string.conversation_jump_message_not_arrived)
                return JumpTargetOutcome.TimedOut
            }

            Logger.i(tag = NOTIF_TAP) {
                "message stage: convo=$conversationId msg=$messageId landed after " +
                    "${start.elapsedNow().inWholeMilliseconds}ms — re-seeding window around it"
            }
            seedWindowAround(conversationId, messageId)
            return JumpTargetOutcome.Arrived
        } finally {
            // Also covers cancellation (the user switched conversations, which cancels
            // currentConversationJob) so the affordance can't strand on the next screen.
            setWaiting(messageId, false)
        }
    }

    private fun pollTicks(): Flow<Unit> = flow {
        while (true) {
            delay(POLL_INTERVAL)
            emit(Unit)
        }
    }

    companion object {
        private const val NOTIF_TAP = "NotifTap"

        /** Backstop re-check cadence: worst case the jump resolves this late, rather than never. */
        internal val POLL_INTERVAL: Duration = 1.seconds

        /**
         * Budget for a pending notification jump. The issue calls for 10-15s; the
         * reported log shows late WS pushes at +0.7s, +3s, +30s and +40s after the sync
         * round reported Completed, so the upper end of that range is the useful one —
         * long enough to cover the common roaming case, short enough that the user
         * isn't left watching a spinner.
         */
        val DEFAULT_TIMEOUT: Duration = 15.seconds
    }
}
