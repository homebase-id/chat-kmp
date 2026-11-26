package id.homebase.chat.agentui

import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.util.codePointCount
import id.homebase.api.util.strictBoolean
import id.homebase.api.util.strictInt
import id.homebase.api.util.strictString
import id.homebase.api.util.truncateToCodePoints
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import id.homebase.api.serialization.OdinSystemSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

object AgentUiLimits {
    const val MAX_BYTES = 64 * 1024
    const val MAX_NODES = 200
    const val MAX_DEPTH = 10
    const val MAX_CHILDREN = 50
    const val MAX_IMAGES = 10
    const val MAX_TEXT_CP = 2000
    const val MAX_LABEL_CP = 200
    const val MAX_TABLE_ROWS = 20
    const val MAX_TABLE_COLS = 8
    const val MAX_OPTIONS = 50
    const val MAX_VALUES = 20
    const val MAX_VALUE_CP = 1000
    const val MAX_COPY_CP = 2000
    const val MAX_URL_LENGTH = 2000
    const val MAX_FACTS = 12
    const val MAX_ID_LENGTH = 64
    const val MAX_JSON_NESTING = 8
    val ACTION_ID = Regex("^[a-z0-9_.-]{1,64}$")
    val IMAGE_KEY = Regex("^chat_web\\d$")
    // ASCII RFC 3986 characters only, which every platform's link handler accepts; lowercase scheme, no userinfo.
    val HTTPS_URL = Regex("^https://[A-Za-z0-9.-]+(:[0-9]{1,5})?([/?#][A-Za-z0-9._~:/?#\\[\\]@!$&'()*+,;=%-]*)?$")
}

/** Keys of this message's payloads that a tree may show as images. */
fun agentUiImageKeys(payloads: List<PayloadDescriptor>?): Set<String> =
    payloads.orEmpty().filter { it.isImage() && AgentUiLimits.IMAGE_KEY.matches(it.key) && it.iv != null }.mapTo(HashSet()) { it.key }

enum class Spacing { None, Small, Medium }
enum class Tone { Default, Primary, Secondary, Error }
enum class TextStyleKind { Title, Body, Label, Caption }
enum class ButtonStyle { Filled, Tonal, Outlined, Text, Destructive }
enum class ListDirection { Vertical, Horizontal }
enum class BadgeTone { Neutral, Primary, Success, Warning, Error }

data class AgentUiNodeAction(val id: String, val include: List<String>)

data class SelectOption(val id: String, val label: String)

data class Fact(val label: String, val value: String)

const val MULTI_SELECT_SEPARATOR = ","

sealed interface AgentUiNode {
    val id: String

    data class Column(override val id: String, val children: List<AgentUiNode>, val spacing: Spacing) : AgentUiNode
    data class Row(override val id: String, val children: List<AgentUiNode>, val spacing: Spacing) : AgentUiNode
    data class Card(override val id: String, val children: List<AgentUiNode>, val tone: Tone) : AgentUiNode
    data class Text(override val id: String, val markdown: String, val style: TextStyleKind, val tone: Tone) : AgentUiNode
    data class Image(override val id: String, val payloadKey: String, val alt: String) : AgentUiNode
    data class Chip(override val id: String, val label: String, val selected: Boolean) : AgentUiNode
    data class Divider(override val id: String) : AgentUiNode
    data class ListNode(override val id: String, val children: List<AgentUiNode>, val direction: ListDirection) : AgentUiNode
    data class Table(override val id: String, val header: List<String>, val rows: List<List<String>>) : AgentUiNode
    data class Progress(override val id: String, val value: Float?, val label: String?) : AgentUiNode
    data class ListItem(
        override val id: String,
        val title: String,
        val subtitle: String?,
        val caption: String?,
        val trailing: AgentUiNode?,
    ) : AgentUiNode
    data class Facts(override val id: String, val items: List<Fact>) : AgentUiNode
    data class Badge(override val id: String, val label: String, val tone: BadgeTone) : AgentUiNode
    /** At most one of [action], [copy] and [url]: [copy] and [url] act on the device and never send anything. */
    data class Button(
        override val id: String,
        val label: String,
        val style: ButtonStyle,
        val action: AgentUiNodeAction?,
        val copy: String? = null,
        val url: String? = null,
    ) : AgentUiNode
    data class Toggle(override val id: String, val label: String, val value: Boolean, val action: AgentUiNodeAction?) : AgentUiNode
    data class TextInput(
        override val id: String,
        val label: String,
        val value: String,
        val multiline: Boolean,
        val maxLength: Int,
        val action: AgentUiNodeAction?,
    ) : AgentUiNode
    data class Select(
        override val id: String,
        val label: String,
        val options: List<SelectOption>,
        val value: String,
        val action: AgentUiNodeAction?,
    ) : AgentUiNode
    /** Takes no `action` of its own (one in the JSON is ignored); its value reaches the agent only through another node's `include`. */
    data class MultiSelect(
        override val id: String,
        val label: String,
        val options: List<SelectOption>,
        val value: List<String>,
        val min: Int,
        val max: Int,
    ) : AgentUiNode {
        internal fun join(selected: Collection<String>): String = options.filter { it.id in selected }.joinToString(MULTI_SELECT_SEPARATOR) { it.id }

        internal fun countOk(joined: String): Boolean =
            (if (joined.isEmpty()) 0 else joined.count { it == ',' } + 1) in min..max

        internal val worstCase: String = options.sortedByDescending { it.id.length }.take(max).joinToString(MULTI_SELECT_SEPARATOR) { it.id }
    }
    data class Unknown(override val id: String, val fallback: String?) : AgentUiNode
}

sealed interface AgentUiTreeResult {
    data class Valid(val tree: AgentUiTree) : AgentUiTreeResult
    data class Invalid(val reason: String) : AgentUiTreeResult
}

data class AgentUiTree(
    val root: AgentUiNode,
    /** Default value of every input node (toggle "true"/"false", text, select option id, multiSelect ids joined by ","), by node id. */
    val inputs: Map<String, String>,
    val multiSelects: Map<String, AgentUiNode.MultiSelect> = emptyMap(),
) {
    /** The acting node's own value plus every `include`d input node; ids that are not inputs are ignored. */
    fun collectValues(action: AgentUiNodeAction, actingNodeId: String, current: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (id in action.include + actingNodeId) {
            if (id in inputs) out[id] = current[id] ?: inputs.getValue(id)
        }
        return out
    }

    /** False while an `include`d multiSelect holds fewer than its `min` or more than its `max` options. */
    fun selectionCountsOk(action: AgentUiNodeAction, current: (String) -> String?): Boolean =
        action.include.all { id -> multiSelects[id]?.let { it.countOk(current(id) ?: inputs.getValue(id)) } ?: true }

    // A multiSelect counts at its longest possible value, so ticking more chips can never push a reply over budget.
    private fun budgetFits(action: AgentUiNodeAction, actingNodeId: String, current: Map<String, String>): Boolean {
        val worst = action.include.mapNotNull { id -> multiSelects[id]?.let { id to it.worstCase } }
        if (worst.any { it.second.codePointCount() > AgentUiLimits.MAX_VALUE_CP }) return false
        return AgentUiActionDescriptor.valuesFit(collectValues(action, actingNodeId, current + worst))
    }

    /**
     * [candidate] when every action touching [nodeId] still fits its reply. Otherwise the edit is clamped,
     * never below what the field already holds: a shrinking edit is kept, an append is cut to fit, and any
     * other growing edit is refused. So defaults that already fill the budget can never erase the field.
     */
    fun fitText(nodeId: String, candidate: String, current: Map<String, String>): String {
        val actions = actionsTouching(nodeId)
        fun fits(text: String) = actions.all { (a, actor) -> budgetFits(a, actor, current + (nodeId to text)) }
        val before = current[nodeId] ?: inputs[nodeId].orEmpty()
        if (fits(candidate) || jsonBytes(candidate) <= jsonBytes(before)) return candidate
        if (!candidate.startsWith(before)) return before
        var lo = before.codePointCount()
        var hi = candidate.codePointCount() - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (fits(candidate.truncateToCodePoints(mid))) lo = mid else hi = mid - 1
        }
        return candidate.truncateToCodePoints(lo)
    }

    /** Ids of actions whose default values already overflow the reply, so a person could never send them; the agent refuses such a tree. */
    fun overBudgetActions(): List<String> {
        val out = ArrayList<String>()
        fun walk(n: AgentUiNode) {
            n.action()?.let { a -> if (!budgetFits(a, n.id, inputs)) out += a.id }
            n.children()?.forEach(::walk)
        }
        walk(root)
        return out
    }

    private fun actionsTouching(nodeId: String): List<Pair<AgentUiNodeAction, String>> {
        val out = ArrayList<Pair<AgentUiNodeAction, String>>()
        fun walk(n: AgentUiNode) {
            val a = n.action()
            if (a != null && (n.id == nodeId || nodeId in a.include)) out += a to n.id
            n.children()?.forEach(::walk)
        }
        walk(root)
        return out
    }

    companion object {
        fun decode(bytes: ByteArray, availableImageKeys: Set<String>): AgentUiTreeResult {
            if (bytes.size > AgentUiLimits.MAX_BYTES) return AgentUiTreeResult.Invalid("payload too large")
            val element = try {
                Json.parseToJsonElement(bytes.decodeToString())
            } catch (e: Exception) {
                return AgentUiTreeResult.Invalid("not json")
            }
            return decodeParsed(element, availableImageKeys)
        }

        // kotlinx refuses input nested too deep to parse safely; this tighter bound keeps every later walk of the tree shallow.
        internal fun decodeParsed(element: JsonElement, availableImageKeys: Set<String>): AgentUiTreeResult = try {
            if (element.nestingExceeds(AgentUiLimits.MAX_JSON_NESTING)) AgentUiTreeResult.Invalid("json nested too deeply")
            else Decoder(availableImageKeys).decode(element)
        } catch (e: Exception) {
            AgentUiTreeResult.Invalid("decode failed: ${e::class.simpleName}")
        }

        private fun JsonElement.nestingExceeds(max: Int): Boolean {
            val stack = ArrayDeque<Pair<JsonElement, Int>>()
            stack.addLast(this to 1)
            while (stack.isNotEmpty()) {
                val (e, depth) = stack.removeLast()
                val children = when (e) {
                    is JsonObject -> e.values
                    is JsonArray -> e
                    else -> continue
                }
                if (depth > max) return true
                children.forEach { stack.addLast(it to depth + 1) }
            }
            return false
        }

        private const val MAX_NOTES = 5

        private fun jsonBytes(s: String): Int = OdinSystemSerializer.serialize(s).encodeToByteArray().size
    }

    private class Decoder(private val imageKeys: Set<String>) {
        private val nodes = HashMap<String, JsonObject>()
        private val visited = HashSet<String>()
        private val inputs = LinkedHashMap<String, String>()
        private val multiSelects = HashMap<String, AgentUiNode.MultiSelect>()
        private var images = 0
        private var failure: String? = null

        fun decode(element: JsonElement): AgentUiTreeResult {
            val obj = element as? JsonObject ?: return AgentUiTreeResult.Invalid("tree is not a JSON object")
            val rootId = obj.strictString("root") ?: return AgentUiTreeResult.Invalid("no root id: \"root\" must be the id of the top node")
            val list = obj["nodes"] as? JsonArray ?: return AgentUiTreeResult.Invalid("no nodes: \"nodes\" must be an array of node objects")
            if (list.size > AgentUiLimits.MAX_NODES) return AgentUiTreeResult.Invalid("too many nodes: ${list.size}, limit ${AgentUiLimits.MAX_NODES}")
            list.forEachIndexed { i, item ->
                val n = item as? JsonObject ?: return@forEachIndexed note("nodes[$i] is not an object")
                val id = n.strictString("id")?.takeIf { it.isNotBlank() && it.length <= AgentUiLimits.MAX_ID_LENGTH }
                    ?: return@forEachIndexed note("nodes[$i] has no usable id (a non-blank string of at most ${AgentUiLimits.MAX_ID_LENGTH} characters)")
                if (nodes.put(id, n) != null) return AgentUiTreeResult.Invalid("duplicate node id '$id'")
            }
            val root = build(rootId, 1, parent = null)
            failure?.let { return AgentUiTreeResult.Invalid(it) }
            if (root == null) return AgentUiTreeResult.Invalid(withNotes("root '$rootId' is not in nodes"))
            // A card that would draw nothing must show its fallback instead.
            if (!root.showsSomething()) {
                return AgentUiTreeResult.Invalid(
                    withNotes("nothing to show: no non-blank text, labelled chip or badge, list item, facts, table, image, input, button or fallback"),
                )
            }
            return AgentUiTreeResult.Valid(AgentUiTree(root, inputs, multiSelects))
        }

        // Things the decoder dropped or left empty without refusing; they explain a tree that ends up with nothing to show.
        private val notes = ArrayList<String>()

        private fun note(s: String) {
            notes += s
        }

        private fun withNotes(reason: String): String = when {
            notes.isEmpty() -> reason
            notes.size <= MAX_NOTES -> "$reason (${notes.joinToString("; ")})"
            else -> "$reason (${notes.take(MAX_NOTES).joinToString("; ")}; ${notes.size - MAX_NOTES} more)"
        }

        private fun fail(reason: String): AgentUiNode? {
            if (failure == null) failure = reason
            return null
        }

        private fun build(id: String, depth: Int, parent: String?): AgentUiNode? {
            if (failure != null) return null
            val obj = nodes[id] ?: run {
                if (parent != null) note("child '$id' of '$parent' is not in nodes")
                return null
            }
            if (depth > AgentUiLimits.MAX_DEPTH) return fail("node '$id' is nested $depth deep, limit ${AgentUiLimits.MAX_DEPTH}")
            if (!visited.add(id)) return fail("node '$id' is referenced twice (a cycle, or a child of two parents)")
            val fallback = obj.text("fallback", AgentUiLimits.MAX_TEXT_CP)?.takeIf { it.isNotBlank() }
            return when (val type = obj.strictString("type")) {
                "column" -> children(id, "column", obj, depth)?.let { AgentUiNode.Column(id, it, spacing(obj)) }
                "row" -> children(id, "row", obj, depth)?.let { AgentUiNode.Row(id, it, spacing(obj)) }
                "card" -> children(id, "card", obj, depth)?.let { AgentUiNode.Card(id, it, tone(obj.strictString("tone"))) }
                "list" -> children(id, "list", obj, depth)?.let {
                    AgentUiNode.ListNode(id, it, if (obj.strictString("direction") == "horizontal") ListDirection.Horizontal else ListDirection.Vertical)
                }
                "text" -> {
                    val markdown = obj.text("markdown", AgentUiLimits.MAX_TEXT_CP) ?: ""
                    if (markdown.isBlank()) note(missing("text node '$id' has no markdown", obj, "markdown", "text", "content", "value", "label", "body"))
                    AgentUiNode.Text(id, markdown, textStyle(obj.strictString("style")), tone(obj.strictString("tone")))
                }
                "image" -> image(id, obj, fallback)
                "chip" -> {
                    val label = obj.label("label") ?: ""
                    if (label.isBlank()) note(missing("chip '$id' has no label", obj, "label", "text", "markdown", "value"))
                    AgentUiNode.Chip(id, label, obj.strictBoolean("selected") ?: false)
                }
                "divider" -> AgentUiNode.Divider(id)
                "table" -> table(id, obj)
                "progress" -> AgentUiNode.Progress(id, progressValue(obj["value"]), obj.label("label"))
                "button" -> button(id, obj)
                "listItem" -> {
                    val title = obj.label("title") ?: ""
                    if (title.isBlank()) note(missing("listItem '$id' has no title", obj, "title", "label", "text", "markdown"))
                    val trailing = obj.strictString("trailing")?.let { build(it, depth + 1, parent = id) }
                    if (failure != null) return null
                    val fits = trailing is AgentUiNode.Button || trailing is AgentUiNode.Badge || trailing is AgentUiNode.Chip
                    if (trailing != null && !fits) note("listItem '$id' trailing '${trailing.id}' is not a button, badge or chip, so it is dropped")
                    AgentUiNode.ListItem(id, title, obj.label("subtitle")?.takeIf { it.isNotBlank() }, obj.label("caption")?.takeIf { it.isNotBlank() }, trailing.takeIf { fits })
                }
                "facts" -> facts(id, obj)
                "badge" -> {
                    val label = obj.label("label") ?: ""
                    if (label.isBlank()) note(missing("badge '$id' has no label", obj, "label", "text", "markdown", "value"))
                    AgentUiNode.Badge(id, label, badgeTone(obj.strictString("tone")))
                }
                "toggle" -> {
                    val v = obj.strictBoolean("value") ?: false
                    inputs[id] = v.toString()
                    AgentUiNode.Toggle(id, obj.label("label") ?: "", v, action(id, obj["action"]))
                }
                "textInput" -> {
                    val max = (obj.strictInt("maxLength") ?: AgentUiLimits.MAX_VALUE_CP).coerceIn(1, AgentUiLimits.MAX_VALUE_CP)
                    val v = (obj.strictString("value") ?: "").truncateToCodePoints(max)
                    inputs[id] = v
                    AgentUiNode.TextInput(id, obj.label("label") ?: "", v, obj.strictBoolean("multiline") ?: false, max, action(id, obj["action"]))
                }
                "select" -> select(id, obj)
                "multiSelect" -> multiSelect(id, obj)
                else -> {
                    if (fallback == null) note(if (type == null) "node '$id' has no type" else "unknown node type '$type' ($id) has no fallback")
                    AgentUiNode.Unknown(id, fallback)
                }
            }
        }

        // "text node 't0' has no markdown (it has \"text\"; put it in \"markdown\")" when the content went under another key.
        private fun missing(what: String, obj: JsonObject, field: String, vararg likely: String): String {
            val misplaced = likely.firstOrNull { it != field && obj.strictString(it)?.isNotBlank() == true } ?: return what
            return "$what (it has \"$misplaced\"; put it in \"$field\")"
        }

        private fun children(id: String, type: String, obj: JsonObject, depth: Int): List<AgentUiNode>? {
            val ids = (obj["children"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            if (ids.isNullOrEmpty()) {
                note("$type '$id' has no children")
                return emptyList()
            }
            if (ids.size > AgentUiLimits.MAX_CHILDREN) {
                fail("$type '$id' has ${ids.size} children, limit ${AgentUiLimits.MAX_CHILDREN}")
                return null
            }
            val out = ArrayList<AgentUiNode>(ids.size)
            for (child in ids) {
                build(child, depth + 1, parent = id)?.let(out::add)
                if (failure != null) return null
            }
            return out
        }

        private fun button(id: String, obj: JsonObject): AgentUiNode? {
            val label = obj.label("label") ?: ""
            if (label.isBlank()) return fail(missing("button '$id' has no label", obj, "label", "text", "markdown", "title"))
            // An explicit JSON null counts as absent, for copy, url and action alike.
            listOf("copy", "url", "action").filter { obj.present(it) != null }.takeIf { it.size > 1 }?.let { (a, b) ->
                return fail("button '$id' has both $a and $b; a button does one thing")
            }
            val copy = if (obj.present("copy") == null) null else {
                val s = obj.strictString("copy")?.takeIf { it.isNotBlank() } ?: return fail("button '$id' copy must be a non-blank string")
                val cp = s.codePointCount()
                if (cp > AgentUiLimits.MAX_COPY_CP) return fail("button '$id' copy is $cp code points, limit ${AgentUiLimits.MAX_COPY_CP}")
                s
            }
            val url = if (obj.present("url") == null) null else {
                val s = obj.strictString("url") ?: return fail("button '$id' url must be a string")
                if (s.length > AgentUiLimits.MAX_URL_LENGTH) return fail("button '$id' url is ${s.length} characters, limit ${AgentUiLimits.MAX_URL_LENGTH}")
                if (!AgentUiLimits.HTTPS_URL.matches(s)) return fail("button '$id' url must be an https:// link with a host, percent-encoded, with no spaces or user@")
                s
            }
            return AgentUiNode.Button(id, label, buttonStyle(obj.strictString("style")), action(id, obj["action"]), copy, url)
        }

        private fun JsonObject.present(key: String): JsonElement? = this[key]?.takeUnless { it is JsonNull }

        private fun image(id: String, obj: JsonObject, fallback: String?): AgentUiNode? {
            val alt = obj.text("alt", AgentUiLimits.MAX_LABEL_CP)?.takeIf { it.isNotBlank() }
            val key = obj.strictString("payloadKey")
            if (alt == null || key == null || !AgentUiLimits.IMAGE_KEY.matches(key) || key !in imageKeys) {
                note(
                    when {
                        alt == null -> "image '$id' has no alt"
                        key == null -> "image '$id' has no payloadKey"
                        else -> "image '$id' payloadKey '$key' is not an image payload of this message"
                    },
                )
                return AgentUiNode.Unknown(id, alt ?: fallback)
            }
            if (++images > AgentUiLimits.MAX_IMAGES) return fail("image '$id' is image $images, limit ${AgentUiLimits.MAX_IMAGES}")
            return AgentUiNode.Image(id, key, alt)
        }

        private fun table(id: String, obj: JsonObject): AgentUiNode {
            fun cells(e: JsonElement?): List<String> =
                (e as? JsonArray).orEmpty().take(AgentUiLimits.MAX_TABLE_COLS).map {
                    (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.truncateToCodePoints(AgentUiLimits.MAX_LABEL_CP) ?: ""
                }
            val rows = (obj["rows"] as? JsonArray).orEmpty().take(AgentUiLimits.MAX_TABLE_ROWS).map { cells(it) }
            val header = cells(obj["header"])
            if (header.isEmpty() && rows.isEmpty()) note("table '$id' has no header or rows")
            return AgentUiNode.Table(id, header, rows)
        }

        private fun facts(id: String, obj: JsonObject): AgentUiNode {
            val items = (obj["items"] as? JsonArray).orEmpty().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                Fact(o.label("label") ?: "", o.label("value") ?: "").takeIf { it.label.isNotBlank() || it.value.isNotBlank() }
            }.take(AgentUiLimits.MAX_FACTS)
            if (items.isEmpty()) note("facts '$id' has no items with a label or value")
            return AgentUiNode.Facts(id, items)
        }

        private fun options(obj: JsonObject, idOk: (String) -> Boolean = { true }): List<SelectOption> {
            val seen = HashSet<String>()
            return (obj["options"] as? JsonArray).orEmpty().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val oid = o.strictString("id")?.takeIf { it.isNotBlank() && it.length <= AgentUiLimits.MAX_ID_LENGTH && idOk(it) && seen.add(it) } ?: return@mapNotNull null
                SelectOption(oid, o.label("label") ?: oid)
            }.take(AgentUiLimits.MAX_OPTIONS)
        }

        private fun select(id: String, obj: JsonObject): AgentUiNode {
            val options = options(obj)
            val value = obj.strictString("value")?.takeIf { v -> options.any { it.id == v } } ?: ""
            inputs[id] = value
            return AgentUiNode.Select(id, obj.label("label") ?: "", options, value, action(id, obj["action"]))
        }

        private fun multiSelect(id: String, obj: JsonObject): AgentUiNode {
            val options = options(obj) { MULTI_SELECT_SEPARATOR !in it }
            val picked = (obj["value"] as? JsonArray).orEmpty().mapNotNullTo(HashSet()) { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            val max = (obj.strictInt("max") ?: options.size).coerceIn(0, options.size)
            val min = (obj.strictInt("min") ?: 0).coerceIn(0, max)
            val value = options.filter { it.id in picked }.map { it.id }
            val node = AgentUiNode.MultiSelect(id, obj.label("label") ?: "", options, value, min, max)
            inputs[id] = value.joinToString(MULTI_SELECT_SEPARATOR)
            multiSelects[id] = node
            return node
        }

        private fun action(nodeId: String, e: JsonElement?): AgentUiNodeAction? {
            val o = e as? JsonObject ?: return null
            val id = o.strictString("id")?.takeIf { AgentUiLimits.ACTION_ID.matches(it) }
                ?: run {
                    note("action of '$nodeId' has no id matching ${AgentUiLimits.ACTION_ID.pattern}, so it is dropped")
                    return null
                }
            val include = (o["include"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.takeIf { s -> s.isNotBlank() && s.length <= AgentUiLimits.MAX_ID_LENGTH } }
                .distinct()
                // The acting node's own value is always sent too, so the reply holds at most MAX_VALUES.
                .take(AgentUiLimits.MAX_VALUES - 1)
            return AgentUiNodeAction(id, include)
        }

        private fun progressValue(e: JsonElement?): Float? {
            val p = e as? JsonPrimitive ?: return null
            if (p.isString) return null
            return p.doubleOrNull?.toFloat()?.coerceIn(0f, 1f)
        }

        private fun spacing(o: JsonObject) = when (o.strictString("spacing")) {
            "none" -> Spacing.None
            "medium" -> Spacing.Medium
            else -> Spacing.Small
        }

        private fun tone(s: String?) = when (s) {
            "primary" -> Tone.Primary
            "secondary" -> Tone.Secondary
            "error" -> Tone.Error
            else -> Tone.Default
        }

        private fun textStyle(s: String?) = when (s) {
            "title" -> TextStyleKind.Title
            "label" -> TextStyleKind.Label
            "caption" -> TextStyleKind.Caption
            else -> TextStyleKind.Body
        }

        private fun badgeTone(s: String?) = when (s) {
            "primary" -> BadgeTone.Primary
            "success" -> BadgeTone.Success
            "warning" -> BadgeTone.Warning
            "error" -> BadgeTone.Error
            else -> BadgeTone.Neutral
        }

        private fun buttonStyle(s: String?) = when (s) {
            "tonal" -> ButtonStyle.Tonal
            "outlined" -> ButtonStyle.Outlined
            "text" -> ButtonStyle.Text
            "destructive" -> ButtonStyle.Destructive
            else -> ButtonStyle.Filled
        }


        private fun JsonObject.text(key: String, max: Int): String? = strictString(key)?.truncateToCodePoints(max)

        private fun JsonObject.label(key: String): String? = text(key, AgentUiLimits.MAX_LABEL_CP)

    }
}

private fun AgentUiNode.action(): AgentUiNodeAction? = when (this) {
    is AgentUiNode.Button -> action
    is AgentUiNode.Toggle -> action
    is AgentUiNode.TextInput -> action
    is AgentUiNode.Select -> action
    else -> null
}

private fun AgentUiNode.showsSomething(): Boolean = when (this) {
    is AgentUiNode.Text -> markdown.isNotBlank()
    is AgentUiNode.Chip -> label.isNotBlank()
    is AgentUiNode.Badge -> label.isNotBlank()
    is AgentUiNode.Facts -> items.isNotEmpty()
    is AgentUiNode.ListItem -> title.isNotBlank() || subtitle != null || caption != null || trailing?.showsSomething() == true
    is AgentUiNode.Table -> header.isNotEmpty() || rows.isNotEmpty()
    is AgentUiNode.Unknown -> fallback != null
    is AgentUiNode.Divider -> false
    else -> children()?.any { it.showsSomething() } ?: true
}

internal fun AgentUiNode.children(): List<AgentUiNode>? = when (this) {
    is AgentUiNode.Column -> children
    is AgentUiNode.Row -> children
    is AgentUiNode.Card -> children
    is AgentUiNode.ListNode -> children
    is AgentUiNode.ListItem -> listOfNotNull(trailing)
    else -> null
}

class AgentUiInputState(defaults: Map<String, String> = emptyMap()) {
    private val values = mutableStateMapOf<String, String>().apply { putAll(defaults) }
    private var baseline: Map<String, String> = defaults

    operator fun get(id: String): String? = values[id]

    operator fun set(id: String, value: String) {
        values[id] = value
    }

    fun snapshot(): Map<String, String> = values.toMap()

    /** A node keeps what the person typed unless the agent changed that node's default in the new revision. */
    fun reconcile(defaults: Map<String, String>) {
        for (id in values.keys.toList()) if (id !in defaults) values.remove(id)
        for ((id, v) in defaults) if (id !in values || baseline[id] != v) values[id] = v
        baseline = defaults
    }

    companion object {
        // Flattened as [valueCount, k1, v1, …, baseline k1, v1, …] so it fits every platform's saved-state bundle.
        val Saver: Saver<AgentUiInputState, Any> = listSaver(
            save = { s -> listOf(s.values.size.toString()) + (s.values.toMap().entries + s.baseline.entries).flatMap { listOf(it.key, it.value) } },
            restore = { l ->
                val n = l[0].toInt()
                val pairs = l.drop(1).chunked(2) { it[0] to it[1] }
                AgentUiInputState(pairs.drop(n).toMap()).apply {
                    values.clear()
                    values.putAll(pairs.take(n))
                }
            },
        )
    }
}
