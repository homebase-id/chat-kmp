package id.homebase.api.media

import co.touchlab.kermit.Logger
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.image.ImageMetadata
import id.homebase.api.image.readImageMetadata
import id.homebase.api.video.locationInMoov
import id.homebase.api.video.readMoov
import kotlinx.coroutines.CancellationException

internal const val TAG = "MediaMetadata"

/**
 * What a photo or video file says about itself, read the same way on every platform: an image's EXIF, and the
 * location a camera recorded in an MP4 or QuickTime video (Apple's ISO 6709 key, the `©xyz` atom Android writes, or
 * the 3GPP `loci` box AVAssetWriter writes). Only the parts that hold it are read: an image's header where the
 * platform allows, a video's box headers and its `moov`, never the media.
 *
 * Null, or empty, when the file has none, can't be read or isn't a format this knows; only cancellation and errors
 * such as running out of memory are thrown.
 * The web has no EXIF reader of its own: a JPEG there reads its camera fields, capture time and orientation only
 * (see [readImageMetadata]), and any other image as null.
 */
class MediaMetadataReader(private val fileOps: FileOperationsProvider) {

    suspend fun image(path: String): ImageMetadata? = softly("image metadata of $path") { readImageMetadata(path, fileOps) }

    suspend fun videoLocation(path: String): MediaLocation? = softly("video location of $path") { readMoov(fileOps, path)?.let(::locationInMoov) }
}

/**
 * [read], or null, logged, when it throws an exception: an unreadable file has no metadata. An error such as running
 * out of memory isn't caught, so a photo that should be stripped can't go out unstripped because it was too big to read.
 */
internal inline fun <T> softly(what: String, read: () -> T?): T? = try {
    read()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Logger.w(tag = TAG, throwable = e) { "No readable $what" }
    null
}
