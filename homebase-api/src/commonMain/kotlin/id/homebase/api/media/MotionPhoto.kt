package id.homebase.api.media

import co.touchlab.kermit.Logger
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.image.ImageMetadataScrubber
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
    suspend fun clipRange(path: String, fileOps: FileOperationsProvider): LongRange? {
        val header = fileOps.readFileHeaderBytes(path, HEADER_BYTES)
        val xml = runCatching { ImageMetadataScrubber.readBlocks(header)?.xmp?.decodeToString() }.getOrNull() ?: return null
        val tail = tailBytes(xml) ?: return null
        val size = fileOps.getFileSize(path)
        if (tail.clip <= 0 || tail.clip > tail.fromEnd || tail.fromEnd >= size) return null
        val start = size - tail.fromEnd
        val ftyp = fileOps.readFileRange(path, start + 4, 4)
        if (!ftyp.contentEquals(FTYP)) {
            Logger.w(tag = TAG) { "the XMP's clip at $start in $path isn't an MP4" }
            return null
        }
        return start until start + tail.clip
    }

    /** [path]'s clip copied to an upload-temp MP4 the caller deletes, or null when [path] has none. */
    suspend fun extractClip(path: String, fileOps: FileOperationsProvider): String? {
        val range = clipRange(path, fileOps) ?: return null
        val out = fileOps.createUploadTempPath("motion", ".mp4")
        fileOps.openRanges(path).use { ranges ->
            fileOps.writeStream(out, flow {
                var at = range.first
                while (at <= range.last) {
                    val length = minOf(CHUNK.toLong(), range.last - at + 1).toInt()
                    emit(ranges.read(at, length))
                    at += length
                }
            })
        }
        return out
    }

    /** The clip's length, and how far from the end of the file it starts. */
    internal class Tail(val clip: Long, val fromEnd: Long)

    internal fun tailBytes(xml: String): Tail? {
        if (xmpValues(xml, CAMERA, "MotionPhoto").firstOrNull() == "1") return directoryTail(xml)
        if (xmpValues(xml, CAMERA, "MicroVideo").firstOrNull() == "1") {
            val offset = xmpValues(xml, CAMERA, "MicroVideoOffset").firstOrNull()?.toLongOrNull() ?: return null
            return Tail(offset, offset)
        }
        return null
    }

    // Items follow the primary image in the directory's order, so the clip starts where the items from it on begin.
    private fun directoryTail(xml: String): Tail? {
        val container = prefixOf(xml, CONTAINER) ?: return null
        val item = prefixOf(xml, ITEM) ?: return null
        val items = Regex("""<${Regex.escape(container)}:Item\s([^<>]*?)/?>""").findAll(xml).map { it.groupValues[1] }.toList()
        fun attribute(attributes: String, name: String) =
            Regex("""(?<![\w.-])${Regex.escape(item)}:$name\s*=\s*["']([^"']*)["']""").find(attributes)?.groupValues?.get(1)
        val motion = items.indexOfFirst { attribute(it, "Semantic") == "MotionPhoto" }.takeIf { it > 0 } ?: return null
        var fromEnd = 0L
        for (attributes in items.drop(motion)) {
            val length = attribute(attributes, "Length")?.toLongOrNull()?.takeIf { it > 0 } ?: return null
            fromEnd += length + (attribute(attributes, "Padding")?.toLongOrNull() ?: 0L)
        }
        val clip = attribute(items[motion], "Length")!!.toLong()
        return Tail(clip, fromEnd)
    }

    private fun prefixOf(xml: String, namespace: String) =
        Regex("""xmlns:([\w.-]+)\s*=\s*["']${Regex.escape(namespace)}["']""").find(xml)?.groupValues?.get(1)

    private const val TAG = "MotionPhoto"
    private const val CAMERA = "http://ns.google.com/photos/1.0/camera/"
    private const val CONTAINER = "http://ns.google.com/photos/1.0/container/"
    private const val ITEM = "http://ns.google.com/photos/1.0/container/item/"
    private val FTYP = "ftyp".encodeToByteArray()

    // The XMP sits in the first segments, after the EXIF.
    private const val HEADER_BYTES = 256 * 1024
    private const val CHUNK = 256 * 1024
}
