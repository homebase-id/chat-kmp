package id.homebase.chat.agentui

import id.homebase.api.util.codePointCount
import id.homebase.api.util.truncateToCodePoints
import kotlinx.serialization.Serializable

/** Wire format of an agent card header (dataType 218). The tree itself rides in the `agent_ui` payload. */
@Serializable
data class AgentUiDescriptor(
    val schemaVersion: Int = 1,
    val fallback: String,
    val title: String? = null,
    val rev: Int = 0,
) {
    fun isValid(): Boolean =
        schemaVersion == 1 &&
            fallback.isNotBlank() && fallback.codePointCount() <= MAX_FALLBACK_CP &&
            (title == null || title.codePointCount() <= MAX_TITLE_CP) &&
            rev >= 0

    fun summaryLine(): String = title?.takeIf { it.isNotBlank() } ?: fallback.truncateToCodePoints(MAX_TITLE_CP)

    companion object {
        const val MAX_FALLBACK_CP = 1000
        const val MAX_TITLE_CP = 80
    }
}
