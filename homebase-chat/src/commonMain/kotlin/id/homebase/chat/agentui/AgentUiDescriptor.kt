package id.homebase.chat.agentui

import id.homebase.api.HomebaseProtocol
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.util.codePointCount
import id.homebase.api.util.truncateToCodePoints
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Wire format of an agent card header (dataType 219). A [tree] that fits the header rides here;
 * a bigger one rides in the `agent_ui` payload. See AGENT_UI.md.
 */
@Serializable
data class AgentUiDescriptor(
    val schemaVersion: Int = 1,
    val fallback: String,
    val title: String? = null,
    val rev: Int = 0,
    val tree: JsonElement? = null,
) {
    fun isValid(): Boolean =
        schemaVersion == 1 &&
            fallback.isNotBlank() && fallback.codePointCount() <= MAX_FALLBACK_CP &&
            (title == null || title.codePointCount() <= MAX_TITLE_CP) &&
            rev >= 0

    fun summaryLine(): String = title?.takeIf { it.isNotBlank() } ?: fallback.truncateToCodePoints(MAX_TITLE_CP)

    /** This descriptor carrying [tree] inline, or null when that would not fit the encrypted header. */
    fun withInlineTree(tree: JsonObject): AgentUiDescriptor? =
        copy(tree = tree).takeIf { HomebaseProtocol.fitsHeaderContent(OdinSystemSerializer.serialize(it)) }

    companion object {
        const val MAX_FALLBACK_CP = 1000
        const val MAX_TITLE_CP = 80
    }
}

/** [AgentUiDescriptor] without its tree; unknown keys are skipped, so this reads a header whose tree the parser refuses. */
@Serializable
internal data class AgentUiHeaderFields(val schemaVersion: Int = 1, val fallback: String, val title: String? = null, val rev: Int = 0) {
    fun descriptor() = AgentUiDescriptor(schemaVersion, fallback, title, rev)
}
