package id.homebase.chat.services.content

import co.touchlab.kermit.Logger
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.chat.dice.DiceRollDescriptor
import id.homebase.chat.event.EventDescriptor
import id.homebase.chat.groodle.GroodleDescriptor
import id.homebase.chat.poll.PollDescriptor
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.builder.LocationPreviewDescriptor

/**
 * Parses the `appData.content` JSON for a typed rich-content message.
 *
 * Three return shapes:
 *
 * 1. **Recognized typed kind, valid content** — `MessageContent.X(descriptor)`
 *    with the parsed descriptor. The bubble renders the full UI.
 * 2. **Recognized typed kind, broken content** (malformed JSON, schema drift,
 *    validation failure) — `MessageContent.X(descriptor = null)`. The bubble
 *    renders the kind-specific "unable to display, update the app" chip.
 *    Returning `null` here would route the message into the text-fallback
 *    path, where the descriptor JSON would fail to deserialize as
 *    `MessageAppData` and the message would disappear from the stream.
 * 3. **`null` return** — the [dataType] is part of the explicit
 *    "MessageAppData-shaped" allowlist (plain text/media, status, location).
 *    The caller deserializes the content as `MessageAppData`.
 *
 * Anything else — a non-zero [dataType] this build doesn't recognize —
 * surfaces as [MessageContent.Unknown] so the user sees an "update the app"
 * chip rather than a vanished message.
 */
object MessageContentParser {

    private const val TAG = "MessageContentParser"

    fun parse(dataType: Int?, content: String?): MessageContent? {
        if (dataType == null || content.isNullOrBlank()) return null
        return when (dataType) {
            ChatProtocol.ChatEventMessageDataType -> parseEvent(content)
            ChatProtocol.ChatDiceRollMessageDataType -> parseDiceRoll(content)
            ChatProtocol.ChatGroodleMessageDataType -> parseGroodle(content)
            ChatProtocol.ChatPollMessageDataType -> parsePoll(content)
            // MessageAppData-shaped dataTypes — caller deserializes content
            // as MessageAppData. 0 = plain text/media; 211 = Location, whose
            // descriptor lives on a payload (the header content is still a
            // MessageAppData); 202 = status, but ChatMessageStream filters it
            // before reaching us — it's listed here so the Defragmenter probe
            // (which calls this parser too) doesn't false-positive on
            // historical status messages.
            // Location: NEW messages carry the LocationPreviewDescriptor verbatim in the header
            // (like Event) → MessageContent.Location. OLD messages have a MessageAppData header
            // (descriptor on the chat_loc payload) → parseLocation fails and returns null, so they
            // flow through the media path unchanged.
            ChatProtocol.ChatLocationMessageDataType -> parseLocation(content)
            0,
            ChatProtocol.ChatStatusMessageDataType -> null
            // Any other non-zero dataType is a typed kind a future version of
            // the app will know about. Surface it so the user sees a visible
            // "update the app" chip instead of a silently-dropped message.
            else -> MessageContent.Unknown(dataType)
        }
    }

    private fun parseEvent(content: String): MessageContent.Event = try {
        MessageContent.Event(OdinSystemSerializer.deserialize<EventDescriptor>(content))
    } catch (e: Exception) {
        Logger.w(tag = TAG, throwable = e) { "failed to parse Event descriptor; rendering unsupported-format chip" }
        MessageContent.Event(descriptor = null)
    }

    private fun parseDiceRoll(content: String): MessageContent.DiceRoll = try {
        val descriptor = OdinSystemSerializer.deserialize<DiceRollDescriptor>(content)
        if (descriptor.isValid()) {
            MessageContent.DiceRoll(descriptor)
        } else {
            // Include mode + first chain entry's results.size + sample so the
            // log says *why* validation failed, not just "it failed". Without
            // this the build-1420 mode=OE + faces=4 stale-closure bug was
            // indistinguishable from any other invalid descriptor.
            val first = descriptor.rolls.firstOrNull()
            val sample = first?.results?.take(4)?.joinToString(",") ?: "(none)"
            Logger.w(tag = TAG) {
                "DiceRoll descriptor failed validation; " +
                    "faces=${descriptor.faces} " +
                    "mode=${descriptor.mode} " +
                    "chainSize=${descriptor.rolls.size} " +
                    "firstResultsSize=${first?.results?.size ?: 0} " +
                    "firstResultsSample=[$sample]"
            }
            MessageContent.DiceRoll(descriptor = null)
        }
    } catch (e: Exception) {
        Logger.w(tag = TAG, throwable = e) { "failed to parse DiceRoll descriptor; rendering unsupported-format chip" }
        MessageContent.DiceRoll(descriptor = null)
    }

    private fun parseGroodle(content: String): MessageContent.Groodle = try {
        val descriptor = OdinSystemSerializer.deserialize<GroodleDescriptor>(content)
        if (descriptor.isValid()) {
            MessageContent.Groodle(descriptor)
        } else {
            Logger.w(tag = TAG) {
                "Groodle descriptor failed validation; " +
                    "slots=${descriptor.slots.size} " +
                    "titleLen=${descriptor.title.length} " +
                    "tz=${descriptor.timezone}"
            }
            MessageContent.Groodle(descriptor = null)
        }
    } catch (e: Exception) {
        Logger.w(tag = TAG, throwable = e) { "failed to parse Groodle descriptor; rendering unsupported-format chip" }
        MessageContent.Groodle(descriptor = null)
    }

    /**
     * NEW-format location: the header IS a [LocationPreviewDescriptor]. Returns null when the header
     * isn't a descriptor (old MessageAppData-shaped location messages) so the caller treats it as
     * MessageAppData and the bubble renders off the chat_loc payload — the back-compat path.
     */
    private fun parseLocation(content: String): MessageContent? = try {
        MessageContent.Location(OdinSystemSerializer.deserialize<LocationPreviewDescriptor>(content))
    } catch (_: Exception) {
        null
    }

    private fun parsePoll(content: String): MessageContent.Poll = try {
        val descriptor = OdinSystemSerializer.deserialize<PollDescriptor>(content)
        if (descriptor.isValid()) {
            MessageContent.Poll(descriptor)
        } else {
            Logger.w(tag = TAG) { "Poll descriptor failed validation" }
            MessageContent.Poll(null)
        }
    } catch (e: Exception) {
        Logger.w(tag = TAG, throwable = e) { "Poll parse failed; rendering chip" }
        MessageContent.Poll(null)
    }

    /**
     * Senders only ever construct a [MessageContent] subtype with a real
     * descriptor, so a null descriptor — or a [MessageContent.Unknown] kind —
     * here means a programming error rather than a malformed wire payload.
     */
    fun serialize(content: MessageContent): String = when (content) {
        is MessageContent.Event ->
            OdinSystemSerializer.serialize(
                requireNotNull(content.descriptor) { "Event descriptor must be non-null on send" }
            )
        is MessageContent.DiceRoll ->
            OdinSystemSerializer.serialize(
                requireNotNull(content.descriptor) { "DiceRoll descriptor must be non-null on send" }
            )
        is MessageContent.Groodle ->
            OdinSystemSerializer.serialize(
                requireNotNull(content.descriptor) { "Groodle descriptor must be non-null on send" }
            )
        is MessageContent.Poll ->
            OdinSystemSerializer.serialize(
                requireNotNull(content.descriptor) { "Poll descriptor must be non-null on send" }
            )
        is MessageContent.Location ->
            OdinSystemSerializer.serialize(
                requireNotNull(content.descriptor) { "Location descriptor must be non-null on send" }
            )
        is MessageContent.Unknown ->
            error("Unknown message kind (dataType=${content.dataType}) cannot be serialized")
    }

    fun dataTypeFor(content: MessageContent): Int = when (content) {
        is MessageContent.Event -> ChatProtocol.ChatEventMessageDataType
        is MessageContent.DiceRoll -> ChatProtocol.ChatDiceRollMessageDataType
        is MessageContent.Groodle -> ChatProtocol.ChatGroodleMessageDataType
        is MessageContent.Poll -> ChatProtocol.ChatPollMessageDataType
        is MessageContent.Location -> ChatProtocol.ChatLocationMessageDataType
        is MessageContent.Unknown ->
            error("Unknown message kind (dataType=${content.dataType}) cannot be re-sent")
    }

    /**
     * True for kinds whose descriptor is stored VERBATIM in `appData.content`
     * (Event/DiceRoll/Groodle/Poll — the kinds [parse] decodes straight from the
     * header), as opposed to the `MessageAppData` envelope used by plain text /
     * media / Location (the dataTypes [parse] returns null for).
     *
     * Editing such a message must write the descriptor JSON directly to the header.
     * Routing it through the envelope builder strips the descriptor's required
     * fields, so the next read fails to parse and renders the unsupported-format
     * chip. Used by [ChatMessageSenderService.updateMessage].
     */
    fun usesRawHeaderContent(content: MessageContent?): Boolean = when (content) {
        is MessageContent.Event,
        is MessageContent.DiceRoll,
        is MessageContent.Groodle,
        is MessageContent.Poll,
        is MessageContent.Location -> true
        is MessageContent.Unknown, null -> false
    }
}
