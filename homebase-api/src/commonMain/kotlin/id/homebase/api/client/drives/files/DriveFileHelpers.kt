package id.homebase.api.client.drives.files

import id.homebase.api.serialization.OdinSystemSerializer
import kotlin.math.abs

/**
 * Result of calculating a byte range header for partial content requests. Used when requesting
 * encrypted content that requires 16-byte aligned boundaries.
 */
data class RangeHeaderResult(
        /** Offset to skip in decrypted content to get to the actual requested start position */
        val startOffset: Int,
        /** Adjusted chunk start (rounded down to 16-byte boundary) */
        val updatedChunkStart: Long?,
        /** Adjusted chunk end (rounded up to 16-byte boundary, minus 1 for inclusive range) */
        val updatedChunkEnd: Long?,
        /** The HTTP Range header value, e.g., "bytes=0-1023" */
        val rangeHeader: String?
)

/** Helper functions for drive file operations. Ported from JS/TS odin-js DriveFileHelper. */
object DriveFileHelpers {

    /**
     * Calculates the Range header for partial content requests with encrypted data.
     *
     * When requesting encrypted content, the range must be aligned to 16-byte boundaries (AES block
     * size). This function:
     * - Rounds the chunk start down to the nearest 16-byte boundary (minus 16 for IV/padding)
     * - Rounds the chunk end up to the nearest 16-byte boundary
     * - Calculates the offset to skip in decrypted content
     *
     * @param chunkStart Optional start byte of the requested range
     * @param chunkLength Optional end byte of the requested range
     * @return RangeHeaderResult containing adjusted range and offset information
     */
    fun getRangeHeader(chunkStart: Long? = null, chunkLength: Long? = null): RangeHeaderResult {
        if (chunkStart == null) {
            return RangeHeaderResult(
                startOffset = 0,
                updatedChunkStart = null,
                updatedChunkEnd = null,
                rangeHeader = null
            )
        }

        val updatedChunkStart =
                if (chunkStart == 0L) {
                    0L
                } else {
                    roundToSmallerMultipleOf16(chunkStart - 16)
                }

        val startOffset = abs(chunkStart - updatedChunkStart).toInt()

        // End of range is inclusive, so we need to subtract 1
        val updatedChunkEnd =
                if (chunkLength != null) {
                    roundToLargerMultipleOf16(chunkLength) - 1
                } else {
                    null
                }

        val rangeHeader =
                if (updatedChunkEnd != null) {
                    "bytes=$updatedChunkStart-$updatedChunkEnd"
                } else {
                    "bytes=$updatedChunkStart-"
                }

        return RangeHeaderResult(
            startOffset = startOffset,
            updatedChunkStart = updatedChunkStart,
            updatedChunkEnd = updatedChunkEnd,
            rangeHeader = rangeHeader
        )
    }

    /**
     * Rounds a value down to the nearest multiple of 16. Used for AES block alignment.
     *
     * @param value The value to round
     * @return The value rounded down to the nearest multiple of 16
     */
    fun roundToSmallerMultipleOf16(value: Long): Long {
        return (value / 16) * 16
    }

    /**
     * Rounds a value up to the nearest multiple of 16. Used for AES block alignment.
     *
     * @param value The value to round
     * @return The value rounded up to the nearest multiple of 16
     */
    fun roundToLargerMultipleOf16(value: Long): Long {
        return ((value + 15) / 16) * 16
    }

    /**
     * Parses a byte array to a typed object by deserializing as JSON. Ported from TypeScript
     * parseBytesToObject function.
     *
     * @param data The data containing bytes and content type, or null
     * @return The parsed object of type T, or null if data is null or parsing fails
     */
    inline fun <reified T> parseBytesToObject(data: BytesWithContentType?): T? {
        if (data == null) return null
        return tryJsonParse<T>(data.bytes.decodeToString())
    }

    /**
     * Attempts to parse a JSON string into the specified type. Returns null if parsing fails
     * instead of throwing an exception.
     *
     * @param json The JSON string to parse
     * @return The parsed object of type T, or null if parsing fails
     */
    inline fun <reified T> tryJsonParse(json: String): T? {
        return try {
OdinSystemSerializer.json
                    .decodeFromString<T>(json)
        } catch (e: Exception) {
            null
        }
    }
}

/** Container for bytes with their associated content type. Used for parsing response data. */
data class BytesWithContentType(val bytes: ByteArray, val contentType: String) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as BytesWithContentType

        if (!bytes.contentEquals(other.bytes)) return false
        if (contentType != other.contentType) return false

        return true
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + contentType.hashCode()
        return result
    }
}
