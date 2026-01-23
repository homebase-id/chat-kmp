package id.homebase.homebasekmppoc.prototype.lib.drives.files

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Archival status for files */
@Serializable(with = _root_ide_package_.id.homebase.homebasekmppoc.prototype.lib.drives.files.ArchivalStatusSerializer::class)
enum class ArchivalStatus(val value: Int) {
    None(0),
    Archived(1),
    Removed(2);

    companion object {
        fun fromInt(value: Int): id.homebase.homebasekmppoc.prototype.lib.drives.files.ArchivalStatus {
            return entries.firstOrNull { it.value == value } ?: None
        }
    }
}


/** Custom serializer for ArchivalStatus that handles integer input (0, 1, 2) */
object ArchivalStatusSerializer : KSerializer<id.homebase.homebasekmppoc.prototype.lib.drives.files.ArchivalStatus> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ArchivalStatus", PrimitiveKind.INT)

    override fun serialize(encoder: Encoder, value: id.homebase.homebasekmppoc.prototype.lib.drives.files.ArchivalStatus) {
        encoder.encodeInt(value.value)
    }

    override fun deserialize(decoder: Decoder): id.homebase.homebasekmppoc.prototype.lib.drives.files.ArchivalStatus {
        val intValue = decoder.decodeInt()
        return _root_ide_package_.id.homebase.homebasekmppoc.prototype.lib.drives.files.ArchivalStatus.Companion.fromInt(intValue)
    }
}