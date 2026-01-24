package id.homebase.api.client.drives.files

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Archival status for files */
@Serializable(with = ArchivalStatusSerializer::class)
enum class ArchivalStatus(val value: Int) {
    None(0),
    Archived(1),
    Removed(2);

    companion object {
        fun fromInt(value: Int): ArchivalStatus {
            return entries.firstOrNull { it.value == value } ?: None
        }
    }
}


/** Custom serializer for ArchivalStatus that handles integer input (0, 1, 2) */
object ArchivalStatusSerializer : KSerializer<ArchivalStatus> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ArchivalStatus", PrimitiveKind.INT)

    override fun serialize(encoder: Encoder, value: ArchivalStatus) {
        encoder.encodeInt(value.value)
    }

    override fun deserialize(decoder: Decoder): ArchivalStatus {
        val intValue = decoder.decodeInt()
        return ArchivalStatus.Companion.fromInt(intValue)
    }
}