package id.homebase.agent

import id.homebase.api.common.OdinId
import id.homebase.api.util.truncateToCodePoints
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.ReplyPreview
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
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
}

class ToolReply(val text: String, val isError: Boolean = false)

private const val LOOKUP_WINDOW = 200
private const val REPLY_QUOTE_CODEPOINTS = 80
private const val ID_PREFIX = 8

fun formatMessageLine(msg: ChatMsg): String {
    val date = Instant.ofEpochMilli(msg.userDate).truncatedTo(ChronoUnit.MINUTES).toString().take(16)
    val text = msg.text.replace("\r", "").replace("\n", "\\n").truncateToCodePoints(MCP_TEXT_CODEPOINTS)
    return "${msg.id.toString().take(ID_PREFIX)} $date ${msg.author}: $text"
}

private fun stringArg(args: JsonObject?, name: String): String? =
    args?.get(name)?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

private fun conversationArg(args: JsonObject?, allowlist: Allowlist): Uuid? {
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

private suspend fun guarded(block: suspend () -> ToolReply): ToolReply =
    try {
        block()
    } catch (e: IllegalArgumentException) {
        ToolReply("refused: ${e.message}", isError = true)
    } catch (e: NotLoggedInException) {
        ToolReply("not logged in: ${e.message}", isError = true)
    } catch (e: Exception) {
        ToolReply("error: ${e.message}", isError = true)
    }

private val BAD_CONVERSATION = ToolReply("conversationId must be an allowed conversation id (uuid or prefix)", true)

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
    val lines = backend.messages(conversationId, limit, before)
        .filter { backend.allowlist.allowsAuthor(it.author, conversationId) }
        .map(::formatMessageLine)
    ToolReply(lines.joinToString("\n").ifEmpty { "(no messages)" })
}

suspend fun toolSearchMessages(backend: AgentBackend, args: JsonObject?): ToolReply = guarded {
    val query = stringArg(args, "query") ?: return@guarded ToolReply("query is empty", true)
    val limit = (args?.get("limit")?.jsonPrimitive?.intOrNull ?: MCP_DEFAULT_SEARCH).coerceIn(1, MCP_MAX_SEARCH)
    val scoped = stringArg(args, "conversationId")?.let {
        conversationArg(args, backend.allowlist) ?: return@guarded BAD_CONVERSATION
    }
    scoped?.let(backend.allowlist::requireConversation)
    val ids = scoped?.let(::listOf) ?: backend.allowlist.allowedConversationIds().toList()
    // ponytail: client-side scan of the newest LOOKUP_WINDOW messages per conversation; upgrade is a server-side search query.
    val hits = ids.flatMap { id ->
        backend.messages(id, LOOKUP_WINDOW)
            .filter { backend.allowlist.allowsAuthor(it.author, id) && it.text.contains(query, ignoreCase = true) }
    }.sortedBy { it.userDate }.takeLast(limit)
    val lines = hits.map { if (scoped == null) "${it.conversationId.toString().take(ID_PREFIX)} ${formatMessageLine(it)}" else formatMessageLine(it) }
    ToolReply(lines.joinToString("\n").ifEmpty { "(no matches)" })
}

suspend fun toolSendMessage(backend: AgentBackend, args: JsonObject?): ToolReply = guarded {
    val conversationId = conversationArg(args, backend.allowlist) ?: return@guarded BAD_CONVERSATION
    backend.allowlist.requireSend(conversationId)
    val text = stringArg(args, "text") ?: return@guarded ToolReply("text is empty", true)
    val reply = stringArg(args, "replyToId")?.let { ref ->
        val parent = findMessage(backend.messages(conversationId, LOOKUP_WINDOW), ref)
        ReplyPreview(
            replyUniqueId = parent.id,
            authorOdinId = parent.author?.domainName ?: "null",
            message = parent.text.trim().truncateToCodePoints(REPLY_QUOTE_CODEPOINTS),
        )
    }
    ToolReply("sent ${backend.send(conversationId, "$BOT_PREFIX $text", reply)}")
}

class SessionBackend(private val profile: String) : AgentBackend {
    private var session: Session? = null
    private var cachedAllowlist: Allowlist? = null
    private var loadedAt = 0L

    private suspend fun open(): Session = session ?: openSession(profile).also { session = it }

    override val allowlist: Allowlist
        get() = cachedAllowlist ?: error("allowlist not loaded")

    suspend fun load() {
        if (cachedAllowlist != null && System.nanoTime() - loadedAt < ALLOWLIST_TTL_NANOS) return
        val session = open()
        cachedAllowlist = loadConfig(profile, session.identity).allowlist.also {
            refreshAllowlist(session, it)
            if (!it.memberMode) it.learn(discoverConversations(session))
        }
        loadedAt = System.nanoTime()
    }

    override suspend fun messages(conversationId: Uuid, limit: Int, beforeMs: Long?) =
        fetchMessages(open(), conversationId, limit, beforeMs)

    override suspend fun send(conversationId: Uuid, text: String, replyTo: ReplyPreview?) =
        sendToConversation(open(), allowlist, conversationId, text, replyTo)
}

private suspend fun ready(backend: SessionBackend, block: suspend () -> ToolReply): CallToolResult {
    val reply = guarded {
        backend.load()
        block()
    }
    return CallToolResult(content = listOf(TextContent(reply.text)), isError = reply.isError)
}

suspend fun mcp(profile: String) {
    val protocolOut = System.out
    System.setOut(System.err)

    val backend = SessionBackend(profile)
    val server = Server(
        Implementation(name = "chat-agent", version = "1.0.0"),
        ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
    )

    fun prop(type: String, description: String) = buildJsonObject {
        put("type", type)
        put("description", description)
    }
    val conversationProp = prop("string", "Conversation id or 8-char prefix")

    server.addTool(
        name = "list_conversations",
        description = "List readable conversations: id, title, member count.",
        inputSchema = ToolSchema(properties = buildJsonObject {}),
    ) { ready(backend) { toolListConversations(backend) } }

    server.addTool(
        name = "get_conversation",
        description = "Show a conversation's title, members and whether sending is allowed.",
        inputSchema = ToolSchema(
            properties = buildJsonObject { put("conversationId", conversationProp) },
            required = listOf("conversationId"),
        ),
    ) { request -> ready(backend) { toolGetConversation(backend, request.arguments) } }

    server.addTool(
        name = "read_messages",
        description = "Read recent messages, oldest first: '<id8> <minute> <author>: <text>'.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("conversationId", conversationProp)
                put("limit", prop("integer", "Max 50, default 20"))
                put("before", prop("string", "ISO date or message id; older only"))
            },
            required = listOf("conversationId"),
        ),
    ) { request -> ready(backend) { toolReadMessages(backend, request.arguments) } }

    server.addTool(
        name = "search_messages",
        description = "Case-insensitive text search over each conversation's last 200 messages.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("query", prop("string", "Text to find"))
                put("conversationId", prop("string", "Limit to one conversation"))
                put("limit", prop("integer", "Max 20, default 10"))
            },
            required = listOf("query"),
        ),
    ) { request -> ready(backend) { toolSearchMessages(backend, request.arguments) } }

    server.addTool(
        name = "send_message",
        description = "Send a text message, prefixed with the robot emoji, to an allowed conversation.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("conversationId", conversationProp)
                put("text", prop("string", "Message text"))
                put("replyToId", prop("string", "Message id to quote"))
            },
            required = listOf("conversationId", "text"),
        ),
    ) { request -> ready(backend) { toolSendMessage(backend, request.arguments) } }

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
