package id.homebase.api.client.drives.files

import id.homebase.api.serialization.Base64Bytes
import id.homebase.api.serialization.LenientBase64BytesSerializer
import androidx.compose.runtime.Immutable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.jsonPrimitive

/**
 * odin-core `docs/payload-hashes.md`; both hashes are base64, like [PayloadDescriptor.iv].
 * [algorithm] stays the raw wire value so a header with a future algorithm still saves back unchanged.
 */
@Serializable
@Immutable
data class PayloadHash(
    @Serializable(with = PayloadHashAlgorithmNameSerializer::class) val algorithm: String,
    @Serializable(with = LenientBase64BytesSerializer::class) val storedHash: Base64Bytes? = null,
    @Serializable(with = LenientBase64BytesSerializer::class) val contentHash: Base64Bytes? = null,
) {
    val knownAlgorithm: PayloadHashAlgorithm? get() = PayloadHashAlgorithm.fromWire(algorithm)
}

enum class PayloadHashAlgorithm(val wireName: String, private val wireNumber: Int) {
    Sha256("sha256", 1),
    Blake3("blake3", 2);

    companion object {
        fun fromWire(value: String): PayloadHashAlgorithm? =
            entries.firstOrNull { it.wireName.equals(value, ignoreCase = true) || it.wireNumber.toString() == value }
    }
}

/** odin-core also reads the algorithm as 1 / 2; a number is kept as its digits and sent back by name. */
internal object PayloadHashAlgorithmNameSerializer : KSerializer<String> {
    override val descriptor = PrimitiveSerialDescriptor("PayloadHashAlgorithm", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: String) =
        encoder.encodeString(PayloadHashAlgorithm.fromWire(value)?.wireName ?: value)

    override fun deserialize(decoder: Decoder): String =
        (decoder as? JsonDecoder)?.decodeJsonElement()?.jsonPrimitive?.content ?: decoder.decodeString()
}
