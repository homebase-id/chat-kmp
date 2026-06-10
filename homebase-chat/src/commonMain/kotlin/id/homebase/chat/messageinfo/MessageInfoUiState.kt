package id.homebase.chat.messageinfo

import androidx.compose.runtime.Immutable
import id.homebase.api.client.auth.OwnerSession
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
 *  sender-sent / we-received rows instead). [Failed] is a local send failure
 *  (the file never settled on our server); [DeliveryFailed] is recipient-level
 *  (the file settled, but ≥1 recipient transfer failed) — distinct wording and
 *  no retry, matching the bubble's orange failed indicator. */
enum class OutgoingSendState { Sending, Sent, Failed, DeliveryFailed }

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
    /** True while the by-uniqueId server existence check is in flight. */
    val isServerCheckLoading: Boolean = false,
    /** Status row for own messages; null for inbound. */
    val sendState: OutgoingSendState? = null,
    /** True when the transfer-history load threw — distinguishes "no
     *  recipients" from "couldn't load the per-recipient delivery details". */
    val transferHistoryFailed: Boolean = false,
    /** Intent a retry would carry; null when no retry is offered. */
    val retryMode: RetryMode? = null,
    /** Whether to surface the Retry affordance (currently a no-op stub). */
    val canRetry: Boolean = false,
)

sealed interface MessageInfoUiEvent {
    object Back : MessageInfoUiEvent
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
