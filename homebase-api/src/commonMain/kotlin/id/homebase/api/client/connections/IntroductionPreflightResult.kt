package id.homebase.api.client.connections

import id.homebase.api.common.OdinId
import kotlinx.serialization.Serializable

/**
 * Per-recipient outcome of an introduction-preflight call.
 *
 * Sender's own identity is filtered out server-side, so the response may contain
 * fewer entries than the original request.
 */
@Serializable
data class RecipientPreflightStatus(
    val recipient: OdinId,
    val status: IntroductionPreflightStatus,
    /** Free-form server detail. Typically populated only when [status] is
     *  [IntroductionPreflightStatus.UnknownError]; UI may surface this when
     *  a generic per-status string is too vague. */
    val detail: String? = null,
    /** Convenience flag: false when [status] is [IntroductionPreflightStatus.RecipientNotConfigured]. */
    val isConfigured: Boolean = true,
    /** Convenience flag: true when [status] is [IntroductionPreflightStatus.RecipientRequiresUpgrade]. */
    val requiresUpgrade: Boolean = false,
    /** Convenience flag: false when [status] is
     *  [IntroductionPreflightStatus.IntroductionsNotPermitted]. */
    val allowsIntroductions: Boolean = true,
)

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
}
