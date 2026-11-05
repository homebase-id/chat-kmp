package id.homebase.agent

import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.file.JvmFileOperationsProvider
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.util.truncateToCodePoints
import id.homebase.chat.agentui.AgentUiActionDescriptor
import id.homebase.chat.agentui.AgentUiDescriptor
import id.homebase.chat.agentui.AgentUiLimits
import id.homebase.chat.agentui.AgentUiTree
import id.homebase.chat.agentui.AgentUiTreeResult
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.content.MessageContent
import id.homebase.chat.services.content.MessageContentParser
import id.homebase.upload.PayloadBundle
import java.io.File
import kotlin.uuid.Uuid
import id.homebase.api.client.KeyHeader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

const val SEND_UI_TOOL = "send_ui"
const val EDIT_UI_TOOL = "edit_ui"
val AGENT_UI_TOOLS = setOf(SEND_UI_TOOL, EDIT_UI_TOOL)

fun ChatMsg.agentUiCard(): AgentUiDescriptor? =
    (MessageContentParser.parse(dataType, rawContent) as? MessageContent.AgentUi)?.descriptor

fun ChatMsg.agentUiAction(): AgentUiActionDescriptor? =
    (MessageContentParser.parse(dataType, rawContent) as? MessageContent.AgentUiAction)?.descriptor

fun requireValidTree(tree: ByteArray, imageKeys: Set<String>) {
    val result = AgentUiTree.decode(tree, imageKeys)
    if (result is AgentUiTreeResult.Invalid) throw IllegalArgumentException("tree is invalid: ${result.reason}")
}

fun cardDescriptor(fallback: String?, title: String?, rev: Int): AgentUiDescriptor {
    val descriptor = AgentUiDescriptor(
        fallback = fallback?.trim().orEmpty().truncateToCodePoints(AgentUiDescriptor.MAX_FALLBACK_CP),
        title = title?.oneLine(AgentUiDescriptor.MAX_TITLE_CP)?.ifEmpty { null },
        rev = rev,
    )
    require(descriptor.isValid()) { "fallback is empty" }
    return descriptor
}

suspend fun sendUi(backend: AgentBackend, conversationId: Uuid, tree: ByteArray, fallback: String?, title: String?, images: List<OutFile> = emptyList()): Uuid {
    require(backend.disclose(conversationId, "").isBlank()) { "an agent card cannot carry the AI disclosure" }
    require(images.size <= AgentUiLimits.MAX_IMAGES) { "at most ${AgentUiLimits.MAX_IMAGES} images" }
    images.firstOrNull { decodeImage(it.bytes) == null }?.let { throw IllegalArgumentException("${it.name} is not an image") }
    requireValidTree(tree, images.indices.map(::payloadKeyFor).toSet())
    return backend.sendAgentUi(conversationId, cardDescriptor(fallback, title, 0), tree, images)
}

suspend fun editUi(backend: AgentBackend, conversationId: Uuid, message: ChatMsg, tree: ByteArray, fallback: String?, title: String?): AgentUiDescriptor {
    val id8 = message.id.toString().take(ID_PREFIX)
    val card = requireNotNull(message.agentUiCard()) { "message $id8 is not an agent card" }
    require(message.sender == null || message.sender == backend.self) { "you can only edit your own cards" }
    requireValidTree(tree, message.payloads.orEmpty().map { it.key }.filter { AgentUiLimits.IMAGE_KEY.matches(it) }.toSet())
    val next = cardDescriptor(fallback ?: card.fallback, title ?: card.title, card.rev + 1)
    backend.editAgentUi(conversationId, message, next, tree)
    return next
}

private fun treeArg(args: JsonObject?): ByteArray {
    val raw = args?.get("tree") ?: throw IllegalArgumentException("tree is empty")
    return ((raw as? JsonPrimitive)?.takeIf { it.isString }?.content ?: raw.toString()).encodeToByteArray()
}

suspend fun toolSendUi(backend: AgentBackend, args: JsonObject?): ToolReply = guarded {
    val conversationId = sendTarget(backend, args)
    val paths = listArg(args, "images")
    val images = if (paths.isEmpty()) emptyList() else {
        val root = backend.filesDir ?: return@guarded ToolReply("refused: no mcpFilesDir is configured, card images are disabled", true)
        paths.map { loadInside(root, it) }
    }
    ToolReply("sent agent card ${sendUi(backend, conversationId, treeArg(args), stringArg(args, "fallback"), stringArg(args, "title"), images)}")
}

suspend fun toolEditUi(backend: AgentBackend, args: JsonObject?): ToolReply = guarded {
    val conversationId = sendTarget(backend, args)
    val message = findMessage(backend.messages(conversationId, LOOKUP_WINDOW), stringArg(args, "messageId") ?: "")
    val next = editUi(backend, conversationId, message, treeArg(args), stringArg(args, "fallback"), stringArg(args, "title"))
    ToolReply("edited agent card ${message.id.toString().take(ID_PREFIX)}, now rev ${next.rev}")
}

/** The header carrying [tree] inline when the encrypted descriptor fits; otherwise the bare header and the tree for the `agent_ui` payload. */
fun placeTree(descriptor: AgentUiDescriptor, tree: ByteArray): Pair<AgentUiDescriptor, ByteArray?> =
    descriptor.withInlineTree(Json.parseToJsonElement(tree.decodeToString()).jsonObject)?.let { it to null } ?: (descriptor to tree)

suspend fun agentUiPayload(tree: ByteArray, fileOps: FileOperationsProvider): PayloadFile =
    PayloadFile(
        key = ChatProtocol.PAYLOAD_KEY_AGENT_UI,
        filePath = fileOps.writeBytesToTempFile(bytes = tree, prefix = "agent_ui", suffix = ".json"),
        contentType = "application/json",
    )

suspend fun agentUiBundle(tree: ByteArray?, images: List<OutFile>, fileOps: FileOperationsProvider): StagedBundle {
    val staged = if (images.isEmpty()) StagedBundle(PayloadBundle(emptyList(), emptyList(), emptyList())) else buildOutgoingBundle(images, fileOps)
    if (tree == null) return staged
    return try {
        staged.plus(agentUiPayload(tree, fileOps))
    } catch (e: Throwable) {
        staged.cleanup()
        throw e
    }
}

sealed interface CardActionCheck {
    class Accepted(val trigger: ChatMsg) : CardActionCheck
    class Rejected(val reason: String) : CardActionCheck
}

private const val CARD_TITLE_CODEPOINTS = 120

// the actor's label and values are theirs to write, so they ride only inside the untrusted frame
fun checkCardAction(action: ChatMsg, known: List<ChatMsg>, isOwn: (ChatMsg) -> Boolean, self: String): CardActionCheck {
    val actor = action.sender ?: action.author
    if (isOwn(action) || actor?.toString() == self) return CardActionCheck.Rejected("skip: own card action")
    val d = action.agentUiAction() ?: return CardActionCheck.Rejected("skip: unreadable card action")
    val target = known.firstOrNull { it.id == d.target } ?: return CardActionCheck.Rejected("skip: card action target ${d.target} not found")
    if (!isOwn(target)) return CardActionCheck.Rejected("skip: card action target ${d.target} is not this bot's card")
    val card = target.agentUiCard() ?: return CardActionCheck.Rejected("skip: card action target ${d.target} is not a card")
    if (d.rev < card.rev) return CardActionCheck.Rejected("skip: stale card action (rev ${d.rev}, card is at rev ${card.rev})")
    val repeat = known.any { other ->
        other.id != action.id && other.userDate < action.userDate && (other.sender ?: other.author) == actor &&
            other.agentUiAction()?.let { it.target == d.target && it.rev == d.rev && it.actionId == d.actionId && it.values == d.values } == true
    }
    if (repeat) return CardActionCheck.Rejected("skip: duplicate card action")
    val data = buildJsonObject {
        put("label", d.label)
        put("values", JsonObject(d.values.mapValues { JsonPrimitive(it.value) }))
    }
    val text = "card action: $actor pressed action ${d.actionId} on your card ${d.target} \"${card.summaryLine().oneLine(CARD_TITLE_CODEPOINTS)}\" (rev ${d.rev}). " +
        "To change or close the card call $EDIT_UI_TOOL with messageId ${d.target}. Its label and the values they entered or picked:\n${untrustedFrame(data.toString())}"
    return CardActionCheck.Accepted(action.withFullText(text))
}

suspend fun sendAgentUiCard(session: Session, allowlist: Allowlist, conversationId: Uuid, descriptor: AgentUiDescriptor, tree: ByteArray, images: List<OutFile>): Uuid {
    val (header, payload) = placeTree(descriptor, tree)
    val media: (suspend (KeyHeader, JvmFileOperationsProvider) -> StagedBundle)? =
        if (payload == null && images.isEmpty()) null else { _, ops -> agentUiBundle(payload, images, ops) }
    return sendToConversation(session, allowlist, conversationId, "", typed = MessageContent.AgentUi(header), media = media)
}

suspend fun sendUiCommand(profile: String, conversation: Uuid?, treeFile: String?, fallback: String?, title: String?, images: String?) {
    val backend = SessionBackend(profile).also { it.load() }
    val files = images?.split(',')?.map { loadOutFile(File(it.trim()).canonicalFile) }.orEmpty()
    println("sent ${sendUi(backend, conversation ?: error("--conversation <id> required"), readTree(treeFile), fallback, title, files)}")
}

suspend fun editUiCommand(profile: String, conversation: Uuid?, messageId: String?, treeFile: String?, fallback: String?, title: String?) {
    val backend = SessionBackend(profile).also { it.load() }
    val id = conversation ?: error("--conversation <id> required")
    val message = findMessage(backend.messages(id, LOOKUP_WINDOW), messageId ?: error("--message <id> required"))
    println("edited ${message.id} rev ${editUi(backend, id, message, readTree(treeFile), fallback, title).rev}")
}

private fun readTree(path: String?): ByteArray = File(path ?: error("--tree <file.json> required")).readBytes()

fun agentUiTools(): List<ToolDef> = listOf(
    ToolDef(
        SEND_UI_TOOL, "Send an interactive card (AGENT_UI.md): a tree of native blocks (text, image, chip, table, progress, button, toggle, textInput, select in columns, rows, cards and lists). Taps come back to you as card actions. Returns the card's message id.",
        buildJsonObject {
            put("conversationId", CONVERSATION_PROP)
            put("tree", buildJsonObject { put("type", "object"); put("description", "{\"root\":\"id\",\"nodes\":[{\"id\":\"id\",\"type\":\"card\",\"children\":[...]}, ...]}; a button is {\"type\":\"button\",\"label\":\"Approve\",\"action\":{\"id\":\"approve\",\"include\":[\"inputId\"]}}") })
            put("fallback", buildJsonObject { put("type", "string"); put("description", "Plain text shown while it loads, on older apps and when copied, at most 1000 characters") })
            put("title", buildJsonObject { put("type", "string"); put("description", "Optional, at most 80 characters, for the conversation list") })
            put("images", buildJsonObject { put("type", "array"); put("description", "Up to 10 image files in the files directory, referenced in the tree as payloadKey chat_web0, chat_web1, ... in this order"); put("items", buildJsonObject { put("type", "string") }) })
        },
        listOf("conversationId", "tree", "fallback"), stdio = false, write = true,
    ) { b, a -> toolSendUi(b, a) },
    ToolDef(
        EDIT_UI_TOOL, "Replace the tree of one of your cards and bump its rev, so actions on the old version are stale. After a terminal action, edit the card to remove its buttons.",
        buildJsonObject {
            put("conversationId", CONVERSATION_PROP)
            put("messageId", MESSAGE_PROP)
            put("tree", buildJsonObject { put("type", "object"); put("description", "The whole new tree; image payloadKeys must be ones the card was sent with") })
            put("fallback", buildJsonObject { put("type", "string"); put("description", "New fallback text, default unchanged") })
            put("title", buildJsonObject { put("type", "string"); put("description", "New title, default unchanged") })
        },
        listOf("conversationId", "messageId", "tree"), stdio = false, write = true,
    ) { b, a -> toolEditUi(b, a) },
)
