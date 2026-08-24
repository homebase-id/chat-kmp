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

enum class JumpTargetOutcome { AlreadyLocal, Arrived, TimedOut }

/**
 * Only a notification tap waits: it announces something not synced yet. Every other jump
 * was rendered from local data moments earlier, so a miss there really is a deletion.
 */
fun shouldWaitForJumpTarget(trigger: ConversationLoadTrigger): Boolean =
    trigger == ConversationLoadTrigger.NotificationResolved

/**
 * Reaching this waiter already proves there is no row at all rather than a deletion:
 * `selectHomebaseFileByUnique` does no `fileState` filtering and `mapToMessageData` maps a
 * soft-deleted header to a non-null `MessageUiModel(isDeleted = true)`, so a tombstone
 * resolves upstream and never gets here.
 */
class JumpTargetWaiter(
    private val arrivals: Flow<Unit>,
    private val isMessageLocal: suspend (messageId: Uuid) -> Boolean,
    private val requestSync: suspend (messageId: Uuid) -> Unit,
    private val setWaiting: (messageId: Uuid, waiting: Boolean) -> Unit,
    private val seedWindowAround: suspend (conversationId: Uuid, messageId: Uuid) -> Unit,
    private val sendInfo: (StringResource) -> Unit,
    private val timeout: Duration = DEFAULT_TIMEOUT,
) {

    /** Suspends; callers must launch it so the conversation stays usable while the jump is pending. */
    suspend fun awaitJumpTarget(conversationId: Uuid, messageId: Uuid): JumpTargetOutcome {
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
                // A round reporting Completed with 0 records is the false negative this whole
                // wait exists for, so nothing short of elapsed time may terminate it.
                merge(arrivals, subscriptionRaceBackstop()).first { isMessageLocal(messageId) }
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
            setWaiting(messageId, false)
        }
    }

    // `arrivals` is a plain Flow, so a row landing before the collector subscribes emits into
    // the void and nothing re-checks until the next signal — which may never come.
    // ponytail: fixed interval; drop this if `arrivals` becomes a SharedFlow we can hook with
    // `onSubscription`.
    private fun subscriptionRaceBackstop(): Flow<Unit> = flow {
        while (true) {
            delay(POLL_INTERVAL)
            emit(Unit)
        }
    }

    companion object {
        private const val NOTIF_TAP = "NotifTap"
        internal val POLL_INTERVAL: Duration = 1.seconds
        val DEFAULT_TIMEOUT: Duration = 15.seconds
    }
}
