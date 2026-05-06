package id.homebase.api.client.connections

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Status of an introduction-preflight check for a single recipient. The server
 * uses sparse integer values (1..7, 99) AND emits the lowercase enum name —
 * production responses currently look like `"status": "ready"`. The serializer
 * accepts either form (int OR string-by-name, case-insensitive) and falls back
 * to [UnknownError] so client code never has to handle nulls or schema drift.
 */
@Serializable(with = IntroductionPreflightStatusSerializer::class)
enum class IntroductionPreflightStatus(val value: Int) {
    /** Good to send. */
    Ready(1),

    /** Local Identity Connection Record (ICR) for this recipient is missing or
     *  invalid; we never even tried the peer call. */
    NotConnected(2),

    /** Recipient's identity server hasn't completed initial setup. */
    RecipientNotConfigured(3),

    /** Recipient's identity server needs a version upgrade before it will
     *  accept introductions. */
    RecipientRequiresUpgrade(4),

    /** Recipient hasn't granted us the AllowIntroductions permission (revoked
     *  the system circle that carries it). */
    IntroductionsNotPermitted(5),

    /** Peer call returned 403. */
    RecipientRejected(6),

    /** DNS / socket / timeout / non-success status from the recipient. */
    Unreachable(7),

    /** Unexpected error; [RecipientPreflightStatus.detail] is populated. */
    UnknownError(99);

    companion object {
        fun fromValue(value: Int): IntroductionPreflightStatus =
            entries.firstOrNull { it.value == value } ?: UnknownError

        fun fromName(name: String): IntroductionPreflightStatus =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: UnknownError
    }
}

internal object IntroductionPreflightStatusSerializer : KSerializer<IntroductionPreflightStatus> {
    // Use a class-shaped descriptor (rather than a primitive) so kotlinx.serialization
    // hands us a JsonDecoder we can branch on int-vs-string, instead of forcing one
    // primitive kind up front.
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("IntroductionPreflightStatus")

    override fun serialize(encoder: Encoder, value: IntroductionPreflightStatus) {
        // Emit the int form on send. The server currently returns string but the
        // request body shape isn't relevant here (preflight requests don't carry
        // a status field — only responses do).
        encoder.encodeInt(value.value)
    }

    override fun deserialize(decoder: Decoder): IntroductionPreflightStatus {
        // Accept BOTH int (e.g. `1`) and string (e.g. `"ready"`, `"Ready"`,
        // `"READY"`) so we tolerate either historical or future server shape.
        val jsonDecoder = decoder as? JsonDecoder
            ?: error("IntroductionPreflightStatus requires a JSON decoder")
        val element = jsonDecoder.decodeJsonElement()
        val primitive = element as? JsonPrimitive
            ?: return IntroductionPreflightStatus.UnknownError
        primitive.intOrNull?.let {
            return IntroductionPreflightStatus.fromValue(it)
        }
        return IntroductionPreflightStatus.fromName(primitive.content)
    }
}
