package id.homebase.api.client.connections

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Who can resolve a non-Ready [IntroductionPreflightStatus].
 *
 * The UI picks its *affordances* from this (and from
 * [RecipientPreflightStatus.canRetry]) rather than from the status value, so a
 * new server-side status lands with sane buttons even before we have bespoke
 * copy for it.
 */
@Serializable(with = PreflightRemedyActorSerializer::class)
enum class PreflightRemedyActor(val value: Int) {
    /** Nobody can act — it either resolves itself or it doesn't. */
    None(0),

    /** We can act: connect, reconnect, or repair our own connection record. */
    Caller(1),

    /** Only the recipient can act. The copy tells the user what to ask for. */
    Recipient(2);

    companion object {
        fun fromValue(value: Int): PreflightRemedyActor =
            entries.firstOrNull { it.value == value } ?: None

        fun fromName(name: String): PreflightRemedyActor =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: None
    }
}

/**
 * State of the *caller's* connection as the recipient's server sees it. Pure
 * diagnostics — it disambiguates statuses 5 / 8 / 9 / 10 in logs and support
 * threads. Never drive user-facing copy off this; use the status.
 */
@Serializable(with = CallerConnectionStateSerializer::class)
enum class CallerConnectionState(val value: Int) {
    Unknown(0),
    Connected(1),
    NotRecognized(2),
    NeedsRepair(3);

    companion object {
        fun fromValue(value: Int): CallerConnectionState =
            entries.firstOrNull { it.value == value } ?: Unknown

        fun fromName(name: String): CallerConnectionState =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: Unknown
    }
}

/**
 * Shared int-or-name tolerant decoding, mirroring
 * [IntroductionPreflightStatusSerializer]: the server may emit either the
 * integer or the (case-insensitive) enum name, and we never want a schema
 * change to fail the whole preflight response.
 */
internal abstract class IntOrNameSerializer<T : Enum<T>>(
    serialName: String,
    private val fallback: T,
    private val valueOf: (T) -> Int,
    private val fromValue: (Int) -> T,
    private val fromName: (String) -> T,
) : KSerializer<T> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(serialName)

    override fun serialize(encoder: Encoder, value: T) = encoder.encodeInt(valueOf(value))

    override fun deserialize(decoder: Decoder): T {
        val jsonDecoder = decoder as? JsonDecoder
            ?: error("$descriptor requires a JSON decoder")
        val primitive = jsonDecoder.decodeJsonElement() as? JsonPrimitive ?: return fallback
        primitive.intOrNull?.let { return fromValue(it) }
        return fromName(primitive.content)
    }
}

internal object PreflightRemedyActorSerializer : IntOrNameSerializer<PreflightRemedyActor>(
    serialName = "PreflightRemedyActor",
    fallback = PreflightRemedyActor.None,
    valueOf = { it.value },
    fromValue = PreflightRemedyActor::fromValue,
    fromName = PreflightRemedyActor::fromName,
)

internal object CallerConnectionStateSerializer : IntOrNameSerializer<CallerConnectionState>(
    serialName = "CallerConnectionState",
    fallback = CallerConnectionState.Unknown,
    valueOf = { it.value },
    fromValue = CallerConnectionState::fromValue,
    fromName = CallerConnectionState::fromName,
)
