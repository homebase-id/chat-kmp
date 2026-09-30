package id.homebase.agent

import id.homebase.api.common.OdinId
import id.homebase.api.util.truncateToCodePoints
import id.homebase.chat.services.ChatProtocol
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import java.time.Instant
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
private const val MCP_TEXT_CODEPOINTS = 1000

interface AgentBackend {
    val allowlist: Allowlist
    suspend fun messages(conversationId: Uuid, limit: Int): List<ChatMsg>
    suspend fun send(conversationId: Uuid, text: String): Uuid
}

class ToolReply(val text: String, val isError: Boolean = false)

fun formatMessageLine(msg: ChatMsg): String {
    val date = Instant.ofEpochMilli(msg.userDate).truncatedTo(ChronoUnit.MINUTES).toString().take(16)
    val text = msg.text.replace("\r", "").replace("\n", "\\n").truncateToCodePoints(MCP_TEXT_CODEPOINTS)
    return "$date ${msg.author}: $text"
}

private fun parseConversation(args: JsonObject?): Uuid? =
    args?.get("conversationId")?.jsonPrimitive?.contentOrNull?.let { runCatching { Uuid.parse(it) }.getOrNull() }

private suspend fun guarded(block: suspend () -> ToolReply): ToolReply =
    try {
        block()
    } catch (e: IllegalArgumentException) {
        ToolReply("refused: ${e.message}", isError = true)
    } catch (e: Exception) {
        ToolReply("error: ${e.message}", isError = true)
    }

suspend fun toolListConversations(backend: AgentBackend): ToolReply = guarded {
    val lines = backend.allowlist.allowedConversationIds().map {
        "$it ${backend.allowlist.title(it) ?: "conversation"}"
    }
    ToolReply(lines.joinToString("\n"))
}

suspend fun toolReadMessages(backend: AgentBackend, args: JsonObject?): ToolReply = guarded {
    val conversationId = parseConversation(args) ?: return@guarded ToolReply("conversationId must be a UUID", true)
    backend.allowlist.requireConversation(conversationId)
    val limit = (args?.get("limit")?.jsonPrimitive?.intOrNull ?: MCP_DEFAULT_READ).coerceIn(1, MCP_MAX_READ)
    val lines = backend.messages(conversationId, limit)
        .filter { backend.allowlist.allowsAuthor(it.author, conversationId) }
        .map(::formatMessageLine)
    ToolReply(lines.joinToString("\n").ifEmpty { "(no messages)" })
}

suspend fun toolSendMessage(backend: AgentBackend, args: JsonObject?): ToolReply = guarded {
    val conversationId = parseConversation(args) ?: return@guarded ToolReply("conversationId must be a UUID", true)
    backend.allowlist.requireSend(conversationId)
    val text = args?.get("text")?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    if (text.isEmpty()) return@guarded ToolReply("text is empty", true)
    ToolReply("sent ${backend.send(conversationId, "$BOT_PREFIX $text")}")
}

class SessionBackend(private val profile: String) : AgentBackend {
    private var session: Session? = null
    private var cachedAllowlist: Allowlist? = null

    private suspend fun open(): Session = session ?: openSession(profile).also { session = it }

    override val allowlist: Allowlist
        get() = cachedAllowlist ?: error("allowlist not loaded")

    suspend fun load() {
        val session = open()
        cachedAllowlist = loadConfig(profile, session.identity).allowlist.also { refreshAllowlist(session.credentials, it) }
    }

    override suspend fun messages(conversationId: Uuid, limit: Int) =
        fetchMessages(open().credentials, conversationId, limit)

    override suspend fun send(conversationId: Uuid, text: String) =
        sendToConversation(open(), allowlist, conversationId, text)
}

private fun CallToolResult.Companion.of(reply: ToolReply) =
    CallToolResult(content = listOf(TextContent(reply.text)), isError = reply.isError)

private suspend fun ready(backend: SessionBackend, block: suspend () -> ToolReply): CallToolResult =
    CallToolResult.of(
        try {
            backend.load()
            block()
        } catch (e: NotLoggedInException) {
            ToolReply("not logged in: ${e.message}", true)
        } catch (e: Exception) {
            ToolReply("error: ${e.message}", true)
        }
    )

suspend fun mcp(profile: String) {
    val protocolOut = System.out
    System.setOut(System.err)

    val backend = SessionBackend(profile)
    val server = Server(
        Implementation(name = "chat-agent", version = "1.0.0"),
        ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
    )

    server.addTool(
        name = "list_conversations",
        description = "List the conversations this agent may read and write.",
        inputSchema = ToolSchema(properties = buildJsonObject {}),
    ) { ready(backend) { toolListConversations(backend) } }

    server.addTool(
        name = "read_messages",
        description = "Read recent messages of an allowed conversation, oldest first, one per line: 'date author: text'.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("conversationId", buildJsonObject { put("type", "string") })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("maximum", MCP_MAX_READ)
                })
            },
            required = listOf("conversationId"),
        ),
    ) { request -> ready(backend) { toolReadMessages(backend, request.arguments) } }

    server.addTool(
        name = "send_message",
        description = "Send a text message to an allowed conversation. The text is prefixed with the robot emoji.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put("conversationId", buildJsonObject { put("type", "string") })
                put("text", buildJsonObject { put("type", "string") })
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
