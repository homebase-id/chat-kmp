package id.homebase.agent

import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.client.drives.files.DescriptorContent
import id.homebase.api.client.ByteApiResponse
import id.homebase.api.client.OdinApiProviderBase
import id.homebase.api.client.PayloadTooLargeException
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.url
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.ReactionSummary
import id.homebase.api.crypto.EncryptedKeyHeader
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.util.truncateToCodePoints
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.poll.PollVote
import id.homebase.chat.services.MessageAppData
import id.homebase.chat.services.decodeReactionCode
import id.homebase.chat.services.builder.LinkPreviewDescriptor
import id.homebase.chat.services.content.MessageContent
import id.homebase.chat.services.content.MessageContentParser
import id.homebase.chat.widget.mediaPayloads
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.io.encoding.Base64
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

const val MAX_ATTACHMENTS = 4
const val IMAGE_MAX_BYTES = 3_500_000L
const val FILE_MAX_BYTES = 2_000_000L
const val PDF_MAX_BYTES = 3_500_000L
const val PDF_MAX_PAGES = 20
const val VOICE_MAX_BYTES = 10_000_000L
const val INLINE_TEXT_CODEPOINTS = 8000
private const val CIPHER_PADDING = 16L
const val TRANSCRIBE_TIMEOUT_MS = 60_000L
private const val LABEL_CODEPOINTS = 120

private val VIEWABLE_IMAGES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")
private val TEXT_EXTENSIONS = setOf("txt", "md", "csv", "tsv", "json", "xml", "yaml", "yml", "log", "kt", "java", "py", "js", "ts", "html", "css", "sh", "toml", "ini", "sql", "c", "h", "cpp", "go", "rs", "swift")

fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${(bytes + 512) / 1024} KB"
    else -> "%.1f MB".format(bytes / 1048576.0)
}

private fun clock(totalSeconds: Long) = "${totalSeconds / 60}:${"%02d".format(totalSeconds % 60)}"

private const val TYPED_LABEL_CODEPOINTS = 600
private const val MAX_REACTION_KINDS = 8
private const val MAX_CONTACT_VALUES = 3

fun reactionsLabel(summary: ReactionSummary?): String? {
    val counts = summary?.reactions?.values.orEmpty()
        .mapNotNull { e -> decodeReactionCode(e.reactionContent)?.takeIf { it.isNotBlank() && !it.startsWith("_") }?.let { it to e.count } }
        .groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }
    return counts.entries.sortedByDescending { it.value }.take(MAX_REACTION_KINDS)
        .joinToString(" ") { "${it.key.oneLine(16)}×${it.value}" }.ifEmpty { null }
}

private fun typedLabel(dataType: Int?, rawContent: String?, reactions: ReactionSummary?): String? {
    val content = MessageContentParser.parse(dataType, rawContent) ?: return null
    val (kind, body) = when (content) {
        is MessageContent.Event -> "event" to (content.descriptor?.let { d ->
            listOfNotNull(d.title.oneLine(NAME_CODEPOINTS), Instant.fromEpochMilliseconds(d.startUtcMs).toString(), d.locationText?.takeIf { it.isNotBlank() }?.let { "at ${it.oneLine(NAME_CODEPOINTS)}" }).joinToString(" | ")
        } ?: content.displayLabel)
        is MessageContent.DiceRoll -> "dice" to content.displayLabel
        is MessageContent.Groodle -> "groodle" to content.displayLabel
        is MessageContent.Poll -> "poll" to (content.descriptor?.let { d ->
            val votes = PollVote.counts(reactions, d.options.size)
            val options = d.options.withIndex().joinToString(", ") { (i, o) -> "${o.oneLine(40)} (${votes[i]})" }
            listOf(d.question.oneLine(140), options + if (d.allowMultiple) " [multiple choice]" else "", if (d.closed) "[closed]" else "").filter { it.isNotEmpty() }.joinToString(" | ")
        } ?: content.displayLabel)
        is MessageContent.ContactCard -> "contact" to (content.descriptor?.let { d ->
            listOfNotNull(d.summaryLine().oneLine(NAME_CODEPOINTS), d.organization.takeIf { it.isNotBlank() }?.oneLine(NAME_CODEPOINTS), d.phones.take(MAX_CONTACT_VALUES).takeIf { it.isNotEmpty() }?.joinToString(", ") { it.oneLine(40) }, d.emails.take(MAX_CONTACT_VALUES).takeIf { it.isNotEmpty() }?.joinToString(", ") { it.oneLine(60) }, d.odinId.takeIf { it.isNotBlank() }?.oneLine(60)).joinToString(" | ")
        } ?: content.displayLabel)
        is MessageContent.Location -> "location" to (content.descriptor?.let { d ->
            "${d.lat},${d.lon}" + listOfNotNull(d.caption, d.address).firstOrNull { it.isNotBlank() }?.let { " ${it.oneLine(NAME_CODEPOINTS)}" }.orEmpty()
        } ?: content.displayLabel)
        is MessageContent.Unknown -> return "[unsupported message kind ${content.dataType}]"
    }
    return "[$kind: ${body.oneLine(TYPED_LABEL_CODEPOINTS)}]"
}

private fun linkLabels(p: PayloadDescriptor): List<String> {
    val links = runCatching { OdinSystemSerializer.deserialize<List<LinkPreviewDescriptor>>(p.descriptorContent.orEmpty()) }.getOrNull()
    if (links.isNullOrEmpty()) return listOf("[link preview]")
    return links.map { "[link: ${it.url.oneLine(LABEL_CODEPOINTS)}${it.title.takeIf { t -> t.isNotBlank() }?.let { t -> " - ${t.oneLine(LABEL_CODEPOINTS)}" }.orEmpty()}]" }
}

fun payloadLabels(payloads: List<PayloadDescriptor>?): List<String> = payloads.mediaPayloads().flatMap { p ->
    when {
        p.key == ChatProtocol.PAYLOAD_KEY_LINKS -> linkLabels(p)
        p.key == ChatProtocol.PAYLOAD_KEY_LOCATION -> listOf("[location]")
        p.isImage() -> listOf(
            when {
                (p.descriptorInfo() as? DescriptorContent.ImageFile)?.isSticker == true -> "[sticker]"
                p.contentType == "image/gif" -> "[gif]"
                else -> "[image]"
            },
        )
        p.isVideo() -> listOf("[video${(p.descriptorInfo() as? DescriptorContent.VideoFile)?.durationMs?.let { " " + clock(it / 1000) }.orEmpty()}]")
        p.isAudio() -> listOf(p.audioLengthSeconds()?.let { "[voice ${clock(it.toLong())}]" } ?: "[audio]")
        else -> listOf("[file ${(p.filename() ?: p.key).oneLine(NAME_CODEPOINTS)}${p.bytesWritten?.let { " " + formatSize(it) }.orEmpty()}]")
    }
}

fun messageDisplay(text: String, dataType: Int?, rawContent: String?, payloads: List<PayloadDescriptor>?, reactions: ReactionSummary? = null): String {
    val labels = payloadLabels(payloads).joinToString(" ")
    val base = typedLabel(dataType, rawContent, reactions) ?: when {
        labels.isEmpty() -> text
        text.isBlank() -> labels
        else -> "$text $labels"
    }
    return reactionsLabel(reactions)?.let { "$base [reactions: $it]" } ?: base
}

fun replyParentId(rawContent: String?): Uuid? = runCatching {
    OdinSystemSerializer.deserialize<MessageAppData>(rawContent.orEmpty()).replyPreview?.replyUniqueId
}.getOrNull()

class Attachment(
    val msgId: Uuid,
    val parent: Boolean,
    val fileName: String,
    val contentType: String,
    val size: Long,
    val bytes: ByteArray? = null,
    val text: String? = null,
    val note: String? = null,
) {
    val viewableImage get() = bytes != null && contentType in VIEWABLE_IMAGES
    val viewablePdf get() = bytes != null && contentType == PDF_TYPE && note == null
    val modelBlock get() = viewableImage || viewablePdf
}

private const val PDF_TYPE = "application/pdf"
private val PDF_PAGE_OBJECT = Regex("/Type\\s*/Page(?![a-zA-Z])")

fun looksLikePdf(bytes: ByteArray) = bytes.size > 5 && String(bytes, 0, 5, Charsets.ISO_8859_1) == "%PDF-"

// object-stream PDFs count 0 and pass; the size cap still applies
fun pdfPageCount(bytes: ByteArray): Int = PDF_PAGE_OBJECT.findAll(String(bytes, Charsets.ISO_8859_1)).count()

fun interface PayloadFetcher {
    suspend fun fetch(fileId: Uuid, key: String, maxBytes: Long): ByteArray?

    suspend fun thumb(fileId: Uuid, key: String, width: Int, height: Int, maxBytes: Long): ByteArray? = null
}

private class PayloadProvider(private val session: Session) : OdinApiProviderBase(session.http, session.credentials) {
    suspend fun get(fileId: Uuid, key: String, maxBytes: Long, thumb: Pair<Int, Int>? = null): ByteApiResponse {
        val creds = requireCreds()
        val endpoint = apiUrl(creds.domain, "/drives/${SystemDriveConstants.chatDrive.alias}/files/$fileId/payload/$key${if (thumb != null) "/thumb" else ""}")
        val response = requestBytes(maxBytes) {
            session.http.get(endpoint) {
                bearerAuth(creds.accessToken)
                thumb?.let { (w, h) -> url { parameters.append("width", w.toString()); parameters.append("height", h.toString()) } }
            }
        }
        if (response.status != 200 && response.status != 206) throwForFailure(response)
        return response
    }
}

// DriveFileProvider.decryptBytes needs the coil-backed DriveFileProviderCached, absent from the trimmed classpath.
fun sessionFetcher(session: Session): PayloadFetcher {
    val provider = PayloadProvider(session)
    suspend fun load(fileId: Uuid, key: String, maxBytes: Long, thumb: Pair<Int, Int>?): ByteArray? {
        val response = provider.get(fileId, key, maxBytes + CIPHER_PADDING, thumb)
        if (response.status == 404) return null
        val encrypted = response.headers["payloadencrypted"]?.equals("true", ignoreCase = true) == true
        if (!encrypted) return response.bytes
        val header = response.headers["sharedsecretencryptedheader64"] ?: error("payload has no key header")
        val secret = session.credentials.getActiveCredentials()?.sharedSecret ?: error("no shared secret")
        return EncryptedKeyHeader.fromBase64(header).decryptAesToKeyHeader(secret).decrypt(response.bytes)
    }
    return object : PayloadFetcher {
        override suspend fun fetch(fileId: Uuid, key: String, maxBytes: Long) = load(fileId, key, maxBytes, null)

        override suspend fun thumb(fileId: Uuid, key: String, width: Int, height: Int, maxBytes: Long) = load(fileId, key, maxBytes, width to height)
    }
}

fun isTextLike(contentType: String, name: String) =
    contentType.startsWith("text/") || contentType in setOf("application/json", "application/xml", "application/x-yaml", "application/javascript") ||
        name.substringAfterLast('.', "").lowercase() in TEXT_EXTENSIONS

class AttachmentLoader(
    private val fetcher: PayloadFetcher,
    private val transcribe: (suspend (ByteArray, String) -> String?)? = null,
    private val log: (String) -> Unit = {},
) {
    suspend fun load(messages: List<Pair<ChatMsg, Boolean>>): List<Attachment> {
        val out = ArrayList<Attachment>()
        for ((msg, parent) in messages) {
            val fileId = msg.fileId ?: continue
            if (MessageContentParser.parse(msg.dataType, msg.rawContent) != null) continue
            for (p in msg.payloads.mediaPayloads()) {
                if (out.size >= MAX_ATTACHMENTS) return out
                if (p.isVideo()) {
                    loadVideoThumb(msg.id, parent, fileId, p, out.size + 1)?.let { out += it }
                    continue
                }
                if (p.key == ChatProtocol.PAYLOAD_KEY_LINKS || p.key == ChatProtocol.PAYLOAD_KEY_LOCATION) continue
                if (p.isAudio() && transcribe == null) continue
                out += loadOne(msg.id, parent, fileId, p, out.size + 1)
            }
        }
        return out
    }

    private suspend fun loadVideoThumb(msgId: Uuid, parent: Boolean, fileId: Uuid, p: PayloadDescriptor, index: Int): Attachment? {
        val thumb = p.thumbnails.orEmpty().filter { (it.bytesWritten ?: 0L) <= IMAGE_MAX_BYTES && (it.pixelWidth ?: 0) > 0 && (it.pixelHeight ?: 0) > 0 }.maxByOrNull { it.pixelWidth ?: 0 } ?: return null
        val bytes = try {
            fetcher.thumb(fileId, p.key, thumb.pixelWidth!!, thumb.pixelHeight!!, IMAGE_MAX_BYTES)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("video thumbnail error: ${e.message}")
            null
        } ?: return null
        val type = thumb.contentType?.takeIf { it in VIEWABLE_IMAGES } ?: "image/jpeg"
        return Attachment(msgId, parent, safeName("$index-video-thumbnail.${extensionFor(type)}"), type, bytes.size.toLong(), bytes = bytes, note = "video thumbnail")
    }

    private suspend fun loadOne(msgId: Uuid, parent: Boolean, fileId: Uuid, p: PayloadDescriptor, index: Int): Attachment {
        val rawName = p.filename() ?: p.key
        val type = contentTypeFor(rawName, p.contentType)
        val fileName = safeName("$index-${if ('.' in rawName) rawName else "$rawName.${extensionFor(type)}"}")
        val size = p.bytesWritten ?: 0L
        val cap = when {
            type == PDF_TYPE -> PDF_MAX_BYTES
            p.isImage() -> IMAGE_MAX_BYTES
            p.isAudio() -> VOICE_MAX_BYTES
            else -> FILE_MAX_BYTES
        }
        fun skipped(note: String) = Attachment(msgId, parent, fileName, type, size, note = note)
        if (size > cap) return skipped("not downloaded: over ${formatSize(cap)} cap")
        val bytes = try {
            fetcher.fetch(fileId, p.key, cap)
        } catch (e: CancellationException) {
            throw e
        } catch (e: PayloadTooLargeException) {
            return skipped("not downloaded: over ${formatSize(cap)} cap")
        } catch (e: Exception) {
            log("attachment download error: ${e.message}")
            return skipped("not downloaded: download failed")
        } ?: return skipped("not downloaded: payload missing")
        if (bytes.size > cap) return skipped("not downloaded: over ${formatSize(cap)} cap")
        if (p.isAudio()) {
            val transcript = transcribe?.invoke(bytes, fileName)
            return Attachment(msgId, parent, fileName, type, bytes.size.toLong(), text = transcript, note = if (transcript == null) "transcription failed" else "voice transcript")
        }
        val text = if (isTextLike(type, rawName)) bytes.decodeToString().truncateToCodePoints(INLINE_TEXT_CODEPOINTS) else null
        val pages = if (type == PDF_TYPE) pdfPageCount(bytes) else 0
        val note = when {
            p.isImage() && type !in VIEWABLE_IMAGES -> "image format not viewable"
            type == PDF_TYPE && !looksLikePdf(bytes) -> "not a valid PDF"
            pages > PDF_MAX_PAGES -> "not shown to the model: $pages pages, limit $PDF_MAX_PAGES"
            else -> null
        }
        return Attachment(msgId, parent, fileName, type, bytes.size.toLong(), bytes = bytes, text = text, note = note)
    }
}

fun describeAttachment(number: Int, a: Attachment, clean: (String) -> String): List<String> {
    val head = "attachment $number: ${clean(a.fileName)} (${clean(a.contentType)}, ${formatSize(a.size)})${a.note?.let { " [$it]" }.orEmpty()}"
    val body = a.text ?: return listOf(head)
    return listOf("$head content:", clean(body))
}

fun streamJsonInput(prompt: String, attachments: List<Attachment>): String {
    val message = buildJsonObject {
        put("type", "user")
        put("message", buildJsonObject {
            put("role", "user")
            put("content", buildJsonArray {
                attachments.filter { it.modelBlock }.forEach { a ->
                    add(buildJsonObject {
                        put("type", if (a.viewablePdf) "document" else "image")
                        put("source", buildJsonObject {
                            put("type", "base64")
                            put("media_type", a.contentType)
                            put("data", Base64.encode(a.bytes!!))
                        })
                    })
                }
                add(buildJsonObject { put("type", "text"); put("text", prompt) })
            })
        })
    }
    return message.toString() + "\n"
}

fun parseStreamResult(stdout: String): BrainOutcome {
    val result = stdout.lineSequence().mapNotNull { line ->
        runCatching { OdinSystemSerializer.json.parseToJsonElement(line).jsonObject }.getOrNull()
    }.lastOrNull { it["type"]?.jsonPrimitive?.contentOrNull == "result" }
        ?: return BrainOutcome.Failed("no result from brain")
    val text = result["result"]?.jsonPrimitive?.contentOrNull.orEmpty()
    return if (result["is_error"]?.jsonPrimitive?.booleanOrNull == true) BrainOutcome.Failed(text.ifEmpty { "brain error" }) else BrainOutcome.Output(text)
}

const val STREAM_JSON_FLAGS = "--input-format stream-json --output-format stream-json --verbose"

fun writeAttachments(dir: File, attachments: List<Attachment>): List<File> =
    attachments.mapNotNull { a ->
        a.bytes?.let {
            val f = File(dir, a.fileName).canonicalFile
            require(f.parentFile == dir.canonicalFile) { "attachment path escapes temp dir" }
            f.also { out -> out.writeBytes(it) }
        }
    }

fun shellTranscriber(command: String): suspend (ByteArray, String) -> String? = { bytes, name ->
    withContext(Dispatchers.IO) {
        val dir = tempDir("voice")
        try {
            val file = File(dir, safeName(name)).also { it.writeBytes(bytes) }
            val process = ProcessBuilder("sh", "-c", "$command \"\$1\"", "transcribe", file.absolutePath).redirectErrorStream(false).start()
            process.outputStream.close()
            process.errorStream.close()
            val out = CompletableFuture.supplyAsync { process.inputStream.readBytes().decodeToString() }
            if (!process.waitFor(TRANSCRIBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                return@withContext null
            }
            if (process.exitValue() != 0) return@withContext null
            out.get(2, TimeUnit.SECONDS).trim().takeIf { it.isNotEmpty() }?.truncateToCodePoints(INLINE_TEXT_CODEPOINTS)
        } finally {
            dir.deleteRecursively()
        }
    }
}
