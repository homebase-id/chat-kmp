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

/** A route's file system, as a query string to append to its path; Standard without it. */
fun FileSystemType?.asQuery(): String = this?.let { "?xfst=$it" }.orEmpty()

/** A route's file system, as the header a JSON route reads it from; Standard without it. */
fun FileSystemType?.asHeader(): Map<String, String> = this?.let { mapOf("X-ODIN-FILE-SYSTEM-TYPE" to it.name) }.orEmpty()