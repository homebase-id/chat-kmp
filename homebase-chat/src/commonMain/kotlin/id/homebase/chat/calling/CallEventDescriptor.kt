package id.homebase.chat.calling

import kotlinx.serialization.Serializable

@Serializable
enum class CallOutcome { Completed, NoAnswer, Declined, Cancelled, Busy }

/** Call log entry; author, time and conversation come from the message envelope, so none are repeated here. */
@Serializable
data class CallEventDescriptor(
    val outcome: CallOutcome,
    val video: Boolean = false,
    val durationSeconds: Long = 0,
    val schemaVersion: Int = 1,
) {
    fun isValid(): Boolean = durationSeconds in 0..MAX_DURATION_SECONDS &&
        (outcome == CallOutcome.Completed || durationSeconds == 0L)

    /** Outside composition (previews, notifications), so plain English. */
    fun summaryLine(): String = when (outcome) {
        CallOutcome.Completed -> if (video) "Video call" else "Voice call"
        CallOutcome.NoAnswer, CallOutcome.Cancelled -> "Missed call"
        CallOutcome.Declined -> "Call declined"
        CallOutcome.Busy -> "Busy"
    }

    companion object {
        const val MAX_DURATION_SECONDS = 7L * 24 * 3600
    }
}
