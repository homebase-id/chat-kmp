package id.homebase.api.media

import co.touchlab.kermit.Logger
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.image.ImageMetadata
import id.homebase.api.image.readImageMetadata
import id.homebase.api.video.locationInMoov
import id.homebase.api.video.readMoov
import kotlinx.coroutines.CancellationException

private const val TAG = "MediaMetadata"

/**
 * What a photo or video file says about itself, read the same way on every platform: an image's EXIF, and the
 * location a camera recorded in an MP4 or QuickTime video (Apple's ISO 6709 key, or the `©xyz` atom Android
 * writes). Only the parts that hold it are read: an image's header where the platform allows, a video's box
 * headers and its `moov`, never the media.
 *
 * Null when the file has none, can't be read or isn't a format this knows; nothing but cancellation is thrown.
 * The web has no EXIF reader, so an image there reads as null.
 */
class MediaMetadataReader(private val fileOps: FileOperationsProvider) {

    suspend fun image(path: String): ImageMetadata? = softly("image metadata of $path") { readImageMetadata(path, fileOps) }

    suspend fun videoLocation(path: String): MediaLocation? = softly("video location of $path") { readMoov(fileOps, path)?.let(::locationInMoov) }
}

/** [read], or null, logged, when it throws anything but cancellation: metadata is never worth failing a send. */
internal inline fun <T> softly(what: String, read: () -> T?): T? = try {
    read()
} catch (e: CancellationException) {
    throw e
} catch (t: Throwable) {
    Logger.w(tag = TAG, throwable = t) { "No readable $what" }
    null
}
