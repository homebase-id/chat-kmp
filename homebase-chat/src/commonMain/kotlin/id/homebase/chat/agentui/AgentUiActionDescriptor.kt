package id.homebase.chat.agentui

import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.util.codePointCount
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Wire format of a person's action on an agent card (dataType 219). The actor is the envelope's
 * server-set sender, never a field here.
 */
@OptIn(ExperimentalUuidApi::class)
@Serializable
data class AgentUiActionDescriptor(
    val schemaVersion: Int = 1,
    val target: Uuid,
    val rev: Int,
    val actionId: String,
    val label: String,
    val values: Map<String, String> = emptyMap(),
) {
    fun isValid(): Boolean =
        schemaVersion == 1 &&
            rev >= 0 &&
            AgentUiLimits.ACTION_ID.matches(actionId) &&
            label.isNotBlank() && label.codePointCount() <= MAX_LABEL_CP &&
            values.size <= AgentUiLimits.MAX_VALUES &&
            values.all { (k, v) -> k.length <= AgentUiLimits.MAX_ID_LENGTH && v.codePointCount() <= AgentUiLimits.MAX_VALUE_CP } &&
            fitsHeader()

    fun summaryLine(): String = label

    private fun fitsHeader(): Boolean = OdinSystemSerializer.serialize(this).encodeToByteArray().size <= MAX_BYTES

    companion object {
        const val MAX_LABEL_CP = 200
        const val MAX_BYTES = 4096
        const val MAX_VALUES_BYTES = MAX_BYTES - 1300
    }
}
