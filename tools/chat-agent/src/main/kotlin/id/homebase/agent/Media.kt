package id.homebase.agent

import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.client.drives.files.DescriptorContent
import id.homebase.api.client.ByteApiResponse
import id.homebase.api.client.OdinApiProviderBase
import id.homebase.api.client.PayloadTooLargeException
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.crypto.EncryptedKeyHeader
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.util.truncateToCodePoints
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.MessageAppData
import id.homebase.chat.services.builder.LinkPreviewDescriptor
import id.homebase.chat.services.content.MessageContent
import id.homebase.chat.services.content.MessageContentParser
import id.homebase.chat.widget.mediaPayloads
import java.io.File
import java.nio.file.Files
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
const val VOICE_MAX_BYTES = 10_000_000L
const val INLINE_TEXT_CODEPOINTS = 8000
private const val CIPHER_PADDING = 16L
const val TRANSCRIBE_TIMEOUT_MS = 60_000L
private const val LABEL_CODEPOINTS = 120
private const val NAME_CODEPOINTS = 60

private val VIEWABLE_IMAGES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")
private val TEXT_EXTENSIONS = setOf("txt", "md", "csv", "tsv", "json", "xml", "yaml", "yml", "log", "kt", "java", "py", "js", "ts", "html", "css", "sh", "toml", "ini", "sql", "c", "h", "cpp", "go", "rs", "swift")

private fun oneLine(s: String, max: Int) = s.replace(Regex("[\\p{Cntrl}]+"), " ").trim().truncateToCodePoints(max)

fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${(bytes + 512) / 1024} KB"
    else -> "%.1f MB".format(bytes / 1048576.0)
}

private fun clock(totalSeconds: Long) = "${totalSeconds / 60}:${"%02d".format(totalSeconds % 60)}"

private fun typedLabel(dataType: Int?, rawContent: String?): String? {
    val content = MessageContentParser.parse(dataType, rawContent) ?: return null
    val (kind, body) = when (content) {
        is MessageContent.Event -> "event" to listOfNotNull(content.displayLabel, content.descriptor?.let { Instant.fromEpochMilliseconds(it.startUtcMs).toString() }).joinToString(" ")
        is MessageContent.DiceRoll -> "dice" to content.displayLabel
        is MessageContent.Groodle -> "groodle" to content.displayLabel
        is MessageContent.Poll -> "poll" to content.displayLabel
        is MessageContent.ContactCard -> "contact" to content.displayLabel
        is MessageContent.Location -> "location" to content.displayLabel
        is MessageContent.Unknown -> "unknown" to "type ${content.dataType}"
    }
    return "[$kind: ${oneLine(body, LABEL_CODEPOINTS)}]"
}

private fun linkLabels(p: PayloadDescriptor): List<String> {
    val links = runCatching { OdinSystemSerializer.deserialize<List<LinkPreviewDescriptor>>(p.descriptorContent.orEmpty()) }.getOrNull()
    if (links.isNullOrEmpty()) return listOf("[link preview]")
    return links.map { "[link: ${oneLine(it.url, LABEL_CODEPOINTS)}${it.title.takeIf { t -> t.isNotBlank() }?.let { t -> " - ${oneLine(t, LABEL_CODEPOINTS)}" }.orEmpty()}]" }
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
        else -> listOf("[file ${oneLine(p.filename() ?: p.key, NAME_CODEPOINTS)}${p.bytesWritten?.let { " " + formatSize(it) }.orEmpty()}]")
    }
}

fun messageDisplay(text: String, dataType: Int?, rawContent: String?, payloads: List<PayloadDescriptor>?): String {
    typedLabel(dataType, rawContent)?.let { return it }
    val labels = payloadLabels(payloads).joinToString(" ")
    return when {
        labels.isEmpty() -> text
        text.isBlank() -> labels
        else -> "$text $labels"
    }
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
}

fun interface PayloadFetcher {
    suspend fun fetch(fileId: Uuid, key: String, maxBytes: Long): ByteArray?
}

private class PayloadProvider(private val session: Session) : OdinApiProviderBase(session.http, session.credentials) {
    suspend fun get(fileId: Uuid, key: String, maxBytes: Long): ByteApiResponse {
        val creds = requireCreds()
        val url = apiUrl(creds.domain, "/drives/${SystemDriveConstants.chatDrive.alias}/files/$fileId/payload/$key")
        val response = requestBytes(maxBytes) { session.http.get(url) { bearerAuth(creds.accessToken) } }
        if (response.status != 200 && response.status != 206) throwForFailure(response)
        return response
    }
}

fun sessionFetcher(session: Session): PayloadFetcher {
    val provider = PayloadProvider(session)
    return PayloadFetcher { fileId, key, maxBytes ->
        val response = provider.get(fileId, key, maxBytes + CIPHER_PADDING)
        if (response.status == 404) return@PayloadFetcher null
        val encrypted = response.headers["payloadencrypted"]?.equals("true", ignoreCase = true) == true
        if (!encrypted) return@PayloadFetcher response.bytes
        val header = response.headers["sharedsecretencryptedheader64"] ?: error("payload has no key header")
        val secret = session.credentials.getActiveCredentials()?.sharedSecret ?: error("no shared secret")
        EncryptedKeyHeader.fromBase64(header).decryptAesToKeyHeader(secret).decrypt(response.bytes)
    }
}

private fun safeName(name: String) =
    name.replace(Regex("[^A-Za-z0-9._-]"), "_").replace(Regex("\\.{2,}"), ".").trim('.', '_').ifEmpty { "file" }.truncateToCodePoints(NAME_CODEPOINTS)

private fun extensionFor(contentType: String): String = when (contentType) {
    "image/jpeg" -> "jpg"
    "image/png" -> "png"
    "image/gif" -> "gif"
    "image/webp" -> "webp"
    else -> contentType.substringAfter('/', "").substringBefore(';').lowercase().takeIf { Regex("[a-z0-9]{1,8}").matches(it) } ?: "bin"
}

private fun guessType(name: String, declared: String?): String {
    if (!declared.isNullOrBlank() && declared != "application/octet-stream") return declared
    return when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "pdf" -> "application/pdf"
        "json" -> "application/json"
        "csv" -> "text/csv"
        "md" -> "text/markdown"
        "txt", "log" -> "text/plain"
        else -> declared ?: "application/octet-stream"
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
                if (p.key == ChatProtocol.PAYLOAD_KEY_LINKS || p.key == ChatProtocol.PAYLOAD_KEY_LOCATION || p.isVideo()) continue
                if (p.isAudio() && transcribe == null) continue
                out += loadOne(msg.id, parent, fileId, p, out.size + 1)
            }
        }
        return out
    }

    private suspend fun loadOne(msgId: Uuid, parent: Boolean, fileId: Uuid, p: PayloadDescriptor, index: Int): Attachment {
        val rawName = p.filename() ?: p.key
        val type = guessType(rawName, p.contentType)
        val fileName = safeName("$index-${if ('.' in rawName) rawName else "$rawName.${extensionFor(type)}"}")
        val size = p.bytesWritten ?: 0L
        val cap = when {
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
        val note = if (p.isImage() && type !in VIEWABLE_IMAGES) "image format not viewable" else null
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
                attachments.filter { it.viewableImage }.forEach { a ->
                    add(buildJsonObject {
                        put("type", "image")
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

fun newAttachmentDir(): File = Files.createTempDirectory("chat-agent-attach").toFile()

fun shellTranscriber(command: String): suspend (ByteArray, String) -> String? = { bytes, name ->
    withContext(Dispatchers.IO) {
        val dir = Files.createTempDirectory("chat-agent-voice").toFile()
        try {
            val file = File(dir, safeName(name)).also { it.writeBytes(bytes) }
            val process = ProcessBuilder("sh", "-c", "$command \"\$1\"", "transcribe", file.absolutePath).redirectErrorStream(false).start()
            process.outputStream.close()
            process.errorStream.close()
            val out = java.util.concurrent.CompletableFuture.supplyAsync { process.inputStream.readBytes().decodeToString() }
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
