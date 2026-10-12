package id.homebase.api.client.drives.files

import androidx.compose.runtime.Immutable
import id.homebase.api.client.connections.IntOrNameSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encoding.Encoder

/** odin-core `docs/payload-hashes.md`; both hashes are base64, like [PayloadDescriptor.iv]. */
@Serializable
@Immutable
data class PayloadHash(
    val algorithm: PayloadHashAlgorithm,
    val storedHash: String,
    val contentHash: String,
)

/** [Unknown] keeps a future algorithm from breaking the parse of a whole file header. */
@Serializable(with = PayloadHashAlgorithmSerializer::class)
enum class PayloadHashAlgorithm(val value: Int) {
    Sha256(1),
    Blake3(2),
    Unknown(0);

    val wireName: String get() = name.lowercase()

    companion object {
        fun fromValue(value: Int): PayloadHashAlgorithm =
            entries.firstOrNull { it.value == value && it != Unknown } ?: Unknown

        fun fromName(name: String): PayloadHashAlgorithm =
            entries.firstOrNull { it.wireName == name.lowercase() && it != Unknown } ?: Unknown
    }
}

internal object PayloadHashAlgorithmSerializer : IntOrNameSerializer<PayloadHashAlgorithm>(
    serialName = "PayloadHashAlgorithm",
    fallback = PayloadHashAlgorithm.Unknown,
    valueOf = { it.value },
    fromValue = PayloadHashAlgorithm::fromValue,
    fromName = PayloadHashAlgorithm::fromName,
) {
    override fun serialize(encoder: Encoder, value: PayloadHashAlgorithm) {
        require(value != PayloadHashAlgorithm.Unknown) { "An unknown payload hash algorithm can't be sent" }
        encoder.encodeString(value.wireName)
    }
}
