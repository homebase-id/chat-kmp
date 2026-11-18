package id.homebase.api.media

import co.touchlab.kermit.Logger
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.file.contentTypeFromBytes
import id.homebase.api.image.ImageMetadataScrubber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "MediaMetadata"

/**
 * The other half of [MediaMetadataReader]: takes a photo's EXIF, XMP and IPTC out without a re-encode, the same way
 * on every platform, keeping its orientation, colour profile and HDR gain map. JPEG, PNG, WebP and HEIF are handled;
 * anything else, or a file that can't be parsed, goes out as it is, logged. A video is stripped by its transcode.
 */
class MediaMetadataStripper(private val fileOps: FileOperationsProvider) {

    /** The image at [path] as it should be sent, read once; null when it isn't an image or can't be read. */
    suspend fun image(path: String, contentType: String): ByteArray? {
        if (!contentType.startsWith("image/")) return null
        val original = softly("image at $path, so its metadata isn't stripped") { fileOps.readFileBytes(path) } ?: return null
        return imageBytes(original, contentType)
    }

    companion object {
        /** [bytes] without their metadata when they're an image that has some, else [bytes] themselves. */
        suspend fun imageBytes(bytes: ByteArray, contentType: String): ByteArray {
            if (!contentType.startsWith("image/")) return bytes
            val why = when (val outcome = withContext(Dispatchers.Default) { ImageMetadataScrubber.scrubWithOutcome(bytes) }) {
                is ImageMetadataScrubber.Outcome.Scrubbed -> return outcome.bytes
                ImageMetadataScrubber.Outcome.NothingToStrip -> return bytes
                ImageMetadataScrubber.Outcome.UnsupportedFormat -> "isn't a format the scrubber handles"
                ImageMetadataScrubber.Outcome.Unparseable -> "couldn't be parsed"
            }
            Logger.w(tag = TAG) {
                "metadata scrub skipped — $contentType (sniffed ${contentTypeFromBytes(bytes)}) $why; any EXIF/XMP goes out intact"
            }
            return bytes
        }
    }
}
