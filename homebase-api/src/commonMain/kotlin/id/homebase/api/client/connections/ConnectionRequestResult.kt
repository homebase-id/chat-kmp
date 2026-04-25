package id.homebase.api.client.connections

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

@Serializable
data class ConnectionRequestResult(
    val outcome: AutoConnectOutcome,
    val detail: String? = null,
)

@Serializable(with = AutoConnectOutcomeSerializer::class)
enum class AutoConnectOutcome(val code: Int) {
    Connected(1),
    AcceptedFromExistingIncoming(2),
    PendingManualApproval(3),
    AlreadyConnected(4),
    Blocked(5),
    OutgoingRequestAlreadyExists(6),
    DuplicateIntroductoryRequest(7),
    RecipientUnreachable(8),
    RecipientRejected(9),
    InvalidRequest(10),
    RecipientIdentityNotConfigured(11),
    RecipientRequiresUpgrade(12),
    Failed(99),

    // Sentinel for forward-compatibility — any server-side enum value we don't
    // recognize decodes to this so callers can treat it as a generic failure.
    Unknown(-1);

    companion object {
        fun fromCode(code: Int): AutoConnectOutcome =
            entries.firstOrNull { it.code == code } ?: Unknown

        fun fromName(name: String): AutoConnectOutcome =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: Unknown
    }
}

internal object AutoConnectOutcomeSerializer : KSerializer<AutoConnectOutcome> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("AutoConnectOutcome", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: AutoConnectOutcome) {
        encoder.encodeInt(value.code)
    }

    override fun deserialize(decoder: Decoder): AutoConnectOutcome {
        // The API contract says the server sends integers, but the current .NET serializer
        // emits camelCase strings (e.g. "outgoingRequestAlreadyExists"). Handle both so we
        // aren't coupled to whichever side flips first.
        val jsonDecoder = decoder as? JsonDecoder
            ?: return AutoConnectOutcome.fromCode(decoder.decodeInt())
        val element = jsonDecoder.decodeJsonElement() as? JsonPrimitive
            ?: return AutoConnectOutcome.Unknown
        return when {
            element.isString -> AutoConnectOutcome.fromName(element.content)
            else -> element.intOrNull?.let(AutoConnectOutcome::fromCode)
                ?: AutoConnectOutcome.Unknown
        }
    }
}
