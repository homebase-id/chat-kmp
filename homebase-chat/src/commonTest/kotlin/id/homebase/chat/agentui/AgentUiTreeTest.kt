package id.homebase.chat.agentui

import id.homebase.chat.agentui.AgentUiFixtures.tree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentUiTreeTest {
    private fun decode(bytes: ByteArray, keys: Set<String> = AgentUiFixtures.imageKeys) =
        AgentUiTree.decode(bytes, keys)

    private fun valid(bytes: ByteArray, keys: Set<String> = AgentUiFixtures.imageKeys): AgentUiTree {
        val r = decode(bytes, keys)
        assertIs<AgentUiTreeResult.Valid>(r, (r as? AgentUiTreeResult.Invalid)?.reason)
        return r.tree
    }

    private fun invalid(bytes: ByteArray, keys: Set<String> = AgentUiFixtures.imageKeys) {
        assertIs<AgentUiTreeResult.Invalid>(decode(bytes, keys))
    }

    private fun col(id: String, vararg kids: String) =
        """{"id":"$id","type":"column","children":[${kids.joinToString(",") { "\"$it\"" }}]}"""

    @Test fun reference_card_decodes() {
        val t = AgentUiFixtures.decodeDraftPostCard()
        val card = assertIs<AgentUiNode.Card>(t.root)
        assertEquals(5, card.children.size)
        val list = assertIs<AgentUiNode.ListNode>(card.children[1])
        assertEquals(ListDirection.Horizontal, list.direction)
        assertTrue(list.children.all { it is AgentUiNode.Image })
        val actions = assertIs<AgentUiNode.Row>(card.children[4])
        val buttons = actions.children.map { assertIs<AgentUiNode.Button>(it) }
        assertEquals(listOf("approve", "edit", "discard"), buttons.map { it.action?.id })
        assertEquals(ButtonStyle.Destructive, buttons[2].style)
    }

    @Test fun unknown_node_type_keeps_its_fallback() {
        val t = valid(tree(col("root", "x"), """{"id":"x","type":"hologram","fallback":"A hologram"}"""))
        val u = assertIs<AgentUiNode.Unknown>(assertIs<AgentUiNode.Column>(t.root).children.single())
        assertEquals("A hologram", u.fallback)
    }

    @Test fun unknown_enum_values_use_defaults() {
        val t = valid(tree("""{"id":"root","type":"text","markdown":"hi","style":"neon","tone":"rainbow"}"""))
        val text = assertIs<AgentUiNode.Text>(t.root)
        assertEquals(TextStyleKind.Body, text.style)
        assertEquals(Tone.Default, text.tone)
    }

    @Test fun wrong_field_types_do_not_throw() {
        val t = valid(tree("""{"id":"root","type":"text","markdown":5,"style":[],"tone":{}}"""))
        assertEquals("", assertIs<AgentUiNode.Text>(t.root).markdown)
    }

    @Test fun cycle_is_invalid() {
        invalid(tree(col("root", "a"), col("a", "root")))
    }

    @Test fun diamond_is_invalid() {
        invalid(tree(col("root", "a", "b"), col("a", "c"), col("b", "c"), """{"id":"c","type":"divider"}"""))
    }

    @Test fun self_reference_is_invalid() = invalid(tree(col("root", "root")))

    @Test fun missing_root_is_invalid() = invalid(tree(col("a"), root = "root"))

    @Test fun duplicate_ids_are_invalid() =
        invalid(tree(col("root", "a"), """{"id":"a","type":"divider"}""", """{"id":"a","type":"divider"}"""))

    @Test fun missing_child_is_dropped() {
        val t = valid(tree(col("root", "ghost", "a"), """{"id":"a","type":"divider"}"""))
        assertEquals(1, assertIs<AgentUiNode.Column>(t.root).children.size)
    }

    @Test fun depth_limit() {
        fun chain(n: Int): ByteArray {
            val nodes = (1..n).map { i ->
                if (i < n) col(if (i == 1) "root" else "n$i", "n${i + 1}") else """{"id":"n$i","type":"divider"}"""
            }
            return tree(*nodes.toTypedArray())
        }
        valid(chain(10))
        invalid(chain(11))
    }

    @Test fun node_count_limit() {
        fun flat(n: Int): ByteArray {
            val kids = (1 until n).map { "n$it" }
            val nodes = mutableListOf(col("root", *kids.take(50).toTypedArray()))
            (1 until n).forEach { nodes += """{"id":"n$it","type":"divider"}""" }
            return tree(*nodes.toTypedArray())
        }
        valid(flat(200))
        invalid(flat(201))
    }

    @Test fun children_per_node_limit() {
        fun wide(n: Int): ByteArray {
            val kids = (1..n).map { "n$it" }
            return tree(col("root", *kids.toTypedArray()), *kids.map { """{"id":"$it","type":"divider"}""" }.toTypedArray())
        }
        valid(wide(50))
        invalid(wide(51))
    }

    @Test fun byte_limit() {
        val pad = "x".repeat(64 * 1024)
        val big = """{"root":"root","nodes":[{"id":"root","type":"text","markdown":"$pad"}]}""".encodeToByteArray()
        invalid(big)
        val exact = ByteArray(64 * 1024 + 1) { ' '.code.toByte() }
        invalid(exact)
    }

    @Test fun deep_json_nesting_is_rejected_before_parsing() {
        val deep = "[".repeat(10_000) + "]".repeat(10_000)
        invalid(deep.encodeToByteArray())
        val deepInNode = """{"root":"root","nodes":[{"id":"root","type":"text","markdown":"x","junk":${"[".repeat(5000)}${"]".repeat(5000)}}]}"""
        invalid(deepInNode.encodeToByteArray())
    }

    @Test fun garbage_is_invalid() {
        invalid(ByteArray(0))
        invalid("not json".encodeToByteArray())
        invalid("[]".encodeToByteArray())
        invalid("""{"root":1,"nodes":"x"}""".encodeToByteArray())
    }

    @Test fun strings_are_clamped_to_codepoints() {
        val emoji = "😀"
        val t = valid(tree("""{"id":"root","type":"text","markdown":"${emoji.repeat(2500)}"}"""))
        val md = assertIs<AgentUiNode.Text>(t.root).markdown
        assertEquals(2000, md.length / 2)
        assertEquals(emoji.repeat(2000), md)
    }

    @Test fun bad_image_key_becomes_unknown_with_alt() {
        val t = valid(tree("""{"id":"root","type":"image","payloadKey":"https://evil.example/x.png","alt":"A pic"}"""))
        assertEquals("A pic", assertIs<AgentUiNode.Unknown>(t.root).fallback)
    }

    @Test fun image_key_not_in_payloads_becomes_unknown() {
        val t = valid(tree("""{"id":"root","type":"image","payloadKey":"chat_web5","alt":"A pic"}"""))
        assertIs<AgentUiNode.Unknown>(t.root)
    }

    @Test fun image_without_alt_becomes_unknown() {
        val t = valid(tree("""{"id":"root","type":"image","payloadKey":"chat_web0"}"""))
        assertIs<AgentUiNode.Unknown>(t.root)
    }

    @Test fun more_than_ten_images_is_invalid() {
        val keys = (0..9).map { "chat_web$it" }.toSet()
        fun imgs(n: Int): ByteArray {
            val ids = (1..n).map { "i$it" }
            return tree(
                col("root", *ids.toTypedArray()),
                *ids.mapIndexed { i, id -> """{"id":"$id","type":"image","payloadKey":"chat_web${i % 10}","alt":"a"}""" }.toTypedArray(),
            )
        }
        valid(imgs(10), keys)
        invalid(imgs(11), keys)
    }

    @Test fun bad_action_id_is_dropped() {
        val t = valid(tree("""{"id":"root","type":"button","label":"Go","action":{"id":"Not Valid!"}}"""))
        assertNull(assertIs<AgentUiNode.Button>(t.root).action)
    }

    @Test fun action_include_is_capped_and_cleaned() {
        val inc = (1..40).joinToString(",") { "\"n$it\"" } + ",5,\"\""
        val t = valid(tree("""{"id":"root","type":"button","label":"Go","action":{"id":"go","include":[$inc]}}"""))
        val a = assertIs<AgentUiNode.Button>(t.root).action!!
        assertEquals(20, a.include.size)
    }

    @Test fun table_and_options_are_clamped() {
        val row = (1..12).joinToString(",") { "\"c$it\"" }
        val rows = (1..30).joinToString(",") { "[$row]" }
        val opts = (1..80).joinToString(",") { """{"id":"o$it","label":"O$it"}""" }
        val t = valid(
            tree(
                col("root", "tb", "sel"),
                """{"id":"tb","type":"table","header":[$row],"rows":[$rows]}""",
                """{"id":"sel","type":"select","label":"Pick","options":[$opts],"value":"o3"}""",
            ),
        )
        val kids = assertIs<AgentUiNode.Column>(t.root).children
        val table = assertIs<AgentUiNode.Table>(kids[0])
        assertEquals(8, table.header.size)
        assertEquals(20, table.rows.size)
        assertTrue(table.rows.all { it.size == 8 })
        val sel = assertIs<AgentUiNode.Select>(kids[1])
        assertEquals(50, sel.options.size)
        assertEquals("o3", sel.value)
    }

    @Test fun select_value_outside_options_is_cleared() {
        val t = valid(tree("""{"id":"root","type":"select","label":"P","options":[{"id":"a","label":"A"}],"value":"zzz"}"""))
        assertEquals("", assertIs<AgentUiNode.Select>(t.root).value)
    }

    @Test fun progress_values() {
        fun p(v: String) = assertIs<AgentUiNode.Progress>(valid(tree("""{"id":"root","type":"progress","value":$v}""")).root).value
        assertEquals(0.4f, p("0.4"))
        assertEquals(1f, p("7"))
        assertEquals(0f, p("-3"))
        assertNull(p("null"))
        assertNull(p("\"half\""))
    }

    @Test fun inputs_defaults_and_value_collection() {
        val t = valid(
            tree(
                col("root", "tg", "ti", "sel", "go"),
                """{"id":"tg","type":"toggle","label":"Notify","value":true}""",
                """{"id":"ti","type":"textInput","label":"Caption","value":"hello","maxLength":5}""",
                """{"id":"sel","type":"select","label":"P","options":[{"id":"a","label":"A"},{"id":"b","label":"B"}],"value":"b"}""",
                """{"id":"go","type":"button","label":"Go","action":{"id":"go","include":["ti","ghost","go"]}}""",
            ),
        )
        assertEquals(mapOf("tg" to "true", "ti" to "hello", "sel" to "b"), t.inputs)
        val go = assertIs<AgentUiNode.Column>(t.root).children.last() as AgentUiNode.Button
        val values = t.collectValues(go.action!!, actingNodeId = "go", current = t.inputs + ("ti" to "edited"))
        assertEquals(mapOf("ti" to "edited"), values)
        val tg = assertIs<AgentUiNode.Column>(t.root).children.first() as AgentUiNode.Toggle
        val own = t.collectValues(AgentUiNodeAction("flip", emptyList()), actingNodeId = tg.id, current = mapOf("tg" to "false"))
        assertEquals(mapOf("tg" to "false"), own)
    }

    @Test fun text_input_default_is_clamped_to_max_length() {
        val t = valid(tree("""{"id":"root","type":"textInput","label":"c","value":"abcdefgh","maxLength":3}"""))
        assertEquals("abc", assertIs<AgentUiNode.TextInput>(t.root).value)
    }

    @Test fun input_reconcile_keeps_typed_text_unless_default_changed() {
        val state = AgentUiInputState(mapOf("a" to "x", "b" to "y"))
        state["a"] = "typed"
        state.reconcile(mapOf("a" to "x", "b" to "y2", "c" to "z"))
        assertEquals("typed", state["a"])
        assertEquals("y2", state["b"])
        assertEquals("z", state["c"])
        state.reconcile(mapOf("a" to "x2"))
        assertEquals("x2", state["a"])
        assertNull(state["b"])
    }

    @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
    @Test fun full_length_non_bmp_input_is_fitted_so_its_action_stays_sendable() {
        val t = valid(
            tree(
                """{"id":"root","type":"column","children":["a","b","go"]}""",
                """{"id":"a","type":"textInput","label":"a","maxLength":1000}""",
                """{"id":"b","type":"textInput","label":"b","maxLength":1000}""",
                """{"id":"go","type":"button","label":"Go","action":{"id":"send","include":["a","b"]}}""",
            ),
        )
        val emoji = "\uD83D\uDE00".repeat(1000)
        val a = t.fitText("a", emoji, t.inputs)
        val b = t.fitText("b", emoji, t.inputs + ("a" to a))
        val go = (assertIs<AgentUiNode.Column>(t.root).children.last() as AgentUiNode.Button).action!!
        val values = t.collectValues(go, "go", t.inputs + ("a" to a) + ("b" to b))
        val label = "\uD83D\uDE00".repeat(200)
        val d = AgentUiActionDescriptor(target = kotlin.uuid.Uuid.random(), rev = 1, actionId = "x".repeat(64), label = label, values = values)
        assertTrue(a.isNotEmpty() && a.length < emoji.length)
        assertTrue(d.isValid())
    }
}
