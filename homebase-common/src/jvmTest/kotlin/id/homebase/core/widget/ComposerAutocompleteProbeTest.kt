package id.homebase.core.widget

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditor
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Same simple names as the runtime/richeditor classes the layout matches by name.
private class RecomposeScopeImpl
private class RememberObserverHolder
private class TriggerQuery

@OptIn(ExperimentalTestApi::class)
class ComposerAutocompleteProbeTest {

    private val mismatches = mutableListOf<String>()
    private var trace = ""

    @BeforeTest
    fun setUp() = ComposerAutocompleteProbe.install(object : ComposerAutocompleteProbe.Reporter {
        override fun onTrace(trace: String) {
            this@ComposerAutocompleteProbeTest.trace = trace
        }

        override fun onMismatch(report: String) {
            mismatches += report
        }
    })

    @AfterTest
    fun tearDown() = ComposerAutocompleteProbe.uninstall()

    private fun healthySlots(): MutableList<Any?> {
        val state = RichTextState()
        return mutableListOf(
            RecomposeScopeImpl(), state, ComposerAutocompleteController(), {}, true,
            state, {}, state, "mention", '@', RememberObserverHolder(),
            mutableStateOf(listOf("a", "b")), TriggerQuery(), {}, {}, "fro", RememberObserverHolder(),
            MutableTransitionState(false), mutableStateOf(null),
        )
    }

    @Test
    fun aSlotOutOfPlaceIsReportedWithTheRingBuffer() {
        ComposerAutocompleteProbe.check("mention", enabled = false, queryPresent = false, slots = healthySlots().take(5).toMutableList().also { it[4] = false })
        ComposerAutocompleteProbe.check("mention", enabled = true, queryPresent = true, slots = healthySlots())
        assertTrue(mismatches.isEmpty(), "healthy layouts must not report: $mismatches")

        val corrupted = healthySlots().also { it[17] = mutableStateOf("secret query text") }
        ComposerAutocompleteProbe.check("mention", enabled = true, queryPresent = true, slots = corrupted)

        val report = mismatches.single()
        assertTrue("slot[17] expected MutableTransitionState, actual MutableState<String(len=17)>" in report, report)
        assertTrue("slots(enabled=true, n=19)" in report, report)
        assertTrue("#1 mention enabled=false query=false slots=5" in report, report)
        assertTrue("#2 mention enabled=true query=true suggestions=2 shown=null visible=false->false slots=19" in report, report)
        assertTrue("#3 mention enabled=true query=true suggestions=2 shown=null visible=? slots=19" in report, report)
        assertFalse("secret" in report, "slot values must never be logged: $report")
        assertEquals(3, trace.lines().size)

        ComposerAutocompleteProbe.check("mention", enabled = true, queryPresent = true, slots = corrupted)
        assertEquals(1, mismatches.size, "only the first mismatch is reported")
    }

    @Test
    fun aMissingOrExtraSlotIsAMismatch() {
        assertEquals(
            SlotMismatch(18, "MutableState<Pair?>", "<missing>"),
            findSlotMismatch(enabled = true, slots = healthySlots().dropLast(1)),
        )
        assertEquals(
            SlotMismatch(5, "<end>", "RichTextState"),
            findSlotMismatch(enabled = false, slots = healthySlots().also { it[4] = false }),
        )
        assertNull(findSlotMismatch(enabled = true, slots = healthySlots()))
    }

    /** Guards the expected layout against the real runtime: a Compose upgrade that moves a slot fails here. */
    @Test
    fun theRealCompositionMatchesTheExpectedLayout() = runComposeUiTest {
        lateinit var state: RichTextState
        var enabled by mutableStateOf(true)
        setContent {
            MaterialTheme {
                state = rememberRichTextState()
                val controller = rememberComposerAutocompleteController()
                Box {
                    RichTextEditor(state = state)
                    ComposerAutocomplete(
                        state = state,
                        controller = controller,
                        triggerId = "test",
                        triggerChar = ':',
                        suggestionsFor = { q -> listOf("smile", "smirk").filter { it.startsWith(q) } },
                        replacementFor = { it },
                        enabled = enabled,
                    ) { item, _ -> Text(item) }
                }
            }
        }
        runOnIdle { state.addTextAfterSelection(":sm") }
        waitForIdle()
        runOnIdle { enabled = false }
        waitForIdle()
        runOnIdle { enabled = true }
        waitForIdle()
        runOnIdle { state.setText("") }
        waitForIdle()

        assertTrue(mismatches.isEmpty(), mismatches.joinToString())
        assertTrue("query=true suggestions=2 shown=set visible=true->true" in trace, trace)
        assertTrue("enabled=false query=false slots=5" in trace, trace)
    }
}
