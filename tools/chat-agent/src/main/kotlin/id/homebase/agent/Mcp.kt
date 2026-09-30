package id.homebase.agent

import id.homebase.api.common.OdinId
import id.homebase.api.util.truncateToCodePoints
import id.homebase.chat.poll.PollDescriptor
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.content.MessageContent
import id.homebase.chat.services.ReplyPreview
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.uuid.Uuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

const val MCP_MAX_READ = 50
const val MCP_DEFAULT_READ = 20
const val MCP_DEFAULT_SEARCH = 10
const val MCP_MAX_SEARCH = 20
private const val MCP_TEXT_CODEPOINTS = 1000
private const val ALLOWLIST_TTL_NANOS = 60_000_000_000L

interface AgentBackend {
    val allowlist: Allowlist
    suspend fun messages(conversationId: Uuid, limit: Int, beforeMs: Long? = null): List<ChatMsg>
    suspend fun send(conversationId: Uuid, text: String, replyTo: ReplyPreview? = null): Uuid
    val filesDir: File? get() = null
    val schedules: ScheduleScope? get() = null
    val sendPrefix: String get() = if (allowlist.delegate) BOT_PREFIX else ""
    suspend fun sendFile(conversationId: Uuid, file: OutFile, caption: String): Uuid = error("sending files is not supported")
    fun visible(messages: List<ChatMsg>, conversationId: Uuid): List<ChatMsg> =
        messages.filter { allowlist.allowsAuthor(it.author, conversationId) }
    fun frame(lines: String): String = lines
    fun isOwn(message: ChatMsg): Boolean = false
    suspend fun react(conversationId: Uuid, message: ChatMsg, emoji: String, add: Boolean): Unit = error("reactions are not supported")
    suspend fun edit(conversationId: Uuid, message: ChatMsg, text: String): Unit = error("editing is not supported")
    suspend fun delete(conversationId: Uuid, message: ChatMsg): Unit = error("deleting is not supported")
    fun disclose(conversationId: Uuid, text: String): String = text
    val ffmpeg: Ffmpeg get() = DEFAULT_FFMPEG
    suspend fun sendTyped(conversationId: Uuid, content: MessageContent): Uuid = error("typed messages are not supported")
    suspend fun vote(conversationId: Uuid, message: ChatMsg, option: Int, poll: PollDescriptor): Boolean = error("voting is not supported")
    suspend fun sendVideo(conversationId: Uuid, video: OutVideo, caption: String): Uuid = error("sending video is not supported")
    suspend fun sendVoice(conversationId: Uuid, voice: OutVoice, caption: String): Uuid = error("sending voice notes is not supported")
}

val DEFAULT_FFMPEG by lazy { Ffmpeg() }

class ToolReply(val text: String, val isError: Boolean = false)

internal const val LOOKUP_WINDOW = 200
internal const val ID_PREFIX = 8
private val LINE_BREAK = Regex("\\R")

fun formatMessageLine(msg: ChatMsg): String {
    val date = Instant.ofEpochMilli(msg.userDate).truncatedTo(ChronoUnit.MINUTES).toString().take(16)
    val text = msg.display.replace(LINE_BREAK) { "\\n" }.truncateToCodePoints(MCP_TEXT_CODEPOINTS)
    return "${msg.id.toString().take(ID_PREFIX)} $date ${msg.author}: $text"
}

internal fun stringArg(args: JsonObject?, name: String): String? =
    args?.get(name)?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

internal fun conversationArg(args: JsonObject?, allowlist: Allowlist): Uuid? {
    val raw = stringArg(args, "conversationId")?.lowercase() ?: return null
    runCatching { Uuid.parse(raw) }.getOrNull()?.let { return it }
    return allowlist.allowedConversationIds().singleOrNull { it.toString().startsWith(raw) }
}

fun findMessage(messages: List<ChatMsg>, ref: String): ChatMsg {
    val key = ref.trim().lowercase()
    require(key.length >= ID_PREFIX) { "message id needs at least $ID_PREFIX characters" }
    val hits = messages.filter { it.id.toString().startsWith(key) }
    require(hits.isNotEmpty()) { "message $ref not found in the last $LOOKUP_WINDOW messages" }
    require(hits.size == 1) { "message id $ref is ambiguous" }
    return hits.single()
}

private fun parseInstant(raw: String): Long? =
    runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
        ?: runCatching { LocalDate.parse(raw).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()

suspend fun guarded(block: suspend () -> ToolReply): ToolReply =
    try {
        block()
    } catch (e: IllegalArgumentException) {
        ToolReply("refused: ${e.message}", isError = true)
    } catch (e: NotLoggedInException) {
        ToolReply("not logged in: ${e.message}", isError = true)
    } catch (e: Exception) {
        ToolReply("error: ${e.message}", isError = true)
    }

internal val BAD_CONVERSATION = ToolReply("conversationId must be an allowed conversation id (uuid or prefix)", true)

private fun Allowlist.line(id: Uuid): String =
    "$id ${title(id) ?: "conversation"}" + (memberCount(id)?.let { " ($it members)" } ?: "")

suspend fun toolListConversations(backend: AgentBackend): ToolReply = guarded {
    ToolReply(backend.allowlist.allowedConversationIds().joinToString("\n") { backend.allowlist.line(it) })
}

suspend fun toolGetConversation(backend: AgentBackend, args: JsonObject?): ToolReply = guarded {
    val conversationId = conversationArg(args, backend.allowlist) ?: return@guarded BAD_CONVERSATION
    val allowlist = backend.allowlist
    allowlist.requireConversation(conversationId)
    val members = if (conversationId == ChatProtocol.ConversationWithYourselfId) "you"
    else allowlist.info(conversationId)?.members?.joinToString(", ") ?: "unknown"
    ToolReply("${allowlist.line(conversationId)}\nmembers: $members\ncan send: ${if (allowlist.allowsSend(conversationId)) "yes" else "no"}")
}

suspend fun toolReadMessages(backend: AgentBackend, args: JsonObject?): ToolReply = guarded {
    val conversationId = conversationArg(args, backend.allowlist) ?: return@guarded BAD_CONVERSATION
    backend.allowlist.requireConversation(conversationId)
    val limit = (args?.get("limit")?.jsonPrimitive?.intOrNull ?: MCP_DEFAULT_READ).coerceIn(1, MCP_MAX_READ)
    val before = stringArg(args, "before")?.let { ref ->
        parseInstant(ref)
            ?: findMessage(backend.messages(conversationId, LOOKUP_WINDOW), ref).userDate
    }
    val lines = backend.visible(backend.messages(conversationId, limit, before), conversationId).map(::formatMessageLine)
    ToolReply(backend.frame(lines.joinToString("\n").ifEmpty { "(no messages)" }))
}

suspend fun toolSearchMessages(backend: AgentBackend, args: JsonObject?): ToolReply = guarded {
    val query = stringArg(args, "query") ?: return@guarded ToolReply("query is empty", true)
    val limit = (args?.get("limit")?.jsonPrimitive?.intOrNull ?: MCP_DEFAULT_SEARCH).coerceIn(1, MCP_MAX_SEARCH)
    val scoped = stringArg(args, "conversationId")?.let {
        conversationArg(args, backend.allowlist) ?: return@guarded BAD_CONVERSATION
    }
    scoped?.let(backend.allowlist::requireConversation)
    val ids = scoped?.let(::listOf) ?: backend.allowlist.allowedConversationIds().toList()
    // ponytail: client-side scan of the newest 200 per conversation; upgrade = server-side search
    val hits = ids.flatMap { id ->
        backend.visible(backend.messages(id, LOOKUP_WINDOW), id).filter { it.display.contains(query, ignoreCase = true) }
    }.sortedBy { it.userDate }.takeLast(limit)
    val lines = hits.map { if (scoped == null) "${it.conversationId.toString().take(ID_PREFIX)} ${formatMessageLine(it)}" else formatMessageLine(it) }
    ToolReply(backend.frame(lines.joinToString("\n").ifEmpty { "(no matches)" }))
}

suspend fun toolSendMessage(backend: AgentBackend, args: JsonObject?): ToolReply = guarded {
    val conversationId = conversationArg(args, backend.allowlist) ?: return@guarded BAD_CONVERSATION
    backend.allowlist.requireSend(conversationId)
    val text = stringArg(args, "text") ?: return@guarded ToolReply("text is empty", true)
    val reply = stringArg(args, "replyToId")?.let { ref ->
        val parent = findMessage(backend.messages(conversationId, LOOKUP_WINDOW), ref)
        parent.toReplyPreview()
    }
    ToolReply("sent ${backend.send(conversationId, tagged(backend.sendPrefix, text), reply)}")
}

suspend fun toolSendFile(backend: AgentBackend, args: JsonObject?): ToolReply = guarded {
    val conversationId = conversationArg(args, backend.allowlist) ?: return@guarded BAD_CONVERSATION
    backend.allowlist.requireSend(conversationId)
    val root = backend.filesDir ?: return@guarded ToolReply("refused: no mcpFilesDir is configured, file sending is disabled", true)
    val path = stringArg(args, "path") ?: return@guarded ToolReply("path is empty", true)
    val file = loadInside(root, path)
    val caption = stringArg(args, "caption").orEmpty()
    ToolReply("sent ${backend.sendFile(conversationId, file, tagged(backend.sendPrefix, caption).trim())}")
}

class SessionBackend(private val profile: String, private val scope: Uuid? = null, private val readOnly: Boolean = false) : AgentBackend {
    private var session: Session? = null
    private var cachedAllowlist: Allowlist? = null
    private var loadedAt = 0L

    private suspend fun open(): Session = session ?: openSession(profile).also { session = it }

    override val allowlist: Allowlist
        get() = cachedAllowlist ?: error("allowlist not loaded")

    suspend fun load() {
        if (cachedAllowlist != null && System.nanoTime() - loadedAt < ALLOWLIST_TTL_NANOS) return
        val session = open()
        val config = loadConfig(profile, session.identity)
        filesDir = config.mcpFilesDir
        val allowlist = config.allowlist
        refreshAllowlist(session, allowlist)
        if (!allowlist.memberMode) allowlist.learn(discoverConversations(session))
        cachedAllowlist = allowlist.copy(scope, readOnly)
        loadedAt = System.nanoTime()
    }

    override suspend fun messages(conversationId: Uuid, limit: Int, beforeMs: Long?) =
        fetchMessages(open(), conversationId, limit, beforeMs)

    override suspend fun send(conversationId: Uuid, text: String, replyTo: ReplyPreview?) =
        sendToConversation(open(), allowlist, conversationId, text, replyTo)

    override var filesDir: File? = null
        private set

    override suspend fun sendFile(conversationId: Uuid, file: OutFile, caption: String) =
        sendToConversation(open(), allowlist, conversationId, caption, files = listOf(file))
}

class ToolDef(
    val name: String,
    val description: String,
    val properties: JsonObject,
    val required: List<String> = emptyList(),
    val stdio: Boolean = true,
    val scoped: Boolean = true,
    val write: Boolean = false,
    val run: suspend (AgentBackend, JsonObject?) -> ToolReply,
)

private fun prop(type: String, description: String) = buildJsonObject {
    put("type", type)
    put("description", description)
}

internal val CONVERSATION_PROP = prop("string", "Conversation id or 8-char prefix")
internal val MESSAGE_PROP = prop("string", "Message id (at least 8 characters)")
private val EMOJI_PROP = prop("string", "One emoji")

private val BASE_TOOLS: List<ToolDef> = listOf(
    ToolDef("list_conversations", "List readable conversations: id, title, member count.", buildJsonObject {}, scoped = false) { b, _ -> toolListConversations(b) },
    ToolDef("get_conversation", "Show a conversation's title, members and whether sending is allowed.", buildJsonObject { put("conversationId", CONVERSATION_PROP) }, listOf("conversationId")) { b, a -> toolGetConversation(b, a) },
    ToolDef(
        "read_messages", "Read recent messages, oldest first: '<id8> <minute> <author>: <text>'.",
        buildJsonObject {
            put("conversationId", CONVERSATION_PROP)
            put("limit", prop("integer", "Max 50, default 20"))
            put("before", prop("string", "ISO date or message id; older only"))
        },
        listOf("conversationId"),
    ) { b, a -> toolReadMessages(b, a) },
    ToolDef(
        "search_messages", "Case-insensitive text search over each conversation's last 200 messages.",
        buildJsonObject {
            put("query", prop("string", "Text to find"))
            put("conversationId", prop("string", "Limit to one conversation"))
            put("limit", prop("integer", "Max 20, default 10"))
        },
        listOf("query"),
    ) { b, a -> toolSearchMessages(b, a) },
    ToolDef(
        "send_message", "Send a text message, prefixed with the robot emoji, to an allowed conversation.",
        buildJsonObject {
            put("conversationId", CONVERSATION_PROP)
            put("text", prop("string", "Message text"))
            put("replyToId", prop("string", "Message id to quote"))
        },
        listOf("conversationId", "text"), write = true,
    ) { b, a -> toolSendMessage(b, a) },
    ToolDef(
        "send_file", "Send a file from the configured files directory to an allowed conversation.",
        buildJsonObject {
            put("conversationId", CONVERSATION_PROP)
            put("path", prop("string", "File inside the files directory (max 10 MB)"))
            put("caption", prop("string", "Optional text"))
        },
        listOf("conversationId", "path"), write = true,
    ) { b, a -> toolSendFile(b, a) },
    ToolDef("react", "Add an emoji reaction to a message.", buildJsonObject { put("messageId", MESSAGE_PROP); put("emoji", EMOJI_PROP) }, listOf("messageId", "emoji"), stdio = false, write = true) { b, a -> toolReact(b, a, add = true) },
    ToolDef("unreact", "Remove your emoji reaction from a message.", buildJsonObject { put("messageId", MESSAGE_PROP); put("emoji", EMOJI_PROP) }, listOf("messageId", "emoji"), stdio = false, write = true) { b, a -> toolReact(b, a, add = false) },
    ToolDef(
        "edit_message", "Replace the text of one of your own short text messages.",
        buildJsonObject { put("messageId", MESSAGE_PROP); put("text", prop("string", "New text")) },
        listOf("messageId", "text"), stdio = false, write = true,
    ) { b, a -> toolEditMessage(b, a) },
    ToolDef("delete_message", "Delete one of your own messages for everyone.", buildJsonObject { put("messageId", MESSAGE_PROP) }, listOf("messageId"), stdio = false, write = true) { b, a -> toolDeleteMessage(b, a) },
)

val CHAT_TOOLS: List<ToolDef> = BASE_TOOLS + typedTools() + scheduleTools()

fun isValidReaction(emoji: String) = emoji.isNotBlank() && emoji.length <= 8 && !emoji.startsWith("_")

private suspend fun ownMessage(backend: AgentBackend, args: JsonObject?, verb: String): Pair<Uuid, ChatMsg>? {
    val conversationId = conversationArg(args, backend.allowlist) ?: return null
    backend.allowlist.requireSend(conversationId)
    val message = findMessage(backend.messages(conversationId, LOOKUP_WINDOW), stringArg(args, "messageId") ?: "")
    require(backend.isOwn(message)) { "you can only $verb your own messages" }
    return conversationId to message
}

suspend fun toolReact(backend: AgentBackend, args: JsonObject?, add: Boolean): ToolReply = guarded {
    val conversationId = conversationArg(args, backend.allowlist) ?: return@guarded BAD_CONVERSATION
    backend.allowlist.requireSend(conversationId)
    val emoji = stringArg(args, "emoji") ?: return@guarded ToolReply("emoji is empty", true)
    require(isValidReaction(emoji)) { "emoji must be a single emoji" }
    val message = findMessage(backend.messages(conversationId, LOOKUP_WINDOW), stringArg(args, "messageId") ?: "")
    backend.react(conversationId, message, emoji, add)
    ToolReply("${if (add) "reacted" else "removed reaction"} ${message.id.toString().take(ID_PREFIX)}")
}

suspend fun toolEditMessage(backend: AgentBackend, args: JsonObject?): ToolReply = guarded {
    val text = stringArg(args, "text") ?: return@guarded ToolReply("text is empty", true)
    val (conversationId, message) = ownMessage(backend, args, "edit") ?: return@guarded BAD_CONVERSATION
    backend.edit(conversationId, message, tagged(backend.sendPrefix, if (backend.sendPrefix.isEmpty()) text else text.removePrefix(backend.sendPrefix).trimStart()))
    ToolReply("edited ${message.id.toString().take(ID_PREFIX)}")
}

suspend fun toolDeleteMessage(backend: AgentBackend, args: JsonObject?): ToolReply = guarded {
    val (conversationId, message) = ownMessage(backend, args, "delete") ?: return@guarded BAD_CONVERSATION
    backend.delete(conversationId, message)
    ToolReply("deleted ${message.id.toString().take(ID_PREFIX)}")
}

private fun callResult(reply: ToolReply) = CallToolResult(content = listOf(TextContent(reply.text)), isError = reply.isError)

private suspend fun ready(backend: SessionBackend, block: suspend () -> ToolReply): ToolReply =
    guarded {
        backend.load()
        block()
    }

private val FILE_TOOLS = MEDIA_SEND_TOOLS + "send_file"

fun scopedTools(names: Set<String>, filesDir: File?): List<ToolDef> =
    CHAT_TOOLS.filter { it.scoped && it.name in names && (it.name !in FILE_TOOLS || filesDir != null) }.map {
        ToolDef(it.name, it.description, JsonObject(it.properties - "conversationId"), it.required - "conversationId", run = it.run)
    }

fun scopedArguments(args: JsonObject?, conversation: Uuid) =
    JsonObject((args?.toMap().orEmpty() - "conversationId") + ("conversationId" to JsonPrimitive(conversation.toString())))

fun Server.addChatTool(def: ToolDef, handler: suspend (JsonObject?) -> ToolReply) = addTool(
    name = def.name,
    description = def.description,
    inputSchema = ToolSchema(properties = def.properties, required = def.required),
) { request -> callResult(handler(request.arguments)) }

suspend fun mcp(profile: String, scope: Uuid? = null, readOnly: Boolean = false) {
    val protocolOut = System.out
    System.setOut(System.err)

    val backend = SessionBackend(profile, scope, readOnly)
    val server = Server(
        Implementation(name = "chat-agent", version = agentVersion()),
        ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
    )
    CHAT_TOOLS.filter { it.stdio && !(readOnly && it.write) }.forEach { def ->
        server.addChatTool(def) { args -> ready(backend) { def.run(backend, args) } }
    }

    val transport = StdioServerTransport(exitOnEof(System.`in`).asSource().buffered(), protocolOut.asSink().buffered())
    val closed = CompletableDeferred<Unit>()
    server.onClose { closed.complete(Unit) }
    server.createSession(transport)
    closed.await()
}

private fun exitOnEof(input: java.io.InputStream): java.io.InputStream {
    fun eof() = Thread { Thread.sleep(2000); kotlin.system.exitProcess(0) }.apply { isDaemon = true }.start()
    return object : java.io.InputStream() {
        override fun read(): Int = input.read().also { if (it < 0) eof() }
        override fun read(b: ByteArray, off: Int, len: Int): Int = input.read(b, off, len).also { if (it < 0) eof() }
    }
}
