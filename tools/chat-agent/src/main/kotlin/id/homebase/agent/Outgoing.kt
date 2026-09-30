package id.homebase.agent

import id.homebase.api.HomebaseProtocol
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.files.ThumbnailFile
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.image.ImageSize
import id.homebase.api.image.getRevisedThumbs
import id.homebase.api.image.readJpegExifOrientation
import id.homebase.api.lib.image.ImageFormatDetector
import id.homebase.api.image.standardThumbSizes
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.builder.AttachmentInput
import id.homebase.chat.services.builder.MessageAttachmentBuilder
import id.homebase.upload.PayloadBundle
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.io.encoding.Base64

const val MAX_OUT_FILES = 4
const val MAX_OUT_BYTES = 10L * 1024 * 1024
private const val TINY_DIMENSION = 20

class OutFile(val name: String, val bytes: ByteArray)

private val ATTACH_LINE = Regex("^ATTACH:\\s*(.+?)\\s*$")

fun parseAttachLines(reply: String): Pair<String, List<String>> {
    val lines = reply.trimEnd().lines().toMutableList()
    val paths = ArrayList<String>()
    while (lines.isNotEmpty()) {
        val last = lines.last()
        if (last.isBlank()) { lines.removeLast(); continue }
        val match = ATTACH_LINE.matchEntire(last.trim()) ?: break
        paths.add(0, match.groupValues[1])
        lines.removeLast()
    }
    return lines.joinToString("\n").trimEnd() to paths
}

// toRealPath resolves symlinks, so a link pointing outside root fails the prefix check
fun resolveInside(root: File, relative: String): File {
    require(relative.isNotBlank() && '\u0000' !in relative) { "empty file path" }
    val realRoot = try {
        root.toPath().toRealPath()
    } catch (e: java.io.IOException) {
        throw IllegalArgumentException("files directory is not available")
    }
    val candidate = try {
        val raw = java.nio.file.Path.of(relative)
        (if (raw.isAbsolute) raw else realRoot.resolve(raw)).toRealPath()
    } catch (e: java.io.IOException) {
        throw IllegalArgumentException("no such file: $relative")
    } catch (e: java.nio.file.InvalidPathException) {
        throw IllegalArgumentException("invalid file path: $relative")
    }
    require(candidate.startsWith(realRoot)) { "$relative is outside the allowed directory" }
    require(Files.isRegularFile(candidate)) { "$relative is not a regular file" }
    return candidate.toFile()
}

private fun readCapped(open: () -> InputStream, name: String, maxBytes: Long): OutFile {
    val bytes = open().use { it.readNBytes(maxBytes.toInt() + 1) }
    require(bytes.isNotEmpty()) { "$name is empty" }
    require(bytes.size <= maxBytes) { "$name is over the size limit of ${formatSize(maxBytes)}" }
    return OutFile(name, bytes)
}

fun loadOutFile(file: File, maxBytes: Long = MAX_OUT_BYTES): OutFile = readCapped({ file.inputStream() }, file.name, maxBytes)

// no-follow open, then re-resolve: catches a swap between check and open
fun loadInside(root: File, relative: String, maxBytes: Long = MAX_OUT_BYTES): OutFile {
    val path = resolveInside(root, relative).toPath()
    return readCapped({
        val input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)
        try {
            require(path.toRealPath() == path) { "$relative changed while it was being opened" }
        } catch (t: Throwable) {
            input.close()
            throw if (t is java.io.IOException) IllegalArgumentException("$relative changed while it was being opened") else t
        }
        input
    }, path.fileName.toString(), maxBytes)
}

class Attached(val text: String, val files: List<OutFile>, val skipped: List<String>)

fun collectAttachments(reply: String, root: File?): Attached {
    val (text, paths) = parseAttachLines(reply)
    if (paths.isEmpty()) return Attached(reply, emptyList(), emptyList())
    val skipped = ArrayList<String>()
    if (root == null) return Attached(text, emptyList(), paths.map { "$it: no directory to attach from" })
    val files = ArrayList<OutFile>()
    for (path in paths.distinct()) {
        if (files.size >= MAX_OUT_FILES) { skipped += "$path: more than $MAX_OUT_FILES files"; continue }
        try {
            files += loadInside(root, path)
        } catch (e: IllegalArgumentException) {
            skipped += "$path: ${e.message}"
        }
    }
    return Attached(text, files, skipped)
}

private const val PNG = "image/png"
private const val JPEG = "image/jpeg"
private const val GIF = "image/gif"

private val DECODABLE = setOf(PNG, JPEG, GIF)

class DecodedImage(val image: BufferedImage, val contentType: String)

fun decodeImage(bytes: ByteArray): DecodedImage? {
    val type = ImageFormatDetector.detectFormat(bytes).takeIf { it in DECODABLE } ?: return null
    val image = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull() ?: return null
    if (image.width <= 0 || image.height <= 0) return null
    return DecodedImage(image, type)
}

private fun scaled(src: BufferedImage, maxDimension: Int, alpha: Boolean): BufferedImage {
    val ratio = minOf(1.0, maxDimension.toDouble() / maxOf(src.width, src.height))
    val w = maxOf(1, Math.round(src.width * ratio).toInt())
    val h = maxOf(1, Math.round(src.height * ratio).toInt())
    val out = BufferedImage(w, h, if (alpha) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
    val g = out.createGraphics()
    try {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        if (!alpha) {
            g.color = java.awt.Color.WHITE
            g.fillRect(0, 0, w, h)
        }
        g.drawImage(src, 0, 0, w, h, null)
    } finally {
        g.dispose()
    }
    return out
}

private fun encodeJpeg(image: BufferedImage, quality: Float): ByteArray {
    val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
    val params = writer.defaultWriteParam.apply {
        compressionMode = ImageWriteParam.MODE_EXPLICIT
        compressionQuality = quality
    }
    val out = ByteArrayOutputStream()
    ImageIO.createImageOutputStream(out).use { ios ->
        writer.output = ios
        writer.write(null, IIOImage(image, null, null), params)
    }
    writer.dispose()
    return out.toByteArray()
}

private fun encodePng(image: BufferedImage): ByteArray =
    ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()

class EncodedThumb(val width: Int, val height: Int, val contentType: String, val bytes: ByteArray)

fun encodeThumb(src: BufferedImage, maxDimension: Int, quality: Int, maxBytes: Int): EncodedThumb? {
    val alpha = src.colorModel.hasAlpha()
    val img = scaled(src, maxDimension, alpha)
    if (alpha) return encodePng(img).takeIf { it.size <= maxBytes }?.let { EncodedThumb(img.width, img.height, PNG, it) }
    var q = quality / 100f
    while (q >= 0.3f) {
        val bytes = encodeJpeg(img, q)
        if (bytes.size <= maxBytes) return EncodedThumb(img.width, img.height, JPEG, bytes)
        q -= 0.15f
    }
    return encodePng(img).takeIf { it.size <= maxBytes }?.let { EncodedThumb(img.width, img.height, PNG, it) }
}

const val STANDARD_IMAGE_DIMENSION = 1600

fun orient(src: BufferedImage, orientation: Int): BufferedImage {
    if (orientation !in 2..8) return src
    val w = src.width
    val h = src.height
    val swap = orientation >= 5
    val out = BufferedImage(if (swap) h else w, if (swap) w else h, if (src.colorModel.hasAlpha()) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
    for (dy in 0 until out.height) for (dx in 0 until out.width) {
        val (sx, sy) = when (orientation) {
            2 -> (w - 1 - dx) to dy
            3 -> (w - 1 - dx) to (h - 1 - dy)
            4 -> dx to (h - 1 - dy)
            5 -> dy to dx
            6 -> dy to (h - 1 - dx)
            7 -> (w - 1 - dy) to (h - 1 - dx)
            else -> (w - 1 - dy) to dx
        }
        out.setRGB(dx, dy, src.getRGB(sx, sy))
    }
    return out
}

class PreparedImage(val bytes: ByteArray, val decoded: DecodedImage)

// mirrors the app's 1600px STANDARD variant and bakes in EXIF rotation; small upright images go out untouched
fun prepareImage(bytes: ByteArray, decoded: DecodedImage): PreparedImage {
    val unchanged = PreparedImage(bytes, decoded)
    if (decoded.contentType == GIF) return unchanged
    val orientation = if (decoded.contentType == JPEG) readJpegExifOrientation(bytes) ?: 1 else 1
    val big = maxOf(decoded.image.width, decoded.image.height) > STANDARD_IMAGE_DIMENSION
    if (orientation == 1 && !big) return unchanged
    val alpha = decoded.image.colorModel.hasAlpha()
    var image = if (big) scaled(decoded.image, STANDARD_IMAGE_DIMENSION, alpha) else decoded.image
    image = orient(image, orientation)
    val encoded = if (decoded.contentType == PNG) encodePng(image) else encodeJpeg(image, 0.85f)
    if (orientation == 1 && encoded.size >= bytes.size) return unchanged
    return PreparedImage(encoded, DecodedImage(image, decoded.contentType))
}

fun tinyThumb(src: BufferedImage): EmbeddedThumb? =
    encodeThumb(src, TINY_DIMENSION, 76, HomebaseProtocol.MaxEmbeddedThumbBytes)?.let { EmbeddedThumb(it.width, it.height, it.contentType, Base64.encode(it.bytes)) }

class ImageThumbs(val preview: EmbeddedThumb?, val thumbnails: List<ThumbnailFile>)

fun imageThumbs(decoded: DecodedImage, payloadKey: String): ImageThumbs {
    val src = decoded.image
    val tiny = tinyThumb(src)
    if (decoded.contentType == GIF) return ImageThumbs(tiny, emptyList())
    val plan = getRevisedThumbs(ImageSize(src.width, src.height), standardThumbSizes)
    val thumbs = plan.mapNotNull { instr ->
        encodeThumb(src, instr.maxPixelDimension, instr.quality, instr.maxBytes * 2)?.let {
            ThumbnailFile(it.width, it.height, it.bytes, payloadKey, it.contentType, instr.quality)
        }
    }
    return ImageThumbs(tiny, thumbs)
}

fun payloadKeyFor(index: Int) = "${ChatProtocol.PAYLOAD_KEY_MESSAGE_WEB}$index"

class StagedBundle(val bundle: PayloadBundle, private val dir: File? = null, val preEncrypted: Boolean = false) {
    fun cleanup() {
        dir?.deleteRecursively()
    }
}

// Not built here (the app does): webp thumbs, PDF page previews.
suspend fun buildOutgoingBundle(files: List<OutFile>, fileOps: FileOperationsProvider): StagedBundle {
    val dir = tempDir("out")
    try {
        val parts = files.mapIndexed { index, file ->
            val key = payloadKeyFor(index)
            val displayName = safeName(file.name, display = true)
            val prepared = decodeImage(file.bytes)?.let { prepareImage(file.bytes, it) }
            val staged = File(dir, "$index.bin").also { it.writeBytes(prepared?.bytes ?: file.bytes) }
            val decoded = prepared?.decoded
            if (decoded != null) {
                val thumbs = imageThumbs(decoded, key)
                PayloadBundle(
                    payloads = listOf(PayloadFile(key = key, filePath = staged.absolutePath, contentType = decoded.contentType, previewThumbnail = thumbs.preview, descriptorContent = "")),
                    thumbnails = thumbs.thumbnails,
                    previewThumbs = listOfNotNull(thumbs.preview),
                )
            } else {
                // Undecodable "images" stay opaque: the attachment builder would try Skia thumbnails.
                val type = contentTypeFor(displayName).takeUnless { it.startsWith("image/") } ?: "application/octet-stream"
                if (type == "application/pdf") {
                    PayloadBundle(listOf(PayloadFile(key = key, filePath = staged.absolutePath, contentType = type, descriptorContent = displayName)), emptyList(), emptyList())
                } else {
                    MessageAttachmentBuilder.build(listOf(AttachmentInput(filePath = staged.absolutePath, contentType = type, displayName = displayName)), fileOps) { _, _ -> key }
                }
            }
        }
        return StagedBundle(
            PayloadBundle(parts.flatMap { it.payloads }, parts.flatMap { it.thumbnails }, parts.flatMap { it.previewThumbs }),
            dir,
        )
    } catch (e: Throwable) {
        dir.deleteRecursively()
        throw e
    }
}
