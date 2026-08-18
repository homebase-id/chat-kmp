package id.homebase.api.client.connections

import id.homebase.api.common.OdinId
import kotlinx.serialization.Serializable

/**
 * Per-recipient outcome of an introduction-preflight call.
 *
 * Sender's own identity is filtered out server-side, so the response may contain
 * fewer entries than the original request — always match rows back to your input
 * by [recipient], never by array position or count.
 */
@Serializable
data class RecipientPreflightStatus(
    val recipient: OdinId,
    val status: IntroductionPreflightStatus,
    /** Free-form server detail: exception text, transport specifics, internal
     *  identifiers. **Diagnostics only — never render this.** It exists for logs
     *  and support threads. User-facing wording comes from the per-status
     *  `chat_introduce_preflight_reason_*` strings. */
    val detail: String? = null,
    /** Convenience flag: false when [status] is [IntroductionPreflightStatus.RecipientNotConfigured]. */
    val isConfigured: Boolean = true,
    /** Convenience flag: true when [status] is [IntroductionPreflightStatus.RecipientRequiresUpgrade]. */
    val requiresUpgrade: Boolean = false,
    /** Convenience flag. **Do not build copy from this** — it is false for
     *  [IntroductionPreflightStatus.IntroductionsNotPermitted],
     *  [IntroductionPreflightStatus.RecipientConnectionNotConfirmed],
     *  [IntroductionPreflightStatus.RecipientDoesNotRecognizeConnection] and
     *  [IntroductionPreflightStatus.RecipientConnectionNeedsRepair] alike, which
     *  is exactly the conflation that produced the wrong "doesn't allow
     *  introductions" message. Switch on [status]. */
    val allowsIntroductions: Boolean = true,
    /** Diagnostic detail behind statuses 5 vs 8: does the recipient hold a
     *  connection record for us at all. Null when the server didn't say. */
    val isCallerConnected: Boolean? = null,
    /** Diagnostic detail behind statuses 5 vs 8: has the recipient confirmed
     *  that connection. Null when the server didn't say. */
    val isCallerConfirmed: Boolean? = null,
    /** Diagnostic detail: was the connection established by auto-connect rather
     *  than an explicit accept. Null when the server didn't say. */
    val isCallerAutoConnected: Boolean? = null,
    /** Diagnostic detail: the recipient's view of our connection record. */
    val callerConnectionState: CallerConnectionState? = null,
    /** Server's view of who can resolve this. Null on servers that predate the
     *  field — read [effectiveRemedyActor] instead of this. */
    val remedyActor: PreflightRemedyActor? = null,
    /** Server's view of whether a retry is worthwhile. Null on servers that
     *  predate the field — read [canRetry] instead of this. */
    val isTransient: Boolean? = null,
) {
    /** Who can act, falling back to the client-side table when the server
     *  omitted [remedyActor]. Drive fix-it affordances off this, not [status]. */
    val effectiveRemedyActor: PreflightRemedyActor
        get() = remedyActor ?: status.defaultRemedyActor

    /** Whether re-running preflight unchanged could plausibly succeed, falling
     *  back to the client-side table when the server omitted [isTransient].
     *  Drive the retry affordance off this, not [status]. */
    val canRetry: Boolean
        get() = isTransient ?: status.defaultIsTransient
}

/**
 * Aggregate response from `POST /connections/introductions/preflight`.
 */
@Serializable
data class IntroductionPreflightResult(
    val recipients: List<RecipientPreflightStatus> = emptyList(),
) {
    /** True iff every recipient that came back is [IntroductionPreflightStatus.Ready]. */
    val allReady: Boolean
        get() = recipients.isNotEmpty() &&
            recipients.all { it.status == IntroductionPreflightStatus.Ready }

    /** Recipients with a non-Ready status — the ones the UI should warn the user about. */
    val nonReady: List<RecipientPreflightStatus>
        get() = recipients.filter { it.status != IntroductionPreflightStatus.Ready }

    /** Recipients with a Ready status — the safe subset to send to when the user
     *  picks "Skip these and send to the rest". */
    val readyRecipients: List<OdinId>
        get() = recipients
            .filter { it.status == IntroductionPreflightStatus.Ready }
            .map { it.recipient }

    /** True when at least one blocked recipient might come back on a plain
     *  retry — gates the dialog's "Check again" affordance. */
    val hasRetryableRecipient: Boolean
        get() = nonReady.any { it.canRetry }
}
