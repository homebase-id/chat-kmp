package id.homebase.api.client.drives

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * FileSystemType enumeration
 * Ported from C# Odin.Core.Storage.FileSystemType
 */
@Serializable
enum class FileSystemType(val value: Int) {
    @SerialName("standard")
    Standard(0),

    @SerialName("comment")
    Comment(1);
    // Add more as needed from the C# enum

    companion object {
        fun fromInt(value: Int): FileSystemType {
            return entries.firstOrNull { it.value == value }
                ?: throw IllegalArgumentException("Unknown FileSystemType: $value")
        }
    }
}