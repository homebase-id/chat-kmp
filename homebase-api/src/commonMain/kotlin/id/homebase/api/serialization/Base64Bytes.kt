@file:OptIn(ExperimentalEncodingApi::class)

package id.homebase.api.serialization

import androidx.compose.runtime.Immutable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Bytes that travel as a standard (padded) Base64 string in JSON. Never mutate [bytes]. */
@Immutable
@Serializable(with = Base64BytesSerializer::class)
class Base64Bytes(val bytes: ByteArray) {
    fun toBase64(): String = Base64.Default.encode(bytes)

    override fun equals(other: Any?): Boolean = other is Base64Bytes && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "Base64Bytes(${bytes.size} bytes)"
}

object Base64BytesSerializer : KSerializer<Base64Bytes> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Base64Bytes", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Base64Bytes) =
        encoder.encodeString(value.toBase64())

    override fun deserialize(decoder: Decoder): Base64Bytes =
        Base64Bytes(Base64.Default.decode(decoder.decodeString()))
}

/** Empty or undecodable input becomes null so one bad field cannot fail a whole file header. */
object LenientBase64BytesSerializer : KSerializer<Base64Bytes?> {
    override val descriptor: SerialDescriptor = Base64BytesSerializer.nullable.descriptor

    override fun serialize(encoder: Encoder, value: Base64Bytes?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(value.toBase64())
    }

    override fun deserialize(decoder: Decoder): Base64Bytes? {
        if (!decoder.decodeNotNullMark()) return decoder.decodeNull()
        val text = decoder.decodeString()
        if (text.isEmpty()) return null
        return try {
            Base64Bytes(Base64.Default.decode(text))
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
