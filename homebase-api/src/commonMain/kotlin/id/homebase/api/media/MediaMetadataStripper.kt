package id.homebase.api.media

import co.touchlab.kermit.Logger
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.file.contentTypeFromBytes
import id.homebase.api.image.ImageMetadataScrubber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The other half of [MediaMetadataReader]: takes out what a photo says about who took it, where and with what,
 * the same way on every platform. EXIF, XMP and IPTC go without a re-encode, so the picture stays bit-identical;
 * the orientation, colour profile and HDR gain map stay. JPEG, PNG, WebP and HEIF are handled.
 *
 * A video has nothing to strip in place: an app that strips has every video transcoded with its metadata dropped
 * (`VideoCompressor.compress`, `keepMetadata = false`), and a video sent untouched is only for an app that keeps it.
 *
 * The file is read once and its bytes to send come back, stripped or not, so the caller sends them without
 * reading the file again. Fail-soft: a format this doesn't handle or can't parse goes out as it is, logged.
 */
class MediaMetadataStripper(private val fileOps: FileOperationsProvider) {

    /** The image at [path] as it should be sent; null when it isn't an image or can't be read. */
    suspend fun image(path: String, contentType: String): ByteArray? {
        if (!contentType.startsWith("image/")) return null
        val original = try {
            fileOps.readFileBytes(path)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Logger.w(throwable = t, tag = TAG) { "metadata scrub skipped — could not read $path" }
            return null
        }
        return imageBytes(original, contentType)
    }

    companion object {
        private const val TAG = "MediaMetadataStripper"

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
