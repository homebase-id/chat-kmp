package id.homebase.chat.services.content

import co.touchlab.kermit.Logger
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.chat.dice.DiceRollDescriptor
import id.homebase.chat.event.EventDescriptor
import id.homebase.chat.services.ChatProtocol

/**
 * Parses the `appData.content` JSON for a typed rich-content message.
 *
 * Returns null for any [dataType] that doesn't correspond to a known
 * [MessageContent] kind, or when parsing fails (older client, schema drift,
 * corruption). The caller falls back to text rendering.
 */
object MessageContentParser {

    private const val TAG = "MessageContentParser"

    fun parse(dataType: Int?, content: String?): MessageContent? {
        if (dataType == null || content.isNullOrBlank()) return null
        return when (dataType) {
            ChatProtocol.ChatEventMessageDataType -> parseEvent(content)
            ChatProtocol.ChatDiceRollMessageDataType -> parseDiceRoll(content)
            else -> null
        }
    }

    private fun parseEvent(content: String): MessageContent.Event? = try {
        MessageContent.Event(OdinSystemSerializer.deserialize<EventDescriptor>(content))
    } catch (e: Exception) {
        Logger.w(tag = TAG, throwable = e) { "failed to parse Event descriptor; falling back to text" }
        null
    }

    private fun parseDiceRoll(content: String): MessageContent.DiceRoll? = try {
        val descriptor = OdinSystemSerializer.deserialize<DiceRollDescriptor>(content)
        if (descriptor.isValid()) MessageContent.DiceRoll(descriptor) else {
            Logger.w(tag = TAG) { "DiceRoll descriptor failed validation; faces=${descriptor.faces} count=${descriptor.results.size}" }
            null
        }
    } catch (e: Exception) {
        Logger.w(tag = TAG, throwable = e) { "failed to parse DiceRoll descriptor; falling back to text" }
        null
    }

    fun serialize(content: MessageContent): String = when (content) {
        is MessageContent.Event -> OdinSystemSerializer.serialize(content.descriptor)
        is MessageContent.DiceRoll -> OdinSystemSerializer.serialize(content.descriptor)
    }

    fun dataTypeFor(content: MessageContent): Int = when (content) {
        is MessageContent.Event -> ChatProtocol.ChatEventMessageDataType
        is MessageContent.DiceRoll -> ChatProtocol.ChatDiceRollMessageDataType
    }
}
