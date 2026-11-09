package id.homebase.chat.agentui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import id.homebase.chat.agentui.AgentUiFixtures.tree
import id.homebase.chat.agentui.AgentUiFixtures.valid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalTestApi::class)
class AgentUiRendererTest {
    private fun androidx.compose.ui.test.ComposeUiTest.show(
        tree: AgentUiTree,
        enabled: Boolean = true,
        pending: Set<String> = emptySet(),
        onAction: (AgentUiActionRequest) -> Unit = {},
    ) = setContent {
        MaterialTheme {
            val inputs = remember { AgentUiInputState(tree.inputs) }
            AgentUiTreeView(
                tree = tree,
                inputs = inputs,
                imageData = { null },
                enabled = enabled,
                pendingActionIds = pending,
                onAction = onAction,
            )
        }
    }

    @Test fun reference_card_shows_caption_chips_and_buttons() = runComposeUiTest {
        show(AgentUiFixtures.decodeDraftPostCard())
        onNodeWithText("Spring is here", substring = true).assertExists()
        onNodeWithText("X").assertExists()
        onNodeWithText("LinkedIn").assertExists()
        onNodeWithText("Instagram").assertExists()
        onNodeWithText("Approve").assertIsEnabled()
        onNodeWithText("Edit").assertExists()
        onNodeWithText("Discard").assertExists()
        onNodeWithText("Slide one: the spring launch").assertExists()
    }

    @Test fun approve_fires_action_with_no_values() = runComposeUiTest {
        val seen = mutableListOf<AgentUiActionRequest>()
        show(AgentUiFixtures.decodeDraftPostCard(), onAction = { seen += it })
        onNodeWithText("Approve").performClick()
        assertEquals(1, seen.size)
        assertEquals("approve", seen.single().actionId)
        assertEquals("Approve", seen.single().label)
        assertEquals(emptyMap(), seen.single().values)
    }

    @Test fun form_values_include_only_the_listed_inputs() = runComposeUiTest {
        val t = valid(
            tree(
                """{"id":"root","type":"column","children":["tg","ti","other","go"]}""",
                """{"id":"tg","type":"toggle","label":"Notify team","value":false}""",
                """{"id":"ti","type":"textInput","label":"Caption","value":""}""",
                """{"id":"other","type":"textInput","label":"Other","value":"keep out"}""",
                """{"id":"go","type":"button","label":"Send","action":{"id":"send","include":["tg","ti"]}}""",
            ),
        )
        val seen = mutableListOf<AgentUiActionRequest>()
        show(t, onAction = { seen += it })
        onNodeWithText("Notify team").performClick()
        onNodeWithText("Caption").performTextInput("Hello")
        onNodeWithText("Send").performClick()
        assertEquals(mapOf("tg" to "true", "ti" to "Hello"), seen.last().values)
    }

    @Test fun select_with_action_fires_with_the_option_id() = runComposeUiTest {
        val t = valid(
            tree(
                """{"id":"root","type":"select","label":"Pick one","options":[{"id":"a","label":"Alpha"},{"id":"b","label":"Beta"}],"action":{"id":"picked"}}""",
            ),
        )
        val seen = mutableListOf<AgentUiActionRequest>()
        show(t, onAction = { seen += it })
        onNodeWithText("Beta").performClick()
        assertEquals("picked", seen.single().actionId)
        assertEquals(mapOf("root" to "b"), seen.single().values)
    }

    @Test fun toggle_with_action_fires_on_change() = runComposeUiTest {
        val t = valid(tree("""{"id":"root","type":"toggle","label":"Auto post","value":false,"action":{"id":"auto"}}"""))
        val seen = mutableListOf<AgentUiActionRequest>()
        show(t, onAction = { seen += it })
        onNodeWithText("Auto post").performClick()
        assertEquals(mapOf("root" to "true"), seen.single().values)
    }

    @Test fun display_only_disables_buttons_and_swallows_taps() = runComposeUiTest {
        val seen = mutableListOf<AgentUiActionRequest>()
        show(AgentUiFixtures.decodeDraftPostCard(), enabled = false, onAction = { seen += it })
        onNodeWithText("Approve").assertIsNotEnabled()
        onNodeWithText("Approve").performClick()
        assertEquals(0, seen.size)
    }

    @Test fun pending_action_is_disabled_others_are_not() = runComposeUiTest {
        show(AgentUiFixtures.decodeDraftPostCard(), pending = setOf("approve"))
        onNodeWithText("Approve").assertIsNotEnabled()
        onNodeWithText("Edit").assertIsEnabled()
    }

    @Test fun unknown_node_shows_its_fallback() = runComposeUiTest {
        show(valid(tree("""{"id":"root","type":"hologram","fallback":"A hologram goes here"}""")))
        onNodeWithText("A hologram goes here").assertExists()
    }

    @Test fun all_node_kinds_render() = runComposeUiTest {
        val t = valid(
            tree(
                """{"id":"root","type":"column","spacing":"medium","children":["t","d","tb","pg","pi","l"]}""",
                """{"id":"t","type":"text","style":"title","tone":"primary","markdown":"Title here"}""",
                """{"id":"d","type":"divider"}""",
                """{"id":"tb","type":"table","header":["Channel","Reach"],"rows":[["X","1.2k"],["LinkedIn","800"]]}""",
                """{"id":"pg","type":"progress","value":0.5,"label":"Rendering slides"}""",
                """{"id":"pi","type":"progress","label":"Waiting"}""",
                """{"id":"l","type":"list","direction":"vertical","children":["c1"]}""",
                """{"id":"c1","type":"chip","label":"In list"}""",
            ),
        )
        show(t)
        onNodeWithText("Title here").assertExists()
        onNodeWithText("Channel").assertExists()
        onNodeWithText("1.2k").assertExists()
        onNodeWithText("Rendering slides").assertExists()
        onNodeWithText("In list").assertExists()
    }

    @Test fun root_card_tone_is_ignored() = runComposeUiTest {
        show(valid(tree(
            """{"id":"root","type":"card","tone":"error","children":["t"]}""",
            """{"id":"t","type":"text","markdown":"Root body"}""",
        )))
        onNodeWithText("Root body").assertExists()
        onNodeWithTag(ErrorIconTag).assertDoesNotExist()
    }

    @Test fun nested_card_tone_is_applied() = runComposeUiTest {
        show(valid(tree(
            """{"id":"root","type":"card","children":["inner"]}""",
            """{"id":"inner","type":"card","tone":"error","children":["t"]}""",
            """{"id":"t","type":"text","markdown":"Inner body"}""",
        )))
        onNodeWithText("Inner body").assertExists()
        onNodeWithTag(ErrorIconTag).assertExists()
    }

    @Test fun display_chips_have_no_click_action() = runComposeUiTest {
        show(valid(tree(
            """{"id":"root","type":"row","children":["on","off"]}""",
            """{"id":"on","type":"chip","label":"Picked","selected":true}""",
            """{"id":"off","type":"chip","label":"Plain","selected":false}""",
        )))
        onNodeWithText("Picked").assertHasNoClickAction()
        onNodeWithText("Plain").assertHasNoClickAction()
    }

    @Test fun new_tree_re_renders() = runComposeUiTest {
        var current by mutableStateOf(valid(tree("""{"id":"root","type":"text","markdown":"Draft"}""")))
        setContent {
            MaterialTheme {
                val inputs = remember { AgentUiInputState(emptyMap()) }
                AgentUiTreeView(current, inputs, { null }, true, emptySet(), {})
            }
        }
        onNodeWithText("Draft").assertExists()
        current = valid(tree("""{"id":"root","type":"text","markdown":"Posted"}"""))
        waitForIdle()
        onNodeWithText("Posted").assertExists()
        assertIs<AgentUiNode.Text>(current.root)
    }
}
