package id.homebase.chat.agentui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
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
        answered: Set<String> = emptySet(),
        clipboard: Clipboard? = null,
        uriHandler: UriHandler? = null,
        onAction: (AgentUiActionRequest) -> Unit = {},
    ) = setContent {
        MaterialTheme {
            val inputs = remember { AgentUiInputState(tree.inputs) }
            val view = @androidx.compose.runtime.Composable {
                AgentUiTreeView(
                    tree = tree,
                    inputs = inputs,
                    imageData = { null },
                    enabled = enabled,
                    pendingActionIds = pending,
                    onAction = onAction,
                    answeredActionIds = answered,
                )
            }
            val withUri = @androidx.compose.runtime.Composable {
                if (uriHandler == null) view() else CompositionLocalProvider(LocalUriHandler provides uriHandler) { view() }
            }
            if (clipboard == null) withUri() else CompositionLocalProvider(LocalClipboard provides clipboard) { withUri() }
        }
    }

    private class FakeClipboard : Clipboard {
        val entries = mutableListOf<ClipEntry?>()
        override suspend fun getClipEntry(): ClipEntry? = entries.lastOrNull()
        override suspend fun setClipEntry(clipEntry: ClipEntry?) {
            entries += clipEntry
        }
        override val nativeClipboard: NativeClipboard get() = error("no native clipboard in tests")
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

    @Test fun picking_the_selected_option_again_sends_nothing() = runComposeUiTest {
        val t = valid(
            tree(
                """{"id":"root","type":"select","label":"Pick one","options":[{"id":"a","label":"Alpha"},{"id":"b","label":"Beta"}],"value":"a","action":{"id":"picked"}}""",
            ),
        )
        val seen = mutableListOf<AgentUiActionRequest>()
        show(t, onAction = { seen += it })
        onNodeWithText("Alpha").performClick()
        assertEquals(emptyList(), seen)
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

    private val multiSelectForm = valid(
        tree(
            """{"id":"root","type":"column","children":["nets","go"]}""",
            """{"id":"nets","type":"multiSelect","label":"Post to","options":[{"id":"x","label":"X"},{"id":"li","label":"LinkedIn"},{"id":"ig","label":"Instagram"}],"value":["li"],"min":1,"max":2,"action":{"id":"ignored"}}""",
            """{"id":"go","type":"button","label":"Post","action":{"id":"post","include":["nets"]}}""",
        ),
    )

    @Test fun multi_select_chip_taps_toggle_without_firing() = runComposeUiTest {
        val seen = mutableListOf<AgentUiActionRequest>()
        show(multiSelectForm, onAction = { seen += it })
        onNodeWithText("Post to").assertExists()
        onNodeWithText("LinkedIn").assertIsSelected()
        onNodeWithText("X").assertIsNotSelected()
        onNodeWithText("Instagram").performClick()
        onNodeWithText("Instagram").assertIsSelected()
        onNodeWithText("X").performClick()
        onNodeWithText("Instagram").performClick()
        assertEquals(emptyList(), seen)
        onNodeWithText("Post").performClick()
        assertEquals(mapOf("nets" to "x,li"), seen.single().values)
    }

    @Test fun button_is_disabled_while_multi_select_count_is_out_of_range() = runComposeUiTest {
        show(multiSelectForm)
        onNodeWithText("Post").assertIsEnabled()
        onNodeWithText("LinkedIn").performClick()
        onNodeWithText("Post").assertIsNotEnabled()
        onNodeWithText("X").performClick()
        onNodeWithText("Instagram").performClick()
        onNodeWithText("Post").assertIsEnabled()
        onNodeWithText("LinkedIn").performClick()
        onNodeWithText("Post").assertIsNotEnabled()
    }

    @Test fun copy_button_copies_on_the_device_and_never_sends_an_action() = runComposeUiTest {
        val t = valid(tree("""{"id":"root","type":"button","label":"Copy code","style":"tonal","copy":"ABC-123"}"""))
        val clipboard = FakeClipboard()
        val seen = mutableListOf<AgentUiActionRequest>()
        show(t, clipboard = clipboard, onAction = { seen += it })
        onNodeWithText("Copy code").assertIsEnabled().performClick()
        waitForIdle()
        assertEquals(1, clipboard.entries.size)
        onNodeWithTag(CopiedIconTag, useUnmergedTree = true).assertExists()
        onNodeWithText("Copied").assertExists()
        assertEquals(emptyList(), seen)
        mainClock.advanceTimeBy(2500)
        onNodeWithText("Copy code").assertExists()
    }

    @Test fun copy_button_is_inert_on_a_display_only_card() = runComposeUiTest {
        val t = valid(tree("""{"id":"root","type":"button","label":"Copy code","copy":"ABC-123"}"""))
        val clipboard = FakeClipboard()
        show(t, enabled = false, clipboard = clipboard)
        onNodeWithText("Copy code").assertIsNotEnabled().performClick()
        assertEquals(0, clipboard.entries.size)
    }

    // A sent action settles on a check instead of spinning until the agent edits the card.
    @Test fun an_answered_action_shows_a_check_stays_disabled_and_sends_nothing() = runComposeUiTest {
        val seen = mutableListOf<AgentUiActionRequest>()
        show(AgentUiFixtures.decodeDraftPostCard(), pending = setOf("approve"), answered = setOf("approve"), onAction = { seen += it })
        onNodeWithText("Approve").assertIsNotEnabled().performClick()
        onNodeWithTag(ChosenIconTag, useUnmergedTree = true).assertExists()
        onNodeWithText("Edit").assertIsEnabled()
        assertEquals(emptyList(), seen)
    }

    @Test fun an_in_flight_action_shows_no_check() = runComposeUiTest {
        show(AgentUiFixtures.decodeDraftPostCard(), pending = setOf("approve"))
        onNodeWithText("Approve").assertIsNotEnabled()
        onNodeWithTag(ChosenIconTag, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun url_button_opens_the_link_and_never_sends_an_action() = runComposeUiTest {
        val t = valid(tree("""{"id":"root","type":"button","label":"Track order","style":"outlined","url":"https://acme.example/orders/1042"}"""))
        val opened = mutableListOf<String>()
        val seen = mutableListOf<AgentUiActionRequest>()
        show(t, uriHandler = object : UriHandler { override fun openUri(uri: String) { opened += uri } }, onAction = { seen += it })
        onNodeWithTag(LinkIconTag, useUnmergedTree = true).assertExists()
        onNodeWithText("Track order").assertIsEnabled().performClick()
        assertEquals(listOf("https://acme.example/orders/1042"), opened)
        assertEquals(emptyList(), seen)
    }

    @Test fun list_item_facts_and_badge_render_their_text() = runComposeUiTest {
        val t = valid(
            tree(
                """{"id":"root","type":"column","children":["a","b","f"]}""",
                """{"id":"a","type":"listItem","title":"Order #1042","subtitle":"2 items","trailing":"st"}""",
                """{"id":"st","type":"badge","label":"Shipped","tone":"success"}""",
                """{"id":"b","type":"listItem","title":"Order #1043","caption":"Due Friday","trailing":"pay"}""",
                """{"id":"pay","type":"button","label":"Pay","style":"tonal","action":{"id":"pay"}}""",
                """{"id":"f","type":"facts","items":[{"label":"Total","value":"$42.00"}]}""",
            ),
        )
        val seen = mutableListOf<AgentUiActionRequest>()
        show(t, onAction = { seen += it })
        listOf("Order #1042", "2 items", "Shipped", "Order #1043", "Due Friday", "Total", "$42.00").forEach { onNodeWithText(it).assertExists() }
        onNodeWithText("Shipped").assertHasNoClickAction()
        onNodeWithText("Pay").performClick()
        assertEquals("pay", seen.single().actionId)
    }
}
