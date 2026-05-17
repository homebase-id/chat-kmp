// No-op MP4 metadata sanitizer hook. Returns inputs untouched.
// Replace with a real EXIF/location-stripping implementation if desired —
// see Mp4FaststartPostProcessor for the call sites.
package id.homebase.api.video.transcoder.stub

import java.io.InputStream

internal object Mp4Sanitizer {
    @Suppress("UNUSED_PARAMETER")
    @JvmStatic
    fun sanitize(input: InputStream, length: Long): SanitizedMetadata =
        SanitizedMetadata(sanitizedMetadata = null, dataOffset = 0L, dataLength = length)

    @Suppress("UNUSED_PARAMETER")
    @JvmStatic
    fun sanitizeFileWithCompoundedMdatBoxes(
        input: InputStream,
        inputLength: Long,
        mdatLength: Int,
    ): SanitizedMetadata =
        SanitizedMetadata(sanitizedMetadata = null, dataOffset = 0L, dataLength = inputLength)
}

/**
 * A null [sanitizedMetadata] is the contract for "input was not sanitized;
 * callers should pass the original stream through untouched."
 */
internal class SanitizedMetadata(
    val sanitizedMetadata: ByteArray?,
    val dataOffset: Long,
    val dataLength: Long,
)
