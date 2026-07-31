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
 * uses sparse integer values (1..17, 99) AND emits the lowercase enum name —
 * production responses currently look like `"status": "ready"`. The serializer
 * accepts either form (int OR string-by-name, case-insensitive) and falls back
 * to [UnknownError] so client code never has to handle nulls or schema drift.
 *
 * Only [IntroductionsNotPermitted] represents a *decision* by the recipient.
 * Every other non-Ready value is a setup gap, a broken connection record, or a
 * transport failure — the UI copy must not present them as a refusal. See
 * `chat_introduce_preflight_reason_*` in strings.xml.
 */
@Serializable(with = IntroductionPreflightStatusSerializer::class)
enum class IntroductionPreflightStatus(val value: Int) {
    /** Good to send. */
    Ready(1),

    /** Local Identity Connection Record (ICR) for this recipient is missing or
     *  invalid; we never even tried the peer call. */
    NotConnected(2),

    /** Recipient's identity server hasn't completed initial setup — they
     *  registered but never logged in to finish provisioning. */
    RecipientNotConfigured(3),

    /** Recipient's identity server needs a version upgrade before it will
     *  accept introductions, and is not currently running. */
    RecipientRequiresUpgrade(4),

    /** Recipient is connected, has confirmed us, and has deliberately not
     *  granted the AllowIntroductions permission. THE ONLY status that reflects
     *  a decision by the recipient — the only one that may render as
     *  "<name> doesn't allow introductions from you". */
    IntroductionsNotPermitted(5),

    /** Peer call returned 403. */
    RecipientRejected(6),

    /** Transport failure that could not be classified any further. */
    Unreachable(7),

    /** Recipient has a connection to us that they have never confirmed.
     *  Nothing is broken and nothing was denied — confirming is a step they
     *  take in their owner console. Historically the biggest contributor to the
     *  bogus "does not allow introductions" message. */
    RecipientConnectionNotConfirmed(8),

    /** Recipient has no usable connection record for us; the connection is
     *  one-sided. Also covers the blocked case — deliberately indistinguishable
     *  from a stale record, since disclosing a block would leak it to its
     *  target. Remedy is to reconnect. */
    RecipientDoesNotRecognizeConnection(9),

    /** Recipient has a connection record for us but it is damaged and unusable.
     *  Repairable by reconnecting. */
    RecipientConnectionNeedsRepair(10),

    /** OUR OWN connection record is present but unusable. The fault is on our
     *  side; copy must not blame the recipient. */
    SenderConnectionInvalid(11),

    /** Recipient runs a server too old to answer preflight, so nothing can be
     *  determined. "Send anyway" is the meaningful offer. */
    PreflightNotSupported(12),

    /** Recipient's server is mid-upgrade. Retry shortly. */
    RecipientUpgradeInProgress(13),

    /** Recipient's domain could not be resolved. Probably permanent. */
    RecipientUnresolvable(14),

    /** TLS/certificate failure — the recipient's server is misconfigured. */
    RecipientCertificateInvalid(15),

    /** Recipient did not respond in time. */
    RecipientTimedOut(16),

    /** Recipient's host refused the socket; their server is down. */
    RecipientConnectionRefused(17),

    /** Unexpected error; [RecipientPreflightStatus.detail] is populated (logs only). */
    UnknownError(99);

    /**
     * Who can actually do something about this status, used when the server
     * omits `remedyActor` (older server, or a value we don't recognize). The
     * UI switches its *affordances* on this — never on the status itself.
     */
    val defaultRemedyActor: PreflightRemedyActor
        get() = when (this) {
            NotConnected,
            RecipientDoesNotRecognizeConnection,
            RecipientConnectionNeedsRepair,
            SenderConnectionInvalid -> PreflightRemedyActor.Caller

            RecipientNotConfigured,
            RecipientRequiresUpgrade,
            IntroductionsNotPermitted,
            RecipientRejected,
            RecipientConnectionNotConfirmed,
            RecipientUnresolvable,
            RecipientCertificateInvalid -> PreflightRemedyActor.Recipient

            Ready,
            Unreachable,
            PreflightNotSupported,
            RecipientUpgradeInProgress,
            RecipientTimedOut,
            RecipientConnectionRefused,
            UnknownError -> PreflightRemedyActor.None
        }

    /**
     * Whether re-running preflight unchanged is worth doing, used when the
     * server omits `isTransient`. Only genuine "the network/server was busy"
     * failures qualify — a missing permission or an unconfirmed connection
     * needs a human to act first, so offering retry there is a lie.
     */
    val defaultIsTransient: Boolean
        get() = when (this) {
            Unreachable,
            RecipientUpgradeInProgress,
            RecipientTimedOut,
            RecipientConnectionRefused -> true

            else -> false
        }

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
