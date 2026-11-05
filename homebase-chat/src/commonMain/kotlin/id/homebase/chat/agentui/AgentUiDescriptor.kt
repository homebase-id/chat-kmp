package id.homebase.chat.agentui

import id.homebase.api.HomebaseProtocol
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.util.codePointCount
import id.homebase.api.util.truncateToCodePoints
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Wire format of an agent card header (dataType 218). A [tree] that fits the header rides here;
 * a bigger one rides in the `agent_ui` payload. See AGENT_UI.md.
 */
@Serializable
data class AgentUiDescriptor(
    val schemaVersion: Int = 1,
    val fallback: String,
    val title: String? = null,
    val rev: Int = 0,
    val tree: JsonObject? = null,
) {
    fun isValid(): Boolean =
        schemaVersion == 1 &&
            fallback.isNotBlank() && fallback.codePointCount() <= MAX_FALLBACK_CP &&
            (title == null || title.codePointCount() <= MAX_TITLE_CP) &&
            rev >= 0

    fun summaryLine(): String = title?.takeIf { it.isNotBlank() } ?: fallback.truncateToCodePoints(MAX_TITLE_CP)

    /** This descriptor carrying [tree] inline, or null when that would not fit the encrypted header. */
    fun withInlineTree(tree: JsonObject): AgentUiDescriptor? =
        copy(tree = tree).takeIf { encryptedHeaderBytes(OdinSystemSerializer.serialize(it)) <= HomebaseProtocol.MaxHeaderContentBytes }

    companion object {
        const val MAX_FALLBACK_CP = 1000
        const val MAX_TITLE_CP = 80

        /** The server caps `appData.content` after encryption: base64 of AES-CBC with PKCS7 padding. */
        fun encryptedHeaderBytes(plain: String): Int {
            val cipher = (plain.encodeToByteArray().size / 16 + 1) * 16
            return (cipher + 2) / 3 * 4
        }
    }
}
