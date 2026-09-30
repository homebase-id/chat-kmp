package id.homebase.agent

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.DescriptorContent
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.crypto.AesCbc
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.file.JvmFileOperationsProvider
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.util.codePointCount
import id.homebase.api.util.truncateToCodePoints
import id.homebase.api.video.VideoMetadata
import id.homebase.chat.contactcard.ContactCardDescriptor
import id.homebase.chat.contactcard.scrubbed
import id.homebase.chat.event.EventDescriptor
import id.homebase.chat.poll.PollDescriptor
import id.homebase.chat.services.builder.LocationPreviewDescriptor
import id.homebase.chat.services.content.MessageContent
import id.homebase.chat.services.content.MessageContentParser
import id.homebase.upload.PayloadBundle
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

const val POLL_QUESTION_CODEPOINTS = PollDescriptor.MAX_QUESTION_CP
const val EVENT_TITLE_CODEPOINTS = 80
const val EVENT_DESCRIPTION_CODEPOINTS = 280
const val EVENT_PLACE_CODEPOINTS = 120
const val LOCATION_LABEL_CODEPOINTS = 200
const val CONTACT_NAME_CODEPOINTS = ContactCardDescriptor.MAX_NAME_CODEPOINTS
const val CAPTION_CODEPOINTS = 500
const val VIDEO_MAX_BYTES = 5L * 1024 * 1024
private const val ONE_HOUR_MS = 3_600_000L

val TYPED_SEND_TOOLS = setOf("send_poll", "send_event", "send_location", "send_contact")
val MEDIA_SEND_TOOLS = setOf("send_video", "send_voice")

private val E164 = Regex("^\\+[1-9]\\d{1,14}$")
private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
private val PHONE_NOISE = Regex("[\\s()\\-.]")
private val VOICE_TYPES = mapOf("m4a" to "audio/mp4", "mp3" to "audio/mpeg", "wav" to "audio/wav", "ogg" to "audio/ogg", "aac" to "audio/aac", "opus" to "audio/opus")

class OutVideo(val name: String, val bytes: ByteArray, val poster: ByteArray, val durationMs: Long, val width: Int, val height: Int, val codec: String)
class OutVoice(val name: String, val bytes: ByteArray, val contentType: String, val seconds: Int)

// the disclosure must ride inside a field recipients see, so the user's own text gives way to it, never the reverse
fun fitDisclosed(disclose: (String) -> String, text: String, limit: Int): String {
    val full = disclose(text).trim()
    if (full.codePointCount() <= limit) return full
    val overhead = full.codePointCount() - text.codePointCount()
    require(overhead < limit) { "the AI disclosure does not fit in this field" }
    return disclose(text.truncateToCodePoints(limit - overhead)).trim()
}

private fun listArg(args: JsonObject?, name: String): List<String> =
    args?.get(name)?.let { raw -> requireNotNull(raw as? JsonArray) { "$name must be a list" }.map { it.jsonPrimitive.content } }.orEmpty()

private fun numberArg(args: JsonObject?, name: String): Double {
    val value = doubleArg(args, name)
    require(value != null && value.isFinite()) { "$name must be a number" }
    return value
}

fun parseEventInstant(raw: String, zone: ZoneId): Long =
    runCatching { Instant.parse(raw) }.getOrNull()?.toEpochMilli()
        ?: runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(raw).atZone(zone).toInstant().toEpochMilli() }.getOrNull()
        ?: throw IllegalArgumentException("'$raw' is not an ISO date-time like 2026-10-01T18:00")

private suspend fun sendTypedContent(backend: AgentBackend, conversationId: Uuid, content: MessageContent, label: String) =
    ToolReply("sent $label ${backend.sendTyped(conversationId, content)}")

suspend fun toolSendPoll(backend: AgentBackend, args: JsonObject?): ToolReply = guarded {
    val conversationId = sendTarget(backend, args)
    val question = stringArg(args, "question")?.oneLine(POLL_QUESTION_CODEPOINTS * 2) ?: return@guarded ToolReply("question is empty", true)
    val options = listArg(args, "options").map { it.oneLine(PollDescriptor.MAX_OPTION_CP) }
    require(options.size in PollDescriptor.MIN_OPTIONS..PollDescriptor.MAX_OPTIONS) { "a poll needs ${PollDescriptor.MIN_OPTIONS} to ${PollDescriptor.MAX_OPTIONS} options" }
    require(options.none { it.isEmpty() }) { "poll options must not be empty" }
    val descriptor = PollDescriptor(
        question = fitDisclosed({ backend.disclose(conversationId, it) }, question, POLL_QUESTION_CODEPOINTS),
        options = options,
        allowMultiple = boolArg(args, "allowMultiple") ?: false,
    )
    require(descriptor.isValid()) { "poll is not valid" }
    sendTypedContent(backend, conversationId, MessageContent.Poll(descriptor), "poll")
}

suspend fun toolVotePoll(backend: AgentBackend, args: JsonObject?): ToolReply = guarded {
    val conversationId = sendTarget(backend, args)
    val message = findMessage(backend.messages(conversationId, LOOKUP_WINDOW), stringArg(args, "messageId") ?: "")
    val poll = (MessageContentParser.parse(message.dataType, message.rawContent) as? MessageContent.Poll)?.descriptor
    requireNotNull(poll) { "message ${message.id.toString().take(ID_PREFIX)} is not a poll" }
    require(!poll.closed) { "this poll is closed" }
    val choice = stringArg(args, "option") ?: return@guarded ToolReply("option is empty", true)
    val index = choice.toIntOrNull()?.minus(1) ?: poll.options.indexOfFirst { it.equals(choice, ignoreCase = true) }
    require(index in poll.options.indices) { "option must be a number from 1 to ${poll.options.size} or the exact option text" }
    val changed = backend.vote(conversationId, message, index, poll)
    ToolReply("${if (changed) "voted for" else "already voted for"} \"${poll.options[index]}\" on ${message.id.toString().take(ID_PREFIX)}")
}

suspend fun toolSendEvent(backend: AgentBackend, args: JsonObject?): ToolReply = guarded {
    val conversationId = sendTarget(backend, args)
    val title = stringArg(args, "title")?.oneLine(EVENT_TITLE_CODEPOINTS * 2) ?: return@guarded ToolReply("title is empty", true)
    val zone = stringArg(args, "timezone")?.let { runCatching { ZoneId.of(it) }.getOrNull() ?: throw IllegalArgumentException("unknown timezone '$it'") } ?: ZoneOffset.UTC
    val start = parseEventInstant(stringArg(args, "start") ?: return@guarded ToolReply("start is empty", true), zone)
    val end = stringArg(args, "end")?.let { parseEventInstant(it, zone) } ?: (start + ONE_HOUR_MS)
    require(end > start) { "end must be after start" }
    val descriptor = EventDescriptor(
        title = fitDisclosed({ backend.disclose(conversationId, it) }, title, EVENT_TITLE_CODEPOINTS),
        description = stringArg(args, "description")?.truncateToCodePoints(EVENT_DESCRIPTION_CODEPOINTS).orEmpty(),
        startUtcMs = start,
        endUtcMs = end,
        timezone = zone.id,
        locationText = stringArg(args, "place")?.oneLine(EVENT_PLACE_CODEPOINTS)?.takeIf { it.isNotEmpty() },
    )
    sendTypedContent(backend, conversationId, MessageContent.Event(descriptor), "event")
}

suspend fun toolSendLocation(backend: AgentBackend, args: JsonObject?): ToolReply = guarded {
    val conversationId = sendTarget(backend, args)
    val lat = numberArg(args, "lat")
    val lon = numberArg(args, "lon")
    require(lat in -90.0..90.0) { "lat must be between -90 and 90" }
    require(lon in -180.0..180.0) { "lon must be between -180 and 180" }
    val label = stringArg(args, "label")?.oneLine(LOCATION_LABEL_CODEPOINTS * 2).orEmpty()
    val caption = fitDisclosed({ backend.disclose(conversationId, it) }, label, LOCATION_LABEL_CODEPOINTS)
    val descriptor = LocationPreviewDescriptor(
        lat = lat,
        lon = lon,
        address = label.truncateToCodePoints(LOCATION_LABEL_CODEPOINTS),
        hasImage = false,
        imageWidth = null,
        imageHeight = null,
        caption = caption.takeIf { it.isNotEmpty() },
    )
    sendTypedContent(backend, conversationId, MessageContent.Location(descriptor), "location")
}

// the card has no free text besides its fields, so on a disclosed send the organization line carries it
suspend fun toolSendContact(backend: AgentBackend, args: JsonObject?): ToolReply = guarded {
    val conversationId = sendTarget(backend, args)
    val name = stringArg(args, "name")?.scrubbed()?.oneLine(CONTACT_NAME_CODEPOINTS) ?: return@guarded ToolReply("name is empty", true)
    val phones = listArg(args, "phones").map { it.replace(PHONE_NOISE, "") }
    val emails = listArg(args, "emails").map { it.trim() }
    phones.firstOrNull { !E164.matches(it) }?.let { throw IllegalArgumentException("phone '$it' is not E.164 (like +14155550123)") }
    emails.firstOrNull { !EMAIL.matches(it) }?.let { throw IllegalArgumentException("email '$it' is not a valid address") }
    val organization = fitDisclosed({ backend.disclose(conversationId, it) }, stringArg(args, "organization")?.scrubbed()?.oneLine(CONTACT_NAME_CODEPOINTS).orEmpty(), CONTACT_NAME_CODEPOINTS)
    val descriptor = ContactCardDescriptor(displayName = name, organization = organization, phones = phones, emails = emails)
    require(descriptor.isValid()) { "contact card is not valid (at most ${ContactCardDescriptor.MAX_VALUES_PER_KIND} phones and emails)" }
    sendTypedContent(backend, conversationId, MessageContent.ContactCard(descriptor), "contact")
}

private fun captionArg(backend: AgentBackend, args: JsonObject?) =
    tagged(backend.sendPrefix, stringArg(args, "caption").orEmpty()).trim().truncateToCodePoints(CAPTION_CODEPOINTS)

suspend fun toolSendVideo(backend: AgentBackend, args: JsonObject?): ToolReply = guarded {
    val conversationId = sendTarget(backend, args)
    val file = fileArg(backend, args)
    val caption = captionArg(backend, args)
    val ffmpeg = backend.ffmpeg
    suspend fun asFile(why: String) = ToolReply("sent ${backend.sendFile(conversationId, file, caption)} as a file: $why")
    if (!ffmpeg.available) return@guarded asFile("ffmpeg/ffprobe not found on PATH, so no video thumbnail or duration")
    require(file.name.lowercase().endsWith(".mp4")) { "send_video needs an .mp4 file" }
    if (file.bytes.size > VIDEO_MAX_BYTES) return@guarded asFile("the video is over ${formatSize(VIDEO_MAX_BYTES)}, larger videos are not segmented here")
    val dir = tempDir("probe")
    val video = try {
        val source = File(dir, "in.mp4").also { it.writeBytes(file.bytes) }
        val probe = ffmpeg.probe(source, VIDEO_FORMAT)
        require(probe != null && probe.width > 0 && probe.height > 0) { "${file.name} is not a playable video" }
        val poster = ffmpeg.poster(source, probe.durationMs)
        requireNotNull(poster) { "could not read a frame from ${file.name}" }
        OutVideo(file.name, file.bytes, poster, probe.durationMs, probe.width, probe.height, probe.codec)
    } finally {
        dir.deleteRecursively()
    }
    ToolReply("sent video ${backend.sendVideo(conversationId, video, caption)}")
}

suspend fun toolSendVoice(backend: AgentBackend, args: JsonObject?): ToolReply = guarded {
    val conversationId = sendTarget(backend, args)
    val file = fileArg(backend, args)
    val type = VOICE_TYPES[file.name.substringAfterLast('.', "").lowercase()]
    requireNotNull(type) { "send_voice needs an audio file (${VOICE_TYPES.keys.joinToString(", ")})" }
    val ffmpeg = backend.ffmpeg
    val seconds = if (ffmpeg.available) {
        val dir = tempDir("probe")
        try {
            val source = File(dir, "in.${file.name.substringAfterLast('.').lowercase()}").also { it.writeBytes(file.bytes) }
            ffmpeg.probe(source)?.let { ((it.durationMs + 500) / 1000).toInt() }
        } finally {
            dir.deleteRecursively()
        }
    } else null
    val voice = OutVoice(file.name, file.bytes, type, seconds ?: 0)
    ToolReply("sent voice note ${backend.sendVoice(conversationId, voice, captionArg(backend, args))}${if (seconds == null) " (duration unknown: ffprobe not available or unreadable)" else ""}")
}

suspend fun stageVideo(video: OutVideo, key: KeyHeader, fileOps: JvmFileOperationsProvider): StagedBundle {
    val dir = tempDir("out")
    try {
        val source = File(dir, "video.mp4").also { it.writeBytes(video.bytes) }
        val payloadKey = payloadKeyFor(0)
        val iv = ByteArrayUtil.getRndByteArray(16)
        val encrypted = fileOps.createOutboxStagingPath("enc", ".encrypted")
        fileOps.writeStream(encrypted, AesCbc.streamEncryptWithCbc(fileOps.readFileAsFlow(source.absolutePath), key.aesKey, iv))
        val thumbs = decodeImage(video.poster)?.let { imageThumbs(it, payloadKey) }
        val size = video.bytes.size.toLong()
        val metadata = VideoMetadata(
            mimeType = "video/mp4",
            isSegmented = false,
            fileSize = size,
            duration = video.durationMs.toFloat(),
            key = payloadKey,
            codec = video.codec.ifEmpty { "video/mp4" },
            widthPx = video.width,
            heightPx = video.height,
            videoBitrateBps = if (video.durationMs > 0) size * 8_000L / video.durationMs else 0L,
        )
        val payload = PayloadFile(
            key = payloadKey,
            filePath = encrypted,
            previewThumbnail = thumbs?.preview,
            contentType = "video/mp4",
            descriptorContent = OdinSystemSerializer.serialize(metadata),
            isPreEncrypted = true,
            iv = iv,
        )
        val sealed = KeyHeader(iv, key.aesKey)
        return StagedBundle(
            PayloadBundle(listOf(payload), thumbs?.thumbnails.orEmpty().map { it.copy(thumbnailBytes = sealed.encryptDataAes(it.thumbnailBytes)) }, listOfNotNull(thumbs?.preview)),
            dir,
            preEncrypted = true,
        )
    } catch (e: Throwable) {
        dir.deleteRecursively()
        throw e
    }
}

fun stageVoice(voice: OutVoice): StagedBundle {
    val dir = tempDir("out")
    try {
        val source = File(dir, "voice.bin").also { it.writeBytes(voice.bytes) }
        val payload = PayloadFile(
            key = payloadKeyFor(0),
            filePath = source.absolutePath,
            contentType = voice.contentType,
            descriptorContent = DescriptorContent.descriptorContentFromAudioFile(safeName(voice.name, display = true), voice.seconds),
        )
        return StagedBundle(PayloadBundle(listOf(payload), emptyList(), emptyList()), dir)
    } catch (e: Throwable) {
        dir.deleteRecursively()
        throw e
    }
}

private fun prop(type: String, description: String) = buildJsonObject {
    put("type", type)
    put("description", description)
}

private fun listProp(description: String) = buildJsonObject {
    put("type", "array")
    put("description", description)
    put("items", buildJsonObject { put("type", "string") })
}

fun typedTools(): List<ToolDef> = listOf(
    ToolDef(
        "send_poll", "Send a poll message: a question and 2 to 10 options.",
        buildJsonObject {
            put("conversationId", CONVERSATION_PROP)
            put("question", prop("string", "Question, at most 140 characters"))
            put("options", listProp("2 to 10 options, each at most 80 characters"))
            put("allowMultiple", prop("boolean", "Let people pick several options, default false"))
        },
        listOf("conversationId", "question", "options"), stdio = false, write = true,
    ) { b, a -> toolSendPoll(b, a) },
    ToolDef(
        "vote_poll", "Cast this identity's vote on a poll message (replaces its earlier vote unless the poll allows several).",
        buildJsonObject {
            put("conversationId", CONVERSATION_PROP)
            put("messageId", MESSAGE_PROP)
            put("option", prop("string", "1-based option number or the exact option text"))
        },
        listOf("conversationId", "messageId", "option"), stdio = false, write = true,
    ) { b, a -> toolVotePoll(b, a) },
    ToolDef(
        "send_event", "Send an event message (no cover photo).",
        buildJsonObject {
            put("conversationId", CONVERSATION_PROP)
            put("title", prop("string", "Title, at most 80 characters"))
            put("start", prop("string", "ISO date-time, e.g. 2026-10-01T18:00 (in timezone) or 2026-10-01T18:00:00Z"))
            put("end", prop("string", "ISO date-time, default one hour after start"))
            put("timezone", prop("string", "IANA zone such as Europe/Berlin, default UTC"))
            put("place", prop("string", "Where, at most 120 characters"))
            put("description", prop("string", "At most 280 characters"))
        },
        listOf("conversationId", "title", "start"), stdio = false, write = true,
    ) { b, a -> toolSendEvent(b, a) },
    ToolDef(
        "send_location", "Send a static location (never live sharing).",
        buildJsonObject {
            put("conversationId", CONVERSATION_PROP)
            put("lat", prop("number", "Latitude, -90 to 90"))
            put("lon", prop("number", "Longitude, -180 to 180"))
            put("label", prop("string", "Optional place name or caption"))
        },
        listOf("conversationId", "lat", "lon"), stdio = false, write = true,
    ) { b, a -> toolSendLocation(b, a) },
    ToolDef(
        "send_contact", "Send a contact card.",
        buildJsonObject {
            put("conversationId", CONVERSATION_PROP)
            put("name", prop("string", "Display name, at most 80 characters"))
            put("phones", listProp("Phone numbers in E.164, like +14155550123"))
            put("emails", listProp("Email addresses"))
            put("organization", prop("string", "Optional organization"))
        },
        listOf("conversationId", "name"), stdio = false, write = true,
    ) { b, a -> toolSendContact(b, a) },
    ToolDef(
        "send_video", "Send an .mp4 video from the files directory as a video message with thumbnail and duration (needs ffmpeg and ffprobe, else it goes as a plain file). Max 5 MB as video.",
        buildJsonObject {
            put("conversationId", CONVERSATION_PROP)
            put("path", prop("string", "File inside the files directory"))
            put("caption", prop("string", "Optional text"))
        },
        listOf("conversationId", "path"), stdio = false, write = true,
    ) { b, a -> toolSendVideo(b, a) },
    ToolDef(
        "send_voice", "Send an audio file from the files directory as a voice note (m4a, mp3, wav, ogg, aac, opus; max 10 MB).",
        buildJsonObject {
            put("conversationId", CONVERSATION_PROP)
            put("path", prop("string", "File inside the files directory"))
            put("caption", prop("string", "Optional text"))
        },
        listOf("conversationId", "path"), stdio = false, write = true,
    ) { b, a -> toolSendVoice(b, a) },
)
