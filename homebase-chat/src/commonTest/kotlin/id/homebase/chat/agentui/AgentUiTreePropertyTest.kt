package id.homebase.chat.agentui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

private fun nodeCount(n: AgentUiNode): Int = 1 + (n.children()?.sumOf { nodeCount(it) } ?: 0)

private fun depth(n: AgentUiNode): Int = 1 + (n.children()?.maxOfOrNull { depth(it) } ?: 0)

@OptIn(ExperimentalTestApi::class)
class AgentUiTreePropertyTest {
    private val types = listOf(
        "column", "row", "card", "text", "image", "chip", "divider", "list", "table",
        "progress", "button", "toggle", "textInput", "select", "bogus", "", "COLUMN",
    )
    private val keys = listOf(
        "id", "type", "children", "markdown", "style", "tone", "spacing", "direction", "payloadKey",
        "alt", "label", "selected", "header", "rows", "value", "action", "multiline", "maxLength",
        "options", "fallback", "include", "root", "nodes",
    )
    private val imageKeys = setOf("chat_web0", "chat_web1")
    private val oddStrings = listOf(
        "", " ", "a", "root", "n1", "n2", "chat_web0", "approve", "‮evil‬", "​​",
        "😀😀", "\uD83D", "x".repeat(5000), "null", "true", "0.5", "-1", "[]", "{}",
    )

    private fun value(r: Random, depth: Int, ids: Int): String = when (r.nextInt(if (depth > 3) 7 else 11)) {
        0 -> "null"
        1 -> r.nextBoolean().toString()
        2 -> r.nextInt(-5, 100).toString()
        3 -> (r.nextDouble() * 4 - 1).toString()
        4, 5 -> quote(oddStrings[r.nextInt(oddStrings.size)])
        6 -> quote("n${r.nextInt(ids + 2)}")
        7, 8 -> "[" + (0 until r.nextInt(0, 8)).joinToString(",") { value(r, depth + 1, ids) } + "]"
        else -> obj(r, depth + 1, ids)
    }

    private fun quote(s: String) =
        "\"" + s.flatMap { c ->
            when (c) {
                '"' -> "\\\"".toList()
                '\\' -> "\\\\".toList()
                '\n' -> "\\n".toList()
                else -> listOf(c)
            }
        }.joinToString("") + "\""

    private fun obj(r: Random, depth: Int, ids: Int): String =
        "{" + (0 until r.nextInt(0, 8)).joinToString(",") {
            quote(keys[r.nextInt(keys.size)]) + ":" + value(r, depth, ids)
        } + "}"

    private fun node(r: Random, i: Int, count: Int): String {
        val type = types[r.nextInt(types.size)]
        val kids = (0 until r.nextInt(0, 6)).joinToString(",") { "\"n${r.nextInt(count + 1)}\"" }
        val extra = if (r.nextInt(3) == 0) "," + quote(keys[r.nextInt(keys.size)]) + ":" + value(r, 2, count) else ""
        val id = if (i == 0) "root" else "n$i"
        val base = when (r.nextInt(4)) {
            0 -> """"payloadKey":"chat_web${r.nextInt(3)}","alt":"a","markdown":"m","label":"l""""
            1 -> """"action":{"id":"go","include":["n1","n2"]},"label":"l","options":[{"id":"a","label":"A"}],"header":["h"],"rows":[["c"]]"""
            2 -> """"value":${value(r, 2, count)},"maxLength":${r.nextInt(-3, 5000)},"selected":${value(r, 2, count)}"""
            else -> """"style":"${keys[r.nextInt(keys.size)]}","tone":"error","spacing":"medium""""
        }
        return """{"id":"$id","type":"$type","children":[$kids],$base$extra}"""
    }

    private fun randomTree(r: Random): ByteArray = when (r.nextInt(6)) {
        0 -> obj(r, 0, 10).encodeToByteArray()
        1 -> value(r, 0, 10).encodeToByteArray()
        else -> {
            val n = r.nextInt(1, 40)
            """{"root":"root","nodes":[${(0 until n).joinToString(",") { node(r, it, n) }}]}""".encodeToByteArray()
        }
    }

    @Test fun random_trees_never_throw() {
        val r = Random(2193)
        var valid = 0
        repeat(2000) {
            val result = AgentUiTree.decode(randomTree(r), imageKeys)
            if (result is AgentUiTreeResult.Valid) {
                valid++
                assertTrue(nodeCount(result.tree.root) <= AgentUiLimits.MAX_NODES)
                assertTrue(depth(result.tree.root) <= AgentUiLimits.MAX_DEPTH)
            }
        }
        assertTrue(valid > 50, "generator should produce a healthy share of valid trees, got $valid")
    }

    @Test fun random_bytes_never_throw() {
        val r = Random(7)
        repeat(500) { AgentUiTree.decode(r.nextBytes(r.nextInt(0, 400)), imageKeys) }
    }

    @Test fun fixed_giants_never_throw() {
        AgentUiTree.decode(ByteArray(10 * 1024 * 1024) { 'a'.code.toByte() }, imageKeys)
        AgentUiTree.decode(ByteArray(10 * 1024 * 1024) { '['.code.toByte() }, imageKeys)
        val many = (0 until 100_000).joinToString(",") { """{"id":"n$it","type":"divider"}""" }
        AgentUiTree.decode("""{"root":"n0","nodes":[$many]}""".encodeToByteArray(), imageKeys)
        AgentUiTree.decode(("[".repeat(10_000) + "]".repeat(10_000)).encodeToByteArray(), imageKeys)
        val deepObj = "{\"a\":".repeat(10_000) + "1" + "}".repeat(10_000)
        AgentUiTree.decode(deepObj.encodeToByteArray(), imageKeys)
        val wide = """{"root":"root","nodes":[{"id":"root","type":"text","markdown":"${"y".repeat(60_000)}"}]}"""
        AgentUiTree.decode(wide.encodeToByteArray(), imageKeys)
    }

    @Test fun valid_random_trees_render_without_throwing() = runComposeUiTest {
        val r = Random(99)
        val trees = generateSequence { AgentUiTree.decode(randomTree(r), imageKeys) }
            .filterIsInstance<AgentUiTreeResult.Valid>()
            .map { it.tree }
            .take(100)
            .toList()
        var current by mutableStateOf(trees.first())
        setContent {
            MaterialTheme {
                val inputs = remember { AgentUiInputState(emptyMap()) }
                AgentUiTreeView(current, inputs, { null }, true, emptySet(), {})
            }
        }
        for (t in trees) {
            current = t
            waitForIdle()
        }
    }
}
