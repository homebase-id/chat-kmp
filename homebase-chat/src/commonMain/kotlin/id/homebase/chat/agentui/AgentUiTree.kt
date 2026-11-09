package id.homebase.chat.agentui

import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.util.codePointCount
import id.homebase.api.util.truncateToCodePoints
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import id.homebase.api.serialization.OdinSystemSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

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
    const val MAX_ID_LENGTH = 64
    const val MAX_JSON_NESTING = 8
    val ACTION_ID = Regex("^[a-z0-9_.-]{1,64}$")
    val IMAGE_KEY = Regex("^chat_web\\d$")
}

/** Keys of this message's payloads that a tree may show as images. */
fun agentUiImageKeys(payloads: List<PayloadDescriptor>?): Set<String> =
    payloads.orEmpty().filter { it.isImage() && AgentUiLimits.IMAGE_KEY.matches(it.key) && it.iv != null }.mapTo(HashSet()) { it.key }

enum class Spacing { None, Small, Medium }
enum class Tone { Default, Primary, Secondary, Error }
enum class TextStyleKind { Title, Body, Label, Caption }
enum class ButtonStyle { Filled, Tonal, Outlined, Text, Destructive }
enum class ListDirection { Vertical, Horizontal }

data class AgentUiNodeAction(val id: String, val include: List<String>)

data class SelectOption(val id: String, val label: String)

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
    data class Button(override val id: String, val label: String, val style: ButtonStyle, val action: AgentUiNodeAction?) : AgentUiNode
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
    data class Unknown(override val id: String, val fallback: String?) : AgentUiNode
}

sealed interface AgentUiTreeResult {
    data class Valid(val tree: AgentUiTree) : AgentUiTreeResult
    data class Invalid(val reason: String) : AgentUiTreeResult
}

data class AgentUiTree(
    val root: AgentUiNode,
    /** Default value of every input node (toggle "true"/"false", text, select option id), by node id. */
    val inputs: Map<String, String>,
) {
    /** The acting node's own value plus every `include`d input node; ids that are not inputs are ignored. */
    fun collectValues(action: AgentUiNodeAction, actingNodeId: String, current: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (id in action.include + actingNodeId) {
            if (id in inputs) out[id] = current[id] ?: inputs.getValue(id)
        }
        return out
    }

    /**
     * [candidate] when every action touching [nodeId] still fits its reply. Otherwise the edit is clamped,
     * never below what the field already holds: a shrinking edit is kept, an append is cut to fit, and any
     * other growing edit is refused. So defaults that already fill the budget can never erase the field.
     */
    fun fitText(nodeId: String, candidate: String, current: Map<String, String>): String {
        val actions = actionsTouching(nodeId)
        fun fits(text: String) = actions.all { (a, actor) -> AgentUiActionDescriptor.valuesFit(collectValues(a, actor, current + (nodeId to text))) }
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
            n.action()?.let { a -> if (!AgentUiActionDescriptor.valuesFit(collectValues(a, n.id, inputs))) out += a.id }
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
            AgentUiTreeResult.Invalid("decode failed")
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

        private fun jsonBytes(s: String): Int = OdinSystemSerializer.serialize(s).encodeToByteArray().size
    }

    private class Decoder(private val imageKeys: Set<String>) {
        private val nodes = HashMap<String, JsonObject>()
        private val visited = HashSet<String>()
        private val inputs = LinkedHashMap<String, String>()
        private var images = 0
        private var failure: String? = null

        fun decode(element: JsonElement): AgentUiTreeResult {
            val obj = element as? JsonObject ?: return AgentUiTreeResult.Invalid("root is not an object")
            val rootId = obj.str("root") ?: return AgentUiTreeResult.Invalid("no root id")
            val list = obj["nodes"] as? JsonArray ?: return AgentUiTreeResult.Invalid("no nodes")
            if (list.size > AgentUiLimits.MAX_NODES) return AgentUiTreeResult.Invalid("too many nodes")
            for (item in list) {
                val n = item as? JsonObject ?: continue
                val id = n.str("id")?.takeIf { it.isNotBlank() && it.length <= AgentUiLimits.MAX_ID_LENGTH } ?: continue
                if (nodes.put(id, n) != null) return AgentUiTreeResult.Invalid("duplicate id")
            }
            val root = build(rootId, 1)
            failure?.let { return AgentUiTreeResult.Invalid(it) }
            if (root == null) return AgentUiTreeResult.Invalid("root missing")
            // A card that would draw nothing must show its fallback instead.
            if (!root.showsSomething()) return AgentUiTreeResult.Invalid("nothing to show")
            return AgentUiTreeResult.Valid(AgentUiTree(root, inputs))
        }

        private fun fail(reason: String): AgentUiNode? {
            if (failure == null) failure = reason
            return null
        }

        private fun build(id: String, depth: Int): AgentUiNode? {
            if (failure != null) return null
            val obj = nodes[id] ?: return null
            if (depth > AgentUiLimits.MAX_DEPTH) return fail("too deep")
            if (!visited.add(id)) return fail("node referenced twice")
            val fallback = obj.text("fallback", AgentUiLimits.MAX_TEXT_CP)?.takeIf { it.isNotBlank() }
            return when (obj.str("type")) {
                "column" -> children(obj, depth)?.let { AgentUiNode.Column(id, it, spacing(obj)) }
                "row" -> children(obj, depth)?.let { AgentUiNode.Row(id, it, spacing(obj)) }
                "card" -> children(obj, depth)?.let { AgentUiNode.Card(id, it, tone(obj.str("tone"))) }
                "list" -> children(obj, depth)?.let {
                    AgentUiNode.ListNode(id, it, if (obj.str("direction") == "horizontal") ListDirection.Horizontal else ListDirection.Vertical)
                }
                "text" -> AgentUiNode.Text(id, obj.text("markdown", AgentUiLimits.MAX_TEXT_CP) ?: "", textStyle(obj.str("style")), tone(obj.str("tone")))
                "image" -> image(id, obj, fallback)
                "chip" -> AgentUiNode.Chip(id, obj.label("label") ?: "", obj.bool("selected") ?: false)
                "divider" -> AgentUiNode.Divider(id)
                "table" -> table(id, obj)
                "progress" -> AgentUiNode.Progress(id, progressValue(obj["value"]), obj.label("label"))
                "button" -> AgentUiNode.Button(id, obj.label("label") ?: "", buttonStyle(obj.str("style")), action(obj["action"]))
                "toggle" -> {
                    val v = obj.bool("value") ?: false
                    inputs[id] = v.toString()
                    AgentUiNode.Toggle(id, obj.label("label") ?: "", v, action(obj["action"]))
                }
                "textInput" -> {
                    val max = (obj.int("maxLength") ?: AgentUiLimits.MAX_VALUE_CP).coerceIn(1, AgentUiLimits.MAX_VALUE_CP)
                    val v = (obj.str("value") ?: "").truncateToCodePoints(max)
                    inputs[id] = v
                    AgentUiNode.TextInput(id, obj.label("label") ?: "", v, obj.bool("multiline") ?: false, max, action(obj["action"]))
                }
                "select" -> select(id, obj)
                else -> AgentUiNode.Unknown(id, fallback)
            }
        }

        private fun children(obj: JsonObject, depth: Int): List<AgentUiNode>? {
            val ids = (obj["children"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } ?: return emptyList()
            if (ids.size > AgentUiLimits.MAX_CHILDREN) {
                fail("too many children")
                return null
            }
            val out = ArrayList<AgentUiNode>(ids.size)
            for (child in ids) {
                build(child, depth + 1)?.let(out::add)
                if (failure != null) return null
            }
            return out
        }

        private fun image(id: String, obj: JsonObject, fallback: String?): AgentUiNode? {
            val alt = obj.text("alt", AgentUiLimits.MAX_LABEL_CP)?.takeIf { it.isNotBlank() }
            val key = obj.str("payloadKey")
            if (alt == null || key == null || !AgentUiLimits.IMAGE_KEY.matches(key) || key !in imageKeys) {
                return AgentUiNode.Unknown(id, alt ?: fallback)
            }
            if (++images > AgentUiLimits.MAX_IMAGES) return fail("too many images")
            return AgentUiNode.Image(id, key, alt)
        }

        private fun table(id: String, obj: JsonObject): AgentUiNode {
            fun cells(e: JsonElement?): List<String> =
                (e as? JsonArray).orEmpty().take(AgentUiLimits.MAX_TABLE_COLS).map {
                    (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.truncateToCodePoints(AgentUiLimits.MAX_LABEL_CP) ?: ""
                }
            val rows = (obj["rows"] as? JsonArray).orEmpty().take(AgentUiLimits.MAX_TABLE_ROWS).map { cells(it) }
            return AgentUiNode.Table(id, cells(obj["header"]), rows)
        }

        private fun select(id: String, obj: JsonObject): AgentUiNode {
            val seen = HashSet<String>()
            val options = (obj["options"] as? JsonArray).orEmpty().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val oid = o.str("id")?.takeIf { it.isNotBlank() && it.length <= AgentUiLimits.MAX_ID_LENGTH && seen.add(it) } ?: return@mapNotNull null
                SelectOption(oid, o.label("label") ?: oid)
            }.take(AgentUiLimits.MAX_OPTIONS)
            val value = obj.str("value")?.takeIf { v -> options.any { it.id == v } } ?: ""
            inputs[id] = value
            return AgentUiNode.Select(id, obj.label("label") ?: "", options, value, action(obj["action"]))
        }

        private fun action(e: JsonElement?): AgentUiNodeAction? {
            val o = e as? JsonObject ?: return null
            val id = o.str("id")?.takeIf { AgentUiLimits.ACTION_ID.matches(it) } ?: return null
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

        private fun spacing(o: JsonObject) = when (o.str("spacing")) {
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

        private fun buttonStyle(s: String?) = when (s) {
            "tonal" -> ButtonStyle.Tonal
            "outlined" -> ButtonStyle.Outlined
            "text" -> ButtonStyle.Text
            "destructive" -> ButtonStyle.Destructive
            else -> ButtonStyle.Filled
        }

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun JsonObject.text(key: String, max: Int): String? = str(key)?.truncateToCodePoints(max)

        private fun JsonObject.label(key: String): String? = text(key, AgentUiLimits.MAX_LABEL_CP)

        private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

        private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
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
