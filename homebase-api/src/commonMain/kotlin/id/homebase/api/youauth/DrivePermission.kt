package id.homebase.api.youauth

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder


/**
 * Bitwise permission flags for drive access. Multiple permissions can be combined by summing their
 * values.
 */
enum class DrivePermission(val value: Int) {
    Read(1),
    Write(2),
    React(4),
    Comment(8);

    companion object {
        /** Parse a combined permission value into a list of permission types. */
        fun fromValue(combinedValue: Int): List<DrivePermission> {
            return entries.filter { (combinedValue and it.value) == it.value }
        }

        /** The server's comma-separated names; it writes a value with a name of its own by that name (15 as readWrite). */
        fun fromNames(names: String): List<DrivePermission> =
            names.split(",").map { it.trim().lowercase() }.flatMap { name ->
                combinedNames[name] ?: listOfNotNull(entries.find { it.name.lowercase() == name })
            }.distinct()

        private val combinedNames = mapOf(
            "readwrite" to entries,
            "all" to entries,
            "writereactionsandcomments" to listOf(React, Comment),
        )

        /** Combine multiple permissions into a single integer value. */
        fun combine(permissions: List<DrivePermission>): Int {
            return permissions.sumOf { it.value }
        }
    }
}

@Serializable(with = DrivePermissionsSerializer::class)
data class DrivePermissionSet(
    val values: List<DrivePermission>
)

object DrivePermissionsSerializer : KSerializer<DrivePermissionSet> {

    override val descriptor =
        PrimitiveSerialDescriptor("DrivePermissions", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): DrivePermissionSet {
        val raw = decoder.decodeString()

        return DrivePermissionSet(DrivePermission.fromNames(raw))
    }

    override fun serialize(encoder: Encoder, value: DrivePermissionSet) {
        val joined = value.values.joinToString(", ") { it.name.lowercase() }
        encoder.encodeString(joined)
    }
}