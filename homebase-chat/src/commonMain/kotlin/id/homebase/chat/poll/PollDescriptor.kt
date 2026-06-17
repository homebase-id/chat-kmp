package id.homebase.chat.poll

import id.homebase.api.util.codePointCount
import id.homebase.api.util.truncateToCodePoints
import kotlinx.serialization.Serializable

/**
 * Wire descriptor for a Poll chat message (typed kind, dataType 214).
 * Header-resident, no payloads. Votes are NOT stored here — they are chat
 * reactions (see [PollVote]). Only [closed] is mutable, and only the message
 * creator writes it (via ChatMessageSenderService.updateMessage).
 * See ADDING_TYPED_MESSAGE_KIND.md.
 */
@Serializable
data class PollDescriptor(
    val question: String,
    val options: List<String>,
    val allowMultiple: Boolean = false,
    val closed: Boolean = false,
    val schemaVersion: Int = 1,
) {
    fun isValid(): Boolean {
        if (question.isBlank() || question.codePointCount() > MAX_QUESTION_CP) return false
        if (options.size !in MIN_OPTIONS..MAX_OPTIONS) return false
        if (options.any { it.isBlank() || it.codePointCount() > MAX_OPTION_CP }) return false
        return true
    }

    fun summaryLine(): String = question.truncateToCodePoints(MAX_QUESTION_CP)

    companion object {
        const val MIN_OPTIONS = 2
        const val MAX_OPTIONS = 10
        const val MAX_QUESTION_CP = 140
        const val MAX_OPTION_CP = 80
    }
}
