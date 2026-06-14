package id.homebase.chat.messageinfo

import androidx.compose.runtime.Immutable
import id.homebase.api.client.auth.OwnerSession
import id.homebase.api.sync.database.OutboxSync
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.ChatDeliveryStatus
import kotlin.coroutines.cancellation.CancellationException
import org.jetbrains.compose.resources.StringResource

data class RecipientStatusUiModel(
    val odinId: String,
    val displayName: String,
    val deliveryStatus: ChatDeliveryStatus,
    val errorDetailRes: StringResource? = null,
)

data class ReactionUiModel(
    val odinId: String,
    val displayName: String,
    val emoji: String,
)

/** High-level send status for an *outgoing* (own) message, shown as the single
 *  status row in Message Info. Null for inbound messages (which keep the
 *  sender-sent / we-received rows instead). [Queued] means an outbox row still
 *  exists for the message — it WILL be sent (possibly after a backoff wait), so
 *  the affordance is "Try now", never "Retry". [Failed] is a local send failure
 *  (the file never settled on our server, no outbox row left); [DeliveryFailed]
 *  is recipient-level (the file settled, but ≥1 recipient transfer failed) —
 *  distinct wording and no retry, matching the bubble's orange failed indicator. */
enum class OutgoingSendState { Sending, Queued, Sent, Failed, DeliveryFailed }

/** What a (future, currently stubbed) retry would do, derived from whether the
 *  file already exists on the server: [Create] = re-upload a never-landed file;
 *  [Update] = re-push an edit/update against a file the server already has. */
enum class RetryMode { Create, Update }

/** Result of the by-uniqueId server existence check. [Unknown] = not yet
 *  checked, still loading, or the check failed (e.g. offline). */
enum class ServerPresence { Unknown, Present, Absent }

/**
 * Map a by-uniqueId server existence probe to a [ServerPresence].
 *
 * Folds any thrown error (offline DNS hiccup, IO) into
 * [ServerPresence.Unknown] — the probe must never surface an exception into
 * the UI, only an "unknown" outcome that leaves a pending message reading
 * "Sending…" rather than blowing up. Coroutine cancellation is deliberately
 * re-thrown (not swallowed) so a navigated-away check tears down cleanly.
 *
 * Extracted from the ViewModel so this — the one piece with real branching —
 * is unit-testable without constructing the VM or faking its heavy deps.
 */
suspend fun resolveServerPresence(
    existsOnServer: suspend () -> Boolean,
    onError: (Throwable) -> Unit = {},
): ServerPresence = try {
    if (existsOnServer()) ServerPresence.Present else ServerPresence.Absent
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    onError(e)
    ServerPresence.Unknown
}

@Immutable
data class MessageInfoUiState(
    val isLoading: Boolean = true,
    val isTransferHistoryLoading: Boolean = false,
    val isReactionsLoading: Boolean = false,
    val message: MessageUiModel? = null,
    val recipients: List<RecipientStatusUiModel> = emptyList(),
    val reactions: List<ReactionUiModel> = emptyList(),
    val ownerSession: OwnerSession? = null,
    val uiEvent: MessageInfoUiEvent? = null,
    /** True when the active identity authored this message (outgoing). */
    val isOwnMessage: Boolean = false,
    /** Server-side existence of the message file, keyed by uniqueId. */
    val serverPresence: ServerPresence = ServerPresence.Unknown,
    /** Pending outbox row for the message (own pending/failed messages only);
     *  non-null means the send is still queued → Queued state + Try-now. */
    val outboxRow: OutboxSync.PendingRowSnapshot? = null,
    /** True while the by-uniqueId server existence check is in flight. */
    val isServerCheckLoading: Boolean = false,
    /** Status row for own messages; null for inbound. */
    val sendState: OutgoingSendState? = null,
    /** True when the transfer-history load threw — distinguishes "no
     *  recipients" from "couldn't load the per-recipient delivery details". */
    val transferHistoryFailed: Boolean = false,
    /** Intent a retry would carry; null when no retry is offered. */
    val retryMode: RetryMode? = null,
    /** Whether to surface the Retry affordance. */
    val canRetry: Boolean = false,
    /** Whether to surface the Try-now affordance (Queued message, not in flight). */
    val canTryNow: Boolean = false,
    /** Whole minutes until the queued message's next attempt; null = no
     *  meaningful deadline (fresh row / in-flight) → render "shortly". */
    val nextAttemptInMinutes: Long? = null,
    /** Attempt about to run (= failed attempts + 1). Null until at least one
     *  attempt has failed, so a normal first send shows no attempt counter. */
    val attemptNumber: Int? = null,
    /** Cap shown next to [attemptNumber] before the row is dropped. */
    val maxAttempts: Int = OutboxSync.MAX_ATTEMPTS,
    /** Absolute ms-epoch the next attempt is due — the target a live countdown
     *  ticks toward; null = no meaningful deadline (in-flight / fresh / legacy
     *  row) → render "shortly". */
    val nextAttemptAtMs: Long? = null,
    /** True when this queued message is blocked behind an earlier unsent one. */
    val waitingOnEarlierMessage: Boolean = false,
    /** Reason the last send attempt failed — the "why it's stuck" line. */
    val stuckReason: String? = null,
    /** Snapshot of the earlier message this one is blocked on (when
     *  [waitingOnEarlierMessage]) — its progress is what's shown, since it's the
     *  message that actually has to send before this one can. */
    val blockingRow: OutboxSync.PendingRowSnapshot? = null,
    /** The effective row (this message, or the blocker when waiting) is held by a
     *  worker. */
    val isCheckedOut: Boolean = false,
    /** That checkout is older than [OutboxSync.STALE_CHECKOUT_MS] — a zombie
     *  whose worker died. Shown as "stuck"; Try-now reclaims it. */
    val checkoutIsStale: Boolean = false,
    /** The message reads Sent/Delivered, yet a (dead/lingering) outbox row still
     *  exists for it — surfaced honestly with a Try-now to clear it. */
    val hasLingeringOutboxRow: Boolean = false,
)

sealed interface MessageInfoUiEvent {
    object Back : MessageInfoUiEvent

    /** Retry could not be enqueued — surface [messageRes] as a snackbar. */
    data class RetryFailed(val messageRes: StringResource) : MessageInfoUiEvent
}

/** Derived send-status fields for the Message Info status row + retry stub. */
data class SendStatusResult(
    val sendState: OutgoingSendState?,
    val retryMode: RetryMode?,
    val canRetry: Boolean,
)

/**
 * Pure mapping of (own/inbound, local tag state, delivery state, server
 * presence) → what the Message Info screen shows. See the scenario matrix in
 * the plan.
 *
 * - Inbound → no status row (keeps the legacy sent/received rows), no retry.
 * - Failed send (`isFailedSend`) → Failed + retry. Retry intent is Create when
 *   the server has no copy (never landed) and Update otherwise.
 * - Pending send (`isPendingSend`) → Sent if the server already has it
 *   (the pending tag just hasn't cleared — e.g. opened info too fast),
 *   otherwise Sending. No retry while still in the outbox.
 * - Settled + `deliveryFailed` → DeliveryFailed: our upload landed but ≥1
 *   recipient transfer failed (the bubble's orange state). No retry — a
 *   redistribution is not an outbox re-enqueue.
 * - Settled otherwise → Sent.
 *
 * The local tags win over `deliveryFailed`: they describe whether *our* upload
 * settled, and an optimistic/pending file has no transfer history anyway
 * (`MessageAppData.deliveryStatus` defaults to Sent).
 */
fun deriveSendStatus(
    isOwnMessage: Boolean,
    isPendingSend: Boolean,
    isFailedSend: Boolean,
    deliveryFailed: Boolean,
    serverPresence: ServerPresence,
): SendStatusResult = when {
    !isOwnMessage -> SendStatusResult(null, null, false)
    isFailedSend -> SendStatusResult(
        sendState = OutgoingSendState.Failed,
        retryMode = if (serverPresence == ServerPresence.Absent) RetryMode.Create else RetryMode.Update,
        canRetry = true,
    )
    isPendingSend -> when (serverPresence) {
        ServerPresence.Present -> SendStatusResult(OutgoingSendState.Sent, null, false)
        ServerPresence.Absent, ServerPresence.Unknown ->
            SendStatusResult(OutgoingSendState.Sending, null, false)
    }
    deliveryFailed -> SendStatusResult(OutgoingSendState.DeliveryFailed, null, false)
    else -> SendStatusResult(OutgoingSendState.Sent, null, false)
}

/** [deriveSendStatus]'s fields plus the outbox-row overlay (Try-now). */
data class RetryUiState(
    val sendState: OutgoingSendState?,
    val retryMode: RetryMode?,
    val canRetry: Boolean,
    val canTryNow: Boolean,
    val nextAttemptInMinutes: Long?,
)

/** Below this raw `nextRunTime`, the value can't be a current ms-epoch: it's a
 *  fresh-insert sentinel (0 / 42) or a legacy seconds-epoch row written before
 *  the checkInFailed unit fix. Either way the row is due "shortly". */
private const val MIN_PLAUSIBLE_MS_EPOCH = 100_000_000_000L // 2001-09-09 in ms

/**
 * Layer the outbox-row reality on top of [deriveSendStatus]'s tag/server view.
 *
 * An existing outbox row is authoritative "still sending": the queued item WILL
 * run again (after its backoff), so the screen shows **Queued** with the next
 * attempt time and a **Try now** button — never Retry (the message is not
 * failed) and never the bare Sending spinner (which reads as "in flight" when
 * the item may be hours of backoff away).
 *
 * - No row → [base] passes through untouched.
 * - Base already settled (Sent / DeliveryFailed) but a row lingers → DON'T
 *   relabel it "Queued": a delivered message can still carry a dead/zombie
 *   outbox row (the row lifecycle is decoupled from server-confirmed delivery).
 *   Keep the settled status and just expose **Try now** to clear the row.
 * - Otherwise (a not-yet-settled send) → **Queued** with the next-attempt time.
 * - Row checked out & fresh → the upload is literally running: no Try-now
 *   (resetting an in-flight row is a no-op). A **stale** checkout
 *   ([checkoutIsStale]) is a zombie → Try-now is offered (it reclaims it).
 * - Row waiting → minutes until [nextRunTimeRaw], floored at 0; raw values
 *   below [MIN_PLAUSIBLE_MS_EPOCH] (insert sentinels, legacy seconds-epoch
 *   rows) normalize to "shortly" (null is reserved for checked-out).
 */
fun retryUiState(
    base: SendStatusResult,
    inOutbox: Boolean,
    isCheckedOut: Boolean,
    checkoutIsStale: Boolean,
    nextRunTimeRaw: Long?,
    nowMs: Long,
): RetryUiState {
    if (!inOutbox) {
        return RetryUiState(base.sendState, base.retryMode, base.canRetry, canTryNow = false, nextAttemptInMinutes = null)
    }
    val minutes = when {
        isCheckedOut -> null
        nextRunTimeRaw == null || nextRunTimeRaw < MIN_PLAUSIBLE_MS_EPOCH -> 0L
        else -> ((nextRunTimeRaw - nowMs).coerceAtLeast(0L)) / 60_000L
    }
    // Try-now helps unless the row is a genuinely in-flight (fresh) upload.
    val canTryNow = !isCheckedOut || checkoutIsStale
    val settled = base.sendState == OutgoingSendState.Sent ||
        base.sendState == OutgoingSendState.DeliveryFailed
    return RetryUiState(
        sendState = if (settled) base.sendState else OutgoingSendState.Queued,
        retryMode = null,
        canRetry = if (settled) base.canRetry else false,
        canTryNow = canTryNow,
        nextAttemptInMinutes = minutes,
    )
}

/**
 * Absolute ms-epoch deadline for the queued message's next attempt — the target
 * a live countdown ticks toward — or null when there's no meaningful deadline:
 * the row is in flight (checked out), or [nextRunTimeRaw] is a fresh-insert
 * sentinel / legacy seconds-epoch value below [MIN_PLAUSIBLE_MS_EPOCH]. Null
 * renders as "shortly", matching [retryUiState]'s minutes==null/0 copy.
 */
fun nextAttemptDeadlineMs(isCheckedOut: Boolean, nextRunTimeRaw: Long?): Long? = when {
    isCheckedOut -> null
    nextRunTimeRaw == null || nextRunTimeRaw < MIN_PLAUSIBLE_MS_EPOCH -> null
    else -> nextRunTimeRaw
}

/** A checked-out row whose stamp is older than [OutboxSync.STALE_CHECKOUT_MS] is
 *  a zombie (its worker died) — shown as "stuck" and reclaimable via Try-now. */
fun OutboxSync.PendingRowSnapshot?.isStaleCheckout(nowMs: Long): Boolean {
    val stamp = this?.checkOutStamp ?: return false
    return nowMs - stamp > OutboxSync.STALE_CHECKOUT_MS
}
