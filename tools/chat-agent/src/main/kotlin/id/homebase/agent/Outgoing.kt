package id.homebase.agent

import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.files.ThumbnailFile
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.image.ImageSize
import id.homebase.api.image.getRevisedThumbs
import id.homebase.api.image.standardThumbSizes
import id.homebase.api.util.truncateToCodePoints
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.builder.AttachmentInput
import id.homebase.chat.services.builder.MessageAttachmentBuilder
import id.homebase.upload.PayloadBundle
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.io.encoding.Base64

const val MAX_OUT_FILES = 4
const val MAX_OUT_BYTES = 10L * 1024 * 1024
private const val OUT_NAME_CODEPOINTS = 100
private const val TINY_DIMENSION = 20
private const val TINY_MAX_BYTES = 1024

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

// toRealPath resolves symlinks, so a link inside root that points outside fails the prefix check.
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

fun loadOutFile(file: File, maxBytes: Long = MAX_OUT_BYTES): OutFile {
    val size = file.length()
    require(size > 0) { "${file.name} is empty" }
    require(size <= maxBytes) { "${file.name} is ${formatSize(size)}, limit is ${formatSize(maxBytes)}" }
    val bytes = file.inputStream().use { it.readNBytes(maxBytes.toInt() + 1) }
    require(bytes.size <= maxBytes) { "${file.name} is over the size limit" }
    return OutFile(file.name, bytes)
}

fun loadInside(root: File, relative: String): OutFile = loadOutFile(resolveInside(root, relative))

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

fun sniffImage(b: ByteArray): String? = when {
    b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte() -> PNG
    b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> JPEG
    b.size > 6 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() -> GIF
    else -> null
}

private val EXTENSION_TYPES = mapOf(
    "pdf" to "application/pdf", "txt" to "text/plain", "md" to "text/plain", "log" to "text/plain",
    "csv" to "text/csv", "json" to "application/json", "zip" to "application/zip",
)

fun guessContentType(name: String): String =
    EXTENSION_TYPES[name.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"

class DecodedImage(val image: BufferedImage, val contentType: String)

fun decodeImage(bytes: ByteArray): DecodedImage? {
    val type = sniffImage(bytes) ?: return null
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

class ImageThumbs(val preview: EmbeddedThumb?, val thumbnails: List<ThumbnailFile>)

fun imageThumbs(decoded: DecodedImage, payloadKey: String): ImageThumbs {
    val src = decoded.image
    val tiny = encodeThumb(src, TINY_DIMENSION, 76, TINY_MAX_BYTES)?.let {
        EmbeddedThumb(it.width, it.height, it.contentType, Base64.encode(it.bytes))
    }
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

class StagedBundle(val bundle: PayloadBundle, private val dir: File) {
    fun cleanup() {
        dir.deleteRecursively()
    }
}

// Not built here (the app does): 1600px STANDARD re-encode of the primary image, webp thumbs, PDF page previews, EXIF-orientation baking.
suspend fun buildOutgoingBundle(files: List<OutFile>, fileOps: FileOperationsProvider): StagedBundle {
    val dir = Files.createTempDirectory("chat-agent-out").toFile()
    try {
        val parts = files.mapIndexed { index, file ->
            val key = payloadKeyFor(index)
            val safeName = file.name.replace(Regex("[\\p{Cntrl}/\\\\]+"), "_").truncateToCodePoints(OUT_NAME_CODEPOINTS)
            val staged = File(dir, "$index.bin").also { it.writeBytes(file.bytes) }
            val decoded = decodeImage(file.bytes)
            if (decoded != null) {
                val thumbs = imageThumbs(decoded, key)
                PayloadBundle(
                    payloads = listOf(PayloadFile(key = key, filePath = staged.absolutePath, contentType = decoded.contentType, previewThumbnail = thumbs.preview, descriptorContent = "")),
                    thumbnails = thumbs.thumbnails,
                    previewThumbs = listOfNotNull(thumbs.preview),
                )
            } else {
                val type = guessContentType(safeName)
                if (type == "application/pdf") {
                    PayloadBundle(listOf(PayloadFile(key = key, filePath = staged.absolutePath, contentType = type, descriptorContent = safeName)), emptyList(), emptyList())
                } else {
                    MessageAttachmentBuilder.build(listOf(AttachmentInput(filePath = staged.absolutePath, contentType = type, displayName = safeName)), fileOps) { _, _ -> key }
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
