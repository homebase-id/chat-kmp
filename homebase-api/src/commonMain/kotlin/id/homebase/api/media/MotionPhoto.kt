package id.homebase.api.media

import co.touchlab.kermit.Logger
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.file.FileRanges
import id.homebase.api.image.ImageMetadataScrubber
import id.homebase.api.image.xmpPrefixes
import id.homebase.api.image.xmpValues
import kotlinx.coroutines.flow.flow

/**
 * An Android motion photo: a JPEG or HEIC with its clip, an MP4, appended. The file's XMP declares it, in Google's
 * Motion Photo format (`Camera:MotionPhoto` and a `Container:Directory` whose `MotionPhoto` item gives the clip's
 * length) or the older `MicroVideo` one (`MicroVideoOffset`, the clip's length). Either way the clip is counted from
 * the end of the file (THUMBNAILS.md, "Live and motion photos").
 */
object MotionPhoto {
    /** Where [path]'s clip lies, or null when it isn't a motion photo or the clip isn't where its XMP says. */
    suspend fun clipRange(path: String, fileOps: FileOperationsProvider): LongRange? =
        fileOps.openRanges(path).use { clipRange(path, it) }

    /** [path]'s clip copied to an upload-temp MP4 the caller deletes, or null when [path] has none. */
    suspend fun extractClip(path: String, fileOps: FileOperationsProvider): String? = fileOps.openRanges(path).use { ranges ->
        val range = clipRange(path, ranges) ?: return null
        val out = fileOps.createUploadTempPath("motion", ".mp4")
        try {
            fileOps.writeStream(out, flow {
                var at = range.first
                while (at <= range.last) {
                    val length = minOf(CHUNK.toLong(), range.last - at + 1).toInt()
                    emit(ranges.read(at, length))
                    at += length
                }
            })
        } catch (e: Throwable) {
            fileOps.deleteTempFile(out)
            throw e
        }
        out
    }

    private suspend fun clipRange(path: String, ranges: FileRanges): LongRange? {
        val size = ranges.size()
        val header = ranges.read(0, minOf(HEADER_BYTES.toLong(), size).toInt())
        val xml = runCatching { ImageMetadataScrubber.readBlocks(header)?.xmp?.decodeToString() }.getOrNull() ?: return null
        val tail = tailOf(xml) ?: return null
        if (tail.fromEnd >= size || tail.clip < 8) return null
        val start = size - tail.fromEnd
        if (!ranges.read(start + 4, 4).contentEquals(FTYP)) {
            Logger.w(tag = TAG) { "the XMP's clip at $start in $path isn't an MP4" }
            return null
        }
        return start until start + tail.clip
    }

    /** The clip's length, and how far from the end of the file it starts. */
    internal class Tail(val clip: Long, val fromEnd: Long)

    internal fun tailOf(xml: String): Tail? {
        if (xmpValues(xml, CAMERA, "MotionPhoto").firstOrNull() == "1") directoryTail(xml)?.let { return it }
        if (xmpValues(xml, CAMERA, "MicroVideo").firstOrNull() == "1") {
            val offset = xmpValues(xml, CAMERA, "MicroVideoOffset").firstOrNull()?.toLongOrNull()?.takeIf { it > 0 } ?: return null
            return Tail(offset, offset)
        }
        return null
    }

    // Items follow the primary image in the directory's order, so the clip starts where the items from it on begin.
    private fun directoryTail(xml: String): Tail? {
        val container = xmpPrefixes(xml, CONTAINER).firstOrNull() ?: return null
        val item = xmpPrefixes(xml, ITEM).firstOrNull() ?: return null
        val items = Regex("""<${Regex.escape(container)}:Item\s([^<>]*?)/?>""").findAll(xml).map { it.groupValues[1] }.toList()
        fun attribute(attributes: String, name: String) =
            Regex("""(?<![/\w.-])${Regex.escape(item)}:$name\s*=\s*["']([^"']*)["']""").find(attributes)?.groupValues?.get(1)
        val motion = items.indexOfFirst { attribute(it, "Semantic") == "MotionPhoto" }.takeIf { it > 0 } ?: return null
        val spans = items.drop(motion).map { attributes ->
            val length = attribute(attributes, "Length")?.toLongOrNull()?.takeIf { it > 0 } ?: return null
            length to length + (attribute(attributes, "Padding")?.toLongOrNull() ?: 0L)
        }
        return Tail(spans.first().first, spans.sumOf { it.second })
    }

    private const val TAG = "MotionPhoto"
    private const val CAMERA = "http://ns.google.com/photos/1.0/camera/"
    private const val CONTAINER = "http://ns.google.com/photos/1.0/container/"
    private const val ITEM = "http://ns.google.com/photos/1.0/container/item/"
    private val FTYP = "ftyp".encodeToByteArray()

    // A JPEG's EXIF and XMP segments are at most 64 KiB each, and come first.
    private const val HEADER_BYTES = 128 * 1024
    private const val CHUNK = 256 * 1024
}
